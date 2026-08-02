package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import com.stapk.mobile.nativeadapter.vector.VectorRoutes
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoFileUpload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

class NativeRouterTest {
    @Test
    fun `injected vector routes accept only the supported POST endpoints`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-vector-router").toFile())
        val server = NativeHttpServer(paths, vectorRoutes = recordingVectorRoutes())
        val pathsToExpectedBodies = mapOf(
            "/api/vector/list" to "list",
            "/api/vector/insert" to "insert",
            "/api/vector/delete" to "delete",
            "/api/vector/query" to "query",
            "/api/vector/query-multi" to "query-multi",
            "/api/vector/purge" to "purge",
            "/api/vector/purge-all" to "purge-all",
            "/api/stapk/embeddings/config/get" to "config-get",
            "/api/stapk/embeddings/config/save" to "config-save",
            "/api/stapk/embeddings/test" to "config-test"
        )

        server.start()
        try {
            pathsToExpectedBodies.forEach { (path, expected) ->
                val (status, body) = post(server, path)
                assertEquals(200, status)
                assertEquals("{\"route\":\"$expected\"}", body)
            }
            val get = URL("http://127.0.0.1:${server.listeningPort}/api/vector/list").openConnection() as HttpURLConnection
            get.requestMethod = "GET"
            assertEquals(404, get.responseCode)
            assertEquals(404, post(server, "/api/vector/missing").first)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `JSON content type defaults to UTF-8 without overriding explicit charsets`() {
        assertEquals(
            "application/json; charset=UTF-8",
            NativeHttpServer.normalizeJsonContentType("application/json")
        )
        assertEquals(
            "application/problem+json; charset=UTF-8",
            NativeHttpServer.normalizeJsonContentType("application/problem+json")
        )
        assertEquals(
            "application/json; charset=ISO-8859-1",
            NativeHttpServer.normalizeJsonContentType("application/json; charset=ISO-8859-1")
        )
        assertEquals(
            "application/x-www-form-urlencoded",
            NativeHttpServer.normalizeJsonContentType("application/x-www-form-urlencoded")
        )
    }

    @Test
    fun `official NanoFileUpload streaming parser is available`() {
        assertEquals("fi.iki.elonen.NanoFileUpload", NanoFileUpload::class.java.name)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate method and path registration fails`() {
        val router = NativeRouter()
        router.post("/api/settings/save") { HttpResponse.json(200, "{}") }

        router.post("/api/settings/save") { HttpResponse.json(200, "{}") }
    }

    @Test
    fun `method mismatch returns null`() {
        val router = NativeRouter()
        val response = HttpResponse.json(200, "{\"ok\":true}")
        router.get("/api/ping") { response }

        assertNull(router.dispatch(request(method = "POST", path = "/api/ping")))
        assertSame(response, router.dispatch(request(method = "GET", path = "/api/ping")))
    }

    @Test
    fun `unknown api request returns endpoint not found JSON instead of static response`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        paths.webDir.mkdirs()
        paths.webDir.resolve("index.html").writeText("static index")
        val server = NativeHttpServer(paths)
        server.start()
        try {
            val connection = URL("http://127.0.0.1:${server.listeningPort}/api/unknown")
                .openConnection() as HttpURLConnection

            assertEquals(404, connection.responseCode)
            assertEquals("application/json; charset=utf-8", connection.contentType)
            assertEquals(
                "{\"error\":\"endpoint_not_found\"}",
                connection.errorStream.bufferedReader().use { it.readText() }
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `single user profile route keeps the official settings snapshots UI reachable`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-current-user").toFile())
        SettingsController(paths).saveSettings("""{"username":"Step8"}""")
        val server = NativeHttpServer(paths)
        server.start()
        try {
            val connection = URL("http://127.0.0.1:${server.listeningPort}/api/users/me")
                .openConnection() as HttpURLConnection

            assertEquals(200, connection.responseCode)
            val user = JsonParser.parseString(connection.inputStream.bufferedReader().use { it.readText() }).asJsonObject
            assertEquals("default-user", user.get("handle").asString)
            assertEquals("Step8", user.get("name").asString)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `HTTP failures appear in diagnostics summary and export creates a ZIP ticket`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-http-diagnostic").toFile())
        val server = NativeHttpServer(paths)
        server.start()
        try {
            val missing = URL("http://127.0.0.1:${server.listeningPort}/api/missing?secret=hidden")
                .openConnection() as HttpURLConnection
            assertEquals(404, missing.responseCode)
            missing.errorStream.close()

            val summary = post(server, "/api/stapk/diagnostics/summary")
            assertEquals(200, summary.first)
            val summaryJson = JsonParser.parseString(summary.second).asJsonObject
            assertEquals(1, summaryJson.getAsJsonObject("counts").get("HTTP").asInt)

            val export = URL("http://127.0.0.1:${server.listeningPort}/api/stapk/diagnostics/export")
                .openConnection() as HttpURLConnection
            export.requestMethod = "POST"
            export.doOutput = true
            export.setRequestProperty("Content-Type", "application/json")
            export.outputStream.use { it.write("{}".toByteArray()) }
            assertEquals(200, export.responseCode)
            val token = requireNotNull(export.getHeaderField("X-stAPK-Export-Token"))
            export.inputStream.close()
            val ticket = requireNotNull(server.findExport(token))
            assertEquals("application/zip", ticket.mimeType)
            assertEquals("stapk-diagnostics.zip", ticket.fileName)

            val diagnostic = paths.logsDir.resolve("diagnostics.jsonl").readText()
            assertTrue(diagnostic.contains("/api/missing"))
            assertFalse(diagnostic.contains("secret"))
            assertFalse(diagnostic.contains("hidden"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `parse request exposes NanoHTTPD upload metadata without moving temporary file`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val temporary = Files.createTempFile("stapk-upload", ".png").toFile()
        temporary.writeBytes(byteArrayOf(1, 2, 3))
        val server = NativeHttpServer(paths)

        try {
            val request = server.parseRequest(
                FakeSession(
                    files = mapOf("avatar" to temporary.absolutePath),
                    parameters = mapOf(
                        "avatar" to listOf("avatar.png"),
                        "caption" to listOf("example"),
                        "source" to listOf("upload")
                    ),
                    query = "source=upload"
                )
            )

            val upload = requireNotNull(request.uploads["avatar"])
            assertEquals("avatar", upload.fieldName)
            assertEquals("avatar.png", upload.originalName)
            assertEquals("application/octet-stream", upload.mimeType)
            assertEquals(temporary, upload.tempFile)
            assertTrue(upload.tempFile.exists())
            assertEquals(listOf("example"), request.form["caption"])
            assertEquals(listOf("upload"), request.query["source"])
            assertNull(request.form["source"])
        } finally {
            temporary.delete()
        }
    }

    @Test
    fun `upload larger than thirty two MiB returns JSON 413`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val temporary = Files.createTempFile("stapk-upload", ".bin").toFile()
        RandomAccessFile(temporary, "rw").use { it.setLength(32L * 1024L * 1024L + 1L) }
        val server = NativeHttpServer(paths)

        try {
            val response = server.serve(
                FakeSession(
                    files = mapOf("upload" to temporary.absolutePath),
                    parameters = mapOf("upload" to listOf("large.bin"))
                )
            )

            assertEquals(413, response.status.requestStatus)
            assertEquals("{\"error\":\"upload_too_large\"}", response.data.bufferedReader().use { it.readText() })
        } finally {
            temporary.delete()
        }
    }

    @Test
    fun `oversized JSON request is rejected before NanoHTTPD parses the body`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val server = NativeHttpServer(paths)
        val session = FakeSession(
            files = mapOf("postData" to "{}"),
            headers = mapOf(
                "content-type" to "application/json",
                "content-length" to (48L * 1024L * 1024L + 1L).toString()
            )
        )

        val response = server.serve(session)

        assertEquals(413, response.status.requestStatus)
        assertEquals("{\"error\":\"upload_too_large\"}", response.data.bufferedReader().use { it.readText() })
        assertFalse(session.parseBodyCalled)
    }

    @Test
    fun `chunked POST is rejected before NanoHTTPD buffers the body`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val server = NativeHttpServer(paths)
        val session = FakeSession(
            files = mapOf("postData" to "{}"),
            headers = mapOf(
                "content-type" to "application/json",
                "transfer-encoding" to "chunked"
            )
        )

        val response = server.serve(session)

        assertEquals(400, response.status.requestStatus)
        assertEquals("{\"error\":\"invalid_request_body\"}", response.data.bufferedReader().use { it.readText() })
        assertFalse(session.parseBodyCalled)
    }

    @Test
    fun `multipart parser preserves declared MIME when it conflicts with file extension`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val boundary = "stapk-multipart-boundary"
        val body = multipartBody(
            boundary,
            "Content-Disposition: form-data; name=\"avatar\"; filename=\"avatar.png\"\r\n" +
                "Content-Type: text/plain\r\n\r\nnot-a-png"
        )

        val request = NativeHttpServer(paths).parseRequest(
            FakeSession(
                files = emptyMap(),
                headers = mapOf(
                    "content-type" to "multipart/form-data; boundary=$boundary",
                    "content-length" to body.size.toString()
                ),
                body = body
            )
        )

        val upload = requireNotNull(request.uploads["avatar"])
        try {
            assertEquals("avatar.png", upload.originalName)
            assertEquals("text/plain", upload.mimeType)
            assertEquals("not-a-png", upload.tempFile.readText())
        } finally {
            upload.tempFile.delete()
        }
        assertPrivateMultipartDirectoryEmpty(paths)
    }

    @Test
    fun `multipart parser defaults missing part MIME to octet stream`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val boundary = "stapk-multipart-boundary"
        val body = multipartBody(
            boundary,
            "Content-Disposition: form-data; name=\"upload\"; filename=\"unknown.bin\"\r\n\r\npayload"
        )

        val request = NativeHttpServer(paths).parseRequest(
            FakeSession(
                files = emptyMap(),
                headers = mapOf(
                    "content-type" to "multipart/form-data; boundary=$boundary",
                    "content-length" to body.size.toString()
                ),
                body = body
            )
        )

        val upload = requireNotNull(request.uploads["upload"])
        try {
            assertEquals("application/octet-stream", upload.mimeType)
        } finally {
            upload.tempFile.delete()
        }
        assertPrivateMultipartDirectoryEmpty(paths)
    }

    @Test
    fun `multipart parser accepts quoted boundary preserves binary content and strips MIME parameters`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val boundary = "stapk-multipart-boundary"
        val expectedBytes = byteArrayOf(0x80.toByte(), 0, 0xff.toByte())
        val body = multipartBinaryBody(
            boundary,
            "Content-Disposition: form-data; name=\"upload\"; filename=\"binary.bin\"\r\n" +
                "Content-Type: application/x-stapk; charset=binary",
            expectedBytes
        )

        val request = NativeHttpServer(paths).parseRequest(
            FakeSession(
                files = emptyMap(),
                headers = mapOf(
                    "content-type" to "multipart/form-data; charset=utf-8; boundary=\"$boundary\"",
                    "content-length" to body.size.toString()
                ),
                body = body
            )
        )

        val upload = requireNotNull(request.uploads["upload"])
        try {
            assertEquals("application/x-stapk", upload.mimeType)
            assertTrue(expectedBytes.contentEquals(upload.tempFile.readBytes()))
        } finally {
            upload.tempFile.delete()
        }
        assertPrivateMultipartDirectoryEmpty(paths)
    }

    @Test
    fun `multipart request with invalid content length returns JSON 400`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val body = multipartBody(
            "stapk-multipart-boundary",
            "Content-Disposition: form-data; name=\"ch_name\"\r\n\r\nMultipart Alice"
        )

        val response = NativeHttpServer(paths).serve(
            FakeSession(
                files = emptyMap(),
                headers = mapOf(
                    "content-type" to "multipart/form-data; boundary=stapk-multipart-boundary",
                    "content-length" to "invalid"
                ),
                body = body
            )
        )

        assertEquals(400, response.status.requestStatus)
        assertEquals("{\"error\":\"invalid_request_body\"}", response.data.bufferedReader().use { it.readText() })
    }

    @Test
    fun `real multipart file limit returns 413 and stops reading before trailing data`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val boundary = "stapk-multipart-boundary"
        val body = multipartBinaryBody(
            boundary,
            "Content-Disposition: form-data; name=\"upload\"; filename=\"large.bin\"\r\n" +
                "Content-Type: application/octet-stream",
            ByteArray(32 * 1024 * 1024 + 1)
        ) + ByteArray(64 * 1024)
        val input = CountingInputStream(ByteArrayInputStream(body))

        val response = NativeHttpServer(paths).serve(
            FakeSession(
                files = emptyMap(),
                headers = mapOf(
                    "content-type" to "multipart/form-data; boundary=$boundary",
                    "content-length" to body.size.toString()
                ),
                input = input
            )
        )

        assertEquals(413, response.status.requestStatus)
        assertEquals("{\"error\":\"upload_too_large\"}", response.data.bufferedReader().use { it.readText() })
        assertTrue(input.bytesRead < body.size.toLong())
        assertPrivateMultipartDirectoryEmpty(paths)
    }

    @Test
    fun `real multipart total part header and malformed limits map to JSON responses and clean uploads`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val boundary = "stapk-multipart-boundary"
        val cases = listOf(
            multipartBody(boundary, *List(33) { index ->
                "Content-Disposition: form-data; name=\"field$index\"\r\n\r\nvalue"
            }.toTypedArray()) to 400,
            multipartBody(
                boundary,
                "Content-Disposition: form-data; name=\"field\"\r\nX-Long: ${"x".repeat(9 * 1024)}\r\n\r\nvalue"
            ) to 400,
            (
                "--$boundary\r\nContent-Disposition: form-data; name=\"first\"; filename=\"first.bin\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\nok\r\n--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"broken\""
                ).toByteArray() to 400,
            ByteArray(33 * 1024 * 1024 + 1) to 413
        )

        cases.forEach { (body, expectedStatus) ->
            val response = NativeHttpServer(paths).serve(
                FakeSession(
                    files = emptyMap(),
                    headers = mapOf(
                        "content-type" to "multipart/form-data; boundary=$boundary",
                        "content-length" to body.size.toString()
                    ),
                    body = body
                )
            )

            assertEquals(expectedStatus, response.status.requestStatus)
            assertPrivateMultipartDirectoryEmpty(paths)
        }
    }

    @Test
    fun `loopback multipart form remains available to existing character route`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router").toFile())
        val server = NativeHttpServer(paths)
        val boundary = "stapk-multipart-boundary"
        server.start()
        try {
            val body = multipartBody(
                boundary,
                "Content-Disposition: form-data; name=\"ch_name\"\r\n\r\nMultipart Alice",
                "Content-Disposition: form-data; name=\"avatar\"; filename=\"avatar.png\"\r\n" +
                    "Content-Type: image/png\r\n\r\nnot-a-png"
            )
            val connection = URL("http://127.0.0.1:${server.listeningPort}/api/characters/create")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { it.write(body) }

            assertEquals(200, connection.responseCode)
            assertEquals("multipart_alice.png", connection.inputStream.bufferedReader().use { it.readText() })
            assertTrue(paths.userDataDir.resolve("multipart").listFiles().orEmpty().isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `chunked multipart imports character card`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-router-real-card").toFile())
        val server = NativeHttpServer(paths)
        val boundary = "stapk-real-card"
        val codec = CharacterCardCodec()
        val card = codec.encodePng(
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            ),
            codec.decodeJson(fixture("character-card-v3.json")).json
        )
        val body = multipartBinaryBody(
            boundary,
            listOf(
                "Content-Disposition: form-data; name=\"file_type\"\r\n\r\npng",
                "Content-Disposition: form-data; name=\"user_name\"\r\n\r\nUser"
            ),
            "Content-Disposition: form-data; name=\"avatar\"; filename=\"character-card-v3.png\"\r\n" +
                "Content-Type: image/png",
            card
        )
        server.start()
        try {
            val requestBody = object : RequestBody() {
                override fun contentType() = "multipart/form-data; boundary=$boundary".toMediaType()
                override fun contentLength(): Long = -1L
                override fun writeTo(sink: BufferedSink) {
                    sink.write(body)
                }
            }
            val request = Request.Builder()
                .url("http://127.0.0.1:${server.listeningPort}/api/characters/import")
                .post(requestBody)
                .build()

            OkHttpClient().newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("v3_character", JsonParser.parseString(requireNotNull(response.body).string())
                    .asJsonObject.get("file_name").asString)
            }
            assertTrue(paths.charactersDir.resolve("v3_character.png").isFile)
            assertPrivateMultipartDirectoryEmpty(paths)
        } finally {
            server.stop()
        }
    }

    private fun request(method: String, path: String): NativeRequest = NativeRequest(
        method = method,
        path = path,
        query = emptyMap(),
        form = emptyMap(),
        bodyText = "",
        uploads = emptyMap()
    )

    private fun post(server: NativeHttpServer, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write("{}".toByteArray()) }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        return status to stream.bufferedReader().use { it.readText() }
    }

    private fun recordingVectorRoutes(): VectorRoutes = object : VectorRoutes {
        override fun list(body: String): HttpResponse = route("list")
        override fun insert(body: String): HttpResponse = route("insert")
        override fun delete(body: String): HttpResponse = route("delete")
        override fun query(body: String): HttpResponse = route("query")
        override fun queryMulti(body: String): HttpResponse = route("query-multi")
        override fun purge(body: String): HttpResponse = route("purge")
        override fun purgeAll(): HttpResponse = route("purge-all")
        override fun getConfig(): HttpResponse = route("config-get")
        override fun saveConfig(body: String): HttpResponse = route("config-save")
        override fun testConfig(): HttpResponse = route("config-test")

        private fun route(name: String): HttpResponse = HttpResponse.json(200, "{\"route\":\"$name\"}")
    }

    private fun multipartBody(boundary: String, vararg parts: String): ByteArray =
        parts.joinToString(
            separator = "\r\n--$boundary\r\n",
            prefix = "--$boundary\r\n",
            postfix = "\r\n--$boundary--\r\n"
        ).toByteArray()

    private fun multipartBinaryBody(boundary: String, headers: String, content: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            output.write("--$boundary\r\n$headers\r\n\r\n".toByteArray())
            output.write(content)
            output.write("\r\n--$boundary--\r\n".toByteArray())
            output.toByteArray()
        }

    private fun multipartBinaryBody(
        boundary: String,
        fields: List<String>,
        fileHeaders: String,
        content: ByteArray
    ): ByteArray = ByteArrayOutputStream().use { output ->
        fields.forEach { field ->
            output.write("--$boundary\r\n$field\r\n".toByteArray())
        }
        output.write("--$boundary\r\n$fileHeaders\r\n\r\n".toByteArray())
        output.write(content)
        output.write("\r\n--$boundary--\r\n".toByteArray())
        output.toByteArray()
    }

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/$name")
    ).use { it.readBytes() }

    private fun assertPrivateMultipartDirectoryEmpty(paths: NativeAdapterPaths) {
        assertTrue(paths.userDataDir.resolve("multipart").listFiles().orEmpty().isEmpty())
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }

    private class FakeSession(
        private val files: Map<String, String>,
        private val parameters: Map<String, List<String>> = emptyMap(),
        private val query: String = "",
        private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
        private val input: InputStream? = null
    ) : NanoHTTPD.IHTTPSession {
        var parseBodyCalled = false
            private set

        override fun execute() = Unit

        override fun getCookies(): NanoHTTPD.CookieHandler = throw UnsupportedOperationException()

        override fun getHeaders(): Map<String, String> = headers

        override fun getInputStream(): InputStream = input ?: ByteArrayInputStream(body)

        override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.POST

        override fun getParms(): Map<String, String> = emptyMap()

        override fun getParameters(): Map<String, List<String>> = parameters

        override fun getQueryParameterString(): String = query

        override fun getUri(): String = "/api/characters/create"

        override fun parseBody(target: MutableMap<String, String>) {
            parseBodyCalled = true
            target.putAll(files)
        }

        override fun getRemoteIpAddress(): String = "127.0.0.1"

        override fun getRemoteHostName(): String = "localhost"
    }
}
