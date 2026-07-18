package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Base64

class ImageMetadataControllerTest {
    @Test
    fun `image upload list folders static read and delete use private user images root`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-images").toFile())
        val controller = ImageMetadataController(paths)
        val png = pngHeader(width = 2, height = 1)
        val uploadBody = JsonObject().apply {
            addProperty("image", Base64.getEncoder().encodeToString(png))
            addProperty("format", "png")
            addProperty("filename", "portrait.v1")
            addProperty("ch_name", "Alice")
        }.toString()

        val uploaded = controller.uploadImage(uploadBody)
        assertEquals(200, uploaded.statusCode)
        assertEquals(
            "user/images/Alice/portrait.png",
            JsonParser.parseString(uploaded.bodyText).asJsonObject.get("path").asString
        )
        assertArrayEquals(png, paths.userImagesDir.resolve("Alice/portrait.png").readBytes())
        paths.userImagesDir.resolve("Alice/clip.mp4").writeBytes(byteArrayOf(0, 0, 0, 16))

        val imagesOnly = JsonParser.parseString(
            controller.listImages("""{"folder":"Alice","type":1,"sortField":"name","sortOrder":"asc"}""").bodyText
        ).asJsonArray
        assertEquals(listOf("portrait.png"), imagesOnly.map { it.asString })
        val imageAndVideo = JsonParser.parseString(
            controller.listImages("""{"folder":"Alice","type":3,"sortField":"name","sortOrder":"asc"}""").bodyText
        ).asJsonArray
        assertEquals(listOf("clip.mp4", "portrait.png"), imageAndVideo.map { it.asString })
        assertEquals(
            listOf("Alice"),
            JsonParser.parseString(controller.listImageFolders().bodyText).asJsonArray.map { it.asString }
        )

        val staticResponse = StaticAssetController(paths).serve("/user/images/Alice/portrait.png")
        assertEquals(200, staticResponse.statusCode)
        assertEquals("image/png", staticResponse.mimeType)
        assertEquals("no-store", staticResponse.headers["Cache-Control"])
        assertArrayEquals(png, staticResponse.bodyBytes)
        assertEquals(403, StaticAssetController(paths).serve("/user/images/../settings.json").statusCode)

