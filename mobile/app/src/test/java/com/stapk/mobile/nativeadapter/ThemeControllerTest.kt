package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThemeControllerTest {
    @Test
    fun `theme keeps arbitrary unknown JSON properties and overwrites atomically by name`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-themes").toFile())
        val controller = ThemeController(paths)

        controller.saveTheme("""{"name":"Dark/Blue","accent":"#123","nested":{"unknown":true}}""")
        controller.saveTheme("""{"name":"Dark/Blue","accent":"#456","nested":{"unknown":false}}""")

        val theme = JsonParser.parseString(File(paths.themesDir, "DarkBlue.json").readText()).asJsonObject
        assertEquals("#456", theme.get("accent").asString)
        assertTrue(theme.getAsJsonObject("nested").has("unknown"))
        assertEquals(1, controller.themes().size())
    }

    @Test
    fun `delete makes a theme disappear immediately`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-themes").toFile())
        val controller = ThemeController(paths)
        controller.saveTheme("""{"name":"Light","value":1}""")

        val response = controller.deleteTheme("""{"name":"Light"}""")

        assertEquals(200, response.statusCode)
        assertTrue(controller.themes().isEmpty)
    }
}
