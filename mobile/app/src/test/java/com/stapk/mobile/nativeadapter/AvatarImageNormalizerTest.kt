package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarImageNormalizerTest {
    @Test
    fun `avatar dimensions reject invalid and decompression bomb sizes`() {
        assertFalse(areAvatarDimensionsSafe(0, 1024))
        assertFalse(areAvatarDimensionsSafe(1024, -1))
        assertFalse(areAvatarDimensionsSafe(8192, 8192))
        assertTrue(areAvatarDimensionsSafe(4096, 4096))
    }
}
