package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class BackgroundControllerTest {
    @Test
    fun `upload accepts supported image and video formats and lists official response shape`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-backgrounds").toFile())
        val controller = BackgroundController(paths)
        val uploads = listOf(
            Triple("alpha.png", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)),
            Triple("bravo.jpg", "image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())),
            Triple("charlie.webp", "image/webp", "RIFF0000WEBPVP8 ".toByteArray()),
            Triple("delta.gif", "image/gif", "GIF89a".toByteArray()),
            Triple("echo.mp4", "video/mp4", byteArrayOf(0, 0, 0, 16) + "ftyp".toByteArray()),
            Triple("foxtrot.webm", "video/webm", byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
        )

        uploads.forEach { (name, mimeType, bytes) ->
            val response = controller.uploadBackground(uploadRequest(paths, name, mimeType, bytes))
            assertEquals(200, response.statusCode)
            assertEquals(name, response.bodyText)
            assertArrayEquals(bytes, paths.backgroundsDir.resolve(name).readBytes())
        }
        val all = JsonParser.parseString(controller.allBackgrounds().bodyText).asJsonObject
        assertEquals(160, all.getAsJsonObject("config").get("width").asInt)
        assertEquals(90, all.getAsJsonObject("config").get("height").asInt)
        assertEquals(uploads.map { it.first }, all.getAsJsonArray("images").map {
            it.asJsonObject.get("filename").asString
        })
        assertTrue(all.getAsJsonArray("images")[3].asJsonObject.get("isAnimated").asBoolean)
        assertFalse(all.getAsJsonArray("images")[0].asJsonObject.get("isAnimated").asBoolean)
        val folders = JsonParser.parseString(controller.folders().bodyText).asJsonObject
        assertTrue(folders.getAsJsonArray("folders").isEmpty)
        assertTrue(folders.getAsJsonObject("imageFolderMap").entrySet().isEmpty())
    }

    @Test
    fun `same name upload atomically replaces content while unsupported formats are rejected`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-background-replace").toFile())
        val controller = BackgroundController(paths)
        val first = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1)
        val second = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 2)

        assertEquals(200, controller.uploadBackground(uploadRequest(paths, "scene.png", "image/png", first)).statusCode)
        assertEquals(200, controller.uploadBackground(uploadRequest(paths, "scene.png", "image/png", second)).statusCode)
        assertArrayEquals(second, paths.backgroundsDir.resolve("scene.png").readBytes())
        assertEquals(
            400,
            controller.uploadBackground(
                uploadRequest(paths, "unsafe.svg", "image/svg+xml", "<svg/>".toByteArray())
            ).statusCode
        )
        assertFalse(paths.backgroundsDir.resolve("unsafe.svg").exists())
    }

    @Test
    fun `upload creates metadata and same name replacement refreshes it without losing folders`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-background-upload-metadata").toFile())
        val controller = BackgroundController(paths)
        val first = pngHeader(4, 2) + byteArrayOf(1)
        val second = pngHeader(3, 3) + byteArrayOf(2)

        assertEquals(200, controller.uploadBackground(uploadRequest(paths, "scene.png", "image/png", first)).statusCode)
        var index = JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
        val firstMetadata = index.getAsJsonObject("images").getAsJsonObject("backgrounds/scene.png")
        val firstHash = firstMetadata.get("hash").asString
        assertEquals(2.0, firstMetadata.get("aspectRatio").asDouble, 0.0001)
        firstMetadata.getAsJsonArray("folderIds").add("folder-1")
        paths.imageMetadataFile.writeText(index.toString())

        assertEquals(200, controller.uploadBackground(uploadRequest(paths, "scene.png", "image/png", second)).statusCode)
        index = JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
        val replaced = index.getAsJsonObject("images").getAsJsonObject("backgrounds/scene.png")
        assertFalse(firstHash == replaced.get("hash").asString)
        assertEquals(1.0, replaced.get("aspectRatio").asDouble, 0.0001)
        assertEquals(listOf("folder-1"), replaced.getAsJsonArray("folderIds").map { it.asString })
    }

    @Test
    fun `rename and delete affect only safe target names`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-background-actions").toFile())
        val controller = BackgroundController(paths)
        paths.backgroundsDir.mkdirs()
        paths.backgroundsDir.resolve("old.png").writeBytes(byteArrayOf(1, 2, 3))
        paths.backgroundsDir.resolve("keep.png").writeBytes(byteArrayOf(4, 5, 6))

        assertEquals(200, controller.renameBackground("""{"old_bg":"old.png","new_bg":"new.png"}""").statusCode)
        assertFalse(paths.backgroundsDir.resolve("old.png").exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), paths.backgroundsDir.resolve("new.png").readBytes())
        assertEquals(400, controller.renameBackground("""{"old_bg":"new.png","new_bg":"keep.png"}""").statusCode)
        assertEquals(400, controller.deleteBackground("""{"bg":"../keep.png"}""").statusCode)
        assertTrue(paths.backgroundsDir.resolve("keep.png").isFile)
        assertEquals(200, controller.deleteBackground("""{"bg":"new.png"}""").statusCode)
        assertFalse(paths.backgroundsDir.resolve("new.png").exists())
    }

    @Test
    fun `rename and delete keep metadata and folder thumbnails synchronized`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-background-metadata").toFile())
        val controller = BackgroundController(paths)
        paths.backgroundsDir.mkdirs()
        paths.backgroundsDir.resolve("old.png").writeBytes(byteArrayOf(1, 2, 3))
        paths.imageMetadataFile.parentFile?.mkdirs()
        paths.imageMetadataFile.writeText(
            """{"version":1,"images":{"backgrounds/old.png":{"folderIds":["folder-1"]}},"folders":[{"id":"folder-1","name":"One","thumbnailFile":"old.png"}]}"""
        )

        assertEquals(200, controller.renameBackground("""{"old_bg":"old.png","new_bg":"new.png"}""").statusCode)
        val renamed = JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
        assertFalse(renamed.getAsJsonObject("images").has("backgrounds/old.png"))
        assertTrue(renamed.getAsJsonObject("images").has("backgrounds/new.png"))
        assertEquals("new.png", renamed.getAsJsonArray("folders")[0].asJsonObject.get("thumbnailFile").asString)

        assertEquals(200, controller.deleteBackground("""{"bg":"new.png"}""").statusCode)
        val deleted = JsonParser.parseString(paths.imageMetadataFile.readText()).asJsonObject
        assertFalse(deleted.getAsJsonObject("images").has("backgrounds/new.png"))
        assertEquals("", deleted.getAsJsonArray("folders")[0].asJsonObject.get("thumbnailFile").asString)
    }

    @Test
    fun `private static assets use safe paths compatible MIME and no store caching`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-background-static").toFile())
        paths.webDir.mkdirs()
        paths.backgroundsDir.mkdirs()
        val assets = mapOf(
            "image.png" to ("image/png" to byteArrayOf(1)),
            "image.jpg" to ("image/jpeg" to byteArrayOf(2)),
            "image.webp" to ("image/webp" to byteArrayOf(3)),
            "image.gif" to ("image/gif" to byteArrayOf(4)),
            "video.mp4" to ("video/mp4" to byteArrayOf(5)),
            "video.webm" to ("video/webm" to byteArrayOf(6))
        )
        assets.forEach { (name, expectation) ->
            paths.backgroundsDir.resolve(name).writeBytes(expectation.second)
        }
        val controller = StaticAssetController(paths)

        assets.forEach { (name, expectation) ->
            val response = controller.serve("/backgrounds/$name")
            assertEquals(200, response.statusCode)
            assertEquals(expectation.first, response.mimeType)
            assertEquals("no-store", response.headers["Cache-Control"])
            assertArrayEquals(expectation.second, response.bodyBytes)
        }
        assertEquals(403, controller.serve("/backgrounds/../settings.json").statusCode)
    }

    @Test
    fun `bundled backgrounds fall back after private files while other private roots stay isolated`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-background-fallback").toFile())
        paths.webDir.resolve("backgrounds").mkdirs()
        paths.webDir.resolve("backgrounds/__transparent.png").writeBytes(byteArrayOf(1, 2, 3))
        paths.webDir.resolve("user/images").mkdirs()
        paths.webDir.resolve("user/images/bundled.png").writeBytes(byteArrayOf(7, 8, 9))
        val controller = StaticAssetController(paths)

        val bundled = controller.serve("/backgrounds/__transparent.png")
        assertEquals(200, bundled.statusCode)
        assertArrayEquals(byteArrayOf(1, 2, 3), bundled.bodyBytes)

        paths.backgroundsDir.mkdirs()
        paths.backgroundsDir.resolve("__transparent.png").writeBytes(byteArrayOf(4, 5, 6))
        val privateOverride = controller.serve("/backgrounds/__transparent.png")
        assertEquals(200, privateOverride.statusCode)
        assertArrayEquals(byteArrayOf(4, 5, 6), privateOverride.bodyBytes)
        assertEquals("no-store", privateOverride.headers["Cache-Control"])

        assertEquals(404, controller.serve("/user/images/bundled.png").statusCode)
    }

    @Test
    fun `native server registers background routes and static prefix`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-background-routes").toFile())
        paths.webDir.mkdirs()
        paths.webDir.resolve("index.html").writeText("ok")
        paths.backgroundsDir.mkdirs()
        paths.backgroundsDir.resolve("route.png").writeBytes(byteArrayOf(9, 8, 7))
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            mapOf(
                "/api/backgrounds/all" to "{}",
                "/api/backgrounds/folders" to "{}",
                "/api/backgrounds/rename" to "{}",
                "/api/backgrounds/delete" to "{}",
                "/api/backgrounds/upload" to "{}"
            ).forEach { (path, body) ->
                val response = postJson(server, path, body)
                assertFalse("route not registered: $path", response.second.contains("endpoint_not_found"))
            }
            val static = URL("http://127.0.0.1:${server.listeningPort}/backgrounds/route.png")
                .openConnection() as HttpURLConnection
            assertEquals(200, static.responseCode)
            assertEquals("no-store", static.getHeaderField("Cache-Control"))
            assertArrayEquals(byteArrayOf(9, 8, 7), static.inputStream.use { it.readBytes() })
            static.disconnect()
            val thumbnail = URL(
                "http://127.0.0.1:${server.listeningPort}/thumbnail?type=bg&file=route.png"
            ).openConnection() as HttpURLConnection
            assertEquals(200, thumbnail.responseCode)
            assertEquals("image/png", thumbnail.contentType)
            assertEquals("no-store", thumbnail.getHeaderField("Cache-Control"))
            assertArrayEquals(byteArrayOf(9, 8, 7), thumbnail.inputStream.use { it.readBytes() })
            thumbnail.disconnect()
        } finally {
            server.stop()
        }
    }

    private fun uploadRequest(
        paths: NativeAdapterPaths,
        name: String,
        mimeType: String,
        bytes: ByteArray
    ): NativeRequest {
        val directory = paths.userDataDir.resolve("multipart").apply { mkdirs() }
        val upload = Files.createTempFile(directory.toPath(), "background-", ".upload").toFile().apply {
            writeBytes(bytes)
        }
        return NativeRequest(
            method = "POST",
            path = "/api/backgrounds/upload",
            query = emptyMap(),
            form = emptyMap(),
            bodyText = "",
            uploads = mapOf("avatar" to UploadedFile("avatar", name, mimeType, upload))
        )
    }

    private fun pngHeader(width: Int, height: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
        (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte()
    )

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
