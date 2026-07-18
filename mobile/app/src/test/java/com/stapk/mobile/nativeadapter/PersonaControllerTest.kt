package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class PersonaControllerTest {
    @Test
    fun `empty avatar list is a JSON array`() {
        val controller = PersonaController(createPaths())

        val response = controller.getAvatars()

        assertEquals(200, response.statusCode)
        assertTrue(JsonParser.parseString(response.bodyText!!).asJsonArray.isEmpty)
    }

    @Test
    fun `upload accepts PNG JPEG and WebP magic bytes and returns normalized names`() {
        val paths = createPaths()
        val controller = PersonaController(paths)

        val png = controller.uploadAvatar(upload("../Alice.png", pngBytes()))
        val jpeg = controller.uploadAvatar(upload("Bob.jpg", jpegBytes()))
        val webp = controller.uploadAvatar(upload("Carol.webp", webpBytes()))

        assertEquals("Alice.png", JsonParser.parseString(png.bodyText!!).asJsonObject.get("path").asString)
        assertEquals("Bob.jpg", JsonParser.parseString(jpeg.bodyText!!).asJsonObject.get("path").asString)
        assertEquals("Carol.webp", JsonParser.parseString(webp.bodyText!!).asJsonObject.get("path").asString)
        assertEquals(
            listOf("Alice.png", "Bob.jpg", "Carol.webp"),
            JsonParser.parseString(controller.getAvatars().bodyText!!).asJsonArray.map { it.asString }
        )
    }

    @Test
    fun `overwrite keeps the official avatar ID while static reads use the uploaded magic MIME`() {
        val paths = createPaths()
        val controller = PersonaController(paths)

        controller.uploadAvatar(upload("first.png", pngBytes(), overwriteName = "Alice.png"))
        val overwritten = controller.uploadAvatar(upload("second.jpg", jpegBytes(), overwriteName = "Alice.png"))

        assertEquals("Alice.png", JsonParser.parseString(overwritten.bodyText!!).asJsonObject.get("path").asString)
        val avatar = File(paths.personasDir, "Alice.png")
        assertTrue(avatar.isFile)
        assertArrayEquals(jpegBytes(), avatar.readBytes())
        assertEquals("image/jpeg", controller.serveAvatar("Alice.png").mimeType)
        assertFalse(File(paths.personasDir, "Alice.jpg").exists())
    }

    @Test
    fun `upload serializes quote-containing avatar IDs as valid JSON`() {
        assumeFalse(System.getProperty("os.name")?.startsWith("Windows") == true)
        val controller = PersonaController(createPaths())

        val response = controller.uploadAvatar(upload("Alice \"quoted\".png", pngBytes()))

        assertEquals("Alice \"quoted\".png", JsonParser.parseString(response.bodyText!!).asJsonObject.get("path").asString)
    }

    @Test
    fun `upload rejects invalid magic bytes without creating an avatar`() {
        val paths = createPaths()
        val controller = PersonaController(paths)

        val response = controller.uploadAvatar(upload("malicious.png", "not an image".toByteArray()))

        assertEquals(400, response.statusCode)
        assertEquals("invalid_avatar", JsonParser.parseString(response.bodyText!!).asJsonObject.get("error").asString)
        assertTrue(paths.personasDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `delete rejects traversal and deletes an existing avatar with an empty object`() {
        val paths = createPaths()
        val controller = PersonaController(paths)
        controller.uploadAvatar(upload("Alice.png", pngBytes()))

        val traversal = controller.deleteAvatar("""{"avatar":"../settings.json"}""")
        val deleted = controller.deleteAvatar("""{"avatar":"Alice.png"}""")

        assertEquals(400, traversal.statusCode)
        assertEquals(200, deleted.statusCode)
        assertEquals("{}", deleted.bodyText)
        assertFalse(File(paths.personasDir, "Alice.png").exists())
    }

    @Test
    fun `serves an avatar only from the persona directory`() {
        val paths = createPaths()
        val controller = PersonaController(paths)
        controller.uploadAvatar(upload("Alice.png", pngBytes()))

        val response = controller.serveAvatar("Alice.png")
        val traversal = controller.serveAvatar("../user_config/settings.json")

        assertEquals(200, response.statusCode)
        assertEquals("image/png", response.mimeType)
        assertArrayEquals(pngBytes(), response.bodyBytes)
        assertEquals(400, traversal.statusCode)
    }

    @Test
    fun `loopback persona thumbnail route serves the stored avatar`() {
        val paths = createPaths()
        PersonaController(paths).uploadAvatar(upload("Alice.png", pngBytes()))
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val connection = URL("http://127.0.0.1:${server.listeningPort}/thumbnail?type=persona&file=Alice.png")
                .openConnection() as HttpURLConnection

            assertEquals(200, connection.responseCode)
            assertEquals("image/png", connection.contentType)
            assertArrayEquals(pngBytes(), connection.inputStream.use { it.readBytes() })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `static avatar rejects magic mismatch and uses JPEG MIME type`() {
        val paths = createPaths()
        val controller = PersonaController(paths)
        controller.uploadAvatar(upload("Alice.jpg", jpegBytes()))
        File(paths.personasDir, "Alice.jpg").writeText("not a jpeg")

        assertEquals(400, controller.serveAvatar("Alice.jpg").statusCode)
        controller.uploadAvatar(upload("Alice.jpg", jpegBytes()))
        assertEquals("image/jpeg", controller.serveAvatar("Alice.jpg").mimeType)
    }

    @Test
    fun `loopback multipart upload returns the upstream path envelope`() {
        val paths = createPaths()
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val boundary = "stapk-boundary"
            val payload = buildString {
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"avatar\"; filename=\"Alice.png\"\r\n")
                append("Content-Type: image/png\r\n\r\n")
            }.toByteArray() + pngBytes() + "\r\n--$boundary--\r\n".toByteArray()
            val connection = URL("http://127.0.0.1:${server.listeningPort}/api/avatars/upload").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Length", payload.size.toString())
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { it.write(payload) }

            val statusCode = connection.responseCode
            val response = (if (statusCode >= 400) connection.errorStream else connection.inputStream).bufferedReader().readText()
            assertEquals("avatar upload response: $response", 200, statusCode)
            assertEquals("Alice.png", JsonParser.parseString(response).asJsonObject.get("path").asString)
        } finally {
            server.stop()
        }
    }

    private fun createPaths(): NativeAdapterPaths =
        NativeAdapterPaths(Files.createTempDirectory("stapk-personas").toFile())

    private fun upload(name: String, bytes: ByteArray, overwriteName: String? = null): NativeRequest {
        val file = Files.createTempFile("stapk-avatar", ".upload").toFile().apply { writeBytes(bytes) }
        return NativeRequest(
            method = "POST",
            path = "/api/avatars/upload",
            query = emptyMap(),
            form = overwriteName?.let { mapOf("overwrite_name" to listOf(it)) }.orEmpty(),
            bodyText = "",
            uploads = mapOf("avatar" to UploadedFile("avatar", name, "application/octet-stream", file))
        )
    }

    private fun pngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00
    )

    private fun jpegBytes(): ByteArray = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00)

    private fun webpBytes(): ByteArray = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x10, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38
    )
}
