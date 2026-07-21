package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class StaticAssetControllerTest {
    @Test
    fun `serves upstream secrets endpoints and preserves provider status through loopback`() {
        val provider = MockWebServer()
        provider.enqueue(MockResponse().setResponseCode(401).setBody("invalid sk-loopback"))
        provider.enqueue(MockResponse().setResponseCode(429).setBody("rate limited sk-loopback"))
        provider.enqueue(MockResponse().setResponseCode(422).setBody("unprocessable sk-loopback"))
        provider.start()
        val filesDir = Files.createTempDirectory("stapk-secrets-loopback").toFile()
        val paths = NativeAdapterPaths(filesDir)
        paths.webDir.mkdirs()
        File(paths.webDir, "index.html").writeText("<html>loopback</html>")
        val server = NativeHttpServer(paths)
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.listeningPort}"
            val saved = postJson(
                "$baseUrl/api/secrets/write",
                """{"key":"api_key_openai","value":"sk-loopback","label":"Primary"}"""
            )
            val state = postJson("$baseUrl/api/secrets/read", "{}")

            assertEquals(200, saved.statusCode)
            assertTrue(JsonParser.parseString(saved.body).asJsonObject.get("id").asString.isNotBlank())
            assertEquals(200, state.statusCode)
            assertFalse(state.body.contains("sk-loopback"))
            assertTrue(JsonParser.parseString(state.body).asJsonObject.has("api_key_openai"))

            val status = postJson(
                "$baseUrl/api/backends/chat-completions/status",
                """{"chat_completion_source":"openai","reverse_proxy":"${provider.url("/v1")}"}"""
            )
            val generated = postJson(
                "$baseUrl/api/backends/chat-completions/generate",
                """{"chat_completion_source":"openai","reverse_proxy":"${provider.url("/v1")}","messages":[]}"""
            )
            val unprocessable = postJson(
                "$baseUrl/api/backends/chat-completions/generate",
                """{"chat_completion_source":"openai","reverse_proxy":"${provider.url("/v1")}","messages":[]}"""
            )

            assertEquals(401, status.statusCode)
            assertEquals(429, generated.statusCode)
            assertEquals(422, unprocessable.statusCode)
            assertFalse(status.body.contains("sk-loopback"))
            assertFalse(generated.body.contains("sk-loopback"))
            assertFalse(unprocessable.body.contains("sk-loopback"))

            val deleted = postJson("$baseUrl/api/secrets/delete", """{"key":"api_key_openai"}""")
            assertEquals(200, deleted.statusCode)
            assertEquals(200, postJson("$baseUrl/api/secrets/delete", """{"key":"api_key_openai"}""").statusCode)
            assertFalse(
                JsonParser.parseString(postJson("$baseUrl/api/secrets/read", "{}").body)
                    .asJsonObject.has("api_key_openai")
            )
        } finally {
            server.stop()
            provider.shutdown()
        }
    }

    @Test
    fun `serves index html for root path`() {
        val webDir = Files.createTempDirectory("stapk-web").toFile()
        File(webDir, "index.html").writeText("<html>ok</html>")

        val response = StaticAssetController(webDir).serve("/")

        assertTextResponse(response, 200, "text/html; charset=utf-8", "ok")
    }

    @Test
    fun `returns not found for missing static file`() {
        val webDir = Files.createTempDirectory("stapk-web").toFile()

        val response = StaticAssetController(webDir).serve("/missing.js")

        assertTextResponse(response, 404, "text/plain; charset=utf-8", "Not found")
    }

    @Test
    fun `serves JavaScript as text`() {
        val webDir = Files.createTempDirectory("stapk-web").toFile()
        File(webDir, "app.js").writeText("console.log('ok')")

        val response = StaticAssetController(webDir).serve("/app.js")

        assertTextResponse(response, 200, "application/javascript; charset=utf-8", "console.log")
    }

    @Test
    fun `serves binary assets as bytes`() {
        val webDir = Files.createTempDirectory("stapk-web").toFile()
        val content = byteArrayOf(0, 1, 2, 3)
        File(webDir, "image.png").writeBytes(content)

        val response = StaticAssetController(webDir).serve("/image.png")

        assertEquals(200, response.statusCode)
        assertEquals("image/png", response.mimeType)
        assertNull(response.bodyText)
        assertArrayEquals(content, response.bodyBytes)
    }

    @Test
    fun `serves no node web asset extensions with compatible MIME types`() {
        val webDir = Files.createTempDirectory("stapk-web").toFile()
        val controller = StaticAssetController(webDir)
        val binaryContent = byteArrayOf(0, 1, 2, 3)
        val textAssets = listOf(
            "README.md" to ("text/markdown; charset=utf-8" to "# documentation"),
            "notice.txt" to ("text/plain; charset=utf-8" to "notice"),
            "app.js.map" to ("application/json; charset=utf-8" to "{\"version\":3}"),
            "theme.less" to ("text/css; charset=utf-8" to ".theme { color: red; }"),
            "module.ts" to ("application/typescript; charset=utf-8" to "export const enabled = true")
        )

        listOf(
            "font.woff" to "font/woff",
            "favicon.ico" to "image/x-icon",
            "notification.mp3" to "audio/mpeg"
        ).forEach { (fileName, mimeType) ->
            File(webDir, fileName).writeBytes(binaryContent)

            val response = controller.serve("/$fileName")

            assertBinaryResponse(response, mimeType, binaryContent)
        }

        textAssets.forEach { (fileName, expectation) ->
            val (mimeType, content) = expectation
            File(webDir, fileName).writeText(content)

            val response = controller.serve("/$fileName")

            assertTextResponse(response, 200, mimeType, content)
        }
    }

    @Test
    fun `serves third party extension assets from private storage and rejects traversal`() {
        val filesDir = Files.createTempDirectory("stapk-extension-assets").toFile()
        val paths = NativeAdapterPaths(filesDir)
        val script = paths.extensionsDir.resolve("ST-Prompt-Template/dist/index.js").apply {
            parentFile?.mkdirs()
            writeText("export const installed = true;")
        }
        val controller = StaticAssetController(paths)

        val response = controller.serve(
            "/scripts/extensions/third-party/ST-Prompt-Template/dist/index.js"
        )
        val traversal = controller.serve(
            "/scripts/extensions/third-party/../state/extensions.json"
        )

        assertEquals(200, response.statusCode)
        assertEquals("application/javascript; charset=utf-8", response.mimeType)
        assertArrayEquals(script.readBytes(), response.bodyBytes)
        assertEquals("no-store", response.headers["Cache-Control"])
        assertEquals(403, traversal.statusCode)
    }

    @Test
    fun `rejects traversal into sibling web directory`() {
        val parentDir = Files.createTempDirectory("stapk-assets").toFile()
        val webDir = File(parentDir, "web").apply { mkdirs() }
        val siblingDir = File(parentDir, "web-evil").apply { mkdirs() }
        File(siblingDir, "secret.txt").writeText("secret")

        val response = StaticAssetController(webDir).serve("/../web-evil/secret.txt")

        assertTextResponse(response, 403, "text/plain; charset=utf-8", "Forbidden")
    }

    @Test
    fun `rejects non canonical configured web root`() {
        val parentDir = Files.createTempDirectory("stapk-assets").toFile()
        val nestedDir = File(parentDir, "nested").apply { mkdirs() }
        val canonicalWebDir = File(parentDir, "web").apply { mkdirs() }
        File(canonicalWebDir, "index.html").writeText("<html>outside configured root</html>")
        val nonCanonicalWebDir = File(nestedDir, "../web")

        val response = StaticAssetController(nonCanonicalWebDir).serve("/")

        assertTextResponse(response, 403, "text/plain; charset=utf-8", "Forbidden")
    }

    @Test
    fun `serves version and index through loopback server`() {
        val filesDir = Files.createTempDirectory("stapk-files").toFile()
        val paths = NativeAdapterPaths(filesDir)
        paths.webDir.mkdirs()
        File(paths.webDir, "index.html").writeText("<html>loopback</html>")
        paths.webManifestFile.parentFile?.mkdirs()
        paths.webManifestFile.writeText(
            """{"upstream":{"ref":"release","commit":"abc123","version":"1.18.0"}}"""
        )
        val server = NativeHttpServer(paths)

        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.listeningPort}"
            val version = JsonParser.parseString(getResponseBody("$baseUrl/version")).asJsonObject
            assertEquals("SillyTavern:1.18.0:Cohee#1207", version.get("agent").asString)
            assertFalse(version.get("node_runtime").asBoolean)
            assertEquals("1.18.0", version.get("pkgVersion").asString)
            assertEquals("abc123", version.get("gitRevision").asString)
            assertEquals("release", version.get("gitBranch").asString)
            assertEquals(
                "stapk-no-node",
                JsonParser.parseString(getResponseBody("$baseUrl/csrf-token")).asJsonObject.get("token").asString
            )
            assertTrue(getResponseBody("$baseUrl/").contains("loopback"))
            assertTrue(
                JsonParser.parseString(postJson("$baseUrl/api/ping", "{}").body).asJsonObject
                    .get("pong").asBoolean
            )
            assertTrue(getResponseBody("$baseUrl/").contains("loopback"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `persists settings including OpenAI streaming through loopback endpoints`() {
        val filesDir = Files.createTempDirectory("stapk-files").toFile()
        val paths = NativeAdapterPaths(filesDir)
        paths.webDir.mkdirs()
        File(paths.webDir, "index.html").writeText("<html>loopback</html>")
        val server = NativeHttpServer(paths)

        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.listeningPort}"
            val initial = postJson("$baseUrl/api/settings/get", "{}")
            assertEquals(200, initial.statusCode)
            val initialEnvelope = JsonParser.parseString(initial.body).asJsonObject
            val initialSettings = JsonParser.parseString(initialEnvelope.get("settings").asString).asJsonObject
            assertEquals("openai", initialSettings.get("main_api").asString)

            val saved = postJson(
                "$baseUrl/api/settings/save",
                """{"username":"Loopback","main_api":"kobold","oai_settings":{"stream_openai":true}}"""
            )
            assertEquals(200, saved.statusCode)

            val updated = postJson("$baseUrl/api/settings/get", "{}")
            assertEquals(200, updated.statusCode)
            val updatedEnvelope = JsonParser.parseString(updated.body).asJsonObject
            val updatedSettings = JsonParser.parseString(updatedEnvelope.get("settings").asString).asJsonObject
            assertEquals("Loopback", updatedSettings.get("username").asString)
            assertEquals("openai", updatedSettings.get("main_api").asString)
            assertTrue(updatedSettings.getAsJsonObject("oai_settings").get("stream_openai").asBoolean)

            val invalid = postJson("$baseUrl/api/settings/save", "{invalid")
            assertEquals(400, invalid.statusCode)
            assertEquals(
                "invalid_settings",
                JsonParser.parseString(invalid.body).asJsonObject.get("error").asString
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `accepts upstream multipart character create through loopback`() {
        val filesDir = Files.createTempDirectory("stapk-files").toFile()
        val paths = NativeAdapterPaths(filesDir)
        paths.webDir.mkdirs()
        File(paths.webDir, "index.html").writeText("<html>loopback</html>")
        val server = NativeHttpServer(paths)

        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.listeningPort}"
            val created = postMultipart(
                "$baseUrl/api/characters/create",
                mapOf("ch_name" to "Multipart Alice", "description" to "from form")
            )
            assertEquals(200, created.statusCode)
            assertEquals("multipart_alice.png", created.body)

            val all = postJson("$baseUrl/api/characters/all", "{}")
            assertEquals(200, all.statusCode)
            val characters = JsonParser.parseString(all.body).asJsonArray
            assertEquals(1, characters.size())
            assertEquals("Multipart Alice", characters[0].asJsonObject.get("name").asString)
            assertEquals("from form", characters[0].asJsonObject.get("description").asString)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `supports character and chat lifecycle through loopback endpoints`() {
        val filesDir = Files.createTempDirectory("stapk-files").toFile()
        val paths = NativeAdapterPaths(filesDir)
        paths.webDir.resolve("img").mkdirs()
        File(paths.webDir, "index.html").writeText("<html>loopback</html>")
        val avatarBytes = byteArrayOf(9, 8, 7, 6)
        File(paths.webDir, "img/ai4.png").writeBytes(avatarBytes)
        val server = NativeHttpServer(paths)

        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.listeningPort}"
            assertEquals(
                "alice.png",
                postJson("$baseUrl/api/characters/create", """{"ch_name":"Alice"}""").body
            )
            assertEquals(
                200,
                postJson(
                    "$baseUrl/api/characters/edit",
                    """{"avatar_url":"alice.png","ch_name":"Alice Updated","description":"edited"}"""
                ).statusCode
            )
            val character = JsonParser.parseString(
                postJson("$baseUrl/api/characters/get", """{"avatar_url":"alice.png"}""").body
            ).asJsonObject
            assertEquals("Alice Updated", character.get("name").asString)
            assertEquals("edited", character.get("description").asString)

            assertArrayEquals(avatarBytes, getBinary("$baseUrl/characters/alice.png").body)
            assertArrayEquals(
                avatarBytes,
                getBinary("$baseUrl/thumbnail?type=avatar&file=alice.png").body
            )

            val chatBody = """{
                "avatar_url":"alice.png",
                "file_name":"hello",
                "chat":[{"chat_metadata":{}},{"name":"User","mes":"Hi Alice"}]
            }""".trimIndent()
            assertEquals(200, postJson("$baseUrl/api/chats/save", chatBody).statusCode)
            val chat = JsonParser.parseString(
                postJson(
                    "$baseUrl/api/chats/get",
                    """{"avatar_url":"alice.png","file_name":"hello"}"""
                ).body
            ).asJsonArray
            assertEquals("Hi Alice", chat[1].asJsonObject.get("mes").asString)

            val summaries = JsonParser.parseString(
                postJson(
                    "$baseUrl/api/characters/chats",
                    """{"avatar_url":"alice.png"}"""
                ).body
            ).asJsonArray
            assertEquals("hello.jsonl", summaries[0].asJsonObject.get("file_name").asString)
            val search = JsonParser.parseString(
                postJson(
                    "$baseUrl/api/chats/search",
                    """{"avatar_url":"alice.png","query":"alice"}"""
                ).body
            ).asJsonArray
            assertEquals("hello", search[0].asJsonObject.get("file_name").asString)

            assertEquals(
                200,
                postJson(
                    "$baseUrl/api/chats/delete",
                    """{"avatar_url":"alice.png","chatfile":"hello.jsonl"}"""
                ).statusCode
            )
            assertEquals(
                200,
                postJson(
                    "$baseUrl/api/characters/delete",
                    """{"avatar_url":"alice.png","delete_chats":true}"""
                ).statusCode
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `contains foreground startup failure and skips server startup`() {
        var cleanupCalled = false
        var serverStarted = false

        val result = startNativeAdapterSafely(
            startForeground = { error("foreground failed") },
            startServer = {
                serverStarted = true
                NativeAdapterState(NativeAdapterStatus.RUNNING, port = 1234)
            },
            cleanup = { cleanupCalled = true }
        )

        assertEquals(
            NativeAdapterState(
                NativeAdapterStatus.FAILED,
                message = "Unable to start local server"
            ),
            result
        )
        assertTrue(cleanupCalled)
        assertFalse(serverStarted)
    }

    @Test
    fun `contains server startup failure and runs cleanup`() {
        var foregroundStarted = false
        var cleanupCalled = false

        val result = startNativeAdapterSafely(
            startForeground = { foregroundStarted = true },
            startServer = { error("server failed") },
            cleanup = { cleanupCalled = true }
        )

        assertEquals(
            NativeAdapterState(
                NativeAdapterStatus.FAILED,
                message = "Unable to start local server"
            ),
            result
        )
        assertTrue(foregroundStarted)
        assertTrue(cleanupCalled)
    }

    @Test
    fun `returns server state after successful foreground and server startup`() {
        val events = mutableListOf<String>()
        var cleanupCalled = false
        val runningState = NativeAdapterState(NativeAdapterStatus.RUNNING, port = 4321)

        val result = startNativeAdapterSafely(
            startForeground = { events += "foreground" },
            startServer = {
                events += "server"
                runningState
            },
            cleanup = { cleanupCalled = true }
        )

        assertEquals(runningState, result)
        assertEquals(listOf("foreground", "server"), events)
        assertFalse(cleanupCalled)
    }

    @Test
    fun `publishes starting before scheduling server work off the caller thread`() {
        val states = mutableListOf<NativeAdapterState>()
        var serverStarted = false
        var scheduledWork: (() -> Unit)? = null

        startNativeAdapterAsync(
            execute = { work -> scheduledWork = work },
            startServer = {
                serverStarted = true
                NativeAdapterState(NativeAdapterStatus.RUNNING, port = 4321)
            },
            cleanup = {},
            publish = { states += it }
        )

        assertEquals(listOf(NativeAdapterState(NativeAdapterStatus.STARTING)), states)
        assertFalse(serverStarted)

        requireNotNull(scheduledWork).invoke()

        assertTrue(serverStarted)
        assertEquals(
            listOf(
                NativeAdapterState(NativeAdapterStatus.STARTING),
                NativeAdapterState(NativeAdapterStatus.RUNNING, port = 4321)
            ),
            states
        )
    }

    @Test
    fun `rejects file in place of web directory`() {
        val webPath = Files.createTempFile("stapk-web", ".tmp").toFile()

        assertThrows(IllegalStateException::class.java) {
            ensureWebDirectory(webPath)
        }
    }

    @Test
    fun `installs bundled web assets and records their manifest`() {
        val filesDir = Files.createTempDirectory("stapk-files").toFile()
        val assetRoot = createAssetRoot("manifest-v1", "web-v1")
        val paths = NativeAdapterPaths(filesDir)

        val installed = installWebAssetsIfNeeded(paths, DirectoryWebAssetSource(assetRoot))

        assertTrue(installed)
        assertEquals("web-v1", File(paths.webDir, "index.html").readText())
        assertEquals("manifest-v1", paths.webManifestFile.readText())
    }

    @Test
    fun `skips bundled web install when manifest and index are current`() {
        val filesDir = Files.createTempDirectory("stapk-files").toFile()
        val assetRoot = createAssetRoot("manifest-v1", "web-v1")
        val paths = NativeAdapterPaths(filesDir)
        installWebAssetsIfNeeded(paths, DirectoryWebAssetSource(assetRoot))
        File(paths.webDir, "local-marker.txt").writeText("keep")

        val installed = installWebAssetsIfNeeded(paths, DirectoryWebAssetSource(assetRoot))

        assertFalse(installed)
        assertTrue(File(paths.webDir, "local-marker.txt").exists())
    }

    @Test
    fun `refreshes bundled web assets when manifest changes`() {
        val filesDir = Files.createTempDirectory("stapk-files").toFile()
        val paths = NativeAdapterPaths(filesDir)
        val firstAssets = createAssetRoot("manifest-v1", "web-v1")
        installWebAssetsIfNeeded(paths, DirectoryWebAssetSource(firstAssets))
        File(paths.webDir, "stale.js").writeText("stale")
        val updatedAssets = createAssetRoot("manifest-v2", "web-v2")

        val installed = installWebAssetsIfNeeded(paths, DirectoryWebAssetSource(updatedAssets))

        assertTrue(installed)
        assertEquals("web-v2", File(paths.webDir, "index.html").readText())
        assertEquals("manifest-v2", paths.webManifestFile.readText())
        assertFalse(File(paths.webDir, "stale.js").exists())
    }

    @Test
    fun `recovers previous web directory after interrupted activation`() {
        val filesDir = Files.createTempDirectory("stapk-files").toFile()
        val paths = NativeAdapterPaths(filesDir)
        paths.webDir.mkdirs()
        File(paths.webDir, "index.html").writeText("partial-new")
        val previousWebDir = File(filesDir, "web.previous").apply { mkdirs() }
        File(previousWebDir, "index.html").writeText("stable-old")
        paths.webManifestFile.parentFile?.mkdirs()
        paths.webManifestFile.writeText("manifest-v1")
        val assetRoot = createAssetRoot("manifest-v1", "bundled-v1")

        val installed = installWebAssetsIfNeeded(paths, DirectoryWebAssetSource(assetRoot))

        assertFalse(installed)
        assertEquals("stable-old", File(paths.webDir, "index.html").readText())
        assertFalse(previousWebDir.exists())
    }

    private fun assertTextResponse(
        response: HttpResponse,
        expectedStatus: Int,
        expectedMimeType: String,
        expectedBodyPart: String
    ) {
        assertEquals(expectedStatus, response.statusCode)
        assertEquals(expectedMimeType, response.mimeType)
        assertNull(response.bodyBytes)
        assertTrue(response.bodyText!!.contains(expectedBodyPart))
    }

    private fun assertBinaryResponse(
        response: HttpResponse,
        expectedMimeType: String,
        expectedBody: ByteArray
    ) {
        assertEquals(200, response.statusCode)
        assertEquals(expectedMimeType, response.mimeType)
        assertNull(response.bodyText)
        assertArrayEquals(expectedBody, response.bodyBytes)
    }

    private fun getResponseBody(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, body: String): HttpResult {
        return postContent(url, "application/json", body.toByteArray())
    }

    private fun postMultipart(url: String, fields: Map<String, String>): HttpResult {
        val boundary = "stapk-boundary"
        val body = buildString {
            fields.forEach { (name, value) ->
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                append(value)
                append("\r\n")
            }
            append("--$boundary--\r\n")
        }.toByteArray()
        return postContent(url, "multipart/form-data; boundary=$boundary", body)
    }

    private fun postContent(url: String, contentType: String, body: ByteArray): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.outputStream.use { it.write(body) }
        return try {
            val statusCode = connection.responseCode
            val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            HttpResult(statusCode, stream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun getBinary(url: String): HttpBinaryResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            val statusCode = connection.responseCode
            val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            HttpBinaryResult(statusCode, stream.readBytes())
        } finally {
            connection.disconnect()
        }
    }

    private fun createAssetRoot(manifest: String, index: String): File {
        val root = Files.createTempDirectory("stapk-assets").toFile()
        File(root, "stapk-web-manifest.json").writeText(manifest)
        File(root, "sillytavern-web").mkdirs()
        File(root, "sillytavern-web/index.html").writeText(index)
        return root
    }

    private class DirectoryWebAssetSource(private val root: File) : WebAssetSource {
        override fun list(path: String): List<String> =
            File(root, path).list()?.sorted().orEmpty()

        override fun open(path: String): InputStream = File(root, path).inputStream()
    }

    private data class HttpResult(val statusCode: Int, val body: String)
    private data class HttpBinaryResult(val statusCode: Int, val body: ByteArray)
}
