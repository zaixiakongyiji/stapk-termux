package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

class BackgroundController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
    private val gson = Gson()
    private val metadata = ImageMetadataStore(paths, store)
    private val imageMetadata = ImageMetadataController(paths, store)

    fun allBackgrounds(): HttpResponse {
        paths.backgroundsDir.mkdirs()
        val images = JsonArray()
        paths.backgroundsDir.listFiles { file -> file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .orEmpty()
            .sortedBy(File::getName)
            .forEach { file ->
                images.add(JsonObject().apply {
                    addProperty("filename", file.name)
                    addProperty("isAnimated", isAnimated(file))
                })
            }
        return HttpResponse.json(200, gson.toJson(JsonObject().apply {
            add("images", images)
            add("config", JsonObject().apply {
                addProperty("width", THUMBNAIL_WIDTH)
                addProperty("height", THUMBNAIL_HEIGHT)
            })
        }))
    }

    fun folders(): HttpResponse {
        return metadata.read { index ->
            val imageFolderMap = JsonObject()
            index.getAsJsonObject("images")?.entrySet().orEmpty().forEach { (relativePath, metadata) ->
                if (!relativePath.startsWith("backgrounds/") || !metadata.isJsonObject) return@forEach
                val folderIds = metadata.asJsonObject.get("folderIds")
                    ?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@forEach
                if (!folderIds.isEmpty) imageFolderMap.add(relativePath.substringAfterLast('/'), folderIds.deepCopy())
            }
            HttpResponse.json(200, gson.toJson(JsonObject().apply {
                add("folders", index.get("folders")?.takeIf(JsonElement::isJsonArray)?.deepCopy() ?: JsonArray())
                add("imageFolderMap", imageFolderMap)
            }))
        }
    }

    @Synchronized
    fun uploadBackground(request: NativeRequest): HttpResponse {
        val upload = request.uploads["avatar"] ?: return invalidBackgroundResponse()
        if (!upload.tempFile.isFile || upload.tempFile.length() > MAX_BACKGROUND_BYTES) {
            return invalidBackgroundResponse()
        }
        val name = SafePath.fileName(upload.originalName, "background")
        if (name.extensionLowercase() !in SUPPORTED_EXTENSIONS) return invalidBackgroundResponse()
        val target = backgroundFile(name) ?: return invalidBackgroundResponse()
        return try {
            store.writeBytes(target, upload.tempFile.readBytes())
            imageMetadata.refreshMetadata("backgrounds/$name", "bg", force = true)
            HttpResponse.text(200, name)
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"background_upload_failed"}""")
        }
    }

    @Synchronized
    fun renameBackground(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidBackgroundResponse()
        val oldName = request.stringValue("old_bg")
        val newName = request.stringValue("new_bg")
        if (newName.extensionLowercase() !in SUPPORTED_EXTENSIONS) return invalidBackgroundResponse()
        val source = backgroundFile(oldName) ?: return invalidBackgroundResponse()
        val target = backgroundFile(newName) ?: return invalidBackgroundResponse()
        if (!source.isFile || target.exists()) return invalidBackgroundResponse()
        return try {
            moveWithoutReplace(source, target)
            metadata.renamePath("backgrounds/$oldName", "backgrounds/$newName")
            HttpResponse.text(200, "ok")
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"background_rename_failed"}""")
        }
    }

    @Synchronized
    fun deleteBackground(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidBackgroundResponse()
        val name = request.stringValue("bg")
        val file = backgroundFile(name) ?: return invalidBackgroundResponse()
        if (!file.isFile) return invalidBackgroundResponse()
        return if (file.delete()) {
            metadata.removePath("backgrounds/$name")
            HttpResponse.text(200, "ok")
        } else {
            HttpResponse.json(500, """{"error":"background_delete_failed"}""")
        }
    }

    private fun backgroundFile(name: String): File? {
        if (name.isBlank() || SafePath.fileName(name) != name) return null
        return runCatching { SafePath.child(paths.backgroundsDir, name) }.getOrNull()
    }

    private fun isAnimated(file: File): Boolean = when (file.extension.lowercase()) {
        "gif", "mp4", "webm" -> true
        "webp" -> runCatching { file.readBytes().indexOfSequence("ANIM".toByteArray()) >= 0 }.getOrDefault(false)
        else -> false
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

    private fun String.extensionLowercase(): String = substringAfterLast('.', "").lowercase()

    private fun ByteArray.indexOfSequence(target: ByteArray): Int {
        if (target.isEmpty() || target.size > size) return -1
        for (index in 0..size - target.size) {
            if (target.indices.all { offset -> this[index + offset] == target[offset] }) return index
        }
        return -1
    }

    private fun invalidBackgroundResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_background"}""")

    private companion object {
        const val MAX_BACKGROUND_BYTES = 32L * 1024L * 1024L
        const val THUMBNAIL_WIDTH = 160
        const val THUMBNAIL_HEIGHT = 90
        val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "mp4", "webm")

        fun moveWithoutReplace(source: File, target: File) {
            requireNotNull(target.parentFile).mkdirs()
            try {
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath())
            }
        }
    }
}
