package com.stapk.mobile.nativeadapter

import java.io.File

object SafePath {
    fun fileName(input: String, fallback: String = "file"): String {
        val safeFallback = cleanFileName(fallback).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "file"
        val safeName = cleanFileName(input).takeUnless { it.isBlank() || it == "." || it == ".." } ?: safeFallback
        return safeName.substring(0, safeName.offsetByCodePoints(0, safeName.codePointCount(0, safeName.length).coerceAtMost(MAX_FILE_NAME_CODE_POINTS)))
    }

    fun child(root: File, relativePath: String): File {
        val segments = segments(relativePath)

        val canonicalRoot = root.canonicalFile
        val child = File(canonicalRoot, segments.joinToString("/")).canonicalFile
        val rootPrefix = "${canonicalRoot.path}${File.separator}"
        require(child.path.startsWith(rootPrefix)) { "Child escapes root" }
        return child
    }

    fun zipEntry(value: String): String {
        require(value.isNotBlank() && !hasControlCharacter(value)) { "Invalid ZIP entry" }
        require(!containsEncodedSeparator(value)) { "Encoded separators are not allowed" }
        require(!WINDOWS_DRIVE_PATH.matches(value) && !value.startsWith("\\\\")) {
            "Windows absolute ZIP entry is not allowed"
        }
        val normalized = value.replace('\\', '/')
        require(!normalized.startsWith('/')) { "Absolute ZIP entry is not allowed" }
        return segments(normalized).joinToString("/")
    }

    fun segments(relativePath: String): List<String> {
        require(relativePath.isNotBlank() && !hasControlCharacter(relativePath)) { "Invalid child path" }
        require(!containsEncodedSeparator(relativePath)) { "Encoded separators are not allowed" }
        require(!isAbsolute(relativePath)) { "Absolute paths are not allowed" }
        require(!relativePath.contains('\\')) { "Backslash separators are not allowed" }
        val segments = relativePath.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe child path" }
        return segments
    }

    private fun containsEncodedSeparator(value: String): Boolean =
        ENCODED_SEPARATOR.containsMatchIn(value)

    private fun hasControlCharacter(value: String): Boolean = value.any { it.isISOControl() }

    private fun cleanFileName(value: String): String =
        value.trim().filterNot { it == '/' || it == '\\' || it.isISOControl() }

    private fun isAbsolute(value: String): Boolean =
        File(value).isAbsolute || value.startsWith('\\') || WINDOWS_DRIVE_PATH.matches(value)

    private val ENCODED_SEPARATOR = Regex("%2f|%5c", RegexOption.IGNORE_CASE)
    private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
    private const val MAX_FILE_NAME_CODE_POINTS = 120
}
