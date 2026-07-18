package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafePathTest {
    @Test
    fun `child rejects traversal absolute control and encoded separator inputs`() {
        val root = Files.createTempDirectory("stapk-safe-path").toFile()

        listOf("../x", "..\\x", File(root.parentFile, "absolute").absolutePath, "a\u0000b", "a\u001Fb", "a%2fb", "a%5Cb")
            .forEach { value -> assertRejected { SafePath.child(root, value) } }
    }

    @Test
    fun `child rejects canonical escape`() {
        val root = Files.createTempDirectory("stapk-safe-path").toFile()

        assertRejected { SafePath.child(root, "nested/../../escaped.json") }
    }

    @Test
    fun `zip entry normalizes separators and rejects unsafe segments`() {
        assertEquals("folder/file.json", SafePath.zipEntry("folder\\file.json"))

        listOf("../x", "..\\x", "/absolute", "a//b", "a/./b", "a/../../x")
            .forEach { value -> assertRejected { SafePath.zipEntry(value) } }
    }

    @Test
    fun `zip entry rejects Windows drive and UNC paths`() {
        listOf("C:\\outside\\x", "C:/outside/x", "C:x", "\\\\server\\share\\x")
            .forEach { value -> assertRejected { SafePath.zipEntry(value) } }
    }

    @Test
    fun `file name cleans display separators and preserves a safe Chinese name`() {
        val fileName = SafePath.fileName("  ../我的\u0000角色\\卡.png  ")
        val longName = SafePath.fileName("角".repeat(121))

        assertTrue(fileName.isNotEmpty())
        assertFalse(fileName.any { it == '/' || it == '\\' || it.isISOControl() })
        assertTrue(fileName.contains("我的角色卡"))
        assertEquals(120, longName.codePointCount(0, longName.length))
        assertEquals("file", SafePath.fileName(".."))
        assertEquals("custom", SafePath.fileName("..", fallback = "custom"))
    }

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
