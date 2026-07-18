package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ImageMetadataController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
    private val metadata = ImageMetadataStore(paths, store)

    internal fun refreshMetadata(relativePath: String, type: String, force: Boolean = false): JsonObject? {
        val target = resolveMetadataPath(relativePath) ?: return null
        if (!target.isFile) return null
        return generateAndCacheMetadata(relativePath, target, type, force)
    }

    @Synchronized
    fun uploadImage(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidImageResponse()
        val encoded = request.stringValue("image")
        val format = request.stringValue("format").lowercase()
        if (encoded.isEmpty() || format !in MEDIA_EXTENSIONS) return invalidImageResponse()
        if (exceedsBase64DecodedLimit(encoded, MAX_IMAGE_BYTES.toLong())) {
            return HttpResponse.json(413, """{"error":"upload_too_large"}""")
        }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return invalidImageResponse()
        }
        if (bytes.size > MAX_IMAGE_BYTES) return HttpResponse.json(413, """{"error":"upload_too_large"}""")

        val rawBase = request.stringValue("filename")
            .substringBeforeLast('.', request.stringValue("filename"))
            .ifBlank { System.currentTimeMillis().toString() }
        val fileName = SafePath.fileName("$rawBase.$format", "image.$format")
        val folder = request.stringValue("ch_name").takeIf { it.isNotBlank() }?.let(SafePath::fileName)
        val relativePath = listOfNotNull(folder, fileName).joinToString("/")
        val target = runCatching { SafePath.child(paths.userImagesDir, relativePath) }.getOrNull()
            ?: return invalidImageResponse()
        return try {
            store.writeBytes(target, bytes)
            HttpResponse.json(200, JsonObject().apply {
                addProperty("path", "user/images/$relativePath")
            }.toString())
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"image_upload_failed"}""")
        }
    }

    fun listImages(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidImageResponse()
        val rawFolder = request.stringValue("folder")
        if (rawFolder.isBlank()) return invalidImageResponse()
        val folder = SafePath.fileName(rawFolder)
        val directory = runCatching { SafePath.child(paths.userImagesDir, folder) }.getOrNull()
            ?: return invalidImageResponse()
        if (!directory.exists() && !directory.mkdirs()) {
            return HttpResponse.json(500, """{"error":"image_list_failed"}""")
        }
        val type = request.intValue("type") ?: IMAGE_TYPE
        val sortField = request.stringValue("sortField").ifBlank { "date" }
        val descending = request.stringValue("sortOrder") == "desc"
        val comparator = when (sortField) {
            "name" -> compareBy<File> { it.name.lowercase() }.thenBy { it.name }
            "size" -> compareBy<File> { it.length() }.thenBy { it.name }
            else -> compareBy<File> { it.lastModified() }.thenBy { it.name }
        }
        val files = directory.listFiles { file -> file.isFile && acceptsMediaType(file.extension.lowercase(), type) }
            .orEmpty().sortedWith(if (descending) comparator.reversed() else comparator)
        return HttpResponse.json(200, JsonArray().apply { files.forEach { add(it.name) } }.toString())
    }

    fun listImageFolders(): HttpResponse {
        paths.userImagesDir.mkdirs()
        val folders = paths.userImagesDir.listFiles { file -> file.isDirectory }.orEmpty()
            .map(File::getName).sorted()
        return HttpResponse.json(200, JsonArray().apply { folders.forEach(::add) }.toString())
    }

    @Synchronized
    fun deleteImage(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidImageResponse()
        val relative = imageUrlRelativePath(request.stringValue("path")) ?: return invalidImageResponse()
        val target = runCatching { SafePath.child(paths.userImagesDir, relative) }.getOrNull()
            ?: return invalidImageResponse()
        if (!target.isFile) return HttpResponse.json(404, """{"error":"image_not_found"}""")
        return if (target.delete()) {
            metadata.removePath("user_images/$relative")
            HttpResponse(statusCode = 200, mimeType = "text/plain; charset=utf-8", bodyText = "")
        } else {
            HttpResponse.json(500, """{"error":"image_delete_failed"}""")
        }
    }

    fun getMetadata(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidMetadataResponse()
        val type = request.stringValue("type")
        val singlePath = request.stringValue("path")
        val pathsElement = request.get("paths")
        if (singlePath.isNotBlank() && (pathsElement == null || pathsElement.isJsonNull)) {
            val target = resolveMetadataPath(singlePath) ?: return invalidMetadataResponse()
            if (!target.isFile) return HttpResponse.json(404, """{"error":"file_not_found"}""")
            val value = generateAndCacheMetadata(singlePath, target, type)
            return HttpResponse.json(200, value.toString())
        }
        if (pathsElement?.isJsonArray != true) return invalidMetadataResponse()
        val results = JsonObject()
        pathsElement.asJsonArray.forEach { element ->
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return@forEach
            val relativePath = element.asString
            val target = resolveMetadataPath(relativePath)
            if (target == null) {
                results.add(relativePath, errorObject("Path is outside the user data directory."))
            } else if (!target.isFile) {
                results.add(relativePath, errorObject("File not found or could not process."))
            } else {
                results.add(relativePath, generateAndCacheMetadata(relativePath, target, type))
            }
        }
        return HttpResponse.json(200, results.toString())
    }

    fun allMetadata(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidMetadataResponse()
        val prefix = request.stringValue("prefix")
        return metadata.read { index ->
            if (prefix.isBlank()) {
                HttpResponse.json(200, index.toString())
            } else {
                val filtered = JsonObject()
                index.getAsJsonObject("images").entrySet()
                    .filter { it.key.startsWith(prefix) }
                    .forEach { (key, value) -> filtered.add(key, value.deepCopy()) }
                HttpResponse.json(200, JsonObject().apply {
                    addProperty("version", index.get("version")?.asInt ?: 1)
                    add("images", filtered)
                }.toString())
            }
        }
    }

    @Synchronized
    fun cleanupMetadata(): HttpResponse {
        val removed = metadata.update { index ->
            val images = index.getAsJsonObject("images")
            val orphaned = images.entrySet().map { it.key }.filter { relativePath ->
                resolveMetadataPath(relativePath)?.isFile != true
            }
            orphaned.forEach(images::remove)
            orphaned.sorted()
        }
        return HttpResponse.json(200, JsonObject().apply {
            add("removed", JsonArray().apply { removed.forEach(::add) })
            addProperty("count", removed.size)
        }.toString())
    }

    fun getFolders(): HttpResponse = metadata.read { index ->
        HttpResponse.json(200, index.getAsJsonArray("folders").toString())
    }

    @Synchronized
    fun createFolder(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidMetadataResponse()
        val name = request.stringValue("name").trim()
        if (name.isBlank()) return invalidMetadataResponse()
        val folder = JsonObject().apply {
            addProperty("id", randomToken())
            addProperty("name", name)
            addProperty("thumbnailFile", "")
        }
        metadata.update { index -> index.getAsJsonArray("folders").add(folder) }
        return HttpResponse.json(200, folder.toString())
    }

    @Synchronized
    fun updateFolder(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidMetadataResponse()
        val id = request.stringValue("id")
        if (id.isBlank()) return invalidMetadataResponse()
        val updated = metadata.update { index ->
            val folder = findFolder(index, id) ?: return@update null
            request.get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.let { folder.addProperty("name", it.asString) }
            request.get("thumbnailFile")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.let { folder.addProperty("thumbnailFile", it.asString) }
            folder.deepCopy()
        } ?: return HttpResponse.json(404, """{"error":"folder_not_found"}""")
        return HttpResponse.json(200, updated.toString())
    }

    @Synchronized
    fun deleteFolder(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidMetadataResponse()
        val id = request.stringValue("id")
        if (id.isBlank()) return invalidMetadataResponse()
        val deleted = metadata.update { index ->
            val folders = index.getAsJsonArray("folders")
            val folder = folders.firstOrNull { it.isJsonObject && it.asJsonObject.stringValue("id") == id }
                ?: return@update false
            folders.remove(folder)
            index.getAsJsonObject("images").entrySet().forEach { (_, element) ->
                val folderIds = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                    ?.get("folderIds")?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@forEach
                folderIds.removeAll { it.isJsonPrimitive && it.asString == id }
            }
            true
        }
        return if (deleted) okResponse() else HttpResponse.json(404, """{"error":"folder_not_found"}""")
    }

    @Synchronized
    fun assignFolder(body: String): HttpResponse = changeFolderAssignment(body, assign = true)

    @Synchronized
    fun unassignFolder(body: String): HttpResponse = changeFolderAssignment(body, assign = false)

    @Synchronized
    fun setFolderThumbnails(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidMetadataResponse()
        val updates = request.get("updates")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: return invalidMetadataResponse()
        if (updates.any { element ->
                !element.isJsonObject || element.asJsonObject.stringValue("id").isBlank() ||
                    element.asJsonObject.get("thumbnailFile")?.let {
                        !it.isJsonPrimitive || !it.asJsonPrimitive.isString
                    } != false
            }
        ) return invalidMetadataResponse()
        metadata.update { index ->
            updates.forEach { element ->
                val update = element.asJsonObject
                findFolder(index, update.stringValue("id"))
                    ?.addProperty("thumbnailFile", update.stringValue("thumbnailFile"))
            }
        }
        return okResponse()
    }

    private fun changeFolderAssignment(body: String, assign: Boolean): HttpResponse {
        val request = parseObject(body) ?: return invalidMetadataResponse()
        val id = request.stringValue("id")
        val relativePaths = request.stringArray("paths") ?: return invalidMetadataResponse()
        if (id.isBlank() || relativePaths.any { backgroundFile(it) == null }) return invalidMetadataResponse()
        val result = metadata.update { index ->
            if (assign && findFolder(index, id) == null) return@update false
            val images = index.getAsJsonObject("images")
            relativePaths.forEach { relativePath ->
                val file = backgroundFile(relativePath) ?: return@forEach
                if (!file.isFile && assign) return@forEach
                val image = images.get(relativePath)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
                    ?: if (assign) JsonObject().also { images.add(relativePath, it) } else return@forEach
                val folderIds = image.get("folderIds")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                    ?: JsonArray().also { image.add("folderIds", it) }
                if (assign && folderIds.none { it.isJsonPrimitive && it.asString == id }) {
                    folderIds.add(id)
                } else if (!assign) {
                    folderIds.removeAll { it.isJsonPrimitive && it.asString == id }
                }
            }
            true
        }
        return if (result) okResponse() else HttpResponse.json(404, """{"error":"folder_not_found"}""")
    }

    private fun generateAndCacheMetadata(
        relativePath: String,
        file: File,
        type: String,
        force: Boolean = false
    ): JsonObject =
        metadata.update { index ->
            val images = index.getAsJsonObject("images")
            val cached = images.get(relativePath)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            val mtime = file.lastModified()
            if (!force && cached?.get("mtime")?.takeIf { it.isJsonPrimitive }?.asLong == mtime && cached.has("hash")) {
                return@update cached.deepCopy()
            }
            val bytes = file.readBytes()
            val dimensions = imageDimensions(bytes, file.extension.lowercase())
            val generated = JsonObject().apply {
                addProperty("hash", bytes.sha256())
                dimensions?.let { (width, height) -> addProperty("aspectRatio", width.toDouble() / height) }
                addProperty("isAnimated", isAnimated(bytes, file.extension.lowercase()))
                addProperty("dominantColor", "#808080")
                add(
                    "folderIds",
                    cached?.get("folderIds")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.deepCopy()
                        ?: JsonArray()
                )
                addProperty("addedTimestamp", cached?.get("addedTimestamp")?.asLong ?: mtime)
                addProperty("thumbnailResolution", if (type == "bg") 160 * 90 else 96 * 144)
                addProperty("mtime", mtime)
            }
            images.add(relativePath, generated)
            generated.deepCopy()
        }

    private fun resolveMetadataPath(relativePath: String): File? =
        runCatching { SafePath.child(paths.userDataDir, relativePath) }.getOrNull()

    private fun backgroundFile(relativePath: String): File? {
        if (!relativePath.startsWith("backgrounds/")) return null
        return runCatching { SafePath.child(paths.backgroundsDir, relativePath.removePrefix("backgrounds/")) }.getOrNull()
    }

    private fun imageUrlRelativePath(value: String): String? {
        val prefixes = listOf("/user/images/", "user/images/")
        return prefixes.firstNotNullOfOrNull { prefix ->
            value.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        }?.takeIf { it.isNotBlank() }
    }

    private fun acceptsMediaType(extension: String, type: Int): Boolean = when {
        extension in IMAGE_EXTENSIONS -> type and IMAGE_TYPE != 0
        extension in VIDEO_EXTENSIONS -> type and VIDEO_TYPE != 0
        extension in AUDIO_EXTENSIONS -> type and AUDIO_TYPE != 0
        else -> false
    }

    private fun imageDimensions(bytes: ByteArray, extension: String): Pair<Int, Int>? = when (extension) {
        "png" -> if (bytes.size >= 24) bytes.bigEndianInt(16) to bytes.bigEndianInt(20) else null
        "gif" -> if (bytes.size >= 10) bytes.littleEndianShort(6) to bytes.littleEndianShort(8) else null
        "jpg", "jpeg", "jfif" -> jpegDimensions(bytes)
        "webp" -> webpDimensions(bytes)
        "bmp" -> bmpDimensions(bytes)
        else -> null
    }?.takeIf { it.first > 0 && it.second > 0 }

    private fun webpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 20 || !bytes.hasAscii(0, "RIFF") || !bytes.hasAscii(8, "WEBP")) return null
        return when {
            bytes.hasAscii(12, "VP8X") && bytes.size >= 30 ->
                (bytes.littleEndian24(24) + 1) to (bytes.littleEndian24(27) + 1)
            bytes.hasAscii(12, "VP8L") && bytes.size >= 25 && bytes[20].toInt() and 0xff == 0x2f -> {
                val b0 = bytes[21].toInt() and 0xff
                val b1 = bytes[22].toInt() and 0xff
                val b2 = bytes[23].toInt() and 0xff
                val b3 = bytes[24].toInt() and 0xff
                ((b0 or ((b1 and 0x3f) shl 8)) + 1) to
                    ((((b1 and 0xc0) ushr 6) or (b2 shl 2) or ((b3 and 0x0f) shl 10)) + 1)
            }
            bytes.hasAscii(12, "VP8 ") && bytes.size >= 30 &&
                bytes[23].toInt() and 0xff == 0x9d &&
                bytes[24].toInt() and 0xff == 0x01 &&
                bytes[25].toInt() and 0xff == 0x2a ->
                (bytes.littleEndianShort(26) and 0x3fff) to (bytes.littleEndianShort(28) and 0x3fff)
            else -> null
        }
    }

    private fun bmpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 26 || !bytes.hasAscii(0, "BM")) return null
        val width = bytes.littleEndianInt(18)
        val rawHeight = bytes.littleEndianInt(22).toLong()
        val height = kotlin.math.abs(rawHeight).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        return width to height
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var offset = 2
        while (offset + 8 < bytes.size) {
            if (bytes[offset].toInt() and 0xff != 0xff) return null
            val marker = bytes[offset + 1].toInt() and 0xff
            val length = bytes.unsignedShort(offset + 2)
            if (marker in JPEG_SOF_MARKERS && offset + 8 < bytes.size) {
                return bytes.unsignedShort(offset + 7) to bytes.unsignedShort(offset + 5)
            }
            if (length < 2) return null
            offset += 2 + length
        }
        return null
    }

    private fun isAnimated(bytes: ByteArray, extension: String): Boolean = when (extension) {
        "gif" -> true
        "mp4", "webm" -> true
        "png" -> bytes.indexOfSequence("acTL".toByteArray()) >= 0
        "webp" -> bytes.indexOfSequence("ANIM".toByteArray()) >= 0 ||
            bytes.indexOfSequence("ANMF".toByteArray()) >= 0
        else -> false
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ByteArray.bigEndianInt(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun ByteArray.littleEndianShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.littleEndian24(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16)

    private fun ByteArray.littleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.hasAscii(offset: Int, value: String): Boolean =
        offset >= 0 && offset + value.length <= size && value.indices.all { index ->
            this[offset + index] == value[index].code.toByte()
        }

    private fun ByteArray.unsignedShort(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.indexOfSequence(target: ByteArray): Int {
        if (target.isEmpty() || target.size > size) return -1
        for (index in 0..size - target.size) {
            if (target.indices.all { offset -> this[index + offset] == target[offset] }) return index
        }
        return -1
    }

    private fun findFolder(index: JsonObject, id: String): JsonObject? = index.getAsJsonArray("folders")
        .firstOrNull { it.isJsonObject && it.asJsonObject.stringValue("id") == id }?.asJsonObject

    private fun randomToken(): String {
        val bytes = ByteArray(18)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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

    private fun JsonObject.intValue(name: String): Int? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt

    private fun JsonObject.stringArray(name: String): List<String>? = get(name)
        ?.takeIf(JsonElement::isJsonArray)?.asJsonArray
        ?.takeIf { array -> array.all { it.isJsonPrimitive && it.asJsonPrimitive.isString } }
        ?.map(JsonElement::getAsString)

    private fun errorObject(message: String): JsonObject = JsonObject().apply { addProperty("error", message) }

    private fun invalidImageResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_image"}""")

    private fun invalidMetadataResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_image_metadata"}""")

    private fun okResponse(): HttpResponse = HttpResponse.json(200, """{"ok":true}""")

    private companion object {
        const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        const val IMAGE_TYPE = 1
        const val VIDEO_TYPE = 2
        const val AUDIO_TYPE = 4
        val IMAGE_EXTENSIONS = setOf("bmp", "png", "jpg", "jpeg", "jfif", "webp", "gif")
        val VIDEO_EXTENSIONS = setOf("mp4", "avi", "mov", "wmv", "flv", "webm", "3gp", "mkv", "mpg")
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "aiff")
        val MEDIA_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
        val JPEG_SOF_MARKERS = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
        val SECURE_RANDOM = SecureRandom()
    }
}

