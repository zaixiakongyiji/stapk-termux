package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class ChatController(
    private val paths: NativeAdapterPaths,
    private val exportStore: ExportStore = ExportStore(paths.userDataDir.resolve("exports")),
    private val backupController: ChatBackupController = ChatBackupController(paths),
    private val diagnosticLogger: DiagnosticLogger = DiagnosticLogger(paths.logsDir)
) {
    private val gson = Gson()

    fun getChat(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val file = chatFile(request, "file_name") ?: return invalidChatResponse()
        file.parentFile?.mkdirs()
        if (!file.isFile) return HttpResponse.json(200, "[]")

        val chat = JsonArray()
        file.readLines().filter { it.isNotBlank() }.forEach { line ->
            runCatching { JsonParser.parseString(line) }.getOrNull()?.let(chat::add)
        }
        return HttpResponse.json(200, gson.toJson(chat))
    }

    @Synchronized
    fun saveChat(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val file = chatFile(request, "file_name") ?: return invalidChatResponse()
        val chat = request.get("chat")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return HttpResponse.json(400, """{"error":"chat_must_be_array"}""")
        val content = chat.joinToString("\n") { gson.toJson(it) }
        file.parentFile?.mkdirs()
        backupController.backupIfChanged(file, content)
        writeAtomically(file, content)
        return HttpResponse.json(200, """{"ok":true}""")
    }

    @Synchronized
    fun deleteChat(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val key = if (request.has("chatfile")) "chatfile" else "file_name"
        val file = chatFile(request, key) ?: return invalidChatResponse()
        if (!file.delete()) return invalidChatResponse()
        return HttpResponse.json(200, """{"ok":true}""")
    }

    fun searchChats(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val queryFragments = request.stringValue("query")
            .trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        val results = JsonArray()
        val groupId = request.stringValue("group_id")
        val files = if (groupId.isNotBlank()) {
            groupSearchFiles(groupId) ?: return invalidChatResponse()
        } else {
            val stem = avatarStem(request.stringValue("avatar_url")) ?: return invalidChatResponse()
            File(paths.chatsDir, stem).listFiles { file ->
                file.isFile && file.extension.equals("jsonl", true)
            }.orEmpty().toList()
        }
        files.sortedBy { it.name }
            .forEach { file ->
                val lines = file.readLines().filter { it.isNotBlank() }
                val objects = lines.mapNotNull { line ->
                    runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                }
                val messages = objects.drop(1)
                val texts = messages.map { it.stringValue("mes").lowercase() }
                val fileNameMatches = queryFragments.all { file.nameWithoutExtension.lowercase().contains(it) }
                val messagesMatch = queryFragments.all { fragment -> texts.any { it.contains(fragment) } }
                if (queryFragments.isNotEmpty() && !fileNameMatches && !messagesMatch) return@forEach

                val lastMessage = messages.lastOrNull()
                results.add(JsonObject().apply {
                    addProperty("file_name", file.nameWithoutExtension)
                    addProperty("file_size", "${file.length()} B")
                    addProperty("message_count", messages.size)
                    if (lastMessage?.has("send_date") == true) {
                        add("last_mes", lastMessage.get("send_date").deepCopy())
                    } else {
                        addProperty("last_mes", file.lastModified())
                    }
                    addProperty("preview_message", lastMessage?.stringValue("mes").orEmpty())
                })
            }
        return HttpResponse.json(200, gson.toJson(results))
    }

    private fun groupSearchFiles(groupId: String): List<File>? {
        if (!groupId.matches(GROUP_ID_PATTERN)) return null
        val groupFile = File(paths.groupsDir, "$groupId.json")
        if (!groupFile.isFile || Files.isSymbolicLink(groupFile.toPath())) return null
        val group = runCatching { JsonParser.parseString(groupFile.readText()).asJsonObject }.getOrNull()
            ?: return null
        return group.get("chats")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                val stem = element.takeIf { it.isJsonPrimitive }?.asString?.let(::chatStem)
                    ?: return@mapNotNull null
                File(paths.groupChatsDir, "$stem.jsonl").takeIf { file ->
                    file.isFile && !Files.isSymbolicLink(file.toPath())
                }
            }
            .orEmpty()
            .distinctBy { it.name }
    }

    fun recentChats(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val max = request.stringValue("max").toIntOrNull()?.coerceAtLeast(0) ?: Int.MAX_VALUE
        val files = (recentCharacterFiles() + recentGroupFiles())
            .sortedWith(compareByDescending<RecentChatFile> { it.file.lastModified() }.thenBy { it.file.path })
            .take(max)
        val recent = JsonArray()
        files.forEach { candidate ->
            val summary = readRecentSummary(candidate) ?: return@forEach
            recent.add(summary)
        }
        return HttpResponse.json(200, gson.toJson(recent))
    }

    @Synchronized
    fun renameChat(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val directory = if (request.get("is_group")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
            paths.groupChatsDir
        } else {
            val avatar = avatarStem(request.stringValue("avatar_url")) ?: return invalidChatResponse()
            File(paths.chatsDir, avatar)
        }
        val sourceStem = chatStem(request.stringValue("original_file")) ?: return invalidChatResponse()
        val targetStem = chatStem(request.stringValue("renamed_file")) ?: return invalidChatResponse()
        val source = File(directory, "$sourceStem.jsonl")
        val target = File(directory, "$targetStem.jsonl")
        if (!source.isFile || target.exists()) return invalidChatResponse()
        return try {
            try {
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath())
            }
            HttpResponse.json(200, gson.toJson(JsonObject().apply {
                addProperty("ok", true)
                addProperty("sanitizedFileName", targetStem)
            }))
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":true}""")
        }
    }

    @Synchronized
    fun importChat(request: NativeRequest): HttpResponse {
        if (request.formValue("file_type") != "jsonl") return invalidChatResponse()
        val avatar = avatarStem(request.formValue("avatar_url")) ?: return invalidChatResponse()
        val upload = request.uploads["avatar"] ?: return invalidChatResponse()
        val baseStem = chatStem(upload.originalName) ?: return invalidChatResponse()
        val content = runCatching { upload.tempFile.readText() }.getOrNull() ?: return invalidChatResponse()
        val validJsonl = content.lineSequence()
            .filter { it.isNotBlank() }
            .all { line -> runCatching { JsonParser.parseString(line).asJsonObject }.isSuccess }
        if (!validJsonl || content.lineSequence().none { it.isNotBlank() }) return invalidChatResponse()

        val directory = File(paths.chatsDir, avatar).apply { mkdirs() }
        val target = uniqueChatFile(directory, baseStem)
        return try {
            writeAtomically(target, content)
            HttpResponse.json(200, gson.toJson(JsonObject().apply {
                addProperty("res", true)
                add("fileNames", JsonArray().apply { add(target.name) })
            }))
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":true}""")
        }
    }

    fun exportChat(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidChatResponse()
        val directory = if (request.booleanValue("is_group")) {
            paths.groupChatsDir
        } else {
            val avatar = avatarStem(request.stringValue("avatar_url")) ?: return invalidChatResponse()
            File(paths.chatsDir, avatar)
        }
        val sourceStem = chatStem(request.stringValue("file")) ?: return invalidChatResponse()
        val source = File(directory, "$sourceStem.jsonl")
        if (!source.isFile || Files.isSymbolicLink(source.toPath())) {
            return HttpResponse.json(404, """{"message":"chat_not_found"}""")
        }
        val format = request.stringValue("format").lowercase()
        if (format != "jsonl" && format != "txt") return invalidChatResponse()
        val exportName = runCatching {
            SafePath.fileName(request.stringValue("exportfilename"))
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return invalidChatResponse()

        return try {
            val mimeType = if (format == "txt") "text/plain" else "application/x-ndjson"
            val ticket = exportStore.create(exportName, mimeType) { target ->
                if (format == "jsonl") {
                    Files.copy(source.toPath(), target.toPath(), REPLACE_EXISTING)
                } else {
                    target.writeText(exportTxt(source))
                }
            }
            HttpResponse.file(ticket.file, ticket.fileName, ticket.token, ticket.mimeType)
        } catch (_: Exception) {
            HttpResponse.json(400, """{"error":"invalid_chat_export"}""")
        }
    }

    fun cleanupExports() = exportStore.cleanupExpired()

    private fun recentCharacterFiles(): List<RecentChatFile> =
        paths.chatsDir.listFiles { file -> file.isDirectory && !Files.isSymbolicLink(file.toPath()) }
            .orEmpty()
            .flatMap { directory ->
                directory.listFiles { file ->
                    file.isFile && !Files.isSymbolicLink(file.toPath()) && file.extension.equals("jsonl", true)
                }.orEmpty().map { file -> RecentChatFile(file, avatarUrl = "${directory.name}.png") }
            }
            .sortedByDescending { it.file.lastModified() }
            .take(MAX_RECENT_PER_KIND)

    private fun recentGroupFiles(): List<RecentChatFile> {
        val groupByChat = linkedMapOf<String, String>()
        paths.groupsDir.listFiles { file -> file.isFile && !Files.isSymbolicLink(file.toPath()) && file.extension.equals("json", true) }
            .orEmpty()
            .forEach { file ->
                val group = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
                    ?: run {
                        recordDiagnostic("recent_invalid_group", file)
                        return@forEach
                    }
                val groupId = group.stringValue("id")
                group.get("chats")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { chat ->
                    chat.takeIf { it.isJsonPrimitive }?.asString?.let { groupByChat[it] = groupId }
                }
            }
        return paths.groupChatsDir.listFiles { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) && file.extension.equals("jsonl", true)
        }.orEmpty()
            .mapNotNull { file -> groupByChat[file.nameWithoutExtension]?.let { RecentChatFile(file, groupId = it) } }
            .sortedByDescending { it.file.lastModified() }
            .take(MAX_RECENT_PER_KIND)
    }

    private fun readRecentSummary(candidate: RecentChatFile): JsonObject? {
        val objects = runCatching {
            candidate.file.readLines().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }
        }.getOrNull() ?: run {
            recordDiagnostic("recent_invalid_chat", candidate.file)
            return null
        }
        val lastMessage = objects.drop(1).lastOrNull() ?: run {
            recordDiagnostic("recent_empty_chat", candidate.file)
            return null
        }
        return JsonObject().apply {
            candidate.avatarUrl?.let { addProperty("avatar", it) }
            candidate.groupId?.let { addProperty("group", it) }
            addProperty("file_name", candidate.file.name)
            addProperty("file_size", "${candidate.file.length()} B")
            addProperty("chat_items", objects.size - 1)
            addProperty("mes", lastMessage.stringValue("mes"))
            if (lastMessage.has("send_date")) {
                add("last_mes", lastMessage.get("send_date").deepCopy())
            } else {
                addProperty("last_mes", candidate.file.lastModified())
            }
        }
    }

    private fun recordDiagnostic(event: String, file: File) {
        runCatching {
            diagnosticLogger.event(DiagnosticArea.STORAGE, event, mapOf("file" to file.name))
        }
    }

    private fun uniqueChatFile(directory: File, baseStem: String): File {
        var suffix = 0
        while (true) {
            val stem = if (suffix == 0) baseStem else "$baseStem-$suffix"
            val candidate = File(directory, "$stem.jsonl")
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val temporary = File.createTempFile("stapk-chat-", ".tmp", target.parentFile)
        try {
            temporary.writeText(content)
            try {
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun exportTxt(source: File): String = source.readLines()
        .filter { it.isNotBlank() }
        .map { JsonParser.parseString(it).asJsonObject }
        .filter { it.has("mes") }
        .joinToString("\n") { message -> "${message.stringValue("name")}: ${message.stringValue("mes")}" }

    private fun chatFile(request: JsonObject, fileKey: String): File? {
        val avatar = avatarStem(request.stringValue("avatar_url")) ?: return null
        val chat = chatStem(request.stringValue(fileKey)) ?: return null
        return File(File(paths.chatsDir, avatar), "$chat.jsonl")
    }

    private fun avatarStem(value: String): String? {
        if (!value.matches(Regex("[A-Za-z0-9_-]+\\.png"))) return null
        return value.removeSuffix(".png")
    }

    private fun chatStem(value: String): String? {
        val stem = value.removeSuffix(".jsonl").trim()
        if (stem.isBlank() || stem.length > 160 || stem.contains("..")) return null
        if (stem.any { it.isISOControl() || it in INVALID_FILE_CHARS }) return null
        return stem
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

    private fun JsonObject.booleanValue(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrDefault(false) ?: false

    private fun NativeRequest.formValue(key: String): String = form[key]?.firstOrNull().orEmpty()

    private fun invalidChatResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_chat"}""")

    private companion object {
        val INVALID_FILE_CHARS = setOf('/', '\\', '<', '>', ':', '"', '|', '?', '*')
        val GROUP_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,160}")
        const val MAX_RECENT_PER_KIND = 200
    }

    private data class RecentChatFile(
        val file: File,
        val avatarUrl: String? = null,
        val groupId: String? = null
    )
}
