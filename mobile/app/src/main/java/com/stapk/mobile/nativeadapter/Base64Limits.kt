package com.stapk.mobile.nativeadapter

internal fun exceedsBase64DecodedLimit(encoded: String, maxBytes: Long): Boolean {
    if (maxBytes < 0) return true
    val maxEncodedCharacters = ((maxBytes + 2L) / 3L) * 4L
    return encoded.length.toLong() > maxEncodedCharacters
}
