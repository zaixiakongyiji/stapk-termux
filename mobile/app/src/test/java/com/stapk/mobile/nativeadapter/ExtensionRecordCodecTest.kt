package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExtensionRecordCodecTest {
    @Test
    fun `encodes and strictly decodes an extension record`() {
        val record = record()

        assertEquals(record, ExtensionRecordCodec.decode(ExtensionRecordCodec.encode(record)))
    }

    @Test
    fun `rejects missing extra and wrong typed record fields`() {
        val valid = ExtensionRecordCodec.encode(record())
        val cases = listOf(
            valid.replace("\"updatedAt\":2", ""),
            valid.dropLast(1) + ",\"unexpected\":true}",
            valid.replace("\"installedAt\":1", "\"installedAt\":\"1\""),
            valid.replace("\"folderName\":\"Test-Extension\"", "\"folderName\":\"../escape\"")
        )

        cases.forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                ExtensionRecordCodec.decode(text)
            }
        }
    }

    private fun record() = ExtensionRecord(
        "Test-Extension", "https://github.com/owner/Test-Extension", "owner", "Test-Extension",
        "main", "commit", 1L, 2L
    )
}
