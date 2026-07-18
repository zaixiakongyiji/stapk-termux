package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class UiStateController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {

    fun saveQuickReplies(body: String): HttpResponse = save(body, paths.quickRepliesDir, "invalid_quick_replies")
    fun deleteQuickReplies(body: String): HttpResponse = delete(body, paths.quickRepliesDir, "invalid_quick_replies")
    fun saveMovingUi(body: String): HttpResponse = save(body, paths.movingUiDir, "invalid_moving_ui")
    fun quickReplyPresets(): JsonArray = readAll(paths.quickRepliesDir)
    fun movingUiPresets(): JsonArray = readAll(paths.movingUiDir)

    private fun save(body: String, directory: File, error: String): HttpResponse {
        val item = parse(body) ?: return invalid(error)
        val target = item.name()?.let { fileFor(directory, it) } ?: return invalid(error)
        directory.mkdirs()
        store.writeText(target, item.toString())
        return HttpResponse.json(200, "{}")
    }
    private fun delete(body: String, directory: File, error: String): HttpResponse {
        val item = parse(body) ?: return invalid(error)
        val target = item.name()?.let { fileFor(directory, it) } ?: return invalid(error)
        target.delete()
        return HttpResponse.json(200, "{}")
    }
    private fun readAll(directory: File): JsonArray = JsonArray().also { result ->
        directory.mkdirs()
        directory.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            .orEmpty().sortedBy(File::getName).mapNotNull(store::readJsonObject).forEach(result::add)
    }
    private fun fileFor(directory: File, name: String): File? {
        if (name.isBlank() || name.contains("..")) return null
        return runCatching { SafePath.child(directory, "${SafePath.fileName(name)}.json") }.getOrNull()
    }
    private fun parse(body: String): JsonObject? = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    private fun JsonObject.name(): String? = get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun invalid(error: String): HttpResponse = HttpResponse.json(400, "{\"error\":\"$error\"}")
}
