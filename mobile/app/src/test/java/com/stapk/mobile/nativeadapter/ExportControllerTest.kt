package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExportControllerTest {
    private val bridgeNonce = "N".repeat(43)

    @Test
    fun `browser generated data is staged in the shared export store`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-generated-export").toFile())
        val store = ExportStore(paths.exportsDir)
        val controller = ExportController(store) { bridgeNonce }
        val upload = Files.createTempFile("stapk-world-info", ".json").toFile().apply {
            writeText("{\"entries\":{}}")
        }

        val response = controller.create(
            NativeRequest(
                method = "POST",
                path = "/api/stapk/exports/create",
                query = emptyMap(),
                form = emptyMap(),
                bodyText = "",
                uploads = mapOf(
                    "file" to UploadedFile("file", "世界书.json", "application/json", upload)
                ),
                headers = mapOf("x-stapk-bridge-nonce" to bridgeNonce)
            )
        )

        assertEquals(200, response.statusCode)
        val body = JsonParser.parseString(response.bodyText).asJsonObject
        val token = body.get("token").asString
        val ticket = store.find(token)
        assertNotNull(ticket)
        assertEquals("世界书.json", ticket?.fileName)
        assertEquals("application/json", ticket?.mimeType)
        assertEquals("{\"entries\":{}}", ticket?.file?.readText())
    }

    @Test
    fun `generated export rejects missing file and unsafe metadata`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-generated-export").toFile())
        val store = ExportStore(paths.exportsDir)
        val controller = ExportController(store) { bridgeNonce }
        val upload = Files.createTempFile("stapk-generated-export", ".txt").toFile().apply {
            writeText("data")
        }

        assertEquals(400, controller.create(request()).statusCode)
        assertEquals(
            400,
            controller.create(
                request(UploadedFile("file", "../bad.json", "application/json", upload))
            ).statusCode
        )
        assertEquals(
            400,
            controller.create(
                request(UploadedFile("file", "good.json", "text/plain; charset=utf-8", upload))
            ).statusCode
        )
        assertNull(store.find("T".repeat(43)))
    }

    @Test
    fun `generated export requires the active bridge nonce`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-generated-auth").toFile())
        val controller = ExportController(ExportStore(paths.exportsDir)) { bridgeNonce }
        val upload = Files.createTempFile("stapk-generated-auth", ".json").toFile().apply {
            writeText("{}")
        }

        assertEquals(
            403,
            controller.create(
                request(UploadedFile("file", "data.json", "application/json", upload), nonce = null)
            ).statusCode
        )
        assertEquals(
            403,
            controller.create(
                request(
                    UploadedFile("file", "data.json", "application/json", upload),
                    nonce = "A".repeat(43)
                )
            ).statusCode
        )
        assertEquals(
            200,
            controller.create(
                request(UploadedFile("file", "data.json", "application/json", upload), nonce = bridgeNonce)
            ).statusCode
        )
    }

    @Test
    fun `generated export rejects executable MIME and mismatched extension`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-generated-mime").toFile())
        val controller = ExportController(ExportStore(paths.exportsDir)) { bridgeNonce }
        val upload = Files.createTempFile("stapk-generated-mime", ".tmp").toFile().apply { writeText("data") }

        assertEquals(
            400,
            controller.create(request(UploadedFile("file", "payload.apk", "application/vnd.android.package-archive", upload), bridgeNonce)).statusCode
        )
        assertEquals(
            400,
            controller.create(request(UploadedFile("file", "payload.json", "text/plain", upload), bridgeNonce)).statusCode
        )
        assertEquals(
            200,
            controller.create(request(UploadedFile("file", "notes.txt", "text/plain", upload), bridgeNonce)).statusCode
        )
    }

    @Test
    fun `server business exports use the shared SAF ticket store`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-shared-export").toFile())
        val store = ExportStore(paths.exportsDir)
        paths.charactersDir.mkdirs()
        val fixture = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("fixtures/character-card-v2.json")
        ).use { it.readBytes() }
        paths.charactersDir.resolve("alice.json").writeBytes(fixture)
        val server = NativeHttpServer(paths, exportStore = store)
        server.start()
        try {
            val connection = URL(
                "http://127.0.0.1:${server.listeningPort}/api/characters/export"
            ).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write("""{"format":"json","avatar_url":"alice.png"}""".toByteArray())
            }

            assertEquals(200, connection.responseCode)
            val token = connection.getHeaderField("X-stAPK-Export-Token")
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            val ticket = server.findExport(token)
            assertNotNull(ticket)
            assertEquals("alice.json", ticket?.fileName)
            assertEquals(ticket, server.consumeExport(token))
            assertNull(server.consumeExport(token))
        } finally {
            server.stop()
        }
    }

    private fun request(upload: UploadedFile? = null, nonce: String? = bridgeNonce): NativeRequest = NativeRequest(
        method = "POST",
        path = "/api/stapk/exports/create",
        query = emptyMap(),
        form = emptyMap(),
        bodyText = "",
        uploads = upload?.let { mapOf("file" to it) }.orEmpty(),
        headers = nonce?.let { mapOf("x-stapk-bridge-nonce" to it) }.orEmpty()
    )
}