        paths.imageMetadataFile.writeText(
            """{"version":1,"images":{"user_images/Alice/portrait.png":{"folderIds":[]}},"folders":[]}"""
        )
        assertEquals(400, controller.deleteImage("""{"path":"../portrait.png"}""").statusCode)
        assertEquals(200, controller.deleteImage("""{"path":"user/images/Alice/portrait.png"}""").statusCode)
        assertFalse(paths.userImagesDir.resolve("Alice/portrait.png").exists())
        assertFalse(
            JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
                .getAsJsonObject("images").has("user_images/Alice/portrait.png")
        )
    }

    @Test
    fun `user image static mapping covers every accepted media format`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-image-mime").toFile())
        paths.webDir.mkdirs()
        val directory = paths.userImagesDir.resolve("gallery").apply { mkdirs() }
        val mimeTypes = mapOf(
            "bmp" to "image/bmp",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "jfif" to "image/jpeg",
            "webp" to "image/webp",
            "gif" to "image/gif",
            "mp4" to "video/mp4",
            "avi" to "video/x-msvideo",
            "mov" to "video/quicktime",
            "wmv" to "video/x-ms-wmv",
            "flv" to "video/x-flv",
            "webm" to "video/webm",
            "3gp" to "video/3gpp",
            "mkv" to "video/x-matroska",
            "mpg" to "video/mpeg",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "flac" to "audio/flac",
            "aac" to "audio/aac",
            "m4a" to "audio/mp4",
            "aiff" to "audio/aiff"
        )
        val staticAssets = StaticAssetController(paths)

        mimeTypes.forEach { (extension, expectedMimeType) ->
            directory.resolve("sample.$extension").writeBytes(byteArrayOf(1, 2, 3))
            val response = staticAssets.serve("/user/images/gallery/sample.$extension")
            assertEquals(extension, expectedMimeType, response.mimeType)
        }
    }

    @Test
    fun `folder CRUD assign unassign and thumbnails are atomic metadata updates`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-image-folders").toFile())
        val controller = ImageMetadataController(paths)
        paths.backgroundsDir.mkdirs()
        paths.backgroundsDir.resolve("one.png").writeBytes(pngHeader(1, 1))
        paths.backgroundsDir.resolve("two.png").writeBytes(pngHeader(1, 2))

        val created = JsonParser.parseString(controller.createFolder("""{"name":"Favorites"}""").bodyText)
            .asJsonObject
        val folderId = created.get("id").asString
        assertTrue(folderId.matches(Regex("^[A-Za-z0-9_-]{20,}$")))
        assertEquals("Favorites", created.get("name").asString)
        assertEquals("", created.get("thumbnailFile").asString)

        assertEquals(
            listOf(folderId),
            JsonParser.parseString(controller.getFolders().bodyText).asJsonArray.map {
                it.asJsonObject.get("id").asString
            }
        )
        assertEquals(
            200,
            controller.assignFolder(
                """{"id":"$folderId","paths":["backgrounds/one.png","backgrounds/two.png","backgrounds/missing.png"]}"""
            ).statusCode
        )
        assertEquals(
            200,
            controller.setFolderThumbnails(
                """{"updates":[{"id":"$folderId","thumbnailFile":"one.png"}]}"""
            ).statusCode
        )
        val updated = JsonParser.parseString(
            controller.updateFolder("""{"id":"$folderId","name":"Selected"}""").bodyText
        ).asJsonObject
        assertEquals("Selected", updated.get("name").asString)
        assertEquals("one.png", updated.get("thumbnailFile").asString)

        assertEquals(
            200,
            controller.unassignFolder(
                """{"id":"$folderId","paths":["backgrounds/two.png"]}"""
            ).statusCode
        )
        var index = JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
        assertTrue(index.getAsJsonObject("images").getAsJsonObject("backgrounds/one.png")
            .getAsJsonArray("folderIds").map { it.asString }.contains(folderId))
        assertTrue(index.getAsJsonObject("images").getAsJsonObject("backgrounds/two.png")
            .getAsJsonArray("folderIds").isEmpty)

        assertEquals(200, controller.deleteFolder("""{"id":"$folderId"}""").statusCode)
        index = JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
        assertTrue(index.getAsJsonArray("folders").isEmpty)
        assertTrue(index.getAsJsonObject("images").getAsJsonObject("backgrounds/one.png")
            .getAsJsonArray("folderIds").isEmpty)
        assertTrue(paths.backgroundsDir.resolve("one.png").isFile)
        assertTrue(paths.backgroundsDir.resolve("two.png").isFile)
    }

    @Test
    fun `metadata query all prefix and cleanup preserve valid entries and remove orphans`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-image-metadata").toFile())
        val controller = ImageMetadataController(paths)
        paths.backgroundsDir.mkdirs()
        paths.backgroundsDir.resolve("valid.png").writeBytes(pngHeader(4, 2))

        val single = JsonParser.parseString(
            controller.getMetadata("""{"path":"backgrounds/valid.png","type":"bg"}""").bodyText
        ).asJsonObject
        assertEquals(2.0, single.get("aspectRatio").asDouble, 0.0001)
        assertEquals(64, single.get("hash").asString.length)
        assertTrue(single.get("folderIds").asJsonArray.isEmpty)
        assertNotNull(single.get("addedTimestamp"))

        val batch = JsonParser.parseString(
            controller.getMetadata(
                """{"paths":["backgrounds/valid.png","backgrounds/missing.png","../escape.png"],"type":"bg"}"""
            ).bodyText
        ).asJsonObject
        assertTrue(batch.get("backgrounds/valid.png").asJsonObject.has("hash"))
        assertTrue(batch.get("backgrounds/missing.png").asJsonObject.has("error"))
        assertTrue(batch.get("../escape.png").asJsonObject.has("error"))

        var index = JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
        index.getAsJsonObject("images").add("backgrounds/missing.png", JsonObject().apply {
            add("folderIds", JsonArray())
        })
        index.getAsJsonObject("images").add("../escape.png", JsonObject().apply {
            add("folderIds", JsonArray())
        })
        paths.imageMetadataFile.writeText(index.toString())

        val filtered = JsonParser.parseString(
            controller.allMetadata("""{"prefix":"backgrounds/"}""").bodyText
        ).asJsonObject
        assertTrue(filtered.getAsJsonObject("images").has("backgrounds/valid.png"))
        assertFalse(filtered.has("folders"))

        val cleanup = JsonParser.parseString(controller.cleanupMetadata().bodyText).asJsonObject
        assertEquals(2, cleanup.get("count").asInt)
        assertEquals(
            setOf("backgrounds/missing.png", "../escape.png"),
            cleanup.getAsJsonArray("removed").map { it.asString }.toSet()
        )
        index = JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
        assertTrue(index.getAsJsonObject("images").has("backgrounds/valid.png"))
        assertEquals(1, index.getAsJsonObject("images").size())
    }

    @Test
    fun `metadata reads WebP VP8X and BMP dimensions`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-image-dimensions").toFile())
        val controller = ImageMetadataController(paths)
        paths.backgroundsDir.mkdirs()
        paths.backgroundsDir.resolve("wide.webp").writeBytes(webpVp8xHeader(6, 3))
        paths.backgroundsDir.resolve("tall.bmp").writeBytes(bmpHeader(2, 4))

        val webp = JsonParser.parseString(
            controller.getMetadata("""{"path":"backgrounds/wide.webp","type":"bg"}""").bodyText
        ).asJsonObject
        val bmp = JsonParser.parseString(
            controller.getMetadata("""{"path":"backgrounds/tall.bmp","type":"bg"}""").bodyText
        ).asJsonObject

        assertEquals(2.0, webp.get("aspectRatio").asDouble, 0.0001)
        assertEquals(0.5, bmp.get("aspectRatio").asDouble, 0.0001)
    }

    @Test
    fun `native server registers image and metadata routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-image-routes").toFile())
        paths.webDir.mkdirs()
        paths.webDir.resolve("index.html").writeText("ok")
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            listOf(
                "/api/images/upload",
                "/api/images/list",
                "/api/images/folders",
                "/api/images/delete",
                "/api/image-metadata",
                "/api/image-metadata/all",
                "/api/image-metadata/cleanup",
                "/api/image-metadata/folders/get",
                "/api/image-metadata/folders/create",
                "/api/image-metadata/folders/update",
                "/api/image-metadata/folders/delete",
                "/api/image-metadata/folders/assign",
                "/api/image-metadata/folders/unassign",
                "/api/image-metadata/folders/set-thumbnails"
            ).forEach { path ->
                val response = postJson(server, path, "{}")
                assertFalse("route not registered: $path", response.second.contains("endpoint_not_found"))
            }
        } finally {
            server.stop()
        }
    }

    private fun pngHeader(width: Int, height: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
        (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte()
    )

    private fun webpVp8xHeader(width: Int, height: Int): ByteArray = ByteArray(30).apply {
        "RIFF".toByteArray().copyInto(this, 0)
        "WEBP".toByteArray().copyInto(this, 8)
        "VP8X".toByteArray().copyInto(this, 12)
        this[16] = 10
        writeLittleEndian24(24, width - 1)
        writeLittleEndian24(27, height - 1)
    }

    private fun bmpHeader(width: Int, height: Int): ByteArray = ByteArray(26).apply {
        this[0] = 'B'.code.toByte()
        this[1] = 'M'.code.toByte()
        writeLittleEndianInt(18, width)
        writeLittleEndianInt(22, height)
    }

    private fun ByteArray.writeLittleEndian24(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
    }

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private fun postJson(server: NativeHttpServer, path: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val response = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to response
    }
}
