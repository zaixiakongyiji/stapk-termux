package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class ExportController(
    private val exportStore: ExportStore,
    private val activeBridgeNonce: () -> String? = { null }
) {
    fun create(request: NativeRequest): HttpResponse {
        val expectedNonce = activeBridgeNonce()
        if (
            expectedNonce == null ||
            request.header(BRIDGE_NONCE_HEADER) != expectedNonce
        ) {
            return HttpResponse.json(403, """{"error":"export_forbidden"}""")
        }
        val upload = request.uploads["file"] ?: return invalidResponse()
        if (!upload.tempFile.isFile || upload.tempFile.length() > MAX_EXPORT_BYTES) return invalidResponse()
        if (!ExportMetadata.isExport(upload.originalName, upload.mimeType)) return invalidResponse()

        return try {
            val ticket = exportStore.create(
                upload.originalName,
                upload.mimeType,
                expectedBytes = upload.tempFile.length()
            ) { target ->
                Files.copy(upload.tempFile.toPath(), target.toPath(), REPLACE_EXISTING)
            }
            HttpResponse.json(200, Gson().toJson(JsonObject().apply {
                addProperty("token", ticket.token)
                addProperty("fileName", ticket.fileName)
                addProperty("mimeType", ticket.mimeType)
            }))
        } catch (_: ExportQuotaExceededException) {
            HttpResponse.json(429, """{"error":"export_quota_exceeded"}""")
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"export_stage_failed"}""")
        }
    }

    private fun invalidResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_export"}""")

    private companion object {
        const val BRIDGE_NONCE_HEADER = "X-stAPK-Bridge-Nonce"
        const val MAX_EXPORT_BYTES = 32L * 1024L * 1024L
    }
}
