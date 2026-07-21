package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

class SettingsControllerTest {
    @Test
    fun `default settings keep streaming disabled`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        val controller = SettingsController(paths)

        val response = controller.getSettings()

        assertEquals(200, response.statusCode)
        val envelope = JsonParser.parseString(response.bodyText!!).asJsonObject
        val settings = currentSettings(response)
        assertEquals("openai", settings.get("main_api").asString)
        assertEquals("openai", settings.getAsJsonObject("oai_settings").get("chat_completion_source").asString)
        assertFalse(settings.getAsJsonObject("oai_settings").get("stream_openai").asBoolean)
        assertTrue(envelope.getAsJsonArray("openai_settings").isEmpty)
        assertTrue(envelope.getAsJsonArray("openai_setting_names").isEmpty)
        assertTrue(envelope.get("enable_extensions").asBoolean)
        assertFalse(envelope.get("enable_accounts").asBoolean)
        assertFalse(envelope.getAsJsonObject("request_compression").get("enabled").asBoolean)
        assertTrue(paths.settingsFile.isFile)
    }

    @Test
    fun `saved OpenAI streaming choice is preserved`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings-streaming").toFile())
        val controller = SettingsController(paths)

        val response = controller.saveSettings(
            """{"main_api":"openai","oai_settings":{"chat_completion_source":"custom","stream_openai":true}}"""
        )

        assertEquals(200, response.statusCode)
        val settings = currentSettings(controller.getSettings())
        assertTrue(settings.getAsJsonObject("oai_settings").get("stream_openai").asBoolean)
    }

    @Test
    fun `current user exposes the fixed single user profile required by snapshots UI`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings-user").toFile())
        paths.personasDir.mkdirs()
        paths.personasDir.resolve("alice.png").writeBytes(byteArrayOf(1, 2, 3))
        val controller = SettingsController(paths)
        controller.saveSettings(
            """{"username":"Alice","user_avatar":"alice.png","api_key_openai":"must-not-leak"}"""
        )

        val user = JsonParser.parseString(controller.getCurrentUser().bodyText!!).asJsonObject

        assertEquals("default-user", user.get("handle").asString)
        assertEquals("Alice", user.get("name").asString)
        assertEquals("/thumbnail?type=persona&file=alice.png", user.get("avatar").asString)
        assertTrue(user.get("admin").asBoolean)
        assertFalse(user.get("password").asBoolean)
        assertTrue(user.get("created").asLong > 0L)
        assertFalse(user.toString().contains("must-not-leak"))
    }

    @Test
    fun `settings returns sorted world names from native lorebook storage`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings-worlds").toFile())
        paths.worldInfoDir.mkdirs()
        paths.worldInfoDir.resolve("Zeta.json").writeText("""{"entries":{}}""")
        paths.worldInfoDir.resolve("Alpha.json").writeText("""{"entries":{}}""")
        paths.worldInfoDir.resolve("ignored.txt").writeText("ignored")

        val envelope = JsonParser.parseString(SettingsController(paths).getSettings().bodyText).asJsonObject

        assertEquals(
            listOf("Alpha", "Zeta"),
            envelope.getAsJsonArray("world_names").map { it.asString }
        )
    }

    @Test
    fun `save preserves custom provider mode while rejecting unsupported sources`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        val controller = SettingsController(paths)
        controller.getSettings()

        val response = controller.saveSettings(
            """{
                "username":"Alice",
                "amount_gen":512,
                "main_api":"kobold",
                "oai_settings":{
                    "chat_completion_source":"custom",
                    "stream_openai":true,
                    "reverse_proxy":"https://example.test/v1"
                }
            }""".trimIndent()
        )

        assertEquals(200, response.statusCode)
        assertEquals("ok", JsonParser.parseString(response.bodyText!!).asJsonObject.get("result").asString)
        val envelope = JsonParser.parseString(controller.getSettings().bodyText!!).asJsonObject
        val settings = JsonParser.parseString(envelope.get("settings").asString).asJsonObject
        val openAi = settings.getAsJsonObject("oai_settings")
        assertEquals("Alice", settings.get("username").asString)
        assertEquals(512, settings.get("amount_gen").asInt)
        assertEquals("openai", settings.get("main_api").asString)
        assertEquals("custom", openAi.get("chat_completion_source").asString)
        assertTrue(openAi.get("stream_openai").asBoolean)
        assertEquals("gpt-4o-mini", openAi.get("openai_model").asString)
        assertEquals("https://example.test/v1", openAi.get("reverse_proxy").asString)

        controller.saveSettings("""{"oai_settings":{"chat_completion_source":"kobold"}}""")
        val unsupported = JsonParser.parseString(controller.getSettings().bodyText!!).asJsonObject
        val unsupportedSettings = JsonParser.parseString(unsupported.get("settings").asString).asJsonObject
        assertEquals(
            "openai",
            unsupportedSettings.getAsJsonObject("oai_settings").get("chat_completion_source").asString
        )
    }

    @Test
    fun `settings route preserves Chinese Regex data without an explicit charset`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings-unicode").toFile())
        val server = NativeHttpServer(paths)
        val scriptName = "中文清理规则"
        val replacement = "保留中文正文"

        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val save = postJson(
                server,
                "/api/settings/save",
                """{"extension_settings":{"regex":[{"scriptName":"$scriptName","replaceString":"$replacement"}]}}"""
            )
            assertEquals(200, save.first)

            val envelope = JsonParser.parseString(postJson(server, "/api/settings/get", "{}").second).asJsonObject
            val settings = JsonParser.parseString(envelope.get("settings").asString).asJsonObject
            val regex = settings.getAsJsonObject("extension_settings").getAsJsonArray("regex").get(0).asJsonObject
            assertEquals(scriptName, regex.get("scriptName").asString)
            assertEquals(replacement, regex.get("replaceString").asString)
            assertFalse(paths.settingsFile.readText().contains('\uFFFD'))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `invalid save returns bad request without corrupting settings`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        val controller = SettingsController(paths)
        controller.getSettings()
        val before = paths.settingsFile.readText()

        val response = controller.saveSettings("{invalid")

        assertEquals(400, response.statusCode)
        assertEquals("invalid_settings", JsonParser.parseString(response.bodyText!!).asJsonObject.get("error").asString)
        assertEquals(before, paths.settingsFile.readText())
    }

    @Test
    fun `settings strips secret fields from input persisted data and existing files`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        paths.userConfigDir.mkdirs()
        paths.settingsFile.writeText(
            """{
                "api_key_openai":"old-openai",
                "api_key_vendor":"old-vendor",
                "oai_settings":{
                    "api_key_custom":"old-custom",
                    "proxy_password":"old-proxy",
                    "custom_include_headers":"old-headers"
                }
            }""".trimIndent()
        )
        val controller = SettingsController(paths)

        val saved = controller.saveSettings(
            """{
                "api_key_openai":"new-openai",
                "api_key_any_provider":"new-provider",
                "proxy_password":"new-proxy",
                "oai_settings":{
                    "api_key_custom":"new-custom",
                    "custom_include_headers":"new-headers"
                }
            }""".trimIndent()
        )
        val response = controller.getSettings()
        val persisted = paths.settingsFile.readText()

        assertEquals(200, saved.statusCode)
        listOf(
            "api_key_openai", "api_key_custom", "api_key_vendor", "api_key_any_provider",
            "proxy_password", "custom_include_headers", "old-openai", "old-custom", "old-vendor",
            "old-proxy", "old-headers", "new-openai", "new-custom", "new-provider", "new-proxy", "new-headers"
        ).forEach { secret ->
            assertFalse(persisted.contains(secret))
            assertFalse(response.bodyText!!.contains(secret))
        }
    }

    @Test
    fun `get normalizes legacy provider source to supported values`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        paths.userConfigDir.mkdirs()
        paths.settingsFile.writeText(
            """{"main_api":"kobold","oai_settings":{"chat_completion_source":"unsupported"}}"""
        )
        val controller = SettingsController(paths)

        val envelope = JsonParser.parseString(controller.getSettings().bodyText!!).asJsonObject
        val settings = JsonParser.parseString(envelope.get("settings").asString).asJsonObject

        assertEquals("openai", settings.get("main_api").asString)
        assertEquals(
            "openai",
            settings.getAsJsonObject("oai_settings").get("chat_completion_source").asString
        )
    }

    @Test
    fun `save deep copies unknown Persona fields without mutating the request shape`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        val controller = SettingsController(paths)
        val request = """{
            "persona_description":"A persona",
            "persona_positions":{"Alice":2},
            "persona_lorebook":"Alice World",
            "persona_unknown":{"nested":[1,2,3]}
        }""".trimIndent()

        assertEquals(200, controller.saveSettings(request).statusCode)

        val settings = JsonParser.parseString(controller.getSettings().bodyText!!).asJsonObject
            .get("settings").asString.let { JsonParser.parseString(it).asJsonObject }
        assertEquals("A persona", settings.get("persona_description").asString)
        assertEquals(2, settings.getAsJsonObject("persona_positions").get("Alice").asInt)
        assertEquals("Alice World", settings.get("persona_lorebook").asString)
        assertEquals(3, settings.getAsJsonObject("persona_unknown").getAsJsonArray("nested").size())
    }

    @Test
    fun `change avatar validates the single user and persists profile data without changing Persona settings`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        paths.userConfigDir.mkdirs()
        paths.providerConfigFile.writeText("{\"provider\":\"custom\"}")
        paths.secretsDir.mkdirs()
        val secret = java.io.File(paths.secretsDir, "openai.json").apply { writeText("secret") }
        paths.userProfileFile.writeText("""{"display_name":"Preserved","avatar":"old"}""")
        val controller = SettingsController(paths)
        controller.saveSettings("""{"username":"Alice"}""")

        assertEquals(400, controller.changeUserAvatar("""{"avatar":"data:image/png;base64,AAA=","handle":"other"}""").statusCode)
        assertEquals(200, controller.changeUserAvatar("""{"avatar":"data:image/png;base64,AAA=","handle":"default-user"}""").statusCode)
        val changed = JsonParser.parseString(controller.getSettings().bodyText!!).asJsonObject
            .get("settings").asString.let { JsonParser.parseString(it).asJsonObject }
        assertEquals("user-default.png", changed.get("user_avatar").asString)
        assertEquals(
            "data:image/png;base64,AAA=",
            JsonParser.parseString(paths.userProfileFile.readText()).asJsonObject.get("avatar").asString
        )
        assertEquals(
            "data:image/png;base64,AAA=",
            JsonParser.parseString(controller.getCurrentUser().bodyText!!).asJsonObject.get("avatar").asString
        )
        assertEquals(
            "Preserved",
            JsonParser.parseString(paths.userProfileFile.readText()).asJsonObject.get("display_name").asString
        )
        paths.userProfileFile.writeText("not json")
        assertEquals(200, controller.changeUserAvatar("""{"avatar":"","handle":"default-user"}""").statusCode)
        assertFalse(JsonParser.parseString(paths.userProfileFile.readText()).asJsonObject.has("avatar"))
        assertEquals(
            "/thumbnail?type=persona&file=user-default.png",
            JsonParser.parseString(controller.getCurrentUser().bodyText!!).asJsonObject.get("avatar").asString
        )
        assertTrue(paths.quarantineDir.walkTopDown().any { it.name == "user-profile.json" })
        assertEquals(200, controller.resetSettings().statusCode)
        assertEquals("", JsonParser.parseString(controller.getSettings().bodyText!!).asJsonObject
            .get("settings").asString.let { JsonParser.parseString(it).asJsonObject }.get("username").asString)
        assertEquals("{\"provider\":\"custom\"}", paths.providerConfigFile.readText())
        assertTrue(secret.isFile)
    }

    @Test
    fun `corrupt settings are quarantined and snapshots never restore secrets`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        paths.userConfigDir.mkdirs()
        paths.settingsFile.writeText("not json")
        val controller = SettingsController(paths)

        assertEquals("", JsonParser.parseString(controller.getSettings().bodyText!!).asJsonObject
            .get("settings").asString.let { JsonParser.parseString(it).asJsonObject }.get("username").asString)
        assertTrue(paths.quarantineDir.walkTopDown().any { it.name == "settings.json" })

        controller.saveSettings("""{"username":"safe","api_key_openai":"secret","nested":{"proxy_password":"bad"}}""")
        val snapshotName = JsonParser.parseString(controller.makeSnapshot().bodyText!!).asJsonObject.get("name").asString
        paths.settingsFile.writeText("""{"api_key_openai":"old","oai_settings":{"custom_include_headers":"bad"}}""")
        assertFalse(controller.loadSnapshot("""{"name":"$snapshotName"}""").bodyText!!.contains("secret"))
        controller.restoreSnapshot("""{"name":"$snapshotName"}""")
        assertFalse(paths.settingsFile.readText().contains("api_key_"))
        assertFalse(paths.settingsFile.readText().contains("custom_include_headers"))
    }

    @Test
    fun `snapshot restore write failure preserves settings and records safe RESTORE diagnostic`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings-restore-failure").toFile())
        paths.userConfigDir.mkdirs()
        paths.settingsBackupsDir.mkdirs()
        paths.settingsFile.writeText("""{"username":"before"}""")
        val snapshotName = "settings_20260716-120000-000.json"
        paths.settingsBackupsDir.resolve(snapshotName).writeText("""{"username":"after","api_key_openai":"secret"}""")
        val store = AtomicFileStore.forTesting(paths.quarantineDir) { _, _ ->
            throw IOException("simulated restore write failure")
        }
        val logger = DiagnosticLogger(paths.logsDir, clock = { 99L })
        val controller = SettingsController(paths, store, logger)

        val response = controller.restoreSnapshot("""{"name":"$snapshotName"}""")

        assertEquals(500, response.statusCode)
        assertEquals("""{"username":"before"}""", paths.settingsFile.readText())
        val diagnostic = paths.logsDir.resolve("diagnostics.jsonl").readText()
        assertTrue(diagnostic.contains("\"area\":\"RESTORE\""))
        assertTrue(diagnostic.contains("\"code\":\"settings_restore_failed\""))
        assertTrue(diagnostic.contains(snapshotName))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("simulated restore write failure"))
    }

    private fun postJson(server: NativeHttpServer, path: String, body: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val response = (if (status >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to response
    }

    private fun currentSettings(response: HttpResponse) = JsonParser.parseString(response.bodyText!!).asJsonObject
        .get("settings").asString.let { JsonParser.parseString(it).asJsonObject }
}
