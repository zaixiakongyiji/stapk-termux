package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpriteControllerTest {
    @Test
    fun `single image upload resolves character stem and delete removes only requested sprite`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-sprites").toFile())
        createCharacter(paths, "alice", "Alice")
        val controller = SpriteController(paths)
        val image = pngBytes()
        val uploaded = temporaryUpload(paths, "image", "joy.png", "image/png", image)
        val request = spriteRequest(
            path = "/api/sprites/upload",
            form = mapOf("name" to listOf("Alice"), "label" to listOf("joy"), "spriteName" to listOf("joy")),
            uploads = mapOf("image" to uploaded)
        )

        assertEquals(200, controller.uploadSprite(request).statusCode)
        assertArrayEquals(image, paths.charactersDir.resolve("alice/sprites/joy.png").readBytes())
        paths.charactersDir.resolve("alice/sprites/joy-1.gif").writeBytes("GIF89a".toByteArray())

        val sprites = JsonParser.parseString(controller.getSprites("Alice").bodyText).asJsonArray
        assertEquals(listOf("joy", "joy"), sprites.map { it.asJsonObject.get("label").asString })
        assertTrue(
            sprites.toString(),
            sprites.all { it.asJsonObject.get("path").asString.startsWith("/characters/alice/") }
        )

        val staticResponse = controller.serveSprite("alice/joy.png")
        assertEquals(200, staticResponse.statusCode)
        assertEquals("image/png", staticResponse.mimeType)
        assertEquals("no-store", staticResponse.headers["Cache-Control"])
        assertArrayEquals(image, staticResponse.bodyBytes)
        assertEquals(403, controller.serveSprite("alice/../alice.json").statusCode)

        assertEquals(200, controller.deleteSprite("""{"name":"Alice","label":"joy"}""").statusCode)
        assertFalse(paths.charactersDir.resolve("alice/sprites/joy.png").exists())
        assertTrue(paths.charactersDir.resolve("alice/sprites/joy-1.gif").isFile)
        assertEquals(400, controller.deleteSprite("""{"name":"../Alice","label":"joy-1"}""").statusCode)
    }

    @Test
    fun `single upload validates supported image and size before replacing existing label`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-sprite-limits").toFile())
        createCharacter(paths, "alice", "Alice")
        val controller = SpriteController(paths)
        val directory = paths.charactersDir.resolve("alice/sprites").apply { mkdirs() }
        directory.resolve("joy.gif").writeBytes("GIF89a-old".toByteArray())
        val invalid = temporaryUpload(paths, "avatar", "joy.svg", "image/svg+xml", "<svg/>".toByteArray())

        assertEquals(
            400,
            controller.uploadSprite(
                spriteRequest(
                    form = mapOf("name" to listOf("Alice"), "label" to listOf("joy")),
                    uploads = mapOf("avatar" to invalid)
                )
            ).statusCode
        )
        assertArrayEquals("GIF89a-old".toByteArray(), directory.resolve("joy.gif").readBytes())

        val hugeFile = Files.createTempFile(paths.userDataDir.apply { mkdirs() }.toPath(), "sprite-", ".upload").toFile()
        RandomAccessFile(hugeFile, "rw").use { it.setLength(32L * 1024L * 1024L + 1L) }
        val huge = UploadedFile("image", "joy.png", "image/png", hugeFile)
        assertEquals(
            413,
            controller.uploadSprite(
                spriteRequest(
                    form = mapOf("name" to listOf("Alice"), "label" to listOf("joy")),
                    uploads = mapOf("image" to huge)
                )
            ).statusCode
        )
        assertArrayEquals("GIF89a-old".toByteArray(), directory.resolve("joy.gif").readBytes())
    }

    @Test
    fun `zip upload imports supported images only after complete validation`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-sprite-zip").toFile())
        createCharacter(paths, "alice", "Alice")
        createCharacter(paths, "bob", "Bob")
        val controller = SpriteController(paths)
        val archive = zipOf(
            "happy.png" to pngBytes(),
            "sad.jpg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        )
        val validUpload = temporaryUpload(paths, "avatar", "sprites.zip", "application/zip", archive)

        val validResponse = controller.uploadSpriteZip(
            spriteRequest(
                path = "/api/sprites/upload-zip",
                form = mapOf("name" to listOf("Alice")),
                uploads = mapOf("avatar" to validUpload)
            )
        )
        assertEquals(200, validResponse.statusCode)
        assertEquals(2, JsonParser.parseString(validResponse.bodyText).asJsonObject.get("count").asInt)
        assertTrue(paths.charactersDir.resolve("alice/sprites/happy.png").isFile)
        assertTrue(paths.charactersDir.resolve("alice/sprites/sad.jpg").isFile)

        val invalidArchive = zipOf(
            "good.png" to pngBytes(),
            "../escape.png" to pngBytes()
        )
        val invalidUpload = temporaryUpload(paths, "image", "invalid.zip", "application/zip", invalidArchive)
        assertEquals(
            400,
            controller.uploadSpriteZip(
                spriteRequest(
                    path = "/api/sprites/upload-zip",
                    form = mapOf("name" to listOf("Bob")),
                    uploads = mapOf("image" to invalidUpload)
                )
            ).statusCode
        )
        assertFalse(paths.charactersDir.resolve("bob/sprites").exists())
        assertFalse(requireNotNull(paths.charactersDir.parentFile).resolve("escape.png").exists())

        val unsupportedArchive = zipOf("notes.txt" to "not an image".toByteArray())
        val unsupportedUpload = temporaryUpload(paths, "avatar", "unsupported.zip", "application/zip", unsupportedArchive)
        assertEquals(
            400,
            controller.uploadSpriteZip(
                spriteRequest(
                    path = "/api/sprites/upload-zip",
                    form = mapOf("name" to listOf("Bob")),
                    uploads = mapOf("avatar" to unsupportedUpload)
                )
            ).statusCode
        )
        assertFalse(paths.charactersDir.resolve("bob/sprites").exists())
    }

    @Test
    fun `native server registers sprite routes and character static mapping`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-sprite-routes").toFile())
        paths.webDir.mkdirs()
        paths.webDir.resolve("index.html").writeText("ok")
        createCharacter(paths, "alice", "Alice")
        paths.charactersDir.resolve("alice/sprites").mkdirs()
        paths.charactersDir.resolve("alice/sprites/joy.png").writeBytes(pngBytes())
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val get = URL("http://127.0.0.1:${server.listeningPort}/api/sprites/get?name=Alice")
                .openConnection() as HttpURLConnection
            assertEquals(200, get.responseCode)
            assertFalse(get.inputStream.bufferedReader().use { it.readText() }.contains("endpoint_not_found"))
            get.disconnect()

            listOf("/api/sprites/upload", "/api/sprites/upload-zip", "/api/sprites/delete").forEach { path ->
                val response = postJson(server, path, "{}")
                assertFalse("route not registered: $path", response.second.contains("endpoint_not_found"))
            }
            val static = URL("http://127.0.0.1:${server.listeningPort}/characters/alice/joy.png")
                .openConnection() as HttpURLConnection
            assertEquals(200, static.responseCode)
            assertEquals("no-store", static.getHeaderField("Cache-Control"))
            assertArrayEquals(pngBytes(), static.inputStream.use { it.readBytes() })
            static.disconnect()
        } finally {
            server.stop()
        }
    }

    private fun createCharacter(paths: NativeAdapterPaths, stem: String, name: String) {
        paths.charactersDir.mkdirs()
        paths.charactersDir.resolve("$stem.json").writeText("""{"name":"$name","data":{"name":"$name"}}""")
    }

    private fun spriteRequest(
        path: String = "/api/sprites/upload",
        form: Map<String, List<String>> = emptyMap(),
        uploads: Map<String, UploadedFile> = emptyMap()
    ): NativeRequest = NativeRequest(
        method = "POST",
        path = path,
        query = emptyMap(),
        form = form,
        bodyText = "",
        uploads = uploads
    )

    private fun temporaryUpload(
        paths: NativeAdapterPaths,
        fieldName: String,
        name: String,
        mimeType: String,
        bytes: ByteArray
    ): UploadedFile {
        val temporary = Files.createTempFile(paths.userDataDir.apply { mkdirs() }.toPath(), "sprite-", ".upload")
            .toFile().apply { writeBytes(bytes) }
        return UploadedFile(fieldName, name, mimeType, temporary)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 1, 0, 0, 0, 1
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
