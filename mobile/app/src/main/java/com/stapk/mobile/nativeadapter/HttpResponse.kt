package com.stapk.mobile.nativeadapter

import java.io.File
import java.nio.charset.StandardCharsets

data class HttpResponse(
    val statusCode: Int,
    val mimeType: String,
    val bodyText: String? = null,
    val bodyBytes: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    val bodyFile: File? = null
) {
    init {
        require(listOf<Any?>(bodyText, bodyBytes, bodyFile).count { it != null } <= 1) {
            "Only one response body is allowed"
        }
    }

    companion object {
        fun json(statusCode: Int, body: String): HttpResponse =
            HttpResponse(statusCode, "application/json; charset=utf-8", bodyText = body)

        fun text(statusCode: Int, body: String): HttpResponse =
            HttpResponse(statusCode, "text/plain; charset=utf-8", bodyText = body)

        fun file(
            file: File,
            fileName: String,
            exportToken: String,
            mimeType: String = "application/octet-stream"
        ): HttpResponse {
            require(file.isFile) { "Response file must exist" }
            return HttpResponse(
                statusCode = 200,
                mimeType = mimeType,
                headers = mapOf(
                    "Content-Length" to file.length().toString(),
                    "Content-Disposition" to "attachment; filename*=UTF-8''${encodeRfc5987(fileName)}",
                    "X-stAPK-Export-Token" to exportToken
                ),
                bodyFile = file
            )
        }

        private fun encodeRfc5987(value: String): String = buildString {
            value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
                val character = byte.toInt() and 0xff
                if (
                    character in 'a'.code..'z'.code ||
                    character in 'A'.code..'Z'.code ||
                    character in '0'.code..'9'.code ||
                    RFC5987_ATTR_CHARS.contains(character.toChar())
                ) {
                    append(character.toChar())
                } else {
                    append('%')
                    append(HEX[character ushr 4])
                    append(HEX[character and 0x0f])
                }
            }
        }

        private const val RFC5987_ATTR_CHARS = "!#$&+-.^_`|~"
        private const val HEX = "0123456789ABCDEF"
    }
}
