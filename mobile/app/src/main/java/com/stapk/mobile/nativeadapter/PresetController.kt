package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File

class PresetController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {

    fun savePreset(body: String): HttpResponse {
        val preset = parse(body) ?: return invalidPreset()
        val name = preset.string("name") ?: return invalidPreset()
        if (preset.string("apiId") != "openai") return invalidPreset()
        val rawPreset = preset.get("preset")?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return invalidPreset()
        val target = fileFor(name) ?: return invalidPreset()
        paths.presetsDir.mkdirs()
        store.writeText(target, rawPreset.toString())
        return HttpResponse.json(200, JsonObject().apply { addProperty("name", target.nameWithoutExtension) }.toString())
    }

    fun deletePreset(body: String): HttpResponse {
        val request = parse(body) ?: return invalidPreset()
        if (request.string("apiId") != "openai") return invalidPreset()
        val target = request.string("name")?.let(::fileFor) ?: return invalidPreset()
        target.delete()
        return HttpResponse.json(200, "{}")
    }

    fun restorePreset(body: String): HttpResponse {
        val request = parse(body) ?: return invalidPreset()
        val name = request.string("name") ?: return invalidPreset()
        if (request.string("apiId") != "openai") return invalidPreset()
        val source = SafePath.child(File(paths.webDir, "defaults/presets/openai"), "${SafePath.fileName(name)}.json")
        val preset = source.takeIf(File::isFile)?.let { runCatching { JsonParser.parseString(it.readText()).asJsonObject }.getOrNull() }
        return HttpResponse.json(200, JsonObject().apply {
            addProperty("isDefault", preset != null)
            add("preset", preset ?: JsonObject())
        }.toString())
    }

    fun openAiPresets(): JsonArray = entries().map { JsonPrimitive(it.second.toString()) }.toJsonArray()
    fun openAiPresetNames(): JsonArray = entries().map { JsonPrimitive(it.first) }.toJsonArray()

    private fun entries(): List<Pair<String, JsonObject>> {
        paths.presetsDir.mkdirs()
        return paths.presetsDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            .orEmpty().sortedBy(File::getName).mapNotNull { file ->
                store.readJsonObject(file)?.let { file.nameWithoutExtension to it }
            }
    }

    private fun fileFor(name: String): File? {
        if (name.isBlank() || name.contains("..")) return null
        return runCatching { SafePath.child(paths.presetsDir, "${SafePath.fileName(name)}.json") }.getOrNull()
    }
    private fun parse(body: String): JsonObject? = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun List<JsonElement>.toJsonArray(): JsonArray = JsonArray().also { array -> forEach(array::add) }
    private fun invalidPreset(): HttpResponse = HttpResponse.json(400, "{\"error\":\"invalid_preset\"}")
}
