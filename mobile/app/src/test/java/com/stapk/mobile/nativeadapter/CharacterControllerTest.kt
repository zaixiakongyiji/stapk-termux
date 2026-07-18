package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Base64

class CharacterControllerTest {
    @Test
    fun `imports supplied ccv3 png fixture without losing its identity`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-real-character").toFile())
        val upload = Files.createTempFile("cc7481f898a8e631", ".png").toFile().apply {
            writeBytes(fixture("cc7481f898a8e631.png"))
        }
        val controller = CharacterController(paths)

        val response = controller.importCharacter(
            uploadRequest(upload, "cc7481f898a8e631.png", "image/png", "png")
        )
        val imported = JsonParser.parseString(response.bodyText).asJsonObject
        val stored = JsonParser.parseString(paths.charactersDir.resolve("character.json").readText()).asJsonObject
        val embeddedWorld = imported.getAsJsonObject("embedded_world")
        val worldName = "-------------------------珞蒹葭"
        val world = JsonParser.parseString(
            paths.worldInfoDir.resolve("$worldName.json").readText()
        ).asJsonObject

        assertEquals(200, response.statusCode)
        assertEquals("character", imported.get("file_name").asString)
        assertEquals("珞蒹葭", stored.getAsJsonObject("data").get("name").asString)
        assertEquals(worldName, embeddedWorld.get("name").asString)
        assertEquals(13, embeddedWorld.get("entry_count").asInt)
        assertTrue(embeddedWorld.get("created").asBoolean)
        assertEquals(13, world.getAsJsonObject("entries").size())
        assertEquals("灵根体系", world.getAsJsonObject("entries").getAsJsonObject("0").get("comment").asString)
        assertEquals(
            worldName,
            stored.getAsJsonObject("data").getAsJsonObject("extensions").get("world").asString
        )
        assertArrayEquals(fixture("cc7481f898a8e631.png"), paths.charactersDir.resolve("character.png").readBytes())
    }

    @Test
    fun `character import rolls back a newly created embedded lorebook when card persistence fails`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character-world-rollback").toFile())
        val upload = Files.createTempFile("cc7481f898a8e631", ".png").toFile().apply {
            writeBytes(fixture("cc7481f898a8e631.png"))
        }
        val failingCharacterStore = AtomicFileStore.forTesting(paths.quarantineDir) { _, _ ->
            throw IOException("simulated character write failure")
        }
        val controller = CharacterController(
            paths,
            store = failingCharacterStore,
            worldInfoController = WorldInfoController(paths)
        )

        val response = controller.importCharacter(
            uploadRequest(upload, "cc7481f898a8e631.png", "image/png", "png")
        )

        assertEquals(500, response.statusCode)
        assertFalse(paths.worldInfoDir.resolve("-------------------------珞蒹葭.json").exists())
        assertTrue(paths.charactersDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `character import preserves a reused embedded lorebook when card persistence fails`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character-world-reuse").toFile())
        val upload = Files.createTempFile("cc7481f898a8e631", ".png").toFile().apply {
            writeBytes(fixture("cc7481f898a8e631.png"))
        }
        val request = uploadRequest(upload, "cc7481f898a8e631.png", "image/png", "png")
        assertEquals(200, CharacterController(paths).importCharacter(request).statusCode)
        val worldFile = paths.worldInfoDir.resolve("-------------------------珞蒹葭.json")
        val originalWorld = worldFile.readBytes()
        paths.charactersDir.deleteRecursively()
        val failingCharacterStore = AtomicFileStore.forTesting(paths.quarantineDir) { _, _ ->
            throw IOException("simulated character write failure")
        }
        val controller = CharacterController(
            paths,
            store = failingCharacterStore,
            worldInfoController = WorldInfoController(paths)
        )

        val response = controller.importCharacter(request)

        assertEquals(500, response.statusCode)
        assertArrayEquals(originalWorld, worldFile.readBytes())
    }

    @Test
    fun `created character uses png identity and appears in upstream list shape`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(paths)

        val created = controller.createCharacter(
            """{"ch_name":"Alice","description":"test","first_mes":"Hello"}"""
        )
        val duplicate = controller.createCharacter("""{"ch_name":"Alice"}""")
        val all = JsonParser.parseString(controller.allCharacters().bodyText!!).asJsonArray
        val fetched = JsonParser.parseString(
            controller.getCharacter("""{"avatar_url":"alice.png"}""").bodyText!!
        ).asJsonObject

        assertEquals(200, created.statusCode)
        assertEquals("alice.png", created.bodyText)
        assertEquals("alice1.png", duplicate.bodyText)
        assertTrue(paths.charactersDir.resolve("alice.json").isFile)
        assertEquals(2, all.size())
        assertEquals("Alice", all[0].asJsonObject.get("name").asString)
        assertEquals("alice.png", all[0].asJsonObject.get("avatar").asString)
        assertEquals("Alice", all[0].asJsonObject.getAsJsonObject("data").get("name").asString)
        assertEquals("chara_card_v2", fetched.get("spec").asString)
        assertEquals("test", fetched.get("description").asString)
        assertEquals("Hello", fetched.get("first_mes").asString)
    }

    @Test
    fun `edits serves and deletes character without allowing traversal`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        paths.webDir.resolve("img").mkdirs()
        val avatarBytes = byteArrayOf(1, 2, 3, 4)
        paths.webDir.resolve("img/ai4.png").writeBytes(avatarBytes)
        val controller = CharacterController(paths)
        controller.createCharacter("""{"ch_name":"Alice","description":"before"}""")
        paths.charactersDir.resolve("alice/sprites").mkdirs()
        paths.charactersDir.resolve("alice/sprites/joy.png").writeBytes(BASE_PNG)

        val edited = controller.editCharacter(
            """{"avatar_url":"alice.png","ch_name":"Alice Updated","description":"after","chat":"Saved Chat"}"""
        )
        val fetched = JsonParser.parseString(
            controller.getCharacter("""{"avatar_url":"alice.png"}""").bodyText!!
        ).asJsonObject
        val avatar = controller.serveAvatar("alice.png")
        val traversal = controller.getCharacter("""{"avatar_url":"../settings.json"}""")
        val deleted = controller.deleteCharacter(
            """{"avatar_url":"alice.png","delete_chats":true}"""
        )

        assertEquals(200, edited.statusCode)
        assertEquals("Alice Updated", fetched.get("name").asString)
        assertEquals("Alice Updated", fetched.getAsJsonObject("data").get("name").asString)
        assertEquals("after", fetched.get("description").asString)
        assertEquals("after", fetched.getAsJsonObject("data").get("description").asString)
        assertEquals("Saved Chat", fetched.get("chat").asString)
        assertEquals(200, avatar.statusCode)
        assertArrayEquals(avatarBytes, avatar.bodyBytes)
        assertEquals(400, traversal.statusCode)
        assertEquals(200, deleted.statusCode)
        assertEquals(0, JsonParser.parseString(controller.allCharacters().bodyText!!).asJsonArray.size())
        assertFalse(paths.charactersDir.resolve("alice/sprites").exists())
    }

    @Test
    fun `edit write failure preserves existing character json`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character-write-failure").toFile())
        CharacterController(paths).createCharacter("""{"ch_name":"Alice","description":"before"}""")
        val characterFile = paths.charactersDir.resolve("alice.json")
        val original = characterFile.readBytes()
        val failingStore = AtomicFileStore.forTesting(paths.quarantineDir) { _, _ ->
            throw IOException("simulated character write failure")
        }
        val controller = CharacterController(paths, store = failingStore)

        assertThrows(IOException::class.java) {
            controller.editCharacter(
                """{"avatar_url":"alice.png","ch_name":"Alice Updated","description":"after"}"""
            )
        }

        assertArrayEquals(original, characterFile.readBytes())
    }

    @Test
    fun `character chats returns upstream summary array`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val characters = CharacterController(paths)
        val chats = ChatController(paths)
        characters.createCharacter("""{"ch_name":"Alice"}""")
        chats.saveChat(
            """{
                "avatar_url":"alice.png",
                "file_name":"hello",
                "chat":[
                    {"chat_metadata":{}},
                    {"name":"User","mes":"Last message","send_date":"2026-07-11T00:00:00Z"}
                ]
            }""".trimIndent()
        )

        val summaries = JsonParser.parseString(
            characters.characterChats("""{"avatar_url":"alice.png"}""").bodyText!!
        ).asJsonArray

        assertEquals(1, summaries.size())
        assertEquals("hello.jsonl", summaries[0].asJsonObject.get("file_name").asString)
        assertEquals("hello", summaries[0].asJsonObject.get("file_id").asString)
        assertEquals(1, summaries[0].asJsonObject.get("chat_items").asInt)
        assertEquals("Last message", summaries[0].asJsonObject.get("mes").asString)
        assertEquals("2026-07-11T00:00:00Z", summaries[0].asJsonObject.get("last_mes").asString)
    }

    @Test
    fun `create preserves upstream v2 form fields and foreign card data`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(paths)

        controller.createCharacter(
            """{
                "ch_name":"Alice",
                "json_data":"{\"vendor\":{\"id\":7}}",
                "system_prompt":"System text",
                "post_history_instructions":"Post text",
                "alternate_greetings":["Hello","Welcome"],
                "depth_prompt_prompt":"Depth text",
                "depth_prompt_depth":"6",
                "depth_prompt_role":"assistant",
                "extensions":"{\"custom\":{\"enabled\":true}}"
            }""".trimIndent()
        )
        val character = JsonParser.parseString(
            controller.getCharacter("""{"avatar_url":"alice.png"}""").bodyText!!
        ).asJsonObject
        val data = character.getAsJsonObject("data")
        val extensions = data.getAsJsonObject("extensions")
        val rawCard = JsonParser.parseString(character.get("json_data").asString).asJsonObject

        assertEquals(7, character.getAsJsonObject("vendor").get("id").asInt)
        assertEquals(7, rawCard.getAsJsonObject("vendor").get("id").asInt)
        assertEquals("System text", data.get("system_prompt").asString)
        assertEquals("Post text", data.get("post_history_instructions").asString)
        assertEquals(2, data.getAsJsonArray("alternate_greetings").size())
        assertEquals("Depth text", extensions.getAsJsonObject("depth_prompt").get("prompt").asString)
        assertEquals(6, extensions.getAsJsonObject("depth_prompt").get("depth").asInt)
        assertEquals("assistant", extensions.getAsJsonObject("depth_prompt").get("role").asString)
        assertTrue(extensions.getAsJsonObject("custom").get("enabled").asBoolean)
    }

    @Test
    fun `edit updates and clears advanced character fields`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(paths)
        controller.createCharacter(
            """{"ch_name":"Alice","first_mes":"Hello","system_prompt":"Old system"}"""
        )

        controller.editCharacter(
            """{
                "avatar_url":"alice.png",
                "ch_name":"Alice",
                "first_mes":"",
                "system_prompt":"New system",
                "post_history_instructions":"New post",
                "alternate_greetings":["Edited greeting"]
            }""".trimIndent()
        )
        val character = JsonParser.parseString(
            controller.getCharacter("""{"avatar_url":"alice.png"}""").bodyText!!
        ).asJsonObject
        val data = character.getAsJsonObject("data")

        assertEquals("", character.get("first_mes").asString)
        assertEquals("", data.get("first_mes").asString)
        assertEquals("New system", data.get("system_prompt").asString)
        assertEquals("New post", data.get("post_history_instructions").asString)
        assertEquals("Edited greeting", data.getAsJsonArray("alternate_greetings")[0].asString)
    }

    @Test
    fun `edit persists and clears the official character world field`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(paths)
        controller.createCharacter("""{"ch_name":"Alice"}""")

        controller.editCharacter("""{"avatar_url":"alice.png","world":"Moon Lore"}""")
        val linked = JsonParser.parseString(
            controller.getCharacter("""{"avatar_url":"alice.png"}""").bodyText!!
        ).asJsonObject
        assertEquals(
            "Moon Lore",
            linked.getAsJsonObject("data").getAsJsonObject("extensions").get("world").asString
        )

        controller.editCharacter("""{"avatar_url":"alice.png","world":""}""")
        val cleared = JsonParser.parseString(
            controller.getCharacter("""{"avatar_url":"alice.png"}""").bodyText!!
        ).asJsonObject
        assertEquals(
            "",
            cleared.getAsJsonObject("data").getAsJsonObject("extensions").get("world").asString
        )
    }

    @Test
    fun `imports json with default avatar and preserves unknown card fields`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        paths.webDir.resolve("img").mkdirs()
        paths.webDir.resolve("img/ai4.png").writeBytes(BASE_PNG)
        val upload = Files.createTempFile("character-card-v2", ".json").toFile().apply {
            writeBytes(fixture("character-card-v2.json"))
        }
        val controller = CharacterController(paths)

        val response = controller.importCharacter(uploadRequest(upload, "character-card-v2.json", "application/json", "json"))
        val body = JsonParser.parseString(response.bodyText!!).asJsonObject
        val stored = JsonParser.parseString(paths.charactersDir.resolve("v2_character.json").readText()).asJsonObject

        assertEquals(200, response.statusCode)
        assertEquals("v2_character", body.get("file_name").asString)
        assertArrayEquals(BASE_PNG, paths.charactersDir.resolve("v2_character.png").readBytes())
        assertEquals("v2-top", stored.getAsJsonObject("vendor_top").get("marker").asString)
        assertEquals("v2-extension", stored.getAsJsonObject("data").getAsJsonObject("extensions")
            .getAsJsonObject("vendor_extension").get("marker").asString)
    }

    @Test
    fun `imports png using embedded avatar and exports png and json through tickets`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val codec = CharacterCardCodec()
        val card = codec.decodeJson(fixture("character-card-v3.json")).json
        val png = codec.encodePng(BASE_PNG, card)
        val upload = Files.createTempFile("character-card-v3", ".png").toFile().apply { writeBytes(png) }
        val exports = ExportStore(paths.userDataDir.resolve("exports"))
        val controller = CharacterController(paths, codec = codec, exportStore = exports)

        val imported = controller.importCharacter(uploadRequest(upload, "character-card-v3.png", "image/png", "png"))
        val pngExport = controller.exportCharacter("""{"format":"png","avatar_url":"v3_character.png"}""")
        val jsonExport = controller.exportCharacter("""{"format":"json","avatar_url":"v3_character.png"}""")
        val exportedPngCard = codec.decodePng(requireNotNull(pngExport.bodyFile).readBytes()).json
        val exportedJsonCard = JsonParser.parseString(requireNotNull(jsonExport.bodyFile).readText()).asJsonObject

        assertEquals("v3_character", JsonParser.parseString(imported.bodyText!!).asJsonObject.get("file_name").asString)
        assertArrayEquals(png, paths.charactersDir.resolve("v3_character.png").readBytes())
        assertEquals(200, pngExport.statusCode)
        assertEquals("image/png", pngExport.mimeType)
        val pngToken = pngExport.headers["X-stAPK-Export-Token"].orEmpty()
        assertTrue(pngToken.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(pngExport.bodyFile, exports.consume(pngToken)?.file)
        assertEquals("v3-extension", exportedPngCard.getAsJsonObject("data").getAsJsonObject("extensions")
            .getAsJsonObject("vendor_extension").get("marker").asString)
        assertEquals(200, jsonExport.statusCode)
        assertEquals("application/json", jsonExport.mimeType)
        assertEquals("v3-extension", exportedJsonCard.getAsJsonObject("data").getAsJsonObject("extensions")
            .getAsJsonObject("vendor_extension").get("marker").asString)
        assertTrue(requireNotNull(pngExport.bodyFile).canonicalPath.startsWith(paths.userDataDir.canonicalPath))
    }

    @Test
    fun `import and export reject unsupported formats traversal and missing default avatar without partial files`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val upload = Files.createTempFile("character", ".txt").toFile().apply { writeText("not a card") }
        val controller = CharacterController(paths)

        val unsupportedImport = controller.importCharacter(
            uploadRequest(upload, "character.txt", "text/plain", "txt")
        )
        val missingDefault = controller.importCharacter(
            uploadRequest(upload.apply { writeBytes(fixture("character-card-v2.json")) }, "character.json", "application/json", "json")
        )
        val traversal = controller.exportCharacter("""{"format":"json","avatar_url":"../settings.json"}""")
        val unsupportedExport = controller.exportCharacter("""{"format":"yaml","avatar_url":"alice.png"}""")

        assertEquals(400, unsupportedImport.statusCode)
        assertEquals(400, missingDefault.statusCode)
        assertEquals(400, traversal.statusCode)
        assertEquals(400, unsupportedExport.statusCode)
        assertTrue(paths.charactersDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `edit avatar accepts png jpeg and webp and always stores png identity`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val normalized = BASE_PNG
        val seen = mutableListOf<ByteArray>()
        val controller = CharacterController(
            paths,
            avatarNormalizer = AvatarImageNormalizer { source ->
                seen += source.copyOf()
                normalized
            }
        )
        controller.createCharacter("""{"ch_name":"Alice"}""")
        val samples = listOf(
            "avatar.png" to BASE_PNG,
            "avatar.jpg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3),
            "avatar.webp" to ("RIFF".toByteArray() + byteArrayOf(4, 0, 0, 0) + "WEBP".toByteArray() + byteArrayOf(1))
        )

        samples.forEach { (name, bytes) ->
            val upload = Files.createTempFile("avatar", ".tmp").toFile().apply { writeBytes(bytes) }
            val response = controller.editAvatar(avatarRequest(upload, name))
            assertEquals(200, response.statusCode)
            assertArrayEquals(normalized, paths.charactersDir.resolve("alice.png").readBytes())
            assertArrayEquals(normalized, controller.serveAvatar("alice.png").bodyBytes)
        }

        assertEquals(3, seen.size)
    }

    @Test
    fun `edit avatar decode failure and unsupported image do not overwrite old avatar`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        var normalizerCalls = 0
        val controller = CharacterController(
            paths,
            avatarNormalizer = AvatarImageNormalizer {
                normalizerCalls += 1
                throw IllegalArgumentException("decode failed")
            }
        )
        controller.createCharacter("""{"ch_name":"Alice"}""")
        val oldAvatar = byteArrayOf(9, 8, 7)
        paths.charactersDir.resolve("alice.png").writeBytes(oldAvatar)
        val jpeg = Files.createTempFile("avatar", ".jpg").toFile().apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1))
        }
        val invalid = Files.createTempFile("avatar", ".gif").toFile().apply { writeBytes("GIF89a".toByteArray()) }

        val decodeFailure = controller.editAvatar(avatarRequest(jpeg, "avatar.jpg"))
        val unsupported = controller.editAvatar(avatarRequest(invalid, "avatar.gif"))
        val traversal = controller.editAvatar(avatarRequest(jpeg, "avatar.jpg", "../alice.png"))

        assertEquals(400, decodeFailure.statusCode)
        assertEquals("invalid_avatar_image", JsonParser.parseString(decodeFailure.bodyText!!).asJsonObject.get("error").asString)
        assertEquals(400, unsupported.statusCode)
        assertEquals(400, traversal.statusCode)
        assertArrayEquals(oldAvatar, paths.charactersDir.resolve("alice.png").readBytes())
        assertEquals(1, normalizerCalls)
    }

    @Test
    fun `duplicate copies card and avatar without copying chats and returns official path`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(paths)
        controller.createCharacter("""{"ch_name":"Alice","json_data":"{\"vendor\":{\"id\":7}}"}""")
        paths.charactersDir.resolve("alice.png").writeBytes(BASE_PNG)
        paths.chatsDir.resolve("alice").apply { mkdirs() }.resolve("chat.jsonl").writeText("{}")
        paths.charactersDir.resolve("alice1.png").writeBytes(byteArrayOf(1))
        paths.chatsDir.resolve("alice2").mkdirs()

        val response = controller.duplicateCharacter("""{"avatar_url":"alice.png"}""")
        val body = JsonParser.parseString(response.bodyText!!).asJsonObject

        assertEquals(200, response.statusCode)
        assertEquals("alice3.png", body.get("path").asString)
        assertEquals(
            paths.charactersDir.resolve("alice.json").readText(),
            paths.charactersDir.resolve("alice3.json").readText()
        )
        assertArrayEquals(BASE_PNG, paths.charactersDir.resolve("alice3.png").readBytes())
        assertFalse(paths.chatsDir.resolve("alice3").exists())
    }

    @Test
    fun `rename moves card avatar and chat directory while preserving unknown fields`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(paths)
        controller.createCharacter("""{"ch_name":"Alice","json_data":"{\"vendor\":{\"id\":7}}"}""")
        paths.charactersDir.resolve("alice.png").writeBytes(BASE_PNG)
        paths.chatsDir.resolve("alice").apply { mkdirs() }.resolve("chat.jsonl").writeText("{}")
        paths.charactersDir.resolve("alice/sprites").mkdirs()
        paths.charactersDir.resolve("alice/sprites/joy.png").writeBytes(BASE_PNG)

        val response = controller.renameCharacter(
            """{"avatar_url":"alice.png","new_name":"Alice Renamed"}"""
        )
        val renamed = JsonParser.parseString(paths.charactersDir.resolve("alice_renamed.json").readText()).asJsonObject

        assertEquals(200, response.statusCode)
        assertEquals("alice_renamed.png", JsonParser.parseString(response.bodyText!!).asJsonObject.get("avatar").asString)
        assertFalse(paths.charactersDir.resolve("alice.json").exists())
        assertFalse(paths.charactersDir.resolve("alice.png").exists())
        assertFalse(paths.charactersDir.resolve("alice/sprites").exists())
        assertFalse(paths.chatsDir.resolve("alice").exists())
        assertTrue(paths.charactersDir.resolve("alice_renamed.png").isFile)
        assertArrayEquals(BASE_PNG, paths.charactersDir.resolve("alice_renamed/sprites/joy.png").readBytes())
        assertTrue(paths.chatsDir.resolve("alice_renamed/chat.jsonl").isFile)
        assertEquals("Alice Renamed", renamed.get("name").asString)
        assertEquals("Alice Renamed", renamed.getAsJsonObject("data").get("name").asString)
        assertEquals(7, renamed.getAsJsonObject("vendor").get("id").asInt)
    }

    @Test
    fun `rename conflict leaves source card avatar and chats intact`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(paths)
        controller.createCharacter("""{"ch_name":"Alice"}""")
        controller.createCharacter("""{"ch_name":"Taken"}""")
        paths.charactersDir.resolve("alice.png").writeBytes(BASE_PNG)
        paths.chatsDir.resolve("alice").apply { mkdirs() }.resolve("chat.jsonl").writeText("{}")

        val response = controller.renameCharacter(
            """{"avatar_url":"alice.png","new_name":"Taken"}"""
        )

        assertEquals(409, response.statusCode)
        assertTrue(paths.charactersDir.resolve("alice.json").isFile)
        assertArrayEquals(BASE_PNG, paths.charactersDir.resolve("alice.png").readBytes())
        assertTrue(paths.chatsDir.resolve("alice/chat.jsonl").isFile)
        assertTrue(paths.charactersDir.resolve("taken.json").isFile)
    }

    @Test
    fun `rename restores original json avatar and chats when a later move fails`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(
            paths,
            pathCopier = { source, target ->
                if (source.name == "alice.png") throw java.io.IOException("avatar move failed")
                if (source.isDirectory) source.copyRecursively(target, overwrite = false)
                else source.copyTo(target, overwrite = false)
            }
        )
        controller.createCharacter("""{"ch_name":"Alice","json_data":"{\"vendor\":{\"id\":7}}"}""")
        paths.charactersDir.resolve("alice.png").writeBytes(BASE_PNG)
        paths.chatsDir.resolve("alice").apply { mkdirs() }.resolve("chat.jsonl").writeText("{}")
        val originalJson = paths.charactersDir.resolve("alice.json").readBytes()

        val response = controller.renameCharacter(
            """{"avatar_url":"alice.png","new_name":"Alice Renamed"}"""
        )

        assertEquals(500, response.statusCode)
        assertArrayEquals(originalJson, paths.charactersDir.resolve("alice.json").readBytes())
        assertArrayEquals(BASE_PNG, paths.charactersDir.resolve("alice.png").readBytes())
        assertTrue(paths.chatsDir.resolve("alice/chat.jsonl").isFile)
        assertFalse(paths.charactersDir.resolve("alice_renamed.json").exists())
        assertFalse(paths.charactersDir.resolve("alice_renamed.png").exists())
        assertFalse(paths.chatsDir.resolve("alice_renamed").exists())
    }

    @Test
    fun `rename restores source json and removes prepared targets when atomic identity move fails`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(
            paths,
            jsonMover = { source, target ->
                throw java.nio.file.AtomicMoveNotSupportedException(source.path, target.path, "unsupported")
            }
        )
        controller.createCharacter("""{"ch_name":"Alice","json_data":"{\"vendor\":{\"id\":7}}"}""")
        paths.charactersDir.resolve("alice.png").writeBytes(BASE_PNG)
        paths.chatsDir.resolve("alice").apply { mkdirs() }.resolve("chat.jsonl").writeText("{}")
        val originalJson = paths.charactersDir.resolve("alice.json").readBytes()

        val response = controller.renameCharacter(
            """{"avatar_url":"alice.png","new_name":"Alice Renamed"}"""
        )

        assertEquals(500, response.statusCode)
        assertArrayEquals(originalJson, paths.charactersDir.resolve("alice.json").readBytes())
        assertArrayEquals(BASE_PNG, paths.charactersDir.resolve("alice.png").readBytes())
        assertTrue(paths.chatsDir.resolve("alice/chat.jsonl").isFile)
        assertFalse(paths.charactersDir.resolve("alice_renamed.json").exists())
        assertFalse(paths.charactersDir.resolve("alice_renamed.png").exists())
        assertFalse(paths.chatsDir.resolve("alice_renamed").exists())
    }

    @Test
    fun `merge attributes deep merges single and batch updates without dropping unknown fields`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        paths.webDir.resolve("img").mkdirs()
        paths.webDir.resolve("img/ai4.png").writeBytes(BASE_PNG)
        val controller = CharacterController(paths)
        val firstUpload = Files.createTempFile("character-card-v2", ".json").toFile().apply {
            writeBytes(fixture("character-card-v2.json"))
        }
        val secondUpload = Files.createTempFile("character-card-v2-copy", ".json").toFile().apply {
            writeBytes(fixture("character-card-v2.json"))
        }
        controller.importCharacter(uploadRequest(firstUpload, "first.json", "application/json", "json"))
        controller.importCharacter(uploadRequest(secondUpload, "second.json", "application/json", "json"))

        val single = controller.mergeAttributes(
            """{
                "avatar":"v2_character.png",
                "chat":"Updated Chat",
                "data":{"extensions":{"vendor_extension":{"added":true,"marker":"__@@UNSET@@__","nullable":null}}}
            }""".trimIndent()
        )
        val afterSingle = JsonParser.parseString(
            paths.charactersDir.resolve("v2_character.json").readText()
        ).asJsonObject
        val singleVendor = afterSingle.getAsJsonObject("data").getAsJsonObject("extensions")
            .getAsJsonObject("vendor_extension")

        assertEquals(200, single.statusCode)
        assertEquals("Updated Chat", afterSingle.get("chat").asString)
        assertFalse(singleVendor.has("marker"))
        assertTrue(singleVendor.get("nullable").isJsonNull)
        assertTrue(singleVendor.get("added").asBoolean)

        val batch = controller.mergeAttributes(
            """{
                "avatars":[],
                "data":{"data":{"extensions":{"batch":{"enabled":true}}}},
                "filter":{"path":"data.extensions.vendor_extension.added"}
            }""".trimIndent()
        )
        val first = JsonParser.parseString(paths.charactersDir.resolve("v2_character.json").readText()).asJsonObject
        val second = JsonParser.parseString(paths.charactersDir.resolve("v2_character1.json").readText()).asJsonObject
        val batchBody = JsonParser.parseString(batch.bodyText!!).asJsonObject

        assertEquals("v2-top", first.getAsJsonObject("vendor_top").get("marker").asString)
        assertEquals(200, batch.statusCode)
        assertEquals(1, batchBody.getAsJsonArray("updated").size())
        assertEquals(1, batchBody.getAsJsonArray("skipped").size())
        assertEquals(0, batchBody.getAsJsonArray("failed").size())
        assertTrue(first.getAsJsonObject("data").getAsJsonObject("extensions")
            .getAsJsonObject("batch").get("enabled").asBoolean)
        assertFalse(second.getAsJsonObject("data").getAsJsonObject("extensions").has("batch"))
    }

    @Test
    fun `native server registers six advanced character routes but not external url imports`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character-routes").toFile())
        val server = NativeHttpServer(paths)
        server.start()
        try {
            val base = "http://127.0.0.1:${server.listeningPort}"
            val routeBodies = linkedMapOf(
                "/api/characters/import" to "{}",
                "/api/characters/export" to "{}",
                "/api/characters/duplicate" to "{}",
                "/api/characters/rename" to "{}",
                "/api/characters/merge-attributes" to "{}",
                "/api/characters/edit-avatar" to "{}"
            )
            routeBodies.forEach { (path, body) ->
                val response = postJson("$base$path", body)
                assertFalse("route not registered: $path", response.second.contains("endpoint_not_found"))
            }
            listOf("/api/content/importURL", "/api/content/importUUID").forEach { path ->
                val response = postJson("$base$path", """{"url":"https://example.com/card.png"}""")
                assertEquals(404, response.first)
                assertEquals("endpoint_not_found", JsonParser.parseString(response.second).asJsonObject.get("error").asString)
            }
        } finally {
            server.stop()
        }
    }

    private fun uploadRequest(file: File, originalName: String, mimeType: String, fileType: String): NativeRequest =
        NativeRequest(
            method = "POST",
            path = "/api/characters/import",
            query = emptyMap(),
            form = mapOf("file_type" to listOf(fileType), "user_name" to listOf("User")),
            bodyText = "",
            uploads = mapOf("avatar" to UploadedFile("avatar", originalName, mimeType, file))
        )

    private fun avatarRequest(file: File, originalName: String, avatarUrl: String = "alice.png"): NativeRequest =
        NativeRequest(
            method = "POST",
            path = "/api/characters/edit-avatar",
            query = emptyMap(),
            form = mapOf("avatar_url" to listOf(avatarUrl)),
            bodyText = "",
            uploads = mapOf("avatar" to UploadedFile("avatar", originalName, "application/octet-stream", file))
        )

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/$name")
    ).use { it.readBytes() }

    private fun postJson(url: String, body: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
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

    private companion object {
        val BASE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
