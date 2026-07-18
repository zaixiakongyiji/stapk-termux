package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.time.Instant

class ChatBackupControllerTest {
    @Test
    fun `loopback server registers chat backup routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-backup-routes").toFile())
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            assertEquals(200, postJson(server, "/api/backups/chat/get", "{}").first)
            assertEquals(400, postJson(server, "/api/backups/chat/download", "{}").first)
            assertEquals(400, postJson(server, "/api/backups/chat/delete", "{}").first)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `backup list download and delete expose only real chat snapshots`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-backup-api").toFile())
        paths.chatBackupsDir.mkdirs()
        val older = paths.chatBackupsDir.resolve("hello_20260715-010203-004.jsonl").apply {
            writeText("""{"chat_metadata":{}}
{"name":"User","mes":"older"}""")
            setLastModified(1_000L)
        }
        val newer = paths.chatBackupsDir.resolve("hello_20260715-010204-004.jsonl").apply {
            writeText("""{"chat_metadata":{}}
{"name":"User","mes":"newer"}""")
            setLastModified(2_000L)
        }
        paths.chatBackupsDir.resolve("not-a-snapshot.jsonl").writeText("unrelated")
        val exports = ExportStore(paths.userDataDir.resolve("exports"))
        val controller = ChatBackupController(paths, exportStore = exports)

        val listed = JsonParser.parseString(requireNotNull(controller.getBackups().bodyText)).asJsonArray
        val downloaded = controller.downloadBackup("""{"name":"${newer.name}"}""")
        val traversal = controller.deleteBackup("""{"name":"../${older.name}"}""")
        val unrelated = controller.deleteBackup("""{"name":"not-a-snapshot.jsonl"}""")
        val deleted = controller.deleteBackup("""{"name":"${older.name}"}""")

        assertEquals(listOf(newer.name, older.name), listed.map { it.asJsonObject.get("name").asString })
        assertEquals(newer.name, listed[0].asJsonObject.get("file_name").asString)
        assertEquals(newer.length(), listed[0].asJsonObject.get("size").asLong)
        assertEquals("${newer.length()} B", listed[0].asJsonObject.get("file_size").asString)
        assertEquals(2_000L, listed[0].asJsonObject.get("date").asLong)
        assertEquals(2_000L, listed[0].asJsonObject.get("last_mes").asLong)
        assertEquals(1, listed[0].asJsonObject.get("chat_items").asInt)
        assertEquals(newer.readText(), requireNotNull(downloaded.bodyFile).readText())
        val token = downloaded.headers["X-stAPK-Export-Token"].orEmpty()
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(downloaded.bodyFile, exports.consume(token)?.file)
        assertEquals(400, traversal.statusCode)
        assertEquals(400, unrelated.statusCode)
        assertEquals(200, deleted.statusCode)
        assertFalse(older.exists())
        assertTrue(newer.exists())
    }

    @Test
    fun `backup retention keeps the newest fifty snapshots per chat`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-backup-retention").toFile())
        val chat = paths.chatsDir.resolve("alice/hello.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("old")
        }
        var now = Instant.parse("2026-07-15T01:02:03Z").toEpochMilli()
        val controller = ChatBackupController(paths, clock = { now++ })

        repeat(51) { controller.backupIfChanged(chat, "changed") }

        val retained = paths.chatBackupsDir.listFiles().orEmpty().sortedBy { it.name }
        assertEquals(50, retained.size)
        assertFalse(retained.any { it.name == "hello_20260715-010203-000.jsonl" })
        assertTrue(retained.any { it.name == "hello_20260715-010203-050.jsonl" })
    }

    @Test
    fun `save snapshots changed existing chat but skips first and unchanged saves`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-backup-save").toFile())
        val backups = ChatBackupController(
            paths,
            clock = { Instant.parse("2026-07-15T01:02:03.004Z").toEpochMilli() }
        )
        val controller = ChatController(paths, backupController = backups)
        val firstRequest = saveRequest("first")
        val changedRequest = saveRequest("changed")

        assertEquals(200, controller.saveChat(firstRequest).statusCode)
        assertTrue(paths.chatBackupsDir.listFiles().orEmpty().isEmpty())

        assertEquals(200, controller.saveChat(firstRequest).statusCode)
        assertTrue(paths.chatBackupsDir.listFiles().orEmpty().isEmpty())

        assertEquals(200, controller.saveChat(changedRequest).statusCode)
        val snapshot = paths.chatBackupsDir.resolve("hello_20260715-010203-004.jsonl")
        assertTrue(snapshot.isFile)
        assertTrue(snapshot.readText().contains("first"))
        assertFalse(snapshot.readText().contains("changed"))
        assertTrue(paths.chatsDir.resolve("alice/hello.jsonl").readText().contains("changed"))
    }

    private fun saveRequest(message: String): String = """{
        "avatar_url":"alice.png",
        "file_name":"hello",
        "chat":[
            {"chat_metadata":{}},
            {"name":"User","is_user":true,"mes":"$message"}
        ]
    }""".trimIndent()

    private fun postJson(server: NativeHttpServer, path: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray()) }
        val status = connection.responseCode
        val response = (if (status >= 400) connection.errorStream else connection.inputStream)
            .bufferedReader().use { it.readText() }
        return status to response
    }
}
