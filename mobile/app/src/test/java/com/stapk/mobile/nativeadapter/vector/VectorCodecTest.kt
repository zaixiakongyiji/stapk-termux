package com.stapk.mobile.nativeadapter.vector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class VectorCodecTest {
    @Test
    fun `normalizes and round trips supported dimensions as little endian float32`() {
        listOf(384, 768, 1024, 1536, 3072, 32768).forEach { dimension ->
            val providerVector = FloatArray(dimension) { (it % 17 + 1).toFloat() }

            val normalized = VectorCodec.normalize(providerVector)
            val encoded = VectorCodec.encodeNormalized(normalized)
            val decoded = VectorCodec.decode(encoded.blob, dimension)

            assertNotSame(providerVector, normalized)
            assertEquals(dimension, encoded.dimension)
            assertEquals(dimension * Float.SIZE_BYTES, encoded.blob.size)
            assertEquals(normalized[0], ByteBuffer.wrap(encoded.blob).order(ByteOrder.LITTLE_ENDIAN).float, 0f)
            assertEquals(1.0, normalized.sumOf { value -> value.toDouble() * value }.let(::sqrt), 1e-5)
            assertArrayEquals(normalized, decoded, 0f)
        }
    }

    @Test
    fun `rejects vectors that cannot safely enter vector storage`() {
        listOf(
            floatArrayOf(),
            floatArrayOf(Float.NaN),
            floatArrayOf(Float.POSITIVE_INFINITY),
            floatArrayOf(Float.NEGATIVE_INFINITY),
            floatArrayOf(0f, 0f)
        ).forEach { vector ->
            assertThrows(IllegalArgumentException::class.java) { VectorCodec.normalize(vector) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VectorCodec.normalize(FloatArray(VectorCodec.MAX_DIMENSION + 1) { 1f })
        }
    }

    @Test
    fun `rejects malformed blobs and incompatible dot products`() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorCodec.decode(byteArrayOf(1, 2, 3), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VectorCodec.decode(ByteArray(8), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VectorCodec.decode(ByteArray(4), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VectorCodec.dot(floatArrayOf(1f), floatArrayOf(1f, 0f))
        }
    }

    @Test
    fun `encoding normalizes finite non unit input and rejects non finite input`() {
        val encoded = VectorCodec.encodeNormalized(floatArrayOf(3f, 4f))

        assertArrayEquals(floatArrayOf(0.6f, 0.8f), VectorCodec.decode(encoded.blob, 2), 0.00001f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                VectorCodec.encodeNormalized(floatArrayOf(value, 1f))
            }
        }
    }

    @Test
    fun `decode rejects non finite and non unit float32 blobs`() {
        listOf(
            floatArrayOf(Float.NaN, 0f),
            floatArrayOf(Float.POSITIVE_INFINITY, 0f),
            floatArrayOf(2f, 0f)
        ).forEach { values ->
            val blob = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { values.forEach(::putFloat) }
                .array()
            assertThrows(IllegalArgumentException::class.java) {
                VectorCodec.decode(blob, values.size)
            }
        }
    }

    @Test
    fun `decode reads a maximum dimension little endian blob in order`() {
        val normalized = VectorCodec.normalize(
            FloatArray(VectorCodec.MAX_DIMENSION) { index -> (index % 31 + 1).toFloat() }
        )
        val encoded = VectorCodec.encodeNormalized(normalized)

        val decoded = VectorCodec.decode(encoded.blob, VectorCodec.MAX_DIMENSION)

        assertArrayEquals(normalized, decoded, 0f)
    }

    @Test
    fun `decode rejects an aligned zero norm blob`() {
        assertThrows(IllegalArgumentException::class.java) {
            VectorCodec.decode(ByteArray(8), 2)
        }
    }
}
