package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

class SettingsController(
    private val paths: NativeAdapterPaths,
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir),
    private val diagnosticLogger: DiagnosticLogger = DiagnosticLogger(paths.logsDir)
) {
    private val gson = Gson()
    private val presets = PresetController(paths, store)
    private val themes = ThemeController(paths, store)
    private val uiState = UiStateController(paths, store)
    private val worldInfo = WorldInfoController(paths, store)

    fun getSettings(): HttpResponse {
        val settings = readOrCreateSettings()
        stripSecretFields(settings)
        enforceProviderMode(settings)
        writeSettings(settings)
        val envelope = JsonObject().apply {
            addProperty("settings", gson.toJson(settings))
            SETTINGS_ARRAY_FIELDS.forEach { add(it, JsonArray()) }
            add("world_names", worldInfo.names())
            add("openai_settings", presets.openAiPresets())
            add("openai_setting_names", presets.openAiPresetNames())
            add("themes", themes.themes())
            add("movingUIPresets", uiState.movingUiPresets())
            add("quickReplyPresets", uiState.quickReplyPresets())
            addProperty("enable_extensions", true)
            addProperty("enable_extensions_auto_update", false)
            addProperty("enable_accounts", false)
            add("request_compression", JsonObject().apply {
                addProperty("enabled", false)
                addProperty("minPayloadSize", 0)
                addProperty("maxPayloadSize", 0)
                addProperty("timeout", 0)
            })
        }
        return HttpResponse.json(200, gson.toJson(envelope))
    }

    fun saveSettings(body: String): HttpResponse {
        val input = try {
            JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
        } catch (_: IllegalStateException) {
            return invalidSettingsResponse()
        } catch (_: JsonParseException) {
            return invalidSettingsResponse()
        }

        stripSecretFields(input)
        val settings = readOrCreateSettings()
        stripSecretFields(settings)
        mergeInto(settings, input)
        enforceProviderMode(settings)
        stripSecretFields(settings)
        writeSettings(settings)
        return HttpResponse.json(200, """{"result":"ok"}""")
    }

    fun getCurrentUser(): HttpResponse {
        val settings = readOrCreateSettings()
        val name = settings.get("username")?.takeIf { it.isJsonPrimitive }?.asString
            ?.takeIf(String::isNotBlank) ?: "User"
        val requestedAvatar = settings.get("user_avatar")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val avatar = SafePath.fileName(requestedAvatar, "user-default.png")
        val encodedAvatar = URLEncoder.encode(avatar, StandardCharsets.UTF_8).replace("+", "%20")
        val profileAvatar = store.readJsonObject(paths.userProfileFile)
            ?.stringValue("avatar")
            ?.takeIf(::isValidAvatarDataUrl)
        val user = JsonObject().apply {
            addProperty("handle", "default-user")
            addProperty("name", name)
            addProperty("avatar", profileAvatar ?: "/thumbnail?type=persona&file=$encodedAvatar")
            addProperty("admin", true)
            addProperty("created", paths.settingsFile.lastModified().coerceAtLeast(1L))
            addProperty("password", false)
        }
        return HttpResponse.json(200, gson.toJson(user))
    }

    fun makeSnapshot(): HttpResponse {
        val settings = readOrCreateSettings()
        stripSecretFields(settings)
        enforceProviderMode(settings)
        paths.settingsBackupsDir.mkdirs()
        val name = "settings_${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.json"
        store.writeText(SafePath.child(paths.settingsBackupsDir, name), gson.toJson(settings))
        return HttpResponse.json(200, """{"name":"$name"}""")
    }

    fun getSnapshots(): HttpResponse {
        paths.settingsBackupsDir.mkdirs()
        val snapshots = JsonArray()
        paths.settingsBackupsDir.listFiles { file -> file.isFile && SNAPSHOT_NAME.matches(file.name) }
            .orEmpty().sortedBy(File::getName).forEach { file ->
                if (store.readJsonObject(file) != null) {
                    snapshots.add(JsonObject().apply {
                        addProperty("name", file.name)
                        addProperty("date", file.lastModified())
                        addProperty("size", file.length())
                    })
                }
            }
        return HttpResponse.json(200, gson.toJson(snapshots))
    }

    fun loadSnapshot(body: String): HttpResponse {
        val snapshot = snapshotFile(body)?.let(store::readJsonObject)
            ?: return HttpResponse.json(404, """{"error":"snapshot_not_found"}""")
        stripSecretFields(snapshot)
        enforceProviderMode(snapshot)
        return HttpResponse.json(200, gson.toJson(snapshot))
    }

    fun restoreSnapshot(body: String): HttpResponse {
        val snapshotFile = snapshotFile(body)
            ?: return HttpResponse.json(404, """{"error":"snapshot_not_found"}""")
        val snapshot = store.readJsonObject(snapshotFile)
            ?: return HttpResponse.json(404, """{"error":"snapshot_not_found"}""")
        stripSecretFields(snapshot)
        enforceProviderMode(snapshot)
        return runCatching {
            writeSettings(snapshot)
            HttpResponse.json(200, "{}")
        }.getOrElse {
            runCatching {
                diagnosticLogger.event(
                    DiagnosticArea.RESTORE,
                    "settings_restore_failed",
                    mapOf("file" to snapshotFile.name)
                )
            }
            HttpResponse.json(500, """{"error":"settings_restore_failed"}""")
        }
    }

    fun resetSettings(): HttpResponse {
        writeSettings(defaultSettings())
        return HttpResponse.json(200, "{}")
    }

    fun changeUserAvatar(body: String): HttpResponse {
        val request = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: return invalidSettingsResponse()
        if (request.stringValue("handle") != SINGLE_USER_HANDLE) return invalidSettingsResponse()
        val avatar = request.stringValue("avatar") ?: return invalidSettingsResponse()
        if (avatar.isNotEmpty() && !isValidAvatarDataUrl(avatar)) return invalidSettingsResponse()
        paths.userConfigDir.mkdirs()
        val profile = store.readJsonObject(paths.userProfileFile) ?: JsonObject()
        if (avatar.isEmpty()) profile.remove("avatar") else profile.addProperty("avatar", avatar)
        store.writeText(paths.userProfileFile, gson.toJson(profile))
        return HttpResponse.json(200, "{}")
    }

    private fun readOrCreateSettings(): JsonObject {
        paths.userConfigDir.mkdirs()
        if (!paths.settingsFile.exists()) {
            val defaults = defaultSettings()
            writeSettings(defaults)
            return defaults
        }
        return store.readJsonObject(paths.settingsFile) ?: defaultSettings().also(::writeSettings)
    }

    private fun defaultSettings(): JsonObject = JsonObject().apply {
        addProperty("firstRun", false)
        addProperty("username", "")
        addProperty("user_avatar", "user-default.png")
        addProperty("amount_gen", 350)
        addProperty("max_context", 8192)
        addProperty("main_api", "openai")
        addProperty("swipes", true)
        add("oai_settings", JsonObject().apply {
            addProperty("chat_completion_source", "openai")
            addProperty("stream_openai", false)
            addProperty("openai_model", "gpt-4o-mini")
            addProperty("custom_model", "")
            addProperty("reverse_proxy", "")
        })
    }

    private fun mergeInto(target: JsonObject, input: JsonObject) {
        input.entrySet().forEach { (key, value) ->
            val existing = target.get(key)
            if (existing?.isJsonObject == true && value.isJsonObject) {
                mergeInto(existing.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value.deepCopy())
            }
        }
    }

    private fun enforceProviderMode(settings: JsonObject) {
        settings.addProperty("main_api", "openai")
        val openAi = settings.get("oai_settings")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: defaultSettings().getAsJsonObject("oai_settings").also {
                settings.add("oai_settings", it)
            }
        val source = openAi.stringValue("chat_completion_source")
            ?.takeIf { it in SUPPORTED_SOURCES }
            ?: "openai"
        openAi.addProperty("chat_completion_source", source)
        openAi.addProperty("stream_openai", false)
    }

    private fun stripSecretFields(element: JsonElement) {
        when {
            element.isJsonObject -> {
                val iterator = element.asJsonObject.entrySet().iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key.startsWith("api_key_") || entry.key in LOCAL_SECRET_FIELDS) {
                        iterator.remove()
                    } else {
                        stripSecretFields(entry.value)
                    }
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach(::stripSecretFields)
        }
    }

    private fun JsonObject.stringValue(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun isValidAvatarDataUrl(value: String): Boolean {
        val match = USER_AVATAR_DATA_URL.matchEntire(value) ?: return false
        return runCatching { Base64.getDecoder().decode(match.groupValues[1]) }.isSuccess
    }

    private fun invalidSettingsResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_settings"}""")

    private fun writeSettings(settings: JsonObject) {
        paths.userConfigDir.mkdirs()
        store.writeText(paths.settingsFile, gson.toJson(settings))
    }

    private fun snapshotFile(body: String): File? {
        val name = runCatching { JsonParser.parseString(body).asJsonObject.get("name")?.asString }.getOrNull() ?: return null
        if (!SNAPSHOT_NAME.matches(name)) return null
        return SafePath.child(paths.settingsBackupsDir, name)
    }

    private companion object {
        val SUPPORTED_SOURCES = setOf("openai", "custom")
        val LOCAL_SECRET_FIELDS = setOf("proxy_password", "custom_include_headers")
        val SNAPSHOT_NAME = Regex("settings_\\d{8}-\\d{6}-\\d{3}\\.json")
        const val SINGLE_USER_HANDLE = "default-user"
        val USER_AVATAR_DATA_URL = Regex("^data:image/[A-Za-z0-9.+-]+;base64,([A-Za-z0-9+/]+={0,2})$")
        val SETTINGS_ARRAY_FIELDS = listOf(
            "koboldai_settings",
            "koboldai_setting_names",
            "world_names",
            "novelai_settings",
            "novelai_setting_names",
            "openai_settings",
            "openai_setting_names",
            "textgenerationwebui_presets",
            "textgenerationwebui_preset_names",
            "themes",
            "movingUIPresets",
            "quickReplyPresets",
            "instruct",
            "context",
            "sysprompt",
            "reasoning"
        )
    }
}
