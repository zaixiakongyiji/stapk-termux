package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

class SpriteController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
    fun getSprites(name: String): HttpResponse {
        val stem = characterStem(name) ?: return HttpResponse.json(200, "[]")
        val directory = spritesDirectory(stem)
        val sprites = JsonArray()
        directory.listFiles { file -> file.isFile && file.extension.lowercase() in SPRITE_EXTENSIONS }
            .orEmpty().sortedBy { it.name.lowercase() }.forEach { file ->
                val baseName = file.nameWithoutExtension.lowercase()
                val label = baseName.substringBefore('-').substringBefore('.')
                sprites.add(JsonObject().apply {
                    addProperty("label", label)
                    addProperty("path", "/characters/$stem/${file.name}?t=${file.lastModified()}")
                })
            }
        return HttpResponse.json(200, sprites.toString())
    }

    @Synchronized
    fun uploadSprite(request: NativeRequest): HttpResponse {
        val name = request.formValue("name")
        val label = request.formValue("label")
        val spriteName = request.formValue("spriteName").ifBlank { label }
        val stem = characterStem(name) ?: return invalidSpriteResponse()
        if (label.isBlank() || !isSafeSpriteName(spriteName)) return invalidSpriteResponse()
        val upload = request.uploads["image"] ?: request.uploads["avatar"] ?: return invalidSpriteResponse()
        if (!upload.tempFile.isFile) return invalidSpriteResponse()
        if (upload.tempFile.length() > MAX_SPRITE_BYTES) return uploadTooLargeResponse()
        val extension = upload.originalName.substringAfterLast('.', "").lowercase()
        if (extension !in SPRITE_EXTENSIONS) return invalidSpriteResponse()
        val bytes = upload.tempFile.readBytes()
        if (!isImage(bytes, extension)) return invalidSpriteResponse()

        val directory = spritesDirectory(stem)
        val target = runCatching { SafePath.child(directory, "$spriteName.$extension") }.getOrNull()
            ?: return invalidSpriteResponse()
        return try {
            store.writeBytes(target, bytes)
            removeOtherExtensions(directory, spriteName, target)
            okResponse()
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"sprite_upload_failed"}""")
        }
    }

    @Synchronized
    fun deleteSprite(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidSpriteResponse()
        val stem = characterStem(request.stringValue("name")) ?: return invalidSpriteResponse()
        val spriteName = request.stringValue("spriteName").ifBlank { request.stringValue("label") }
        if (!isSafeSpriteName(spriteName)) return invalidSpriteResponse()
        val directory = spritesDirectory(stem)
        if (!directory.isDirectory) return HttpResponse.json(404, """{"error":"sprites_not_found"}""")
        directory.listFiles { file -> file.isFile && file.nameWithoutExtension == spriteName }
            .orEmpty().forEach(File::delete)
        return HttpResponse(statusCode = 200, mimeType = "text/plain; charset=utf-8", bodyText = "")
    }

    @Synchronized
    fun uploadSpriteZip(request: NativeRequest): HttpResponse {
        val stem = characterStem(request.formValue("name")) ?: return invalidSpriteResponse()
        val upload = request.uploads["image"] ?: request.uploads["avatar"] ?: return invalidSpriteResponse()
        if (!upload.tempFile.isFile) return invalidSpriteResponse()
        if (upload.tempFile.length() > MAX_SPRITE_BYTES) return uploadTooLargeResponse()

        val stagingDirectory = File(paths.stateDir, "sprite-import/${randomToken()}")
        val staged = try {
            readSpriteArchive(upload.tempFile, stagingDirectory)
        } catch (_: SpriteArchiveLimitException) {
            stagingDirectory.deleteRecursively()
            return uploadTooLargeResponse()
        } catch (_: Exception) {
            stagingDirectory.deleteRecursively()
            return invalidSpriteResponse()
        }
        if (staged.isEmpty()) {
            stagingDirectory.deleteRecursively()
            return invalidSpriteResponse()
        }

        val directory = spritesDirectory(stem)
        return try {
            staged.forEach { stagedFile ->
                val target = SafePath.child(directory, stagedFile.fileName)
                store.writeBytes(target, stagedFile.file.readBytes())
                removeOtherExtensions(directory, target.nameWithoutExtension, target)
            }
            HttpResponse.json(200, JsonObject().apply {
                addProperty("ok", true)
                addProperty("count", staged.size)
            }.toString())
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"sprite_zip_import_failed"}""")
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    fun serveSprite(relativePath: String): HttpResponse {
        val segments = relativePath.split('/')
        if (segments.size != 2) return HttpResponse.text(403, "Forbidden")
        val stem = segments[0]
        val fileName = segments[1]
        if (!STEM.matches(stem) || SafePath.fileName(fileName) != fileName) {
            return HttpResponse.text(403, "Forbidden")
        }
        val target = runCatching { SafePath.child(spritesDirectory(stem), fileName) }.getOrNull()
            ?: return HttpResponse.text(403, "Forbidden")
        if (!target.isFile || target.extension.lowercase() !in SPRITE_EXTENSIONS) {
            return HttpResponse.text(404, "Not found")
        }
        return HttpResponse(
            statusCode = 200,
            mimeType = spriteMimeType(target.extension.lowercase()),
            bodyBytes = target.readBytes(),
            headers = mapOf("Cache-Control" to "no-store")
        )
    }

    private fun readSpriteArchive(archive: File, stagingDirectory: File): List<StagedSprite> {
        val staged = mutableListOf<StagedSprite>()
        val labels = mutableSetOf<String>()
        var entryCount = 0
        var totalBytes = 0L
        if (!stagingDirectory.mkdirs()) throw ZipException("Unable to create staging directory")
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_ZIP_ENTRIES) throw SpriteArchiveLimitException()
                val rawName = entry.name
                if (entry.isDirectory) {
                    SafePath.zipEntry(rawName.removeSuffix("/"))
                    zip.closeEntry()
                    continue
                }
                val normalized = SafePath.zipEntry(rawName)
                val fileName = SafePath.fileName(normalized.substringAfterLast('/'))
                if (fileName != normalized.substringAfterLast('/')) throw ZipException("Invalid sprite name")
                val extension = fileName.substringAfterLast('.', "").lowercase()
                if (extension !in SPRITE_EXTENSIONS) throw ZipException("Unsupported sprite format")
                val labelKey = fileName.substringBeforeLast('.').lowercase(Locale.US)
                if (!labels.add(labelKey)) throw ZipException("Duplicate sprite label")

                val target = SafePath.child(stagingDirectory, "$entryCount-$fileName")
                target.parentFile?.mkdirs()
                var entryBytes = 0L
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        if (entryBytes > MAX_SPRITE_BYTES || totalBytes > MAX_ZIP_TOTAL_BYTES) {
                            throw SpriteArchiveLimitException()
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                if (!isImage(target.readBytes(), extension)) throw ZipException("Invalid sprite image")
                staged.add(StagedSprite(fileName, target))
                zip.closeEntry()
            }
        }
        return staged
    }

    private fun characterStem(name: String): String? {
        if (name.isBlank() || SafePath.fileName(name) != name || name.contains('/')) return null
        val withoutExtension = name.removeSuffix(".png")
        val candidates = paths.charactersDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            .orEmpty()
        candidates.firstOrNull { it.nameWithoutExtension == withoutExtension }
            ?.let { return it.nameWithoutExtension }
        candidates.firstOrNull { it.nameWithoutExtension.equals(withoutExtension, ignoreCase = true) }
            ?.let { return it.nameWithoutExtension }
        candidates.forEach { file ->
            val character = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
                ?: return@forEach
            val displayName = character.stringValue("name").ifBlank {
                character.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.stringValue("name").orEmpty()
            }
            if (displayName == name) return file.nameWithoutExtension
        }
        val slug = name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return slug.takeIf { it.isNotBlank() && File(paths.charactersDir, "$it.json").isFile }
    }

    private fun spritesDirectory(stem: String): File = SafePath.child(paths.charactersDir, "$stem/sprites")

    private fun removeOtherExtensions(directory: File, spriteName: String, target: File) {
        directory.listFiles { file ->
            file.isFile && file.nameWithoutExtension == spriteName && file.canonicalFile != target.canonicalFile
        }.orEmpty().forEach(File::delete)
    }

    private fun isSafeSpriteName(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." && SafePath.fileName(value) == value && !value.contains('/')

    private fun isImage(bytes: ByteArray, extension: String): Boolean = when (extension) {
        "png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)
        "jpg", "jpeg" -> bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()
        "webp" -> bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        "gif" -> bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        else -> false
    }

    private fun spriteMimeType(extension: String): String = when (extension) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }

    private fun randomToken(): String {
        val bytes = ByteArray(18)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
    } catch (_: IllegalStateException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    private fun NativeRequest.formValue(name: String): String = form[name]?.firstOrNull().orEmpty()

    private fun JsonObject.stringValue(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()

    private fun invalidSpriteResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_sprite"}""")

    private fun uploadTooLargeResponse(): HttpResponse =
        HttpResponse.json(413, """{"error":"upload_too_large"}""")

    private fun okResponse(): HttpResponse = HttpResponse.json(200, """{"ok":true}""")

    private data class StagedSprite(val fileName: String, val file: File)
    private class SpriteArchiveLimitException : RuntimeException()

    private companion object {
        const val MAX_SPRITE_BYTES = 32L * 1024L * 1024L
        const val MAX_ZIP_TOTAL_BYTES = 1024L * 1024L * 1024L
        const val MAX_ZIP_ENTRIES = 20_000
        val STEM = Regex("^[A-Za-z0-9_-]+$")
        val SPRITE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )
    }
}
