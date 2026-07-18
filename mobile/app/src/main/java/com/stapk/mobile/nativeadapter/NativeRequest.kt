package com.stapk.mobile.nativeadapter

import java.io.File

data class UploadedFile(
    val fieldName: String,
    val originalName: String,
    val mimeType: String,
    val tempFile: File
)

data class NativeRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val form: Map<String, List<String>>,
    val bodyText: String,
    val uploads: Map<String, UploadedFile>,
    val headers: Map<String, String> = emptyMap()
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value
}
