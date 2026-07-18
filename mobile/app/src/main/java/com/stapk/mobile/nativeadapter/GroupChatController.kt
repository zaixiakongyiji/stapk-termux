package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.security.SecureRandom
import java.util.Locale

class GroupChatController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir),
    private val importIdGenerator: () -> String = ::generateImportId
) {
    private val gson = Gson()

    fun getChat(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val file = chatFile(request.stringValue("id")) ?: return invalidChatResponse()
        if (!file.isFile) return HttpResponse.json(200, "[]")
        val chat = readChat(file) ?: return invalidChatResponse()
        return HttpResponse.json(200, gson.toJson(chat))
    }

    fun info(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val file = chatFile(request.stringValue("id")) ?: return invalidChatResponse()
        if (!file.isFile) return invalidChatResponse()
        val chat = readChat(file) ?: return invalidChatResponse()
        val metadata = chat.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("chat_metadata")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: JsonObject()
        val lastMessage = chat.lastOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val lastMes = lastMessage?.get("send_date")?.takeIf { it.isJsonPrimitive }
        val info = JsonObject().apply {
            addProperty("file_name", file.name)
            addProperty("file_size", "${file.length()} B")
            addProperty("chat_items", (chat.size() - 1).coerceAtLeast(0))
            if (lastMes != null) add("last_mes", lastMes.deepCopy()) else addProperty("last_mes", file.lastModified())
            add("chat_metadata", metadata.deepCopy())
        }
        return HttpResponse.json(200, gson.toJson(info))
    }

    @Synchronized
    fun saveChat(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val file = chatFile(request.stringValue("id")) ?: return invalidChatResponse()
        val chat = request.get("chat")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return HttpResponse.json(400, """{"error":"chat_must_be_array"}""")
        if (chat.any { !it.isJsonObject }) {
            return HttpResponse.json(400, """{"error":"chat_items_must_be_objects"}""")
        }
        if (!hasMetadataHeader(chat)) return invalidChatResponse()
        store.writeText(file, chat.joinToString("\n") { gson.toJson(it) })
        return HttpResponse.json(200, """{"ok":true}""")
    }

    @Synchronized
    fun deleteChat(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val file = chatFile(request.stringValue("id")) ?: return invalidChatResponse()
        if (!file.delete()) return invalidChatResponse()
        return HttpResponse.json(200, """{"ok":true}""")
    }

    @Synchronized
    fun importChat(request: NativeRequest): HttpResponse {
        val upload = request.uploads["avatar"] ?: return invalidChatResponse()
        if (upload.originalName.substringAfterLast('.', "").lowercase(Locale.US) != "jsonl") {
            return invalidChatResponse()
        }
        if (!upload.tempFile.isFile || upload.tempFile.length() > MAX_IMPORT_BYTES) {
            return invalidChatResponse()
        }
        val chat = parseImportedChat(upload.tempFile) ?: run {
            runCatching { store.quarantine(upload.tempFile, "invalid_group_chat_jsonl") }
            return invalidChatResponse()
        }
        val id = importIdGenerator()
        val target = chatFile(id) ?: return invalidChatResponse()
        if (target.exists()) return HttpResponse.json(409, """{"error":"group_chat_exists"}""")
        store.writeText(target, chat.joinToString("\n") { gson.toJson(it) })
        return HttpResponse.json(200, gson.toJson(JsonObject().apply { addProperty("res", id) }))
    }

    private fun readChat(file: File): JsonArray? {
        val chat = JsonArray()
        return try {
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                chat.add(JsonParser.parseString(line).asJsonObject)
            }
            chat
        } catch (_: Exception) {
            store.quarantine(file, "invalid_group_chat_jsonl")
            null
        }
    }

    private fun parseImportedChat(file: File): JsonArray? = try {
        JsonArray().apply {
            file.readLines().filter { it.isNotBlank() }.forEach { line ->
                add(JsonParser.parseString(line).asJsonObject)
            }
            if (size() == 0) error("Empty group chat")
            val metadata = get(0).asJsonObject.get("chat_metadata")
            if (metadata == null || !metadata.isJsonObject) error("Missing group chat metadata")
        }
    } catch (_: Exception) {
        null
    }

    private fun hasMetadataHeader(chat: JsonArray): Boolean =
        chat.size() > 0 &&
            chat[0].isJsonObject &&
            chat[0].asJsonObject.get("chat_metadata")?.isJsonObject == true

    private fun chatFile(value: String): File? {
        val stem = value.removeSuffix(".jsonl").trim()
        if (stem.isBlank() || stem.length > 160 || stem.contains("..")) return null
        if (stem.any { it.isISOControl() || it in INVALID_FILE_CHARS }) return null
        return paths.groupChatsDir.resolve("$stem.jsonl")
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
    } catch (_: IllegalStateException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    private fun JsonObject.stringValue(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun invalidChatResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_group_chat"}""")

    private companion object {
        val INVALID_FILE_CHARS = setOf('/', '\\', '<', '>', ':', '"', '|', '?', '*')
        const val MAX_IMPORT_BYTES = 64L * 1024L * 1024L
        val random = SecureRandom()

        fun generateImportId(): String {
            val suffix = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            return "${System.currentTimeMillis()}-$suffix"
        }
    }
}
