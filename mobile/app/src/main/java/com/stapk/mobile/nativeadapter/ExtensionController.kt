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
    private val installer: ExtensionArchiveInstaller,
    private val coordinator: ExtensionMutationCoordinator = ExtensionMutationCoordinator(
        paths,
        registry,
        ExtensionTransactionJournal(paths),
        ExtensionDirectoryQuarantine(paths)
    ),
    private val diagnosticLogger: DiagnosticLogger? = null
) : ExtensionRoutes {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun discover(): HttpResponse {
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

    override fun install(body: String): HttpResponse {
        val request = parseObject(body) ?: return error(400, "invalid_extension_request")
        if (request.unsupportedGlobal()) return error(400, "global_extensions_not_supported")
        val url = request.stringValue("url").takeIf(String::isNotBlank)
            ?: return error(400, "invalid_extension_request")
        val branch = request.stringValue("branch").takeIf(String::isNotBlank)
        return mutationResponse("install") {
            coordinator.requireRecoveryReady()
            val release = resolve(url, branch)
            installer.prepare(release).use { prepared ->
                val displayName = readDisplayName(prepared.stagingDirectory)
                    ?: prepared.record.folderName
                val installed = coordinator.install(prepared)
                HttpResponse.json(200, gson.toJson(JsonObject().apply {
                    addProperty("display_name", displayName)
                    addProperty("extensionPath", "/scripts/extensions/third-party/${installed.folderName}")
                    addProperty("folderName", installed.folderName)
                }))
            }
        }
    }

    override fun version(body: String): HttpResponse {
        val request = parseObject(body) ?: return error(400, "invalid_extension_request")
        if (request.unsupportedGlobal()) return error(400, "global_extensions_not_supported")
        val record = findRecord(request) ?: return error(404, "extension_not_found")
        val release = try {
            source.resolve(record.repositoryUrl, record.branch)
        } catch (_: IllegalArgumentException) {
            return error(400, "invalid_github_repository")
        } catch (exception: ExtensionSourceException) {
            recordSourceFailure("version", exception)
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

    override fun update(body: String): HttpResponse {
        val request = parseObject(body) ?: return error(400, "invalid_extension_request")
        if (request.unsupportedGlobal()) return error(400, "global_extensions_not_supported")
        return mutationResponse("update") {
            val folderName = requestedFolderName(request)
                ?: return@mutationResponse error(404, "extension_not_found")
            val record = coordinator.findRecordForMutation(folderName)
                ?: return@mutationResponse error(404, "extension_not_found")
            val release = resolve(record.repositoryUrl, record.branch)
            installer.prepare(release, replacing = record).use { prepared ->
                val updated = coordinator.update(record, prepared)
                updateResponse(updated.commitSha, updated.commitSha == record.commitSha)
            }
        }
    }

    override fun delete(body: String): HttpResponse {
        val request = parseObject(body) ?: return error(400, "invalid_extension_request")
        if (request.unsupportedGlobal()) return error(400, "global_extensions_not_supported")
        return mutationResponse("delete") {
            val folderName = requestedFolderName(request)
                ?: return@mutationResponse error(404, "extension_not_found")
            val record = coordinator.findRecordForMutation(folderName)
                ?: return@mutationResponse error(404, "extension_not_found")
            coordinator.delete(record)
            HttpResponse.json(200, "{}")
        }
    }

    private fun resolve(url: String, branch: String?): ExtensionRelease = try {
        source.resolve(url, branch)
    } catch (exception: IllegalArgumentException) {
        throw InvalidGitHubRepositoryException(exception)
    }

    private fun mutationResponse(
        operation: String,
        block: () -> HttpResponse
    ): HttpResponse = try {
        block()
    } catch (_: InvalidGitHubRepositoryException) {
        error(400, "invalid_github_repository")
    } catch (_: ExtensionAlreadyInstalledException) {
        error(409, "extension_already_installed")
    } catch (_: ExtensionOperationConflictException) {
        error(409, "extension_operation_conflict")
    } catch (_: InvalidExtensionArchiveException) {
        error(422, "invalid_extension_archive")
    } catch (exception: ExtensionSourceException) {
        recordSourceFailure(operation, exception)
        error(502, "extension_source_unavailable")
    } catch (_: ExtensionRegistryWriteException) {
        error(500, "extension_registry_write_failed")
    } catch (_: ExtensionTransactionException) {
        error(500, "extension_transaction_failed")
    } catch (_: ExtensionRecoveryRequiredException) {
        error(503, "extension_recovery_required")
    }

    private fun recordSourceFailure(operation: String, exception: ExtensionSourceException) {
        var rootCause: Throwable = exception
        while (rootCause.cause != null && rootCause.cause !== rootCause) {
            rootCause = rootCause.cause!!
        }
        runCatching {
            diagnosticLogger?.event(
                DiagnosticArea.HTTP,
                "extension_source_failed",
                mapOf(
                    "operation" to operation,
                    "phase" to exception.phase.diagnosticValue,
                    "errorClass" to rootCause.javaClass.name
                )
            )
        }
    }

    private fun findRecord(request: JsonObject): ExtensionRecord? {
        val folderName = requestedFolderName(request) ?: return null
        return registry.find(folderName)
    }

    private fun requestedFolderName(request: JsonObject): String? =
        request.stringValue("extensionName")
            .removePrefix("third-party/")
            .removePrefix("/")
            .takeIf(String::isNotBlank)

    private fun updateResponse(commitSha: String, isUpToDate: Boolean): HttpResponse =
        HttpResponse.json(200, gson.toJson(JsonObject().apply {
            addProperty("isUpToDate", isUpToDate)
            addProperty("shortCommitHash", commitSha.take(7))
            addProperty("commitHash", commitSha)
        }))

    private fun readDisplayName(directory: java.io.File): String? =
        JsonParser.parseString(directory.resolve("manifest.json").readText()).asJsonObject
            .stringValue("display_name")
            .takeIf(String::isNotBlank)

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
            "memory",
            "vectors"
        )
    }

    private class InvalidGitHubRepositoryException(cause: IllegalArgumentException) :
        IllegalArgumentException(cause)
}
