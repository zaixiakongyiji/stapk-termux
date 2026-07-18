package com.stapk.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StapkFileBridgeTest {
    private val nonce = "N".repeat(43)
    private val token = "T".repeat(43)

    @Test
    fun `valid bridge request is forwarded once`() {
        val requests = mutableListOf<PendingSafExport>()
        val bridge = StapkFileBridge(nonce) { exportToken, fileName, mimeType ->
            requests += PendingSafExport(exportToken, fileName, mimeType)
        }

        bridge.saveExport(nonce, token, "角色卡.json", "application/json")

        assertEquals(
            listOf(PendingSafExport(token, "角色卡.json", "application/json")),
            requests
        )
    }

    @Test
    fun `wrong nonce and malformed token are rejected`() {
        var calls = 0
        val bridge = StapkFileBridge(nonce) { _, _, _ -> calls++ }

        bridge.saveExport("wrong", token, "chat.jsonl", "application/x-ndjson")
        bridge.saveExport(nonce, "", "chat.jsonl", "application/x-ndjson")
        bridge.saveExport(nonce, "A".repeat(42), "chat.jsonl", "application/x-ndjson")
        bridge.saveExport(nonce, "+" + "A".repeat(42), "chat.jsonl", "application/x-ndjson")
        bridge.saveExport(null, token, "chat.jsonl", "application/x-ndjson")
        bridge.saveExport(nonce, null, "chat.jsonl", "application/x-ndjson")
        bridge.saveExport(nonce, token, null, "application/x-ndjson")
        bridge.saveExport(nonce, token, "chat.jsonl", null)

        assertEquals(0, calls)
    }

    @Test
    fun `unsafe file names and MIME values are rejected`() {
        var calls = 0
        val bridge = StapkFileBridge(nonce) { _, _, _ -> calls++ }

        bridge.saveExport(nonce, token, "A".repeat(121), "application/json")
        bridge.saveExport(nonce, token, "bad\u0000name.json", "application/json")
        bridge.saveExport(nonce, token, "../bad.json", "application/json")
        bridge.saveExport(nonce, token, "good.json", "text")
        bridge.saveExport(nonce, token, "good.json", "text/plain; charset=utf-8")
        bridge.saveExport(nonce, token, "good.json", "text/\nplain")

        assertEquals(0, calls)
    }

    @Test
    fun `session nonce and injected script are URL safe and read only`() {
        val first = createBridgeSessionNonce()
        val second = createBridgeSessionNonce()
        val script = bridgeNonceScript(first)

        assertEquals(43, first.length)
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertFalse(first == second)
        assertTrue(script.contains(first))
        assertTrue(script.contains("writable: false"))
        assertTrue(script.contains("configurable: false"))
    }
}
