package com.stapk.mobile.nativeadapter.vector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class EncodedVector(val dimension: Int, val blob: ByteArray)

object VectorCodec {
    const val MAX_DIMENSION = 32768

    fun normalize(vector: FloatArray): FloatArray {
        validateDimension(vector.size)
        var squaredNorm = 0.0
        vector.forEach { value ->
            require(value.isFinite()) { "Vector contains a non-finite value" }
            squaredNorm += value.toDouble() * value
        }
        require(squaredNorm.isFinite() && squaredNorm > 0.0) { "Vector norm must be finite and non-zero" }
        val inverseNorm = 1.0 / sqrt(squaredNorm)
        return FloatArray(vector.size) { index -> (vector[index] * inverseNorm).toFloat() }
    }

    fun encodeNormalized(vector: FloatArray): EncodedVector {
        val normalized = normalize(vector)
        val blob = ByteBuffer.allocate(normalized.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { normalized.forEach(::putFloat) }
            .array()
        return EncodedVector(normalized.size, blob)
    }

    fun decode(blob: ByteArray, expectedDimension: Int): FloatArray {
        validateDimension(expectedDimension)
        require(blob.size % Float.SIZE_BYTES == 0) { "Vector blob is not Float32 aligned" }
        require(blob.size == expectedDimension * Float.SIZE_BYTES) { "Vector blob dimension does not match" }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(expectedDimension) {
            buffer.float
                .also { value -> require(value.isFinite()) { "Vector blob contains a non-finite value" } }
        }
        requireUnitNorm(vector)
        return vector
    }

    fun dot(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size && left.isNotEmpty()) { "Vector dimensions must match" }
        var sum = 0.0
        left.indices.forEach { index ->
            require(left[index].isFinite() && right[index].isFinite()) { "Vector contains a non-finite value" }
            sum += left[index].toDouble() * right[index]
        }
        return sum.toFloat()
    }

    private fun validateDimension(dimension: Int) {
        require(dimension in 1..MAX_DIMENSION) { "Vector dimension is out of range" }
    }

    private fun requireUnitNorm(vector: FloatArray) {
        var squaredNorm = 0.0
        vector.forEach { value -> squaredNorm += value.toDouble() * value }
        require(squaredNorm.isFinite() && squaredNorm > 0.0) { "Vector norm must be finite and non-zero" }
        require(abs(sqrt(squaredNorm) - 1.0) <= UNIT_NORM_TOLERANCE) { "Vector blob is not unit-normalized" }
    }

    private const val UNIT_NORM_TOLERANCE = 1e-5
}
