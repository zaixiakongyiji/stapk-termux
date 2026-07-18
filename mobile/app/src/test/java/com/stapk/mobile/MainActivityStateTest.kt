package com.stapk.mobile

import com.stapk.mobile.nativeadapter.NativeAdapterState
import com.stapk.mobile.nativeadapter.NativeAdapterStatus
import com.stapk.mobile.nativeadapter.ExportTicket
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStateTest {
    @Test
    fun `running adapter maps to loopback Web screen`() {
        val model = toMainUiModel(
            NativeAdapterState(NativeAdapterStatus.RUNNING, port = 43123)
        )

        assertEquals(MainScreen.WEB, model.screen)
        assertEquals("http://127.0.0.1:43123/", model.url)
        assertNull(model.message)
    }

    @Test
    fun `failed adapter maps to visible error`() {
        val model = toMainUiModel(
            NativeAdapterState(NativeAdapterStatus.FAILED, message = "asset install failed")
        )

        assertEquals(MainScreen.ERROR, model.screen)
        assertEquals("asset install failed", model.message)
        assertNull(model.url)
    }

    @Test
    fun `starting adapter remains on loading screen`() {
        val model = toMainUiModel(NativeAdapterState(NativeAdapterStatus.STARTING))

        assertEquals(MainScreen.LOADING, model.screen)
        assertNull(model.url)
        assertNull(model.message)
    }

    @Test
    fun `only external HTTPS navigation leaves WebView`() {
        assertTrue(shouldOpenExternally("https://docs.sillytavern.app/usage/"))
        assertFalse(shouldOpenExternally("http://127.0.0.1:43123/characters"))
        assertFalse(shouldOpenExternally("/api/settings/get"))
        assertFalse(shouldOpenExternally("javascript:void(0)"))
    }

    @Test
    fun `only loopback HTTP documents may remain in the WebView`() {
        assertFalse(isAllowedLoopbackUrl("http://127.0.0.1:43123/"))
        assertFalse(isAllowedLoopbackUrl("http://localhost:43123/characters", 43123))
        assertTrue(isAllowedLoopbackUrl("http://127.0.0.1:43123/", 43123))
        assertFalse(isAllowedLoopbackUrl("http://127.0.0.1:43124/", 43123))
        assertFalse(isAllowedLoopbackUrl("https://127.0.0.1:43123/"))
        assertFalse(isAllowedLoopbackUrl("http://example.com/"))
        assertTrue(shouldBlockMainFrameNavigation("http://example.com/"))
        assertTrue(shouldBlockMainFrameNavigation("https://example.com/"))
        assertTrue(shouldBlockMainFrameNavigation("http://127.0.0.1:43123/"))
        assertTrue(shouldBlockMainFrameNavigation("http://127.0.0.1:43124/", 43123))
        assertFalse(shouldBlockMainFrameNavigation("http://127.0.0.1:43123/", 43123))
        assertFalse(shouldBlockMainFrameNavigation("/api/settings/get"))
    }

    @Test
    fun `pending SAF write waits for service and is consumed once after reconnect`() {
        val queue = PendingSafWriteQueue<String>()
        queue.enqueue("content://downloads/export.json")

        assertNull(queue.takeIfReady(serviceAvailable = false))
        assertEquals("content://downloads/export.json", queue.takeIfReady(serviceAvailable = true))
        assertNull(queue.takeIfReady(serviceAvailable = true))
    }

    @Test
    fun `pending SAF request must match ticket metadata`() {
        val file = Files.createTempFile("stapk-export-ticket", ".json").toFile()
        val ticket = ExportTicket(
            token = "T".repeat(43),
            file = file,
            fileName = "角色卡.json",
            mimeType = "application/json",
            expiresAt = Long.MAX_VALUE
        )

        assertTrue(matchesExportTicket(PendingSafExport(ticket.token, ticket.fileName, ticket.mimeType), ticket))
        assertFalse(matchesExportTicket(PendingSafExport(ticket.token, "伪造.json", ticket.mimeType), ticket))
        assertFalse(matchesExportTicket(PendingSafExport(ticket.token, ticket.fileName, "text/plain"), ticket))
        assertFalse(matchesExportTicket(PendingSafExport("A".repeat(43), ticket.fileName, ticket.mimeType), ticket))
    }

    @Test
    fun `jsonl chooser accepts Android generic binary MIME`() {
        assertArrayEquals(
            arrayOf("application/json", "application/x-ndjson", "application/octet-stream"),
            expandedFileChooserMimeTypes(arrayOf(".json, .jsonl"))
        )
        assertNull(expandedFileChooserMimeTypes(arrayOf("image/png")))
    }
}
