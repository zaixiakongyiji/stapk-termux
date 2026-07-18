package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class GroupChatControllerTest {
    @Test
    fun `save writes one object per line and get returns the complete chat`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat").toFile())
        val controller = GroupChatController(paths)
        val chat = fixtureChat()

        val saved = controller.saveChat("""{"id":"fixture-chat","chat":${Gson().toJson(chat)}}""")
        val fetched = JsonParser.parseString(controller.getChat("""{"id":"fixture-chat"}""").bodyText!!).asJsonArray

        assertEquals(200, saved.statusCode)
        assertTrue(JsonParser.parseString(saved.bodyText!!).asJsonObject.get("ok").asBoolean)
        assertEquals(3, paths.groupChatsDir.resolve("fixture-chat.jsonl").readLines().size)
        assertEquals(3, fetched.size())
        assertEquals("fixture-lore", fetched[0].asJsonObject.getAsJsonObject("chat_metadata").get("world_info").asString)
        assertEquals("Hello User", fetched[2].asJsonObject.get("mes").asString)
        assertEquals(7, fetched[2].asJsonObject.getAsJsonObject("vendor_message").get("score").asInt)
    }

    @Test
    fun `save fully replaces a chat after messages were appended`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat-overwrite").toFile())
        val controller = GroupChatController(paths)
        controller.saveChat("""{"id":"fixture-chat","chat":${Gson().toJson(fixtureChat())}}""")
        paths.groupChatsDir.resolve("fixture-chat.jsonl").appendText("\n{\"mes\":\"appended\"}")
        val replacement = JsonArray().apply {
            add(fixtureChat()[0].deepCopy())
            add(JsonParser.parseString("""{"mes":"replacement","send_date":"2026-07-13T11:00:00Z"}"""))
        }

        val response = controller.saveChat("""{"id":"fixture-chat","chat":${Gson().toJson(replacement)}}""")
        val stored = paths.groupChatsDir.resolve("fixture-chat.jsonl").readLines()

        assertEquals(200, response.statusCode)
        assertEquals(2, stored.size)
        assertTrue(stored.last().contains("replacement"))
        assertTrue(stored.none { it.contains("appended") })
    }

    @Test
    fun `save rejects a chat without a metadata header`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat-save-header").toFile())
        val controller = GroupChatController(paths)

        val response = controller.saveChat(
            """{"id":"missing-header","chat":[{"mes":"No metadata"}]}"""
        )

        assertEquals(400, response.statusCode)
        assertTrue(!paths.groupChatsDir.resolve("missing-header.jsonl").exists())
    }

    @Test
    fun `info summarizes messages and returns first line metadata`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat-info").toFile())
        val controller = GroupChatController(paths)
        controller.saveChat("""{"id":"fixture-chat","chat":${Gson().toJson(fixtureChat())}}""")

        val response = controller.info("""{"id":"fixture-chat"}""")
        val info = JsonParser.parseString(response.bodyText!!).asJsonObject
        val file = paths.groupChatsDir.resolve("fixture-chat.jsonl")

        assertEquals(200, response.statusCode)
        assertEquals("fixture-chat.jsonl", info.get("file_name").asString)
        assertEquals("${file.length()} B", info.get("file_size").asString)
        assertEquals(2, info.get("chat_items").asInt)
        assertEquals("2026-07-13T10:01:00Z", info.get("last_mes").asString)
        assertEquals("fixture-lore", info.getAsJsonObject("chat_metadata").get("world_info").asString)
        assertTrue(info.getAsJsonObject("chat_metadata").get("vendor_flag").asBoolean)
    }

    @Test
    fun `delete removes an existing group chat`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat-delete").toFile())
        val controller = GroupChatController(paths)
        controller.saveChat("""{"id":"fixture-chat","chat":${Gson().toJson(fixtureChat())}}""")

        val response = controller.deleteChat("""{"id":"fixture-chat"}""")

        assertEquals(200, response.statusCode)
        assertTrue(JsonParser.parseString(response.bodyText!!).asJsonObject.get("ok").asBoolean)
        assertTrue(!paths.groupChatsDir.resolve("fixture-chat.jsonl").exists())
    }

    @Test
    fun `import accepts JSONL and returns a new chat id without changing objects`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat-import").toFile())
        val controller = GroupChatController(paths) { "imported-chat" }
        val request = uploadRequest(paths, "fixture.jsonl", fixture("group-chat.jsonl"))

        val response = controller.importChat(request)
        val imported = paths.groupChatsDir.resolve("imported-chat.jsonl")

        assertEquals(200, response.statusCode)
        assertEquals("imported-chat", JsonParser.parseString(response.bodyText!!).asJsonObject.get("res").asString)
        assertTrue(imported.isFile)
        assertEquals(fixtureChat(), JsonArray().apply {
            imported.readLines().filter { it.isNotBlank() }.forEach { add(JsonParser.parseString(it).asJsonObject) }
        })
    }

    @Test
    fun `import quarantines invalid JSONL without overwriting an existing chat`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat-import-invalid").toFile())
        paths.groupChatsDir.mkdirs()
        val existing = paths.groupChatsDir.resolve("imported-chat.jsonl").apply { writeText("existing") }
        val controller = GroupChatController(paths) { "imported-chat" }
        val request = uploadRequest(paths, "broken.jsonl", "{\"chat_metadata\":{}}\nnot-json")
        val uploaded = requireNotNull(request.uploads["avatar"]).tempFile

        val response = controller.importChat(request)

        assertEquals(400, response.statusCode)
        assertEquals("existing", existing.readText())
        assertTrue(!uploaded.exists())
        assertTrue(paths.quarantineDir.walkTopDown().any { it.name == uploaded.name })
    }

    @Test
    fun `valid import reports conflict without overwriting an existing chat`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat-import-conflict").toFile())
        paths.groupChatsDir.mkdirs()
        val existing = paths.groupChatsDir.resolve("imported-chat.jsonl").apply { writeText("existing") }
        val controller = GroupChatController(paths) { "imported-chat" }

        val response = controller.importChat(uploadRequest(paths, "fixture.jsonl", fixture("group-chat.jsonl")))

        assertEquals(409, response.statusCode)
        assertEquals("existing", existing.readText())
    }

    @Test
    fun `import rejects object JSONL without a metadata header`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-chat-import-header").toFile())
        val controller = GroupChatController(paths) { "missing-header" }
        val request = uploadRequest(
            paths,
            "missing-header.jsonl",
            """{"name":"User","mes":"No metadata"}"""
        )

        val response = controller.importChat(request)

        assertEquals(400, response.statusCode)
        assertTrue(!paths.groupChatsDir.resolve("missing-header.jsonl").exists())
    }

    @Test
    fun `loopback server registers all group and group chat routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-routes").toFile())
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            assertEquals(200, postJson(server, "/api/groups/all", "{}").first)
            val created = postJson(server, "/api/groups/create", "{}")
            assertEquals(200, created.first)
            val groupId = JsonParser.parseString(created.second).asJsonObject.get("id").asString
            assertEquals(200, postJson(server, "/api/groups/edit", """{"id":"$groupId","name":"Edited"}""").first)

            val chat = """[{"chat_metadata":{}},{"mes":"Hello","send_date":"2026-07-13T12:00:00Z"}]"""
            assertEquals(200, postJson(server, "/api/chats/group/save", """{"id":"route-chat","chat":$chat}""").first)
            assertEquals(200, postJson(server, "/api/chats/group/get", """{"id":"route-chat"}""").first)
            assertEquals(200, postJson(server, "/api/chats/group/info", """{"id":"route-chat"}""").first)
            assertEquals(400, postJson(server, "/api/chats/group/import", "{}").first)
            assertEquals(200, postJson(server, "/api/chats/group/delete", """{"id":"route-chat"}""").first)
            assertEquals(200, postJson(server, "/api/groups/delete", """{"id":"$groupId","delete_chats":false}""").first)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `group chat ids reject traversal without writing outside the chat root`() {
        val filesDir = Files.createTempDirectory("stapk-group-chat-traversal").toFile()
        val paths = NativeAdapterPaths(filesDir)
        val outside = paths.userDataDir.resolve("escape.jsonl").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("keep")
        }
        val controller = GroupChatController(paths)

        val response = controller.saveChat(
            """{"id":"../escape","chat":[{"chat_metadata":{}}]}"""
        )

        assertEquals(400, response.statusCode)
        assertEquals("keep", outside.readText())
    }

    private fun fixtureChat(): JsonArray = JsonArray().apply {
        fixture("group-chat.jsonl").lineSequence().filter { it.isNotBlank() }.forEach {
            add(JsonParser.parseString(it).asJsonObject)
        }
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")).readText()

    private fun uploadRequest(paths: NativeAdapterPaths, name: String, contents: String): NativeRequest {
        val directory = paths.userDataDir.resolve("multipart").apply { mkdirs() }
        val upload = Files.createTempFile(directory.toPath(), "group-chat-", ".upload").toFile().apply {
            writeText(contents)
        }
        return NativeRequest(
            method = "POST",
            path = "/api/chats/group/import",
            query = emptyMap(),
            form = emptyMap(),
            bodyText = "",
            uploads = mapOf("avatar" to UploadedFile("avatar", name, "application/json", upload))
        )
    }

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
