package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Base64

class FileControllerTest {
    @Test
    fun `base64 upper bound rejects payloads that cannot fit before decoding`() {
        assertFalse(exceedsBase64DecodedLimit("AQID", 3))
        assertTrue(exceedsBase64DecodedLimit("AQIDBA==", 3))
    }

    @Test
    fun `sanitize filename removes reserved characters`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-file-sanitize").toFile())
        val controller = FileController(paths)

        val response = controller.sanitizeFilename("""{"fileName":"report<>:\"/\\|?*.txt"}""")

        assertEquals(200, response.statusCode)
        assertEquals(
            "report.txt",
            JsonParser.parseString(response.bodyText).asJsonObject.get("fileName").asString
        )
        assertEquals(400, controller.sanitizeFilename("{}").statusCode)
    }

    @Test
    fun `json and multipart uploads use official path and atomic private storage`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-file-upload").toFile())
        val controller = FileController(paths)
        val text = "native attachment".toByteArray()
        val jsonBody = JsonObject().apply {
            addProperty("name", "notes.txt")
            addProperty("data", Base64.getEncoder().encodeToString(text))
        }.toString()

        val jsonResponse = controller.uploadFile(request(bodyText = jsonBody))
        assertEquals(200, jsonResponse.statusCode)
        assertEquals(
            "user/files/notes.txt",
            JsonParser.parseString(jsonResponse.bodyText).asJsonObject.get("path").asString
        )
        assertArrayEquals(text, paths.uploadsDir.resolve("notes.txt").readBytes())

        val binary = byteArrayOf(0, 1, 2, 3)
        val upload = temporaryUpload(paths, "archive.bin", "application/octet-stream", binary)
        val multipartResponse = controller.uploadFile(
            request(uploads = mapOf("file" to upload))
        )
        assertEquals(200, multipartResponse.statusCode)
        assertEquals(
            "user/files/archive.bin",
            JsonParser.parseString(multipartResponse.bodyText).asJsonObject.get("path").asString
        )
        assertArrayEquals(binary, paths.uploadsDir.resolve("archive.bin").readBytes())

        assertEquals(
            400,
            controller.uploadFile(request(bodyText = """{"name":"../escape.txt","data":"YQ=="}""")).statusCode
        )
        assertFalse(requireNotNull(paths.userDataDir.parentFile).resolve("escape.txt").exists())
    }

    @Test
    fun `upload enforces text and binary size limits before reading content`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-file-limits").toFile())
        val controller = FileController(paths)
        val textUpload = sparseUpload(paths, "large.txt", 8L * 1024L * 1024L + 1L)
        val binaryUpload = sparseUpload(paths, "large.bin", 32L * 1024L * 1024L + 1L)

        assertEquals(413, controller.uploadFile(request(uploads = mapOf("file" to textUpload))).statusCode)
        assertEquals(413, controller.uploadFile(request(uploads = mapOf("file" to binaryUpload))).statusCode)
        assertFalse(paths.uploadsDir.resolve("large.txt").exists())
        assertFalse(paths.uploadsDir.resolve("large.bin").exists())
    }

    @Test
    fun `verify and delete remain inside files root`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-file-actions").toFile())
        val controller = FileController(paths)
        paths.uploadsDir.mkdirs()
        paths.uploadsDir.resolve("exists.txt").writeText("ok")
        val verify = controller.verifyFiles(
            """{"urls":["user/files/exists.txt","/files/missing.txt","../settings.json","https://example.com/a"]}"""
        )

        val result = JsonParser.parseString(verify.bodyText).asJsonObject
        assertTrue(result.get("user/files/exists.txt").asBoolean)
        assertFalse(result.get("/files/missing.txt").asBoolean)
        assertNull(result.get("../settings.json"))
        assertNull(result.get("https://example.com/a"))
        assertEquals(400, controller.deleteFile("""{"path":"../settings.json"}""").statusCode)
        assertTrue(paths.uploadsDir.resolve("exists.txt").isFile)
        assertEquals(200, controller.deleteFile("""{"path":"user/files/exists.txt"}""").statusCode)
        assertFalse(paths.uploadsDir.resolve("exists.txt").exists())
        assertEquals(404, controller.deleteFile("""{"path":"/files/missing.txt"}""").statusCode)
    }

    @Test
    fun `file static responses keep text readable and force dangerous or unknown content to attachment`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-file-static").toFile())
        paths.webDir.mkdirs()
        paths.uploadsDir.mkdirs()
        paths.uploadsDir.resolve("notes.txt").writeText("plain text")
        paths.uploadsDir.resolve("page.html").writeText("<script>alert(1)</script>")
        paths.uploadsDir.resolve("icon.svg").writeText("<svg/>")
        paths.uploadsDir.resolve("code.js").writeText("alert(1)")
        paths.uploadsDir.resolve("payload.blob").writeBytes(byteArrayOf(7, 8, 9))
        val staticAssets = StaticAssetController(paths)

        val text = staticAssets.serve("/user/files/notes.txt")
        assertEquals(200, text.statusCode)
        assertEquals("text/plain; charset=utf-8", text.mimeType)
        assertNull(text.headers["Content-Disposition"])
        assertArrayEquals("plain text".toByteArray(), text.bodyBytes)

        listOf("page.html", "icon.svg", "code.js", "payload.blob").forEach { name ->
            val response = staticAssets.serve("/files/$name")
            assertEquals("application/octet-stream", response.mimeType)
            assertEquals("attachment; filename*=UTF-8''$name", response.headers["Content-Disposition"])
            assertEquals("no-store", response.headers["Cache-Control"])
        }
        assertEquals(403, staticAssets.serve("/files/../settings.json").statusCode)
    }

    @Test
    fun `native server registers file routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-file-routes").toFile())
        paths.webDir.mkdirs()
        paths.webDir.resolve("index.html").writeText("ok")
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            listOf(
                "/api/files/sanitize-filename",
                "/api/files/upload",
                "/api/files/delete",
                "/api/files/verify"
            ).forEach { path ->
                val response = postJson(server, path, "{}")
                assertFalse("route not registered: $path", response.second.contains("endpoint_not_found"))
            }
        } finally {
            server.stop()
        }
    }

    private fun request(
        bodyText: String = "",
        uploads: Map<String, UploadedFile> = emptyMap()
    ): NativeRequest = NativeRequest(
        method = "POST",
        path = "/api/files/upload",
        query = emptyMap(),
        form = emptyMap(),
        bodyText = bodyText,
        uploads = uploads
    )

    private fun temporaryUpload(
        paths: NativeAdapterPaths,
        name: String,
        mimeType: String,
        bytes: ByteArray
    ): UploadedFile {
        val temporary = Files.createTempFile(paths.userDataDir.apply { mkdirs() }.toPath(), "file-", ".upload")
            .toFile().apply { writeBytes(bytes) }
        return UploadedFile("file", name, mimeType, temporary)
    }

    private fun sparseUpload(paths: NativeAdapterPaths, name: String, size: Long): UploadedFile {
        val temporary = Files.createTempFile(paths.userDataDir.apply { mkdirs() }.toPath(), "file-", ".upload").toFile()
        RandomAccessFile(temporary, "rw").use { it.setLength(size) }
        return UploadedFile("file", name, "application/octet-stream", temporary)
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
