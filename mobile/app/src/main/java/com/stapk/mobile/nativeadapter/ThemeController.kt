package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

class ThemeController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir)
) {

    fun saveTheme(body: String): HttpResponse {
        val theme = parse(body) ?: return invalidTheme()
        val target = fileFor(theme.name() ?: return invalidTheme()) ?: return invalidTheme()
        paths.themesDir.mkdirs()
        store.writeText(target, theme.toString())
        return HttpResponse.json(200, "{}")
    }

    fun deleteTheme(body: String): HttpResponse {
        val target = parse(body)?.name()?.let(::fileFor) ?: return invalidTheme()
        target.delete()
        return HttpResponse.json(200, "{}")
    }

    fun themes(): JsonArray = JsonArray().also { result ->
        paths.themesDir.mkdirs()
        paths.themesDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            .orEmpty().sortedBy(File::getName).mapNotNull(store::readJsonObject).forEach(result::add)
    }

    private fun fileFor(name: String): File? {
        if (name.isBlank() || name.contains("..")) return null
        return runCatching { SafePath.child(paths.themesDir, "${SafePath.fileName(name)}.json") }.getOrNull()
    }
    private fun parse(body: String): JsonObject? = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    private fun JsonObject.name(): String? = get("name")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun invalidTheme(): HttpResponse = HttpResponse.json(400, "{\"error\":\"invalid_theme\"}")
}
