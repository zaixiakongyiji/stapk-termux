package com.stapk.mobile.nativeadapter

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

class ExtensionController(
    private val paths: NativeAdapterPaths,
    private val registry: ExtensionRegistry,
    private val source: ExtensionSource,
    private val installer: ExtensionArchiveInstaller
) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun discover(): HttpResponse {
        val extensions = JsonArray()
        SYSTEM_EXTENSIONS.forEach { name ->
            extensions.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("type", "system")
            })
        }
        registry.list().forEach { record ->
            extensions.add(JsonObject().apply {
                addProperty("name", "third-party/${record.folderName}")
                addProperty("type", "local")
            })
        }
        return HttpResponse.json(200, gson.toJson(extensions))
    }

    fun install(body: String): HttpResponse {
        val request = parseObject(body) ?: return error(400, "invalid_extension_request")
        if (request.unsupportedGlobal()) return error(400, "global_extensions_not_supported")
        val url = request.stringValue("url").takeIf(String::isNotBlank)
            ?: return error(400, "invalid_extension_request")
        val branch = request.stringValue("branch").takeIf(String::isNotBlank)
        val release = try {
            source.resolve(url, branch)
        } catch (_: IllegalArgumentException) {
            return error(400, "invalid_github_repository")
        } catch (_: ExtensionSourceException) {
            return error(502, "extension_source_unavailable")
        }
        if (registry.list().any {
                it.repositoryUrl.equals(release.repository.canonicalUrl, ignoreCase = true) ||
                    it.folderName.equals(release.repository.repository, ignoreCase = true)
            }
        ) {
            release.archive.close()
            return error(409, "extension_already_installed")
        }
        val installed = try {
            installer.install(release)
        } catch (_: InvalidExtensionArchiveException) {
            return error(422, "invalid_extension_archive")
        }
        try {
            registry.install(installed.record)
        } catch (_: IllegalStateException) {
            installed.directory.deleteRecursively()
            return error(409, "extension_already_installed")
        } catch (_: Exception) {
            installed.directory.deleteRecursively()
            return error(500, "extension_registry_write_failed")
        }
        val displayName = readDisplayName(installed.directory) ?: installed.record.folderName
        return HttpResponse.json(200, gson.toJson(JsonObject().apply {
            addProperty("display_name", displayName)
            addProperty("extensionPath", "/scripts/extensions/third-party/${installed.record.folderName}")
            addProperty("folderName", installed.record.folderName)
        }))
    }

    fun version(body: String): HttpResponse {
        val request = parseObject(body) ?: return error(400, "invalid_extension_request")
        if (request.unsupportedGlobal()) return error(400, "global_extensions_not_supported")
        val record = findRecord(request) ?: return error(404, "extension_not_found")
        val release = try {
            source.resolve(record.repositoryUrl, record.branch)
        } catch (_: IllegalArgumentException) {
            return error(400, "invalid_github_repository")
        } catch (_: ExtensionSourceException) {
            return error(502, "extension_source_unavailable")
        }
        release.archive.close()
        return HttpResponse.json(200, gson.toJson(JsonObject().apply {
            addProperty("currentBranchName", record.branch)
            addProperty("currentCommitHash", record.commitSha)
            addProperty("isUpToDate", record.commitSha == release.commitSha)
            addProperty("remoteUrl", record.repositoryUrl)
        }))
    }

    fun update(body: String): HttpResponse {
        val request = parseObject(body) ?: return error(400, "invalid_extension_request")
        if (request.unsupportedGlobal()) return error(400, "global_extensions_not_supported")
        val record = findRecord(request) ?: return error(404, "extension_not_found")
        val release = try {
            source.resolve(record.repositoryUrl, record.branch)
        } catch (_: IllegalArgumentException) {
            return error(400, "invalid_github_repository")
        } catch (_: ExtensionSourceException) {
            return error(502, "extension_source_unavailable")
        }
        if (record.commitSha == release.commitSha) {
            release.archive.close()
            return updateResponse(record.commitSha, true)
        }
        val installed = try {
            installer.install(release, replacing = record)
        } catch (_: InvalidExtensionArchiveException) {
            return error(422, "invalid_extension_archive")
        }
        return try {
            registry.update(installed.record)
            updateResponse(installed.record.commitSha, false)
        } catch (_: Exception) {
            error(500, "extension_registry_write_failed")
        }
    }

    fun delete(body: String): HttpResponse {
        val request = parseObject(body) ?: return error(400, "invalid_extension_request")
        if (request.unsupportedGlobal()) return error(400, "global_extensions_not_supported")
        val record = findRecord(request) ?: return error(404, "extension_not_found")
        val directory = paths.extensionsDir.resolve(record.folderName)
        if (directory.exists() && !directory.deleteRecursively()) {
            return error(500, "extension_delete_failed")
        }
        return try {
            registry.remove(record.folderName)
            HttpResponse.json(200, "{}")
        } catch (_: Exception) {
            error(500, "extension_registry_write_failed")
        }
    }

    private fun findRecord(request: JsonObject): ExtensionRecord? {
        val folderName = request.stringValue("extensionName")
            .removePrefix("third-party/")
            .removePrefix("/")
            .takeIf(String::isNotBlank)
            ?: return null
        return registry.find(folderName)
    }

    private fun updateResponse(commitSha: String, isUpToDate: Boolean): HttpResponse =
        HttpResponse.json(200, gson.toJson(JsonObject().apply {
            addProperty("isUpToDate", isUpToDate)
            addProperty("shortCommitHash", commitSha.take(7))
            addProperty("commitHash", commitSha)
        }))

    private fun readDisplayName(directory: java.io.File): String? = try {
        JsonParser.parseString(directory.resolve("manifest.json").readText()).asJsonObject
            .stringValue("display_name")
            .takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.unsupportedGlobal(): Boolean {
        val value = get("global") ?: return false
        return !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean || value.asBoolean
    }

    private fun JsonObject.stringValue(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body).asJsonObject
    } catch (_: IllegalStateException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    private fun error(statusCode: Int, code: String): HttpResponse =
        HttpResponse.json(statusCode, gson.toJson(JsonObject().apply { addProperty("error", code) }))

    private companion object {
        val SYSTEM_EXTENSIONS = listOf(
            "quick-reply",
            "attachments",
            "gallery",
            "expressions",
            "regex",
            "memory"
        )
    }
}
