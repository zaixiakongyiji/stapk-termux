package com.stapk.mobile.nativeadapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

fun interface AvatarImageNormalizer {
    fun toPng(source: ByteArray): ByteArray
}

object AndroidAvatarImageNormalizer : AvatarImageNormalizer {
    override fun toPng(source: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (!areAvatarDimensionsSafe(bounds.outWidth, bounds.outHeight)) {
            throw IllegalArgumentException("Avatar image dimensions are unsafe")
        }
        val bitmap = BitmapFactory.decodeByteArray(source, 0, source.size)
            ?: throw IllegalArgumentException("Unable to decode avatar image")
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IllegalArgumentException("Unable to encode avatar image")
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}

internal fun areAvatarDimensionsSafe(width: Int, height: Int): Boolean =
    width in 1..MAX_AVATAR_DIMENSION &&
        height in 1..MAX_AVATAR_DIMENSION &&
        width.toLong() * height.toLong() <= MAX_AVATAR_PIXELS

private const val MAX_AVATAR_DIMENSION = 8192
private const val MAX_AVATAR_PIXELS = 4096L * 4096L
