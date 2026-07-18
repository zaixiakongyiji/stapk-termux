package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UiStateControllerTest {
    @Test
    fun `quick replies and moving UI persist complete named objects`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-ui-state").toFile())
        val controller = UiStateController(paths)

        assertEquals(200, controller.saveQuickReplies("""{"name":"Main/Set","qrList":[{"label":"Hi"}],"unknown":true}""").statusCode)
        assertEquals(200, controller.saveMovingUi("""{"name":"Phone","layout":{"left":1},"unknown":true}""").statusCode)

        assertTrue(JsonParser.parseString(File(paths.quickRepliesDir, "MainSet.json").readText()).asJsonObject.get("unknown").asBoolean)
        assertTrue(JsonParser.parseString(File(paths.movingUiDir, "Phone.json").readText()).asJsonObject.get("unknown").asBoolean)
        assertEquals(1, controller.quickReplyPresets().size())
        assertEquals(1, controller.movingUiPresets().size())
        assertEquals(200, controller.deleteQuickReplies("""{"name":"Main/Set"}""").statusCode)
        assertTrue(controller.quickReplyPresets().isEmpty)
    }

    @Test
    fun `snapshots load without applying restore atomically and quarantine corrupt files`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-snapshots").toFile())
        val settings = SettingsController(paths)
        settings.saveSettings("""{"username":"before"}""")

        val made = settings.makeSnapshot()
        val snapshotName = JsonParser.parseString(made.bodyText!!).asJsonObject.get("name").asString
        settings.saveSettings("""{"username":"after"}""")

        assertEquals("before", JsonParser.parseString(settings.loadSnapshot("""{"name":"$snapshotName"}""").bodyText!!).asJsonObject.get("username").asString)
        assertEquals("after", currentUsername(settings))
        assertEquals(200, settings.restoreSnapshot("""{"name":"$snapshotName"}""").statusCode)
        assertEquals("before", currentUsername(settings))

        File(paths.settingsBackupsDir, "settings_20260713-120000-000.json").writeText("not json")
        val snapshots = JsonParser.parseString(settings.getSnapshots().bodyText!!).asJsonArray
        assertEquals(1, snapshots.size())
        assertEquals(
            File(paths.settingsBackupsDir, snapshotName).length(),
            snapshots[0].asJsonObject.get("size").asLong
        )
        assertTrue(paths.quarantineDir.walkTopDown().any { it.name == "settings_20260713-120000-000.json" })
    }

    private fun currentUsername(settings: SettingsController): String {
        val envelope = JsonParser.parseString(settings.getSettings().bodyText!!).asJsonObject
        return JsonParser.parseString(envelope.get("settings").asString).asJsonObject.get("username").asString
    }
}
