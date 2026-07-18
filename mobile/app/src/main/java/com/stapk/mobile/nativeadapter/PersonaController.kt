package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class PersonaController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
    private val gson = Gson()

    fun getAvatars(): HttpResponse {
        paths.personasDir.mkdirs()
        val names = paths.personasDir.listFiles { file -> file.isFile && imageExtension(file.extension) != null }
            .orEmpty()
            .map(File::getName)
            .sorted()
        return HttpResponse.json(200, gson.toJson(JsonArray().apply { names.forEach(::add) }))
    }

    @Synchronized
    fun uploadAvatar(request: NativeRequest): HttpResponse {
        val upload = request.uploads["avatar"] ?: return invalidAvatar()
        val extension = imageExtension(upload.tempFile.readBytes()) ?: return invalidAvatar()
        val targetName = request.form["overwrite_name"]?.lastOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let(::normalizedAvatarId)
            ?: normalizedAvatarName(upload.originalName, extension)
            ?: return invalidAvatar()
        val target = SafePath.child(paths.personasDir, targetName)
        paths.personasDir.mkdirs()
        store.writeBytes(target, upload.tempFile.readBytes())
        return HttpResponse.json(200, gson.toJson(JsonObject().apply { addProperty("path", targetName) }))
    }

    @Synchronized
    fun deleteAvatar(body: String): HttpResponse {
        val name = parseAvatarName(body) ?: return invalidAvatar()
        val target = safeAvatarFile(name) ?: return invalidAvatar()
        if (target.isFile) target.delete()
        return HttpResponse.json(200, "{}")
    }

    fun serveAvatar(name: String): HttpResponse {
        val file = safeAvatarFile(name) ?: return invalidAvatar()
        if (!file.isFile) return HttpResponse.json(404, "{\"error\":\"avatar_not_found\"}")
        val bytes = file.readBytes()
        val detectedExtension = imageExtension(bytes) ?: return invalidAvatar()
        val mimeType = when (detectedExtension) {
            "jpg" -> "image/jpeg"
            else -> "image/$detectedExtension"
        }
        return HttpResponse(200, mimeType, bodyBytes = bytes)
    }

    private fun parseAvatarName(body: String): String? = runCatching {
        JsonParser.parseString(body).asJsonObject.get("avatar")?.asString
    }.getOrNull()

    private fun safeAvatarFile(name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        if (imageExtension(name.substringAfterLast('.', "")) == null) return null
        return runCatching { SafePath.child(paths.personasDir, name) }.getOrNull()
    }

    private fun normalizedAvatarName(value: String, extension: String): String? {
        val withoutExtension = value.substringBeforeLast('.', value).trim().trimStart('.')
        if (withoutExtension.isBlank()) return null
        return "${SafePath.fileName(withoutExtension).takeUnless { it.isBlank() } ?: return null}.$extension"
    }

    private fun normalizedAvatarId(value: String): String? {
        if (value.contains('/') || value.contains('\\') || value.contains("..")) return null
        val normalized = SafePath.fileName(value.trim())
        return normalized.takeIf { it.isNotBlank() && imageExtension(it.substringAfterLast('.', "")) != null }
    }

    private fun imageExtension(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC) -> "png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(RIFF_MAGIC) &&
            bytes.copyOfRange(8, 12).contentEquals(WEBP_MAGIC) -> "webp"
        else -> null
    }

    private fun imageExtension(extension: String): String? = when (extension.lowercase()) {
        "png" -> "png"
        "jpg", "jpeg" -> "jpg"
        "webp" -> "webp"
        else -> null
    }

    private fun invalidAvatar(): HttpResponse = HttpResponse.json(400, "{\"error\":\"invalid_avatar\"}")

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val RIFF_MAGIC = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        val WEBP_MAGIC = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    }
}