internal class ImageMetadataStore(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
    private val lock = locks.getOrPut(paths.imageMetadataFile.canonicalPath) { ReentrantLock() }

    fun <T> read(block: (JsonObject) -> T): T = lock.withLock { block(loadIndex()) }

    fun <T> update(block: (JsonObject) -> T): T = lock.withLock {
        val index = loadIndex()
        val result = block(index)
        store.writeText(paths.imageMetadataFile, index.toString())
        result
    }

    fun renamePath(oldPath: String, newPath: String) {
        update { index ->
            val images = index.getAsJsonObject("images")
            images.remove(oldPath)?.let { images.add(newPath, it) }
            val oldName = oldPath.substringAfterLast('/')
            val newName = newPath.substringAfterLast('/')
            index.getAsJsonArray("folders").forEach { element ->
                val folder = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
                if (folder.get("thumbnailFile")?.asString == oldName) {
                    folder.addProperty("thumbnailFile", newName)
                }
            }
        }
    }

    fun removePath(relativePath: String) {
        update { index ->
            index.getAsJsonObject("images").remove(relativePath)
            val fileName = relativePath.substringAfterLast('/')
            index.getAsJsonArray("folders").forEach { element ->
                val folder = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
                if (folder.get("thumbnailFile")?.asString == fileName) {
                    folder.addProperty("thumbnailFile", "")
                }
            }
        }
    }

    private fun loadIndex(): JsonObject {
        val index = store.readJsonObject(paths.imageMetadataFile) ?: defaultIndex()
        if (index.get("version")?.isJsonPrimitive != true) index.addProperty("version", 1)
        if (index.get("images")?.isJsonObject != true) index.add("images", JsonObject())
        if (index.get("folders")?.isJsonArray != true) index.add("folders", JsonArray())
        return index
    }

    private fun defaultIndex(): JsonObject = JsonObject().apply {
        addProperty("version", 1)
        add("images", JsonObject())
        add("folders", JsonArray())
    }

    private companion object {
        val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
