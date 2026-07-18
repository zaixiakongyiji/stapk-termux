package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.security.SecureRandom

class GroupController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir),
    private val idGenerator: () -> String = ::generateGroupId
) {
    private val gson = Gson()

    @Synchronized
    fun createGroup(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidGroupResponse()
        val id = idGenerator()
        val group = request.deepCopy().apply {
            addProperty("id", id)
            addDefault("name", "New Group")
            addDefault("allow_self_responses", false)
            addDefault("activation_strategy", 0)
            addDefault("generation_mode", 0)
            addDefault("chat_id", id)
            if (!hasValue("chats")) add("chats", JsonArray().apply { add(id) })
            addDefault("auto_mode_delay", 5)
            addDefault("generation_mode_join_prefix", "")
            addDefault("generation_mode_join_suffix", "")
            add("members", filteredMembers(get("members")))
            add("disabled_members", filteredDisabledMembers(get("disabled_members"), getAsJsonArray("members")))
        }
        store.writeText(paths.groupsDir.resolve("$id.json"), gson.toJson(group))
        return HttpResponse.json(200, gson.toJson(group))
    }

    @Synchronized
    fun editGroup(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidGroupResponse()
        val id = request.stringValue("id").takeIf(::isValidId) ?: return invalidGroupResponse()
        val target = paths.groupsDir.resolve("$id.json")
        val existing = store.readJsonObject(target) ?: return invalidGroupResponse()
        val merged = existing.deepCopy().apply {
            request.entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
            if (request.has("members")) add("members", filteredMembers(request.get("members")))
            if (request.has("members") || request.has("disabled_members")) {
                add("disabled_members", filteredDisabledMembers(get("disabled_members"), getAsJsonArray("members")))
            }
        }
        store.writeText(target, gson.toJson(merged))
        return HttpResponse.json(200, """{"ok":true}""")
    }

    fun allGroups(): HttpResponse {
        val groups = paths.groupsDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            .orEmpty()
            .mapNotNull(store::readJsonObject)
            .sortedBy { it.stringValue("id") }
        return HttpResponse.json(200, gson.toJson(groups))
    }

    @Synchronized
    fun deleteGroup(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidGroupResponse()
        val id = request.stringValue("id").takeIf(::isValidId) ?: return invalidGroupResponse()
        val target = paths.groupsDir.resolve("$id.json")
        val existing = store.readJsonObject(target) ?: return invalidGroupResponse()
        val deleteChats = request.get("delete_chats")
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean
            ?: true
        if (deleteChats) {
            existing.get("chats")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { chat ->
                chat.takeIf { it.isJsonPrimitive }?.asString?.let(::groupChatFile)?.delete()
            }
        }
        if (!target.delete()) return invalidGroupResponse()
        return HttpResponse.json(200, """{"ok":true}""")
    }

    private fun filteredMembers(element: com.google.gson.JsonElement?): JsonArray {
        val members = JsonArray()
        val seen = linkedSetOf<String>()
        element?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { member ->
            val avatar = member.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (avatar.matches(AVATAR_PATTERN) && paths.charactersDir.resolve("${avatar.removeSuffix(".png")}.json").isFile && seen.add(avatar)) {
                members.add(avatar)
            }
        }
        return members
    }

    private fun filteredDisabledMembers(element: com.google.gson.JsonElement?, members: JsonArray): JsonArray {
        val memberSet = members.map { it.asString }.toSet()
        val disabled = JsonArray()
        val seen = linkedSetOf<String>()
        element?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { member ->
            val avatar = member.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (avatar in memberSet && seen.add(avatar)) disabled.add(avatar)
        }
        return disabled
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
    } catch (_: IllegalStateException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    private fun invalidGroupResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_group"}""")

    private fun JsonObject.stringValue(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun isValidId(value: String): Boolean = value.matches(GROUP_ID_PATTERN)

    private fun groupChatFile(value: String): java.io.File? {
        val stem = value.removeSuffix(".jsonl").trim()
        if (stem.isBlank() || stem.length > 160 || stem.contains("..")) return null
        if (stem.any { it.isISOControl() || it in INVALID_FILE_CHARS }) return null
        return paths.groupChatsDir.resolve("$stem.jsonl")
    }

    private fun JsonObject.hasValue(key: String): Boolean = has(key) && !get(key).isJsonNull

    private fun JsonObject.addDefault(key: String, value: String) {
        if (!hasValue(key)) addProperty(key, value)
    }

    private fun JsonObject.addDefault(key: String, value: Boolean) {
        if (!hasValue(key)) addProperty(key, value)
    }

    private fun JsonObject.addDefault(key: String, value: Number) {
        if (!hasValue(key)) addProperty(key, value)
    }

    private companion object {
        val AVATAR_PATTERN = Regex("[A-Za-z0-9_-]+\\.png")
        val GROUP_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,160}")
        val INVALID_FILE_CHARS = setOf('/', '\\', '<', '>', ':', '"', '|', '?', '*')
        val random = SecureRandom()

        fun generateGroupId(): String {
            val suffix = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            return "${System.currentTimeMillis()}-$suffix"
        }
    }
}
