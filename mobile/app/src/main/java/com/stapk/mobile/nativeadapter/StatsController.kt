package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant

class StatsController(
    private val paths: NativeAdapterPaths,
    private val diagnosticLogger: DiagnosticLogger = DiagnosticLogger(paths.logsDir)
) {
    private val gson = Gson()

    fun getStats(): HttpResponse {
        if (!paths.statsFile.isFile) return HttpResponse.json(200, "{}")
        return runCatching { JsonParser.parseString(paths.statsFile.readText()).asJsonObject }
            .fold(
                onSuccess = { HttpResponse.json(200, gson.toJson(it)) },
                onFailure = { HttpResponse.json(500, """{"error":"invalid_stats"}""") }
            )
    }

    @Synchronized
    fun recreateStats(): HttpResponse {
        val result = JsonObject()
        paths.chatsDir.listFiles { file -> file.isDirectory && !Files.isSymbolicLink(file.toPath()) }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { directory -> result.add(directory.name, calculateCharacterStats(directory)) }
        return runCatching {
            writeStats(result)
            HttpResponse.json(200, """{"ok":true}""")
        }.getOrElse {
            HttpResponse.json(500, """{"error":"stats_write_failed"}""")
        }
    }

    @Synchronized
    fun updateStats(body: String): HttpResponse {
        val update = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return HttpResponse.json(400, """{"error":"invalid_stats"}""")
        val current = if (paths.statsFile.isFile) {
            runCatching { JsonParser.parseString(paths.statsFile.readText()).asJsonObject }.getOrNull()
                ?: return HttpResponse.json(400, """{"error":"invalid_stats"}""")
        } else {
            JsonObject()
        }
        mergeObjects(current, update)
        return runCatching {
            writeStats(current)
            HttpResponse.json(200, """{"ok":true}""")
        }.getOrElse {
            HttpResponse.json(500, """{"error":"stats_write_failed"}""")
        }
    }

    private fun calculateCharacterStats(directory: File): JsonObject {
        val stats = CharacterStats()
        val seenMessages = mutableSetOf<String>()
        directory.listFiles { file ->
            file.isFile && !Files.isSymbolicLink(file.toPath()) && file.extension.equals("jsonl", true)
        }.orEmpty().sortedBy { it.name }.forEach { file ->
            val messages = runCatching {
                file.readLines().filter { it.isNotBlank() }.map { JsonParser.parseString(it).asJsonObject }
            }.getOrNull() ?: run {
                recordDiagnostic(file)
                return@forEach
            }
            messages.forEach { message -> accumulateMessage(stats, message, seenMessages) }
            stats.chatSize += file.length()
            stats.dateLastChat = maxOf(stats.dateLastChat, file.lastModified())
        }
        return stats.toJson()
    }

    private fun accumulateMessage(stats: CharacterStats, message: JsonObject, seenMessages: MutableSet<String>) {
        val text = message.stringValue("mes")
        if (text.isNotEmpty() && !seenMessages.add(text)) return

        val generationTime = duration(message.stringValue("gen_started"), message.stringValue("gen_finished"))
        stats.totalGenTime += generationTime
        val swipes = message.get("swipes")?.takeIf { it.isJsonArray }?.asJsonArray
        val swipeInfo = message.get("swipe_info")?.takeIf { it.isJsonArray }?.asJsonArray
        if (generationTime > 0 && swipes != null && swipeInfo == null) {
            stats.totalGenTime += generationTime * swipes.size()
        }
        swipeInfo?.drop(1)?.forEach { swipe ->
            swipe.takeIf { it.isJsonObject }?.asJsonObject?.let {
                stats.totalGenTime += duration(it.stringValue("gen_started"), it.stringValue("gen_finished"))
            }
        }

        if (text.isNotEmpty()) stats.addMessage(message.booleanValue("is_user"), text)
        if (swipes != null && swipes.size() > 1) {
            stats.totalSwipeCount += swipes.size() - 1
            swipes.drop(1).forEach { swipe ->
                swipe.takeIf { it.isJsonPrimitive }?.asString?.let {
                    stats.addMessage(message.booleanValue("is_user"), it)
                }
            }
        }
        if (message.booleanValue("is_user")) {
            val sent = parseTimestamp(message.get("send_date"))
            if (sent > 0) stats.dateFirstChat = minOf(stats.dateFirstChat, sent)
        }
    }

    private fun mergeObjects(target: JsonObject, update: JsonObject) {
        update.entrySet().forEach { (key, value) ->
            val current = target.get(key)
            if (current?.isJsonObject == true && value.isJsonObject) {
                mergeObjects(current.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value.deepCopy())
            }
        }
    }

    private fun writeStats(stats: JsonObject) {
        paths.statsFile.parentFile?.mkdirs()
        val temporary = File.createTempFile("stapk-stats-", ".tmp", paths.statsFile.parentFile)
        try {
            temporary.writeText(gson.toJson(stats))
            try {
                Files.move(temporary.toPath(), paths.statsFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), paths.statsFile.toPath(), REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun duration(start: String, finish: String): Long = runCatching {
        (Instant.parse(finish).toEpochMilli() - Instant.parse(start).toEpochMilli()).coerceAtLeast(0)
    }.getOrDefault(0)

    private fun parseTimestamp(value: JsonElement?): Long {
        if (value == null || !value.isJsonPrimitive) return 0
        return runCatching { value.asLong }.getOrElse {
            runCatching { Instant.parse(value.asString).toEpochMilli() }.getOrDefault(0)
        }
    }

    private fun recordDiagnostic(file: File) {
        runCatching {
            diagnosticLogger.event(
                DiagnosticArea.STORAGE,
                "stats_invalid_chat",
                mapOf("file" to file.name)
            )
        }
    }

    private fun JsonObject.stringValue(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.booleanValue(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrDefault(false) ?: false

    private class CharacterStats {
        var totalGenTime = 0L
        var userWordCount = 0
        var nonUserWordCount = 0
        var userMsgCount = 0
        var nonUserMsgCount = 0
        var totalSwipeCount = 0
        var chatSize = 0L
        var dateLastChat = 0L
        var dateFirstChat = Long.MAX_VALUE

        fun addMessage(isUser: Boolean, text: String) {
            val words = WORD_PATTERN.findAll(text).count()
            if (isUser) {
                userWordCount += words
                userMsgCount++
            } else {
                nonUserWordCount += words
                nonUserMsgCount++
            }
        }

        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("total_gen_time", totalGenTime)
            addProperty("user_word_count", userWordCount)
            addProperty("non_user_word_count", nonUserWordCount)
            addProperty("user_msg_count", userMsgCount)
            addProperty("non_user_msg_count", nonUserMsgCount)
            addProperty("total_swipe_count", totalSwipeCount)
            addProperty("total_chat_size", chatSize)
            addProperty("chat_size", chatSize)
            addProperty("date_last_chat", dateLastChat)
            addProperty("date_first_chat", if (dateFirstChat == Long.MAX_VALUE) 0 else dateFirstChat)
        }
    }

    private companion object {
        val WORD_PATTERN = Regex("\\b\\w+\\b")
    }
}
