package com.stapk.mobile

import com.stapk.mobile.nativeadapter.ExportTicket
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafExportCoordinatorTest {
    @Test
    fun `document request preserves ticket name and MIME`() {
        assertEquals(
            SafDocumentRequest("角色卡.json", "application/json"),
            safDocumentRequest("角色卡.json", "application/json")
        )
    }

    @Test
    fun `copy streams the private export in chunks no larger than 64 KiB`() {
        val bytes = ByteArray(200_000) { index -> (index % 251).toByte() }
        val file = Files.createTempFile("stapk-saf-export", ".bin").toFile().apply {
            writeBytes(bytes)
        }
        val ticket = ExportTicket(
            token = "T".repeat(43),
            file = file,
            fileName = "export.bin",
            mimeType = "application/octet-stream",
            expiresAt = Long.MAX_VALUE
        )
        val output = RecordingOutputStream()

        val copied = SafExportCoordinator().copy(ticket, output)

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, output.bytes())
        assertTrue(output.largestWrite <= 64 * 1024)
    }

    private class RecordingOutputStream : OutputStream() {
        private val delegate = ByteArrayOutputStream()
        var largestWrite = 0
            private set

        override fun write(value: Int) = delegate.write(value)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            largestWrite = maxOf(largestWrite, length)
            delegate.write(bytes, offset, length)
        }

        fun bytes(): ByteArray = delegate.toByteArray()
    }
}
