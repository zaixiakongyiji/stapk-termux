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

class ChatControllerTest {
    @Test
    fun `export preserves jsonl fields and includes system and narrator in txt ticket`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-export").toFile())
        val source = paths.chatsDir.resolve("alice/conversation.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText(
                """{"chat_metadata":{"vendor":"keep"},"unknown_header":7}
{"name":"User","mes":"Hello","unknown_message":{"nested":true}}
{"name":"System","mes":"Safety notice","is_system":true}
{"name":"Narrator","mes":"The room changes.","is_user":false}"""
            )
        }
        val exports = ExportStore(paths.userDataDir.resolve("exports"))
        val controller = ChatController(paths, exportStore = exports)

        val jsonl = controller.exportChat(
            """{"avatar_url":"alice.png","file":"conversation.jsonl","format":"jsonl","exportfilename":"chat.jsonl","is_group":false}"""
        )
        val txt = controller.exportChat(
            """{"avatar_url":"alice.png","file":"conversation.jsonl","format":"txt","exportfilename":"chat.txt","is_group":false}"""
        )

        assertEquals(jsonl.bodyText, 200, jsonl.statusCode)
        assertEquals(txt.bodyText, 200, txt.statusCode)
        assertEquals(source.readText(), requireNotNull(jsonl.bodyFile).readText())
        assertEquals(
            """User: Hello
System: Safety notice
Narrator: The room changes.""",
            requireNotNull(txt.bodyFile).readText()
        )
        listOf(jsonl, txt).forEach { response ->
            assertEquals(200, response.statusCode)
            val token = response.headers["X-stAPK-Export-Token"].orEmpty()
            assertTrue(token.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertEquals(response.bodyFile, exports.consume(token)?.file)
        }
    }

    @Test
    fun `jsonl import validates every object and preserves existing chats on conflict`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-import").toFile())
        val upload = Files.createTempFile("conversation", ".jsonl").toFile().apply {
            writeText(
                """{"chat_metadata":{"vendor":"keep"},"unknown_header":7}
{"name":"User","mes":"Hello","unknown_message":{"nested":true}}

{"name":"Alice","mes":"Hi"}"""
            )
        }
        val chatDir = paths.chatsDir.resolve("alice").apply { mkdirs() }
        chatDir.resolve("conversation.jsonl").writeText("existing")
        val controller = ChatController(paths)

        val imported = controller.importChat(chatUploadRequest(upload, "conversation.jsonl"))
        val body = JsonParser.parseString(requireNotNull(imported.bodyText)).asJsonObject
        val importedFile = chatDir.resolve("conversation-1.jsonl")

        assertEquals(200, imported.statusCode)
        assertTrue(body.get("res").asBoolean)
        assertEquals(listOf("conversation-1.jsonl"), body.getAsJsonArray("fileNames").map { it.asString })
        assertEquals("existing", chatDir.resolve("conversation.jsonl").readText())
        assertEquals(upload.readText(), importedFile.readText())
    }

    @Test
    fun `jsonl import rejects non object lines without creating a partial chat`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-import-invalid").toFile())
        val upload = Files.createTempFile("invalid-conversation", ".jsonl").toFile().apply {
            writeText("""{"chat_metadata":{}}
["not","an","object"]""")
        }

        val response = ChatController(paths).importChat(chatUploadRequest(upload, "invalid.jsonl"))

        assertEquals(400, response.statusCode)
        assertTrue(paths.chatsDir.resolve("alice").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `recent merges character and group chats ordered by file time`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-recent").toFile())
        writeChat(paths.chatsDir.resolve("alice/alice-chat.jsonl"), "Alice", "old", "2026-07-15T10:00:00Z", 1_000L)
        writeChat(paths.chatsDir.resolve("bob/bob-chat.jsonl"), "Bob", "new", "2026-07-15T12:00:00Z", 3_000L)
        writeChat(paths.groupChatsDir.resolve("group-chat.jsonl"), "Group", "middle", "2026-07-15T11:00:00Z", 2_000L)
        paths.groupsDir.mkdirs()
        paths.groupsDir.resolve("group-1.json").writeText("""{"id":"group-1","chats":["group-chat"]}""")

        val response = ChatController(paths).recentChats("""{"max":3}""")
        val recent = JsonParser.parseString(response.bodyText!!).asJsonArray

        assertEquals(200, response.statusCode)
        assertEquals(listOf("bob-chat.jsonl", "group-chat.jsonl", "alice-chat.jsonl"), recent.map {
            it.asJsonObject.get("file_name").asString
        })
        assertEquals("bob.png", recent[0].asJsonObject.get("avatar").asString)
        assertEquals("new", recent[0].asJsonObject.get("mes").asString)
        assertEquals("group-1", recent[1].asJsonObject.get("group").asString)
        assertEquals("2026-07-15T11:00:00Z", recent[1].asJsonObject.get("last_mes").asString)
        assertEquals(1, recent[1].asJsonObject.get("chat_items").asInt)
        assertEquals(
            "${paths.groupChatsDir.resolve("group-chat.jsonl").length()} B",
            recent[1].asJsonObject.get("file_size").asString
        )
        assertEquals("alice.png", recent[2].asJsonObject.get("avatar").asString)
    }

    @Test
    fun `recent skips corrupt chats and records only a sanitized file name`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-recent-corrupt").toFile())
        writeChat(paths.chatsDir.resolve("alice/valid.jsonl"), "Alice", "valid", "2026-07-15T10:00:00Z", 1_000L)
        val broken = paths.chatsDir.resolve("alice/broken.jsonl").apply { writeText("not-json") }

        val response = ChatController(paths).recentChats("{}")
        val recent = JsonParser.parseString(response.bodyText!!).asJsonArray
        val diagnostic = paths.logsDir.resolve("diagnostics.jsonl").readText()

        assertEquals(1, recent.size())
        assertTrue(diagnostic.contains("broken.jsonl"))
        assertFalse(diagnostic.contains(requireNotNull(broken.parentFile).absolutePath))
    }

    @Test
    fun `search returns only chats belonging to requested group`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-group-search").toFile())
        writeChat(paths.groupChatsDir.resolve("group-chat.jsonl"), "Group", "matching message", "2026-07-15T11:00:00Z", 2_000L)
        writeChat(paths.groupChatsDir.resolve("other-chat.jsonl"), "Other", "matching message", "2026-07-15T12:00:00Z", 3_000L)
        paths.groupsDir.mkdirs()
        paths.groupsDir.resolve("group-1.json").writeText("""{"id":"group-1","chats":["group-chat"]}""")

        val response = ChatController(paths).searchChats("""{"group_id":"group-1","query":"matching"}""")
        val results = JsonParser.parseString(response.bodyText!!).asJsonArray

        assertEquals(200, response.statusCode)
        assertEquals(1, results.size())
        assertEquals("group-chat", results[0].asJsonObject.get("file_name").asString)
        assertEquals(1, results[0].asJsonObject.get("message_count").asInt)
    }

    @Test
    fun `loopback server registers chat management routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-routes").toFile())
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            assertEquals(200, postJson(server, "/api/chats/recent", "{}").first)
            assertEquals(400, postJson(server, "/api/chats/rename", "{}").first)
            assertEquals(400, postJson(server, "/api/chats/import", "{}").first)
            assertEquals(400, postJson(server, "/api/chats/export", "{}").first)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `saved chat can be read searched and deleted in upstream shapes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat").toFile())
        val controller = ChatController(paths)
        val request = """{
            "avatar_url":"alice.png",
            "file_name":"hello",
            "chat":[
                {"chat_metadata":{},"user_name":"unused","character_name":"unused"},
                {"name":"User","is_user":true,"mes":"Hi Alice","send_date":"2026-07-11T00:00:00Z"}
            ]
        }""".trimIndent()

        val saved = controller.saveChat(request)
        val fetched = JsonParser.parseString(
            controller.getChat("""{"avatar_url":"alice.png","file_name":"hello"}""").bodyText!!
        ).asJsonArray
        val search = JsonParser.parseString(
            controller.searchChats("""{"avatar_url":"alice.png","query":"alice"}""").bodyText!!
        ).asJsonArray

        assertEquals(saved.bodyText, 200, saved.statusCode)
        assertTrue(JsonParser.parseString(saved.bodyText!!).asJsonObject.get("ok").asBoolean)
        assertEquals(2, paths.chatsDir.resolve("alice/hello.jsonl").readLines().size)
        assertEquals(2, fetched.size())
        assertEquals("Hi Alice", fetched[1].asJsonObject.get("mes").asString)
        assertEquals(1, search.size())
        assertEquals("hello", search[0].asJsonObject.get("file_name").asString)
        assertEquals(1, search[0].asJsonObject.get("message_count").asInt)
        assertEquals("Hi Alice", search[0].asJsonObject.get("preview_message").asString)

        val deleted = controller.deleteChat(
            """{"avatar_url":"alice.png","chatfile":"hello.jsonl"}"""
        )
        assertEquals(200, deleted.statusCode)
        assertFalse(paths.chatsDir.resolve("alice/hello.jsonl").exists())
    }

    @Test
    fun `rename stays within one character and rejects existing targets`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat-rename").toFile())
        val alice = paths.chatsDir.resolve("alice").apply { mkdirs() }
        val bob = paths.chatsDir.resolve("bob").apply { mkdirs() }
        alice.resolve("source.jsonl").writeText("source")
        alice.resolve("conflict-source.jsonl").writeText("conflict-source")
        alice.resolve("occupied.jsonl").writeText("occupied")
        bob.resolve("other.jsonl").writeText("other")
        val controller = ChatController(paths)

        val renamed = controller.renameChat(
            """{"avatar_url":"alice.png","original_file":"source.jsonl","renamed_file":"renamed.jsonl"}"""
        )
        val conflict = controller.renameChat(
            """{"avatar_url":"alice.png","original_file":"conflict-source.jsonl","renamed_file":"occupied.jsonl"}"""
        )
        val crossCharacter = controller.renameChat(
            """{"avatar_url":"alice.png","original_file":"../bob/other.jsonl","renamed_file":"moved.jsonl"}"""
        )

        assertEquals(200, renamed.statusCode)
        assertEquals("renamed", JsonParser.parseString(renamed.bodyText!!).asJsonObject.get("sanitizedFileName").asString)
        assertFalse(alice.resolve("source.jsonl").exists())
        assertEquals("source", alice.resolve("renamed.jsonl").readText())
        assertEquals(400, conflict.statusCode)
        assertEquals("conflict-source", alice.resolve("conflict-source.jsonl").readText())
        assertEquals("occupied", alice.resolve("occupied.jsonl").readText())
        assertEquals(400, crossCharacter.statusCode)
        assertEquals("other", bob.resolve("other.jsonl").readText())
    }

    @Test
    fun `rejects traversal without writing outside chat root`() {
        val filesDir = Files.createTempDirectory("stapk-chat").toFile()
        val paths = NativeAdapterPaths(filesDir)
        val controller = ChatController(paths)

        val invalidAvatar = controller.saveChat(
            """{"avatar_url":"../escape.png","file_name":"hello","chat":[]}"""
        )
        val invalidFile = controller.saveChat(
            """{"avatar_url":"alice.png","file_name":"../escape","chat":[]}"""
        )

        assertEquals(400, invalidAvatar.statusCode)
        assertEquals(400, invalidFile.statusCode)
        assertFalse(filesDir.resolve("escape.jsonl").exists())
    }

    private fun writeChat(
        file: java.io.File,
        name: String,
        message: String,
        sendDate: String,
        modified: Long
    ) {
        requireNotNull(file.parentFile).mkdirs()
        file.writeText(
            """{"chat_metadata":{}}
{"name":"$name","mes":"$message","send_date":"$sendDate"}"""
        )
        assertTrue(file.setLastModified(modified))
    }

    private fun chatUploadRequest(file: java.io.File, originalName: String): NativeRequest =
        NativeRequest(
            method = "POST",
            path = "/api/chats/import",
            query = emptyMap(),
            form = mapOf(
                "file_type" to listOf("jsonl"),
                "avatar_url" to listOf("alice.png"),
                "character_name" to listOf("Alice"),
                "user_name" to listOf("User")
            ),
            bodyText = "",
            uploads = mapOf("avatar" to UploadedFile("avatar", originalName, "application/json", file))
        )

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
