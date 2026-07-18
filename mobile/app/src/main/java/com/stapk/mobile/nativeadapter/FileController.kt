package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.util.Base64

class FileController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
    fun sanitizeFilename(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidFileResponse()
        val fileName = request.stringValue("fileName")
        if (fileName.isEmpty()) return invalidFileResponse()
        return HttpResponse.json(200, JsonObject().apply {
            addProperty("fileName", sanitizeFilenameValue(fileName))
        }.toString())
    }

    @Synchronized
    fun uploadFile(request: NativeRequest): HttpResponse {
        val multipart = request.uploads["file"] ?: request.uploads["avatar"]
        val name: String
        val bytes: ByteArray
        if (multipart != null) {
            name = request.form["name"]?.firstOrNull().orEmpty().ifBlank { multipart.originalName }
            val limit = sizeLimit(name)
            if (!multipart.tempFile.isFile || multipart.tempFile.length() > limit) return uploadTooLargeResponse()
            bytes = multipart.tempFile.readBytes()
        } else {
            val body = parseObject(request.bodyText) ?: return invalidFileResponse()
            name = body.stringValue("name")
            val encoded = body.stringValue("data")
            if (name.isEmpty() || encoded.isEmpty()) return invalidFileResponse()
            val limit = sizeLimit(name)
            if (exceedsBase64DecodedLimit(encoded, limit)) return uploadTooLargeResponse()
            bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                return invalidFileResponse()
            }
            if (bytes.size.toLong() > limit) return uploadTooLargeResponse()
        }
        if (!isValidUploadName(name)) return invalidFileResponse()

        val target = runCatching { SafePath.child(paths.uploadsDir, name) }.getOrNull()
            ?: return invalidFileResponse()
        return try {
            store.writeBytes(target, bytes)
            HttpResponse.json(200, JsonObject().apply {
                addProperty("path", "user/files/$name")
            }.toString())
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"file_upload_failed"}""")
        }
    }

    @Synchronized
    fun deleteFile(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidFileResponse()
        val target = resolveFileUrl(request.stringValue("path")) ?: return invalidFileResponse()
        if (!target.isFile) return HttpResponse.json(404, """{"error":"file_not_found"}""")
        return if (target.delete()) {
            HttpResponse(statusCode = 200, mimeType = "text/plain; charset=utf-8", bodyText = "")
        } else {
            HttpResponse.json(500, """{"error":"file_delete_failed"}""")
        }
    }

    fun verifyFiles(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidFileResponse()
        val urls = request.get("urls")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return invalidFileResponse()
        val result = JsonObject()
        urls.forEach { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return@forEach
            val url = element.asString
            val target = resolveFileUrl(url) ?: return@forEach
            result.addProperty(url, target.isFile)
        }
        return HttpResponse.json(200, result.toString())
    }

    private fun resolveFileUrl(value: String): File? {
        val relative = FILE_PREFIXES.firstNotNullOfOrNull { prefix ->
            value.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        } ?: return null
        if (relative.isBlank()) return null
        return runCatching { SafePath.child(paths.uploadsDir, relative) }.getOrNull()
    }

    private fun isValidUploadName(name: String): Boolean {
        if (!UPLOAD_NAME.matches(name) || name.startsWith('.')) return false
        if (SafePath.fileName(name) != name) return false
        return name.substringAfterLast('.', "").lowercase() !in UNSAFE_EXTENSIONS
    }

    private fun sizeLimit(name: String): Long =
        if (name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS) MAX_TEXT_BYTES else MAX_FILE_BYTES

    private fun sanitizeFilenameValue(value: String): String {
        val cleaned = value.filterNot { character ->
            character.isISOControl() || character in INVALID_FILENAME_CHARACTERS
        }.trimEnd(' ', '.')
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return ""
        val baseName = cleaned.substringBefore('.').uppercase()
        if (baseName in WINDOWS_RESERVED_NAMES) return ""
        return SafePath.fileName(cleaned)
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
    } catch (_: IllegalStateException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    private fun JsonObject.stringValue(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()

    private fun invalidFileResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_file"}""")

    private fun uploadTooLargeResponse(): HttpResponse =
        HttpResponse.json(413, """{"error":"upload_too_large"}""")

    private companion object {
        const val MAX_TEXT_BYTES = 8L * 1024L * 1024L
        const val MAX_FILE_BYTES = 32L * 1024L * 1024L
        val FILE_PREFIXES = listOf("/user/files/", "user/files/", "/files/", "files/")
        val TEXT_EXTENSIONS = setOf("txt", "md", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log")
        val UNSAFE_EXTENSIONS = setOf("html", "htm", "svg", "js", "mjs")
        val UPLOAD_NAME = Regex("^[A-Za-z0-9_.-]+$")
        val WINDOWS_RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach { index ->
                add("COM$index")
                add("LPT$index")
            }
        }
        val INVALID_FILENAME_CHARACTERS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    }
}
