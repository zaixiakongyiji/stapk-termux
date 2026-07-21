package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val MAX_STREAM_JSON_FALLBACK_BYTES = 1024 * 1024

class OpenAiCompatibleController(
    paths: NativeAdapterPaths,
    private val client: OkHttpClient = OkHttpClient(),
    private val diagnosticLogger: DiagnosticLogger? = null,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val secrets = SecretStore(paths)
    private val generationClient = client.newBuilder()
        .readTimeout(GENERATION_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun readSecrets(): HttpResponse = HttpResponse.json(200, secrets.readStateJson())

    fun writeSecret(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidRequest()
        val key = input.requiredString("key") ?: return invalidRequest()
        val value = input.requiredString("value") ?: return invalidRequest()
        val label = input.requiredString("label") ?: return invalidRequest()
        val id = secrets.write(
            key,
            value,
            label
        ) ?: return HttpResponse.json(400, """{"error":true,"message":"不支持的密钥或空密钥"}""")
        return HttpResponse.json(200, JsonObject().apply { addProperty("id", id) }.toString())
    }

    fun deleteSecret(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidRequest()
        val key = input.requiredString("key") ?: return invalidRequest()
        val id = if (input.has("id")) input.requiredString("id") ?: return invalidRequest() else null
        if (!secrets.isSupported(key)) return invalidRequest()
        secrets.delete(key, id)
        return HttpResponse.json(200, "{}")
    }

    fun status(body: String): HttpResponse {
        val provider = when (val resolution = resolveProvider(body)) {
            is ProviderResolution.Ready -> resolution.provider
            is ProviderResolution.Rejected -> return resolution.response
        }
        return execute(provider, "models") { responseText ->
            val parsed = runCatching { JsonParser.parseString(responseText) }.getOrNull()
                ?: return@execute HttpResponse.json(502, """{"error":true,"message":"provider 返回了无效的模型列表"}""")
            when {
                parsed.isJsonObject && parsed.asJsonObject.get("data")?.isJsonArray == true ->
                    HttpResponse.json(200, responseText)
                parsed.isJsonArray -> HttpResponse.json(200, JsonObject().apply { add("data", parsed) }.toString())
                else -> HttpResponse.json(502, """{"error":true,"message":"provider 返回了无效的模型列表"}""")
            }
        }
    }

    fun generate(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidRequest()
        val stream = when {
            !input.has("stream") -> false
            input.get("stream").isJsonPrimitive && input.getAsJsonPrimitive("stream").isBoolean ->
                input.get("stream").asBoolean
            else -> return invalidRequest()
        }
        val provider = when (val resolution = resolveProvider(input)) {
            is ProviderResolution.Ready -> resolution.provider
            is ProviderResolution.Rejected -> return resolution.response
        }
        val payload = JsonObject()
        input.entrySet()
            .filter { (key, _) -> key in CHAT_COMPLETION_FIELDS && key != "stream" }
            .forEach { (key, value) -> payload.add(key, value.deepCopy()) }
        if (!payload.has("model")) payload.addProperty("model", DEFAULT_MODEL)
        if (!payload.has("messages")) payload.add("messages", JsonArray())
        payload.addProperty("stream", stream)

        return if (stream) {
            executeStreaming(provider, payload.toString())
        } else {
            execute(provider, "chat/completions", payload.toString(), generationClient) { responseText ->
                HttpResponse.json(200, responseText)
            }
        }
    }

    private fun executeStreaming(provider: Provider, body: String): HttpResponse {
        val request = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .header("Accept", "text/event-stream")
            .apply { provider.apiKey?.let { header("Authorization", "Bearer $it") } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val startedAt = nanoTime()
        val call = generationClient.newCall(request)
        return try {
            val response = call.execute()
            when {
                !response.isSuccessful -> response.use {
                    val responseText = readBounded(response.body, MAX_PROVIDER_ERROR_RESPONSE_BYTES)
                        ?.toString(Charsets.UTF_8)
                        .orEmpty()
                    recordProviderDiagnostic(
                        "provider_http_error",
                        streamingFields(request, response.code, startedAt)
                    )
                    HttpResponse.json(
                        response.code,
                        errorJson(
                            "provider 请求失败 (${response.code})：${providerMessage(responseText, response.message)}",
                            provider.apiKey
                        )
                    )
                }
                isJsonResponse(response.body) -> response.use {
                    val responseBytes = readBounded(response.body, MAX_STREAM_JSON_FALLBACK_BYTES)
                    if (responseBytes == null) {
                        recordProviderDiagnostic(
                            "provider_stream_json_too_large",
                            streamingFields(request, response.code, startedAt)
                        )
                        return@use HttpResponse.json(
                            502,
                            """{"error":true,"message":"provider 流式 JSON 响应过大"}"""
                        )
                    }
                    val responseJson = runCatching {
                        JsonParser.parseString(responseBytes.toString(Charsets.UTF_8))
                    }.getOrNull()
                    if (responseJson == null || (!responseJson.isJsonObject && !responseJson.isJsonArray)) {
                        recordProviderDiagnostic(
                            "provider_stream_invalid_json",
                            streamingFields(request, response.code, startedAt)
                        )
                        return@use HttpResponse.json(
                            502,
                            """{"error":true,"message":"provider 返回了无效的流式 JSON 响应"}"""
                        )
                    }
                    recordProviderDiagnostic(
                        "provider_stream_json_fallback",
                        streamingFields(request, response.code, startedAt)
                    )
                    val fallback = "data: $responseJson\n\ndata: [DONE]\n\n"
                    HttpResponse.stream(
                        response.code,
                        "text/event-stream; charset=utf-8",
                        ByteArrayInputStream(fallback.toByteArray(Charsets.UTF_8))
                    )
                }
                else -> {
                    val mimeType = response.header("Content-Type")
                        ?.takeIf { it.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true) }
                        ?: "text/event-stream"
                    HttpResponse.stream(
                        response.code,
                        mimeType,
                        ProviderResponseStream(call, response) { terminal, error ->
                            val fields = streamingFields(request, response.code, startedAt).toMutableMap()
                            fields["terminal"] = terminal.diagnosticValue
                            error?.let { fields["errorClass"] = it.javaClass.name }
                            recordProviderDiagnostic("provider_stream_terminal", fields)
                        }
                    )
                }
            }
        } catch (exception: IOException) {
            recordProviderDiagnostic(
                "provider_network_error",
                mapOf(
                    "host" to request.url.host,
                    "durationMs" to elapsedMs(startedAt).toString(),
                    "stream" to "true",
                    "errorClass" to exception.javaClass.name
                )
            )
            HttpResponse.json(502, """{"error":true,"message":"无法连接 OpenAI-compatible provider"}""")
        }
    }

    private fun resolveProvider(body: String): ProviderResolution {
        val input = parseObject(body) ?: return ProviderResolution.Rejected(invalidRequest())
        return resolveProvider(input)
    }

    private fun resolveProvider(input: JsonObject): ProviderResolution {
        val source = if (input.has("chat_completion_source")) {
            input.requiredString("chat_completion_source") ?: return ProviderResolution.Rejected(invalidRequest())
        } else {
            "openai"
        }
        val key = when (source) {
            "openai" -> OPENAI_KEY
            "custom" -> CUSTOM_KEY
            else -> return ProviderResolution.Rejected(
                HttpResponse.json(400, """{"error":true,"message":"不支持的 OpenAI-compatible provider"}""")
            )
        }
        val baseUrl = when (source) {
            "openai" -> {
                if (input.has("reverse_proxy")) {
                    input.requiredString("reverse_proxy")?.ifBlank { DEFAULT_BASE_URL }
                        ?: return ProviderResolution.Rejected(invalidRequest())
                } else {
                    DEFAULT_BASE_URL
                }
            }
            else -> input.requiredString("custom_url") ?: return ProviderResolution.Rejected(invalidRequest())
        }.trimEnd('/')
        val parsedUrl = baseUrl.toHttpUrlOrNull()
        if (parsedUrl == null || parsedUrl.scheme !in setOf("http", "https")) {
            return ProviderResolution.Rejected(
                HttpResponse.json(400, """{"error":true,"message":"OpenAI-compatible base URL 无效"}""")
            )
        }
        val secret = secrets.load(key)
        if (source == "openai" && secret == null) {
            return ProviderResolution.Rejected(
                HttpResponse.json(400, """{"error":true,"message":"缺少 OpenAI-compatible API key"}""")
            )
        }
        return ProviderResolution.Ready(Provider(baseUrl, secret?.value))
    }

    private fun execute(
        provider: Provider,
        path: String,
        body: String? = null,
        requestClient: OkHttpClient = client,
        onSuccess: (String) -> HttpResponse
    ): HttpResponse {
        val builder = Request.Builder()
            .url("${provider.baseUrl}/$path")
            .header("Accept", "application/json")
        provider.apiKey?.let { builder.header("Authorization", "Bearer $it") }
        if (body != null) {
            builder.post(body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.get()
        }
        val request = builder.build()
        val startedAt = nanoTime()
        return try {
            requestClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (response.isSuccessful) return onSuccess(responseText)
                recordProviderDiagnostic(
                    "provider_http_error",
                    mapOf(
                        "host" to request.url.host,
                        "status" to response.code.toString(),
                        "durationMs" to elapsedMs(startedAt).toString()
                    )
                )
                HttpResponse.json(
                    response.code,
                    errorJson("provider 请求失败 (${response.code})：${providerMessage(responseText, response.message)}", provider.apiKey)
                )
            }
        } catch (exception: IOException) {
            recordProviderDiagnostic(
                "provider_network_error",
                mapOf(
                    "host" to request.url.host,
                    "durationMs" to elapsedMs(startedAt).toString(),
                    "errorClass" to exception.javaClass.name
                )
            )
            HttpResponse.json(502, """{"error":true,"message":"无法连接 OpenAI-compatible provider"}""")
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        ((nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    private fun streamingFields(request: Request, status: Int, startedAt: Long): Map<String, String> =
        mapOf(
            "host" to request.url.host,
            "status" to status.toString(),
            "durationMs" to elapsedMs(startedAt).toString(),
            "stream" to "true"
        )

    private fun isJsonResponse(body: ResponseBody?): Boolean {
        val mediaType = body?.contentType() ?: return false
        return mediaType.type.equals("application", ignoreCase = true) &&
            (mediaType.subtype.equals("json", ignoreCase = true) || mediaType.subtype.endsWith("+json", ignoreCase = true))
    }

    private fun readBounded(body: ResponseBody?, maxBytes: Int): ByteArray? {
        body ?: return ByteArray(0)
        if (body.contentLength() > maxBytes.toLong()) return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        val input = body.byteStream()
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total + 1))
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun recordProviderDiagnostic(code: String, fields: Map<String, String>) {
        runCatching { diagnosticLogger?.event(DiagnosticArea.PROVIDER, code, fields) }
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
    } catch (_: IllegalStateException) {
        null
    } catch (_: JsonSyntaxException) {
        null
    }

    private fun providerMessage(body: String, fallback: String): String {
        val parsed = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val error = parsed?.get("error")
        return when {
            error?.isJsonObject == true -> error.asJsonObject.requiredString("message") ?: fallback
            error?.isJsonPrimitive == true && error.asJsonPrimitive.isString -> error.asString
            body.isNotBlank() -> body.take(MAX_PROVIDER_ERROR_LENGTH)
            else -> fallback
        }
    }

    private fun errorJson(message: String, apiKey: String?): String = JsonObject().apply {
        addProperty("error", true)
        addProperty("message", apiKey?.takeIf { it.isNotBlank() }?.let {
            message.replace(it, "[REDACTED]")
        } ?: message)
    }.toString()

    private fun invalidRequest(): HttpResponse =
        HttpResponse.json(400, """{"error":true,"message":"请求参数无效"}""")

    private fun JsonObject.requiredString(name: String): String? {
        val element = get(name) ?: return null
        return element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }

    private data class Provider(val baseUrl: String, val apiKey: String?)

    private sealed interface ProviderResolution {
        data class Ready(val provider: Provider) : ProviderResolution
        data class Rejected(val response: HttpResponse) : ProviderResolution
    }

    private companion object {
        const val OPENAI_KEY = "api_key_openai"
        const val CUSTOM_KEY = "api_key_custom"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        const val MAX_PROVIDER_ERROR_LENGTH = 512
        const val MAX_PROVIDER_ERROR_RESPONSE_BYTES = 16 * 1024
        const val GENERATION_READ_TIMEOUT_SECONDS = 120L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val CHAT_COMPLETION_FIELDS = setOf(
            "model", "messages", "temperature", "top_p", "n", "stop", "max_tokens",
            "max_completion_tokens", "presence_penalty", "frequency_penalty", "logit_bias", "user",
            "response_format", "seed", "tools", "tool_choice", "parallel_tool_calls", "logprobs",
            "top_logprobs", "service_tier", "reasoning_effort", "store", "metadata", "modalities",
            "audio", "prediction"
        )
    }
}
