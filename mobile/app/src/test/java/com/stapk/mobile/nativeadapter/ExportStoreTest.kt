package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class ExportStoreTest {
    @Test
    fun `created ticket is URL safe writes export and can only be consumed once`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        var now = 1_000L
        val store = ExportStore(exportsDir, clock = { now })

        val ticket = store.create("export.json", "application/json") { file -> file.writeText("{}") }

        assertEquals(43, ticket.token.length)
        assertTrue(ticket.token.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals("export.json", ticket.fileName)
        assertEquals("application/json", ticket.mimeType)
        assertEquals(now + 15 * 60 * 1_000L, ticket.expiresAt)
        assertEquals(ticket.expiresAt, ticket.file.lastModified())
        assertEquals("{}", ticket.file.readText())
        assertEquals(exportsDir.canonicalFile, requireNotNull(ticket.file.parentFile).canonicalFile)
        assertEquals(ticket, store.consume(ticket.token))
        assertNull(store.consume(ticket.token))
    }

    @Test
    fun `find validates a ticket without consuming it`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        val store = ExportStore(exportsDir)
        val ticket = store.create("export.json", "application/json") { it.writeText("{}") }

        assertEquals(ticket, store.find(ticket.token))
        assertEquals(ticket, store.find(ticket.token))
        assertEquals(ticket, store.consume(ticket.token))
        assertNull(store.find(ticket.token))
    }

    @Test
    fun `find removes expired and missing tickets`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        var now = 1_000L
        val store = ExportStore(exportsDir, clock = { now })
        val expired = store.create("expired.json", "application/json") { it.writeText("{}") }
        now = expired.expiresAt

        assertNull(store.find(expired.token))
        assertFalse(expired.file.exists())

        val missing = store.create("missing.json", "application/json") { it.writeText("{}") }
        assertTrue(missing.file.delete())
        assertNull(store.find(missing.token))
    }

    @Test
    fun `expired tickets are removed by cleanup and cannot be consumed`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        var now = 1_000L
        val store = ExportStore(exportsDir, clock = { now })
        val ticket = store.create("export.json", "application/json") { file -> file.writeText("{}") }

        now += 15 * 60 * 1_000L
        store.cleanupExpired()

        assertFalse(ticket.file.exists())
        assertNull(store.consume(ticket.token))
    }

    @Test
    fun `writer failure leaves no export file or ticket`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        val store = ExportStore(exportsDir)

        try {
            store.create("export.json", "application/json") { throw IllegalStateException("writer failure") }
        } catch (_: IllegalStateException) {
            // 预期 writer 在 store 事务内失败。
        }

        assertTrue(exportsDir.listFiles().orEmpty().isEmpty())
        store.cleanupExpired()
        assertTrue(exportsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cleanup removes consumed export after its TTL`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        var now = 1_000L
        val store = ExportStore(exportsDir, clock = { now })
        val ticket = store.create("export.json", "application/json") { it.writeText("{}") }
        assertEquals(ticket, store.consume(ticket.token))

        now = ticket.expiresAt
        store.cleanupExpired()

        assertFalse(ticket.file.exists())
    }

    @Test
    fun `new store removes expired orphan export after process restart`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        val orphan = exportsDir.resolve("${"A".repeat(43)}-orphan.json")
        orphan.writeText("{}")
        orphan.setLastModified(1_000L)

        ExportStore(exportsDir, clock = { 2_000L }).cleanupExpired()

        assertFalse(orphan.exists())
    }

    @Test
    fun `cleanup preserves active tickets even when their mtime is stale`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        val now = 1_000L
        val store = ExportStore(exportsDir, clock = { now })
        val ticket = store.create("export.json", "application/json") { it.writeText("{}") }
        Files.setLastModifiedTime(ticket.file.toPath(), FileTime.fromMillis(now - 1))

        store.cleanupExpired()

        assertTrue(ticket.file.exists())
    }

    @Test
    fun `cleanup preserves concurrent export temp files`() {
        val exportsDir = Files.createTempDirectory("stapk-export").toFile()
        val temporary = Files.createTempFile(exportsDir.toPath(), "stapk-export-", ".tmp").toFile()
        temporary.setLastModified(1_000L)

        ExportStore(exportsDir, clock = { 2_000L }).cleanupExpired()

        assertTrue(temporary.exists())
    }

    @Test
    fun `active export quota limits ticket count and aggregate bytes`() {
        val exportsDir = Files.createTempDirectory("stapk-export-quota").toFile()
        val store = ExportStore(exportsDir, maxActiveTickets = 2, maxActiveBytes = 5)

        store.create("first.txt", "text/plain", expectedBytes = 2) { it.writeText("12") }
        store.create("second.txt", "text/plain", expectedBytes = 3) { it.writeText("345") }

        assertThrows(ExportQuotaExceededException::class.java) {
            store.create("third.txt", "text/plain", expectedBytes = 1) { it.writeText("6") }
        }
    }

    @Test
    fun `released consumed export no longer occupies quota`() {
        val exportsDir = Files.createTempDirectory("stapk-export-release").toFile()
        val store = ExportStore(exportsDir, maxActiveTickets = 1, maxActiveBytes = 4)
        val first = store.create("first.txt", "text/plain", expectedBytes = 4) { it.writeText("1234") }

        assertEquals(first, store.consume(first.token))
        store.release(first.token)
        val second = store.create("second.txt", "text/plain", expectedBytes = 4) { it.writeText("5678") }

        assertEquals("second.txt", second.fileName)
    }

    @Test
    fun `file response exposes length encoded name and export token`() {
        val export = Files.createTempFile("stapk-export", ".json").toFile()
        export.writeText("{}")

        val response = HttpResponse.file(export, "我的 导出.json", "export-token")

        assertEquals(export, response.bodyFile)
        assertNull(response.bodyText)
        assertNull(response.bodyBytes)
        assertEquals(export.length().toString(), response.headers["Content-Length"])
        assertEquals(
            "attachment; filename*=UTF-8''%E6%88%91%E7%9A%84%20%E5%AF%BC%E5%87%BA.json",
            response.headers["Content-Disposition"]
        )
        assertEquals("export-token", response.headers["X-stAPK-Export-Token"])
        assertFalse(response.headers["Content-Disposition"]!!.contains('+'))
    }
}
