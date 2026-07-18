package com.stapk.mobile.nativeadapter

import java.io.File

class StaticAssetController private constructor(
    webDir: File,
    private val privateRoots: List<PrivateRoot>
) {
    constructor(webDir: File) : this(webDir, emptyList())

    constructor(paths: NativeAdapterPaths) : this(
        paths.webDir,
        listOf(
            PrivateRoot("/backgrounds/", paths.backgroundsDir, allowWebFallback = true),
            PrivateRoot("/user/images/", paths.userImagesDir),
            PrivateRoot("/files/", paths.uploadsDir, isFileAttachment = true),
            PrivateRoot("/user/files/", paths.uploadsDir, isFileAttachment = true),
            PrivateRoot("/scripts/extensions/third-party/", paths.extensionsDir)
        )
    )

    private val configuredRoot = webDir.absoluteFile
    private val canonicalRoot = webDir.canonicalFile

    fun serve(path: String): HttpResponse {
        privateRoots.firstOrNull { path.startsWith(it.prefix) }?.let { root ->
            val privateResponse = servePrivate(root, path.removePrefix(root.prefix))
            if (privateResponse.statusCode != 404 || !root.allowWebFallback) {
                return privateResponse
            }
        }
        if (configuredRoot.path != canonicalRoot.path) {
            return HttpResponse.text(403, "Forbidden")
        }

        val normalized = if (path == "/") "index.html" else path.removePrefix("/")
        val canonicalTarget = File(configuredRoot, normalized).canonicalFile

        if (!isInsideWebDir(canonicalTarget)) {
            return HttpResponse.text(403, "Forbidden")
        }

        if (!canonicalTarget.exists() || canonicalTarget.isDirectory) {
            return HttpResponse.text(404, "Not found")
        }

        val mimeType = mimeType(canonicalTarget)

        return if (isTextMimeType(mimeType)) {
            HttpResponse(200, mimeType, bodyText = canonicalTarget.readText())
        } else {
            HttpResponse(200, mimeType, bodyBytes = canonicalTarget.readBytes())
        }
    }

    private fun servePrivate(root: PrivateRoot, relativePath: String): HttpResponse {
        val target = try {
            SafePath.child(root.directory, relativePath)
        } catch (_: IllegalArgumentException) {
            return HttpResponse.text(403, "Forbidden")
        }
        if (!target.isFile) return HttpResponse.text(404, "Not found")
        val detectedMimeType = mimeType(target)
        val forceAttachment = root.isFileAttachment && (
            target.extension.lowercase() in EXECUTABLE_INLINE_EXTENSIONS ||
                detectedMimeType == "application/octet-stream"
            )
        val headers = linkedMapOf("Cache-Control" to "no-store")
        if (forceAttachment) {
            headers["Content-Disposition"] = "attachment; filename*=UTF-8''${encodeRfc5987(target.name)}"
        }
        return HttpResponse(
            statusCode = 200,
            mimeType = if (forceAttachment) "application/octet-stream" else detectedMimeType,
            bodyBytes = target.readBytes(),
            headers = headers
        )
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "ts" -> "application/typescript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "less" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "map" -> "application/json; charset=utf-8"
            "md" -> "text/markdown; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "png" -> "image/png"
            "bmp" -> "image/bmp"
            "jpg", "jpeg", "jfif" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "svg" -> "image/svg+xml"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            "aiff" -> "audio/aiff"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mkv" -> "video/x-matroska"
            "mpg" -> "video/mpeg"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }

    private fun isInsideWebDir(target: File): Boolean {
        val rootPath = canonicalRoot.path
        val rootPrefix = if (rootPath.endsWith(File.separator)) rootPath else "$rootPath${File.separator}"
        return target.path == rootPath || target.path.startsWith(rootPrefix)
    }

    private fun isTextMimeType(mimeType: String): Boolean =
        mimeType.startsWith("text/") ||
            mimeType.contains("javascript") ||
            mimeType.contains("typescript") ||
            mimeType.contains("json") ||
            mimeType.contains("svg")

    private fun encodeRfc5987(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val character = byte.toInt() and 0xff
            if (
                character in 'a'.code..'z'.code ||
                character in 'A'.code..'Z'.code ||
                character in '0'.code..'9'.code ||
                character.toChar() in RFC5987_ATTR_CHARS
            ) {
                append(character.toChar())
            } else {
                append('%')
                append(HEX[character ushr 4])
                append(HEX[character and 0x0f])
            }
        }
    }

    private data class PrivateRoot(
        val prefix: String,
        val directory: File,
        val isFileAttachment: Boolean = false,
        val allowWebFallback: Boolean = false
    )

    private companion object {
        val EXECUTABLE_INLINE_EXTENSIONS = setOf("html", "htm", "svg", "js", "mjs")
        const val RFC5987_ATTR_CHARS = "!#$&+-.^_`|~"
        const val HEX = "0123456789ABCDEF"
    }
}
