package com.stapk.mobile.nativeadapter

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File

data class EmbeddedWorldInfoImportResult(
    val name: String,
    val entryCount: Int,
    val created: Boolean
)

class WorldInfoController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {
    private val gson = GsonBuilder().serializeNulls().create()

    fun listWorldInfo(): HttpResponse = HttpResponse.json(200, gson.toJson(names()))

    internal fun names(): JsonArray = JsonArray().apply {
        paths.worldInfoDir.mkdirs()
        paths.worldInfoDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()
            .forEach { add(it) }
    }

    fun getWorldInfo(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidWorldInfoResponse()
        val file = worldInfoFile(request.stringValue("name")) ?: return invalidWorldInfoResponse()
        if (!file.isFile) return HttpResponse.json(404, """{"error":"world_info_not_found"}""")
        val data = store.readJsonObject(file)
            ?: return HttpResponse.json(500, """{"error":"invalid_world_info_data"}""")
        return HttpResponse.json(200, gson.toJson(data))
    }

    @Synchronized
    fun editWorldInfo(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidWorldInfoResponse()
        val file = worldInfoFile(request.stringValue("name")) ?: return invalidWorldInfoResponse()
        val data = request.get("data")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: return invalidWorldInfoResponse()
        if (data.get("entries")?.isJsonObject != true) return invalidWorldInfoResponse()
        return try {
            store.writeText(file, gson.toJson(data))
            HttpResponse.json(200, "{}")
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"world_info_write_failed"}""")
        }
    }

    @Synchronized
    fun deleteWorldInfo(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidWorldInfoResponse()
        val file = worldInfoFile(request.stringValue("name")) ?: return invalidWorldInfoResponse()
        if (!file.isFile) return HttpResponse.json(404, """{"error":"world_info_not_found"}""")
        return if (file.delete()) {
            HttpResponse.json(200, "{}")
        } else {
            HttpResponse.json(500, """{"error":"world_info_delete_failed"}""")
        }
    }

    @Synchronized
    fun importWorldInfo(request: NativeRequest): HttpResponse {
        val upload = request.uploads["avatar"] ?: return invalidWorldInfoResponse()
        if (!upload.tempFile.isFile || upload.tempFile.length() > MAX_IMPORT_BYTES) {
            return invalidWorldInfoResponse()
        }
        val source = request.form["convertedData"]?.firstOrNull()?.takeIf(String::isNotBlank)
            ?: runCatching { upload.tempFile.readText() }.getOrNull()
            ?: return invalidWorldInfoResponse()
        val input = parseObject(source) ?: return invalidWorldInfoResponse()
        val data = normalizeImportedData(input) ?: return invalidWorldInfoResponse()
        val requestedName = upload.originalName.substringBeforeLast('.', upload.originalName)
        val baseName = normalizeImportedName(requestedName)
        val name = uniqueName(baseName)
        val file = worldInfoFile(name) ?: return invalidWorldInfoResponse()
        return try {
            store.writeText(file, gson.toJson(data))
            HttpResponse.json(200, gson.toJson(JsonObject().apply {
                addProperty("name", name)
                addProperty("entry_count", data.getAsJsonObject("entries").size())
            }))
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"world_info_import_failed"}""")
        }
    }

    @Synchronized
    internal fun importEmbeddedCharacterBook(
        book: JsonObject,
        fallbackName: String
    ): EmbeddedWorldInfoImportResult {
        val data = normalizeImportedData(book)
            ?: throw IllegalArgumentException("Invalid embedded character book")
        val baseName = normalizeImportedName(book.stringValue("name").ifBlank { fallbackName })
        val baseFile = worldInfoFile(baseName)
            ?: throw IllegalArgumentException("Invalid embedded character book name")
        val existing = if (baseFile.isFile) store.readJsonObject(baseFile) else null
        val created = existing != data
        val name = when {
            existing == data -> baseName
            baseFile.exists() -> uniqueName(baseName)
            else -> baseName
        }
        if (created) {
            val file = worldInfoFile(name)
                ?: throw IllegalArgumentException("Invalid embedded character book name")
            store.writeText(file, gson.toJson(data))
        }
        return EmbeddedWorldInfoImportResult(
            name = name,
            entryCount = data.getAsJsonObject("entries").size(),
            created = created
        )
    }

    @Synchronized
    internal fun rollbackEmbeddedCharacterBook(result: EmbeddedWorldInfoImportResult) {
        if (!result.created) return
        worldInfoFile(result.name)?.delete()
    }

    private fun normalizeImportedData(input: JsonObject): JsonObject? = when {
        input.get("entries")?.isJsonObject == true -> input.deepCopy()
        input.get("entries")?.isJsonArray == true -> convertCharacterBook(input)
        input.stringValue("spec") == "lorebook_v3" -> input.get("data")
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?.takeIf { it.get("entries")?.isJsonArray == true }
            ?.let(::convertCharacterBook)
        else -> null
    }

    private fun convertCharacterBook(book: JsonObject): JsonObject? {
        val output = book.deepCopy()
        val entries = JsonObject()
        book.getAsJsonArray("entries").forEachIndexed { index, element ->
            val source = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEachIndexed
            val uid = source.get("id")?.takeIf { it.isJsonPrimitive }?.deepCopy()
                ?: JsonParser.parseString(index.toString())
            if (entries.has(uid.asString)) return null
            val extensions = source.get("extensions")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
                ?: JsonObject()
            val entry = source.deepCopy().apply {
                defaultEntry().entrySet().forEach { (name, value) ->
                    if (!has(name)) add(name, value.deepCopy())
                }
                add("uid", uid.deepCopy())
                add("key", source.arrayValue("keys"))
                add("keysecondary", source.arrayValue("secondary_keys"))
                addProperty("comment", source.stringValue("comment"))
                addProperty("content", source.stringValue("content"))
                addProperty("constant", source.booleanValue("constant", false))
                addProperty("selective", source.booleanValue("selective", false))
                addProperty("order", source.numberValue("insertion_order", 100))
                addProperty(
                    "position",
                    extensions.numberValueOrNull("position")
                        ?: if (source.stringValue("position") == "before_char") 0 else 1
                )
                addProperty("excludeRecursion", extensions.booleanValue("exclude_recursion", false))
                addProperty("preventRecursion", extensions.booleanValue("prevent_recursion", false))
                addProperty("delayUntilRecursion", extensions.numberValue("delay_until_recursion", 0))
                addProperty("disable", !source.booleanValue("enabled", true))
                addProperty("addMemo", source.stringValue("comment").isNotBlank())
                addProperty("displayIndex", extensions.numberValue("display_index", index))
                addProperty("probability", extensions.numberValue("probability", 100))
                addProperty(
                    "useProbability",
                    extensions.booleanValue("useProbability", extensions.booleanValue("use_probability", true))
                )
                addProperty("depth", extensions.numberValue("depth", DEFAULT_DEPTH))
                addProperty("selectiveLogic", extensions.numberValue("selectiveLogic", 0))
                addProperty("outletName", extensions.stringValue("outlet_name"))
                addProperty("group", extensions.stringValue("group"))
                addProperty("groupOverride", extensions.booleanValue("group_override", false))
                addProperty("groupWeight", extensions.numberValue("group_weight", DEFAULT_GROUP_WEIGHT))
                copyNullableExtension(this, extensions, "scanDepth", "scan_depth")
                copyNullableExtension(this, extensions, "caseSensitive", "case_sensitive")
                copyNullableExtension(this, extensions, "matchWholeWords", "match_whole_words")
                copyNullableExtension(this, extensions, "useGroupScoring", "use_group_scoring")
                addProperty("automationId", extensions.stringValue("automation_id"))
                addProperty("role", extensions.numberValue("role", 0))
                addProperty("vectorized", extensions.booleanValue("vectorized", false))
                copyNullableExtension(this, extensions, "sticky", "sticky")
                copyNullableExtension(this, extensions, "cooldown", "cooldown")
                copyNullableExtension(this, extensions, "delay", "delay")
                addProperty(
                    "matchPersonaDescription",
                    extensions.booleanValue("match_persona_description", false)
                )
                addProperty(
                    "matchCharacterDescription",
                    extensions.booleanValue("match_character_description", false)
                )
                addProperty(
                    "matchCharacterPersonality",
                    extensions.booleanValue("match_character_personality", false)
                )
                addProperty(
                    "matchCharacterDepthPrompt",
                    extensions.booleanValue("match_character_depth_prompt", false)
                )
                addProperty("matchScenario", extensions.booleanValue("match_scenario", false))
                addProperty("matchCreatorNotes", extensions.booleanValue("match_creator_notes", false))
                add("extensions", extensions.deepCopy())
                add("triggers", extensions.arrayValue("triggers"))
                addProperty("ignoreBudget", extensions.booleanValue("ignore_budget", false))
            }
            entries.add(uid.asString, entry)
        }
        output.add("entries", entries)
        output.add("originalData", book.deepCopy())
        return output
    }

    private fun defaultEntry(): JsonObject = JsonObject().apply {
        add("key", JsonArray())
        add("keysecondary", JsonArray())
        addProperty("comment", "")
        addProperty("content", "")
        addProperty("constant", false)
        addProperty("vectorized", false)
        addProperty("selective", true)
        addProperty("selectiveLogic", 0)
        addProperty("addMemo", false)
        addProperty("order", 100)
        addProperty("position", 0)
        addProperty("disable", false)
        addProperty("ignoreBudget", false)
        addProperty("excludeRecursion", false)
        addProperty("preventRecursion", false)
        addProperty("matchPersonaDescription", false)
        addProperty("matchCharacterDescription", false)
        addProperty("matchCharacterPersonality", false)
        addProperty("matchCharacterDepthPrompt", false)
        addProperty("matchScenario", false)
        addProperty("matchCreatorNotes", false)
        addProperty("delayUntilRecursion", 0)
        addProperty("probability", 100)
        addProperty("useProbability", true)
        addProperty("depth", DEFAULT_DEPTH)
        addProperty("outletName", "")
        addProperty("group", "")
        addProperty("groupOverride", false)
        addProperty("groupWeight", DEFAULT_GROUP_WEIGHT)
        listOf("scanDepth", "caseSensitive", "matchWholeWords", "useGroupScoring", "sticky", "cooldown", "delay")
            .forEach { add(it, JsonNull.INSTANCE) }
        addProperty("automationId", "")
        addProperty("role", 0)
        add("triggers", JsonArray())
    }

    private fun copyNullableExtension(target: JsonObject, source: JsonObject, targetName: String, sourceName: String) {
        target.add(targetName, source.get(sourceName)?.deepCopy() ?: JsonNull.INSTANCE)
    }

    private fun worldInfoFile(name: String): File? {
        if (!isSafeName(name)) return null
        return runCatching { SafePath.child(paths.worldInfoDir, "$name.json") }.getOrNull()
    }

    private fun normalizeImportedName(value: String): String {
        val cleaned = value.trim()
            .replace(INVALID_FILE_NAME_CHARACTER, "-")
            .trim(' ', '.')
        return SafePath.fileName(cleaned, "World Info")
    }

    private fun uniqueName(baseName: String): String {
        if (worldInfoFile(baseName)?.exists() != true) return baseName
        var suffix = 1
        while (worldInfoFile("$baseName-$suffix")?.exists() == true) suffix++
        return "$baseName-$suffix"
    }

    private fun isSafeName(name: String): Boolean =
        name.isNotBlank() && name == name.trim() && name != "." && name != ".." &&
            !INVALID_FILE_NAME_CHARACTER.containsMatchIn(name) && name.none(Char::isISOControl) &&
            name.codePointCount(0, name.length) <= MAX_NAME_CODE_POINTS

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
    } catch (_: IllegalStateException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    private fun invalidWorldInfoResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_world_info"}""")

    private fun JsonObject.stringValue(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString.orEmpty()

    private fun JsonObject.booleanValue(name: String, fallback: Boolean): Boolean = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean ?: fallback

    private fun JsonObject.numberValue(name: String, fallback: Int): Int = numberValueOrNull(name) ?: fallback

    private fun JsonObject.numberValueOrNull(name: String): Int? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt

    private fun JsonObject.arrayValue(name: String): JsonArray = get(name)
        ?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray
        ?.deepCopy() ?: JsonArray()

    private companion object {
        const val MAX_IMPORT_BYTES = 16L * 1024L * 1024L
        const val MAX_NAME_CODE_POINTS = 120
        const val DEFAULT_DEPTH = 4
        const val DEFAULT_GROUP_WEIGHT = 100
        val INVALID_FILE_NAME_CHARACTER = Regex("[<>:\"/\\\\|?*]")
    }
}
