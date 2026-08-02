package com.stapk.mobile.nativeadapter.vector

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.stapk.mobile.nativeadapter.DiagnosticArea
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody

data class EmbeddingBatch(val vectors: List<FloatArray>, val dimension: Int)

class EmbeddingProviderClient(
    httpClient: OkHttpClient,
    private val diagnosticLogger: DiagnosticLogger,
    private val clock: () -> Long = System::currentTimeMillis
) : EmbeddingGateway {
    private val transport = httpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun embed(snapshot: EmbeddingProviderSnapshot, inputs: List<String>): EmbeddingBatch {
        if (inputs.isEmpty()) throw EmbeddingFailure(422, "embedding_invalid_vector")
        val baseUrl = snapshot.normalizedBaseUrl.toHttpUrlOrNull()
            ?: throw EmbeddingFailure(502, "embedding_provider_error")
        val endpoint = baseUrl.newBuilder().addPathSegment("embeddings").build()
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${snapshot.apiKey}")
            .post(requestBody(snapshot.config.model, inputs))
            .build()
        val startedAt = clock()

        try {
            transport.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val failure = if (response.code == 429) {
                        EmbeddingFailure(429, "embedding_rate_limited")
                    } else {
                        EmbeddingFailure(502, "embedding_provider_error")
                    }
                    record(request, startedAt, inputs.size, response.code, null, failure.javaClass)
                    throw failure
                }
                val body = response.body ?: providerFailure(request, startedAt, inputs.size, response.code, null)
                val responseBytes = try {
                    readBounded(body)
                } catch (_: ResponseTooLargeException) {
                    responseTooLarge(request, startedAt, inputs.size, response.code)
                } catch (exception: SocketTimeoutException) {
                    timeoutFailure(request, startedAt, inputs.size, response.code, exception.javaClass)
                } catch (exception: IOException) {
                    providerFailure(request, startedAt, inputs.size, response.code, exception.javaClass)
                }
                val batch = try {
                    parseBatch(responseBytes, inputs.size)
                } catch (exception: InvalidVectorException) {
                    invalidVector(request, startedAt, inputs.size, response.code, exception.javaClass)
                } catch (exception: Exception) {
                    providerFailure(request, startedAt, inputs.size, response.code, exception.javaClass)
                }
                record(request, startedAt, inputs.size, response.code, batch.dimension, null)
                return batch
            }
        } catch (exception: SocketTimeoutException) {
            timeoutFailure(request, startedAt, inputs.size, null, exception.javaClass)
        } catch (exception: IOException) {
            record(request, startedAt, inputs.size, null, null, exception.javaClass)
            throw EmbeddingFailure(502, "embedding_provider_error")
        }
    }

    private fun requestBody(model: String, inputs: List<String>): okhttp3.RequestBody = JsonObject().apply {
        addProperty("model", model)
        add("input", JsonArray().apply { inputs.forEach(::add) })
    }.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun parseBatch(bytes: ByteArray, expectedCount: Int): EmbeddingBatch {
        val data = try {
            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.get("data")
        } catch (exception: Exception) {
            throw ProviderResponseException(exception)
        }
        if (data == null || !data.isJsonArray || data.asJsonArray.size() != expectedCount) {
            throw InvalidVectorException()
        }
        val ordered = MutableList<FloatArray?>(expectedCount) { null }
        var dimension: Int? = null
        data.asJsonArray.forEach { item ->
            if (!item.isJsonObject) throw InvalidVectorException()
            val objectValue = item.asJsonObject
            val index = strictIndex(objectValue) ?: throw InvalidVectorException()
            if (index !in 0 until expectedCount || ordered[index] != null) throw InvalidVectorException()
            val embedding = objectValue.get("embedding")
            if (embedding == null || !embedding.isJsonArray) throw InvalidVectorException()
            val vector = try {
                FloatArray(embedding.asJsonArray.size()) { position -> strictFloat(embedding.asJsonArray[position]) }
            } catch (_: Exception) {
                throw InvalidVectorException()
            }
            val normalized = try {
                VectorCodec.normalize(vector)
            } catch (_: IllegalArgumentException) {
                throw InvalidVectorException()
            }
            if (dimension != null && dimension != normalized.size) throw InvalidVectorException()
            dimension = normalized.size
            ordered[index] = normalized
        }
        val finalDimension = dimension ?: throw InvalidVectorException()
        return EmbeddingBatch(ordered.map { it ?: throw InvalidVectorException() }, finalDimension)
    }

    private fun strictIndex(item: JsonObject): Int? {
        val value = item.get("index") ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return value.asString.toIntOrNull()
    }

    private fun strictFloat(value: com.google.gson.JsonElement): Float {
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw InvalidVectorException()
        val number = value.asDouble
        if (!number.isFinite()) throw InvalidVectorException()
        return number.toFloat().takeIf(Float::isFinite) ?: throw InvalidVectorException()
    }

    private fun readBounded(body: ResponseBody): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_RESPONSE_BYTES + 1L - total).toInt())
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) throw ResponseTooLargeException()
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun providerFailure(
        request: Request,
        startedAt: Long,
        batchCount: Int,
        status: Int?,
        errorClass: Class<*>?
    ): Nothing {
        record(request, startedAt, batchCount, status, null, errorClass)
        throw EmbeddingFailure(502, "embedding_provider_error")
    }

    private fun responseTooLarge(
        request: Request,
        startedAt: Long,
        batchCount: Int,
        status: Int
    ): Nothing {
        record(request, startedAt, batchCount, status, null, ResponseTooLargeException::class.java)
        throw EmbeddingFailure(413, "vector_request_too_large")
    }

    private fun timeoutFailure(
        request: Request,
        startedAt: Long,
        batchCount: Int,
        status: Int?,
        errorClass: Class<*>?
    ): Nothing {
        record(request, startedAt, batchCount, status, null, errorClass)
        throw EmbeddingFailure(504, "embedding_timeout")
    }

    private fun invalidVector(
        request: Request,
        startedAt: Long,
        batchCount: Int,
        status: Int,
        errorClass: Class<*>?
    ): Nothing {
        record(request, startedAt, batchCount, status, null, errorClass)
        throw EmbeddingFailure(422, "embedding_invalid_vector")
    }

    private fun record(
        request: Request,
        startedAt: Long,
        batchCount: Int,
        status: Int?,
        dimension: Int?,
        errorClass: Class<*>?
    ) {
        val fields = mutableMapOf(
            "host" to request.url.host,
            "durationMs" to (clock() - startedAt).coerceAtLeast(0L).toString(),
            "batchCount" to batchCount.toString()
        )
        status?.let { fields["status"] = it.toString() }
        dimension?.let { fields["dimension"] = it.toString() }
        errorClass?.let { fields["errorClass"] = it.name }
        runCatching { diagnosticLogger.event(DiagnosticArea.VECTOR, "provider_request", fields) }
    }

    private class ResponseTooLargeException : IOException()
    private class InvalidVectorException : RuntimeException()
    private class ProviderResponseException(cause: Throwable) : RuntimeException(cause)

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val WRITE_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 60L
        const val MAX_RESPONSE_BYTES = 32L * 1024L * 1024L
        const val BUFFER_SIZE = 8192
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun embeddingHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()
