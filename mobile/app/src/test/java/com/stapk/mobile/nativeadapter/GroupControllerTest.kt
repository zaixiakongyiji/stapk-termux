package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GroupControllerTest {
    @Test
    fun `create generates an id filters members and preserves upstream fields`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group").toFile())
        paths.charactersDir.mkdirs()
        paths.charactersDir.resolve("alice.json").writeText("{}")
        paths.charactersDir.resolve("bob.json").writeText("{}")
        val controller = GroupController(paths) { "1700000000000-a1b2c3d4e5f6" }

        val response = controller.createGroup(fixture("group.json"))
        val group = JsonParser.parseString(response.bodyText!!).asJsonObject

        assertEquals(200, response.statusCode)
        assertEquals("1700000000000-a1b2c3d4e5f6", group.get("id").asString)
        assertEquals(listOf("alice.png", "bob.png"), group.getAsJsonArray("members").map { it.asString })
        assertEquals(listOf("bob.png"), group.getAsJsonArray("disabled_members").map { it.asString })
        assertEquals(1, group.get("activation_strategy").asInt)
        assertTrue(group.get("allow_self_responses").asBoolean)
        assertTrue(group.get("fav").asBoolean)
        assertEquals("chat-main", group.get("chat_id").asString)
        assertEquals("legacy", group.getAsJsonObject("past_metadata").getAsJsonObject("chat-old").get("world_info").asString)
        assertTrue(group.getAsJsonObject("vendor_extension").getAsJsonObject("nested").get("enabled").asBoolean)
        assertTrue(paths.groupsDir.resolve("1700000000000-a1b2c3d4e5f6.json").isFile)
    }

    @Test
    fun `create applies official defaults using the generated id`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-defaults").toFile())
        val controller = GroupController(paths) { "1700000000001-010203040506" }

        val response = controller.createGroup("{}")
        val group = JsonParser.parseString(response.bodyText!!).asJsonObject

        assertEquals(200, response.statusCode)
        assertEquals("New Group", group.get("name").asString)
        assertTrue(group.getAsJsonArray("members").isEmpty)
        assertTrue(group.getAsJsonArray("disabled_members").isEmpty)
        assertFalse(group.get("allow_self_responses").asBoolean)
        assertEquals(0, group.get("activation_strategy").asInt)
        assertEquals(0, group.get("generation_mode").asInt)
        assertEquals(5, group.get("auto_mode_delay").asInt)
        assertEquals("", group.get("generation_mode_join_prefix").asString)
        assertEquals("", group.get("generation_mode_join_suffix").asString)
        assertEquals("1700000000001-010203040506", group.get("chat_id").asString)
        assertEquals(listOf("1700000000001-010203040506"), group.getAsJsonArray("chats").map { it.asString })
    }

    @Test
    fun `default group id contains a timestamp and six random bytes`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-id").toFile())

        val response = GroupController(paths).createGroup("{}")
        val id = JsonParser.parseString(response.bodyText!!).asJsonObject.get("id").asString

        assertTrue(id.matches(Regex("[0-9]+-[a-f0-9]{12}")))
        assertTrue(paths.groupsDir.resolve("$id.json").isFile)
    }

    @Test
    fun `edit merges known changes while preserving unknown fields`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-edit").toFile())
        paths.charactersDir.mkdirs()
        paths.charactersDir.resolve("alice.json").writeText("{}")
        paths.charactersDir.resolve("bob.json").writeText("{}")
        val id = "1700000000002-aabbccddeeff"
        val controller = GroupController(paths) { id }
        controller.createGroup(fixture("group.json"))

        val response = controller.editGroup(
            """{"id":"$id","name":"Edited Group","members":["bob.png","missing.png"]}"""
        )
        val stored = JsonParser.parseString(paths.groupsDir.resolve("$id.json").readText()).asJsonObject

        assertEquals(200, response.statusCode)
        assertTrue(JsonParser.parseString(response.bodyText!!).asJsonObject.get("ok").asBoolean)
        assertEquals("Edited Group", stored.get("name").asString)
        assertEquals(listOf("bob.png"), stored.getAsJsonArray("members").map { it.asString })
        assertEquals(listOf("bob.png"), stored.getAsJsonArray("disabled_members").map { it.asString })
        assertTrue(stored.getAsJsonObject("vendor_extension").getAsJsonObject("nested").get("enabled").asBoolean)
        assertEquals("legacy", stored.getAsJsonObject("past_metadata").getAsJsonObject("chat-old").get("world_info").asString)
    }

    @Test
    fun `all returns groups sorted by id and quarantines invalid JSON`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-all").toFile())
        paths.groupsDir.mkdirs()
        paths.groupsDir.resolve("z-group.json").writeText("""{"id":"z-group","name":"Z"}""")
        paths.groupsDir.resolve("a-group.json").writeText("""{"id":"a-group","name":"A"}""")
        paths.groupsDir.resolve("broken.json").writeText("not-json")
        val controller = GroupController(paths)

        val response = controller.allGroups()
        val groups = JsonParser.parseString(response.bodyText!!).asJsonArray

        assertEquals(200, response.statusCode)
        assertEquals(listOf("a-group", "z-group"), groups.map { it.asJsonObject.get("id").asString })
        assertFalse(paths.groupsDir.resolve("broken.json").exists())
        assertTrue(paths.quarantineDir.walkTopDown().any { it.name == "broken.json" })
    }

    @Test
    fun `delete can preserve associated group chats`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-delete-preserve").toFile())
        paths.groupChatsDir.mkdirs()
        paths.groupChatsDir.resolve("chat-main.jsonl").writeText("{}")
        paths.groupChatsDir.resolve("chat-old.jsonl").writeText("{}")
        val id = "1700000000003-112233445566"
        val controller = GroupController(paths) { id }
        controller.createGroup(fixture("group.json"))

        val response = controller.deleteGroup("""{"id":"$id","delete_chats":false}""")

        assertEquals(200, response.statusCode)
        assertFalse(paths.groupsDir.resolve("$id.json").exists())
        assertTrue(paths.groupChatsDir.resolve("chat-main.jsonl").isFile)
        assertTrue(paths.groupChatsDir.resolve("chat-old.jsonl").isFile)
    }

    @Test
    fun `delete removes associated group chats by default`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-group-delete-default").toFile())
        paths.groupChatsDir.mkdirs()
        paths.groupChatsDir.resolve("chat-main.jsonl").writeText("{}")
        paths.groupChatsDir.resolve("chat-old.jsonl").writeText("{}")
        val id = "1700000000004-66778899aabb"
        val controller = GroupController(paths) { id }
        controller.createGroup(fixture("group.json"))

        val response = controller.deleteGroup("""{"id":"$id"}""")

        assertEquals(200, response.statusCode)
        assertFalse(paths.groupsDir.resolve("$id.json").exists())
        assertFalse(paths.groupChatsDir.resolve("chat-main.jsonl").exists())
        assertFalse(paths.groupChatsDir.resolve("chat-old.jsonl").exists())
    }

    @Test
    fun `group ids reject traversal without touching files outside the group root`() {
        val filesDir = Files.createTempDirectory("stapk-group-traversal").toFile()
        val paths = NativeAdapterPaths(filesDir)
        val outside = paths.userDataDir.resolve("escape.json").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("keep")
        }

        val edit = GroupController(paths).editGroup("""{"id":"../escape","name":"bad"}""")
        val delete = GroupController(paths).deleteGroup("""{"id":"../escape"}""")

        assertEquals(400, edit.statusCode)
        assertEquals(400, delete.statusCode)
        assertEquals("keep", outside.readText())
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")).readText()
}
