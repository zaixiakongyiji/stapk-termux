package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class WorldInfoControllerTest {
    @Test
    fun `fixture round trips through edit get list and delete without losing extensions`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-world-info").toFile())
        val controller = WorldInfoController(paths)
        val fixture = fixture("world-info.json")

        assertEquals(
            200,
            controller.editWorldInfo("""{"name":"Moon Lore","data":$fixture}""").statusCode
        )
        assertEquals(
            listOf("Moon Lore"),
            JsonParser.parseString(controller.listWorldInfo().bodyText).asJsonArray.map { it.asString }
        )

        val stored = JsonParser.parseString(
            controller.getWorldInfo("""{"name":"Moon Lore"}""").bodyText
        ).asJsonObject
        val entry = stored.getAsJsonObject("entries").getAsJsonObject("7")
        assertEquals(7, entry.get("uid").asInt)
        assertEquals("moon-hook", entry.get("automationId").asString)
        assertTrue(entry.getAsJsonObject("unknownEntryField").get("preserve").asBoolean)
        assertEquals("fixture", stored.getAsJsonObject("unknownBookField").get("source").asString)

        paths.worldInfoDir.resolve("Keep.json").writeText("""{"entries":{}}""")
        assertEquals(200, controller.deleteWorldInfo("""{"name":"Moon Lore"}""").statusCode)
        assertFalse(paths.worldInfoDir.resolve("Moon Lore.json").exists())
        assertTrue(paths.worldInfoDir.resolve("Keep.json").isFile)
    }

    @Test
    fun `edit rejects unsafe names and malformed data without overwriting existing lorebook`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-world-info-invalid").toFile())
        val controller = WorldInfoController(paths)
        val original = """{"entries":{"1":{"uid":1}}}"""
        paths.worldInfoDir.mkdirs()
        paths.worldInfoDir.resolve("Safe.json").writeText(original)

        assertEquals(400, controller.editWorldInfo("""{"name":"../Safe","data":{"entries":{}}}""").statusCode)
        assertEquals(400, controller.editWorldInfo("""{"name":"Safe","data":{"notEntries":{}}}""").statusCode)
        assertEquals(400, controller.editWorldInfo("{invalid").statusCode)
        assertEquals(original, paths.worldInfoDir.resolve("Safe.json").readText())
        assertEquals(404, controller.getWorldInfo("""{"name":"Missing"}""").statusCode)
    }

    @Test
    fun `imports official world info with conflict suffix and preserves unknown fields`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-world-info-official").toFile())
        val controller = WorldInfoController(paths)
        val fixture = fixture("world-info.json")

        val first = controller.importWorldInfo(uploadRequest(paths, "Moon Lore.json", fixture))
        val second = controller.importWorldInfo(uploadRequest(paths, "Moon Lore.json", fixture))

        assertEquals(200, first.statusCode)
        val firstBody = JsonParser.parseString(first.bodyText).asJsonObject
        assertEquals("Moon Lore", firstBody.get("name").asString)
        assertTrue(firstBody.has("entry_count"))
        assertEquals(1, firstBody.get("entry_count").asInt)
        assertEquals("Moon Lore-1", JsonParser.parseString(second.bodyText).asJsonObject.get("name").asString)
        val imported = JsonParser.parseString(paths.worldInfoDir.resolve("Moon Lore.json").readText()).asJsonObject
        assertEquals("fixture", imported.getAsJsonObject("unknownBookField").get("source").asString)
        assertTrue(imported.getAsJsonObject("entries").getAsJsonObject("7")
            .getAsJsonObject("unknownEntryField").get("preserve").asBoolean)
    }

    @Test
    fun `supplied world info round trips through multipart import and get`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-real-world-info").toFile())
        val server = NativeHttpServer(paths)
        val source = JsonParser.parseString(fixture("real-world-v7.82.json")).asJsonObject
        assertEquals(25, source.getAsJsonObject("entries").size())

        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val importResponse = postMultipart(
                server,
                "/api/worldinfo/import",
                "avatar",
                "real-world-v7.82.json",
                "application/json",
                fixture("real-world-v7.82.json").toByteArray()
            )
            assertEquals(200, importResponse.first)
            val importedName = JsonParser.parseString(importResponse.second).asJsonObject.get("name").asString

            val getResponse = postJson(server, "/api/worldinfo/get", """{"name":"$importedName"}""")
            assertEquals(200, getResponse.first)
            val returned = JsonParser.parseString(getResponse.second).asJsonObject
            assertEquals(25, returned.getAsJsonObject("entries").size())
            assertEquals(
                source.getAsJsonObject("entries").getAsJsonObject("0").get("content"),
                returned.getAsJsonObject("entries").getAsJsonObject("0").get("content")
            )
            assertEquals(source, returned)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `json routes preserve Chinese names and content without an explicit charset`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-world-info-unicode").toFile())
        val server = NativeHttpServer(paths)
        val name = "中文世界书"
        val comment = "中文词条正文"

        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val edit = postJson(
                server,
                "/api/worldinfo/edit",
                """{"name":"$name","data":{"entries":{"0":{"uid":0,"comment":"$comment"}}}}"""
            )
            assertEquals(200, edit.first)

            val listed = postJson(server, "/api/worldinfo/list", "{}")
            assertEquals(listOf(name), JsonParser.parseString(listed.second).asJsonArray.map { it.asString })

            val get = postJson(server, "/api/worldinfo/get", """{"name":"$name"}""")
            assertEquals(200, get.first)
            assertEquals(
                comment,
                JsonParser.parseString(get.second).asJsonObject
                    .getAsJsonObject("entries").getAsJsonObject("0").get("comment").asString
            )

            val delete = postJson(server, "/api/worldinfo/delete", """{"name":"$name"}""")
            assertEquals(200, delete.first)
            assertFalse(paths.worldInfoDir.resolve("$name.json").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `imports character book v2 and lorebook v3 with structural conversion`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-world-info-spec").toFile())
        val controller = WorldInfoController(paths)
        val characterBook = """{
            "name":"Character Moon",
            "extensions":{"book_extension":"keep-book"},
            "future_root":{"preserve":"root"},
            "entries":[{
                "id":42,
                "keys":["moon"],
                "secondary_keys":["night"],
                "content":"Character lore",
                "comment":"memo",
                "constant":true,
                "selective":true,
                "insertion_order":77,
                "position":"before_char",
                "enabled":false,
                "future_entry":{"preserve":"entry"},
                "extensions":{
                    "probability":64,
                    "scan_depth":5,
                    "case_sensitive":true,
                    "automation_id":"character-hook",
                    "unknown_extension":"keep-entry"
                }
            }]
        }""".trimIndent()
        val lorebookV3 = """{
            "spec":"lorebook_v3",
            "data":{
                "name":"V3 Moon",
                "extensions":{"v3_extension":"keep-v3"},
                "entries":[{
                    "id":"entry-a",
                    "keys":["tide"],
                    "content":"V3 lore",
                    "enabled":true,
                    "insertion_order":12,
                    "use_regex":false,
                    "extensions":{"match_whole_words":true,"future_field":"keep-future"}
                }]
            }
        }""".trimIndent()

        val v2Response = controller.importWorldInfo(uploadRequest(paths, "Character Book.json", characterBook))
        val v3Response = controller.importWorldInfo(uploadRequest(paths, "V3 Book.json", lorebookV3))

        assertEquals(200, v2Response.statusCode)
        assertEquals(200, v3Response.statusCode)
        val v2 = JsonParser.parseString(paths.worldInfoDir.resolve("Character Book.json").readText()).asJsonObject
        val v2Entry = v2.getAsJsonObject("entries").getAsJsonObject("42")
        assertEquals(listOf("moon"), v2Entry.getAsJsonArray("key").map { it.asString })
        assertEquals(listOf("night"), v2Entry.getAsJsonArray("keysecondary").map { it.asString })
        assertTrue(v2Entry.get("disable").asBoolean)
        assertEquals(0, v2Entry.get("position").asInt)
        assertEquals(64, v2Entry.get("probability").asInt)
        assertEquals("keep-entry", v2Entry.getAsJsonObject("extensions").get("unknown_extension").asString)
        assertEquals("root", v2.getAsJsonObject("future_root").get("preserve").asString)
        assertEquals("entry", v2Entry.getAsJsonObject("future_entry").get("preserve").asString)
        assertEquals("keep-book", v2.getAsJsonObject("originalData")
            .getAsJsonObject("extensions").get("book_extension").asString)

        val v3 = JsonParser.parseString(paths.worldInfoDir.resolve("V3 Book.json").readText()).asJsonObject
        val v3Entry = v3.getAsJsonObject("entries").getAsJsonObject("entry-a")
        assertEquals("V3 lore", v3Entry.get("content").asString)
        assertFalse(v3Entry.get("disable").asBoolean)
        assertTrue(v3Entry.get("matchWholeWords").asBoolean)
        assertEquals("keep-future", v3Entry.getAsJsonObject("extensions").get("future_field").asString)
        assertEquals("keep-v3", v3.getAsJsonObject("originalData")
            .getAsJsonObject("extensions").get("v3_extension").asString)
    }

    @Test
    fun `character book conversion rejects duplicate ids without writing a file`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-world-info-duplicate").toFile())
        val controller = WorldInfoController(paths)
        val duplicateIds = """{
            "name":"Duplicate IDs",
            "entries":[
                {"id":7,"keys":["first"],"content":"first"},
                {"id":7,"keys":["second"],"content":"second"}
            ]
        }""".trimIndent()

        val response = controller.importWorldInfo(
            uploadRequest(paths, "Duplicate IDs.json", duplicateIds)
        )

        assertEquals(400, response.statusCode)
        assertFalse(paths.worldInfoDir.resolve("Duplicate IDs.json").exists())
    }

    @Test
    fun `embedded character book reuses identical data and suffixes conflicting data`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-embedded-world-info").toFile())
        val controller = WorldInfoController(paths)
        val book = JsonParser.parseString(
            """{
                "name":"中文内嵌书",
                "entries":[{
                    "id":0,
                    "keys":["月亮"],
                    "comment":"第一条",
                    "content":"原始正文",
                    "enabled":true
                }]
            }""".trimIndent()
        ).asJsonObject

        val first = controller.importEmbeddedCharacterBook(book, "角色的世界书")
        val identical = controller.importEmbeddedCharacterBook(book.deepCopy(), "角色的世界书")
        val conflictingBook = book.deepCopy().apply {
            getAsJsonArray("entries")[0].asJsonObject.addProperty("content", "冲突正文")
        }
        val conflicting = controller.importEmbeddedCharacterBook(conflictingBook, "角色的世界书")

        assertEquals("中文内嵌书", first.name)
        assertEquals(1, first.entryCount)
        assertTrue(first.created)
        assertEquals("中文内嵌书", identical.name)
        assertFalse(identical.created)
        assertEquals("中文内嵌书-1", conflicting.name)
        assertTrue(conflicting.created)
        assertEquals(
            "原始正文",
            JsonParser.parseString(paths.worldInfoDir.resolve("中文内嵌书.json").readText()).asJsonObject
                .getAsJsonObject("entries").getAsJsonObject("0").get("content").asString
        )
        assertEquals(
            "冲突正文",
            JsonParser.parseString(paths.worldInfoDir.resolve("中文内嵌书-1.json").readText()).asJsonObject
                .getAsJsonObject("entries").getAsJsonObject("0").get("content").asString
        )
    }

    @Test
    fun `native server registers all world info routes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-world-info-routes").toFile())
        val server = NativeHttpServer(paths)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            mapOf(
                "/api/worldinfo/list" to "{}",
                "/api/worldinfo/get" to """{"name":"Missing"}""",
                "/api/worldinfo/edit" to """{"name":"Route","data":{"entries":{}}}""",
                "/api/worldinfo/delete" to """{"name":"Missing"}"""
            ).forEach { (path, body) ->
                val response = postJson(server, path, body)
                assertFalse("route not registered: $path", response.second.contains("endpoint_not_found"))
            }
            val importResponse = postJson(server, "/api/worldinfo/import", "{}")
            assertFalse("route not registered: /api/worldinfo/import", importResponse.second.contains("endpoint_not_found"))
        } finally {
            server.stop()
        }
    }

    private fun uploadRequest(paths: NativeAdapterPaths, name: String, contents: String): NativeRequest {
        val uploadDir = paths.userDataDir.resolve("multipart").apply { mkdirs() }
        val upload = Files.createTempFile(uploadDir.toPath(), "world-info-", ".upload").toFile().apply {
            writeText(contents)
        }
        return NativeRequest(
            method = "POST",
            path = "/api/worldinfo/import",
            query = emptyMap(),
            form = emptyMap(),
            bodyText = "",
            uploads = mapOf("avatar" to UploadedFile("avatar", name, "application/json", upload))
        )
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")).readText()

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

    private fun postMultipart(
        server: NativeHttpServer,
        path: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        contents: ByteArray
    ): Pair<Int, String> {
        val boundary = "stapk-real-world-info"
        val body = ByteArrayOutputStream().use { output ->
            output.write(
                ("--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n" +
                    "Content-Type: $mimeType\r\n\r\n").toByteArray()
            )
            output.write(contents)
            output.write("\r\n--$boundary--\r\n".toByteArray())
            output.toByteArray()
        }
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        val response = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to response
    }
}
