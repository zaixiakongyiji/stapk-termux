package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PresetControllerTest {
    @Test
    fun `saves openai preset with unknown fields and exposes it in settings`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-presets").toFile())
        val controller = PresetController(paths)

        val response = controller.savePreset("""{"name":"My / preset","apiId":"openai","preset":{"temperature":0.7,"unknown":true}}""")

        assertEquals(200, response.statusCode)
        assertEquals("My  preset", JsonParser.parseString(response.bodyText!!).asJsonObject.get("name").asString)
        val saved = JsonParser.parseString(File(paths.presetsDir, "My  preset.json").readText()).asJsonObject
        assertTrue(saved.get("unknown").asBoolean)
        assertEquals(listOf("My  preset"), controller.openAiPresetNames().map { it.asString })
        assertEquals(listOf(saved.toString()), controller.openAiPresets().map { it.asString })
    }

    @Test
    fun `rejects preset for unsupported API and does not persist it`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-presets").toFile())
        val response = PresetController(paths).savePreset("""{"name":"bad","apiId":"kobold","preset":{}}""")

        assertEquals(400, response.statusCode)
        assertEquals(400, PresetController(paths).savePreset("""{"name":"bad","apiId":"openai","preset":[]}""").statusCode)
        assertFalse(paths.presetsDir.exists())
    }

    @Test
    fun `delete removes a preset immediately and restore reads transformed defaults without overwriting custom`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-presets").toFile())
        val controller = PresetController(paths)
        controller.savePreset("""{"name":"local","apiId":"openai","preset":{"x":1}}""")

        assertEquals(400, controller.deletePreset("""{"name":"local","apiId":"kobold"}""").statusCode)
        assertEquals(200, controller.deletePreset("""{"name":"local","apiId":"openai"}""").statusCode)
        assertTrue(controller.openAiPresetNames().isEmpty)
        assertEquals("false", JsonParser.parseString(controller.restorePreset("""{"name":"missing","apiId":"openai"}""").bodyText!!).asJsonObject.get("isDefault").asString)

        controller.savePreset("""{"name":"starter","apiId":"openai","preset":{"x":1}}""")
        val defaults = File(paths.webDir, "defaults/presets/openai").apply { mkdirs() }
        File(defaults, "starter.json").writeText("""{"x":2,"unknown":true}""")
        val restored = JsonParser.parseString(controller.restorePreset("""{"name":"starter","apiId":"openai"}""").bodyText!!).asJsonObject
        assertTrue(restored.get("isDefault").asBoolean)
        assertEquals(2, restored.getAsJsonObject("preset").get("x").asInt)
        assertEquals(1, JsonParser.parseString(File(paths.presetsDir, "starter.json").readText()).asJsonObject.get("x").asInt)
        assertEquals(400, controller.restorePreset("""{"name":"starter","apiId":"kobold"}""").statusCode)
    }
}
