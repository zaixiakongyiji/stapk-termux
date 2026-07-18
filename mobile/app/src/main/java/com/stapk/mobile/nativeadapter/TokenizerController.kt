package com.stapk.mobile.nativeadapter

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

class TokenizerController(
    registry: EncodingRegistry = Encodings.newLazyEncodingRegistry()
) {
    private val cl100k: Encoding = registry.getEncoding(EncodingType.CL100K_BASE)
    private val o200k: Encoding = registry.getEncoding(EncodingType.O200K_BASE)

    fun encode(model: String, bodyText: String): HttpResponse = respond {
        requireUtf8Budget(bodyText, MAX_REQUEST_BYTES)
        val body = parseObject(bodyText)
        val text = body.requiredString("text")
        requireUtf8Budget(text, MAX_TEXT_BYTES)
        val encoding = encodingForModel(model)
        val tokens = encoding.encode(text)
        if (tokens.size() > MAX_TOKEN_IDS) throw TokenizerRequestTooLarge()
        val tokenArray = tokens.toArray()
        val ids = JsonArray().apply { tokenArray.forEach(::add) }
        val chunks = JsonArray().apply {
            tokenArray.forEach { token -> add(encoding.decode(singleToken(token))) }
        }
        HttpResponse.json(200, JsonObject().apply {
            add("ids", ids)
            addProperty("count", tokens.size())
            add("chunks", chunks)
        }.toString())
    }

    fun decode(model: String, bodyText: String): HttpResponse = respond {
        requireUtf8Budget(bodyText, MAX_REQUEST_BYTES)
        val body = parseObject(bodyText)
        val ids = body.get("ids")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: throw InvalidTokenizerRequest()
        if (ids.size() > MAX_TOKEN_IDS) throw TokenizerRequestTooLarge()
        val tokens = IntArrayList(ids.size()).apply {
            ids.forEach { add(it.requiredTokenId()) }
        }
        HttpResponse.json(200, JsonObject().apply {
            addProperty("text", encodingForModel(model).decode(tokens))
        }.toString())
    }

    fun count(model: String, bodyText: String): HttpResponse = respond {
        requireUtf8Budget(bodyText, MAX_REQUEST_BYTES)
        val messages = JsonParser.parseString(bodyText)
            .takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: throw InvalidTokenizerRequest()
        if (messages.size() > MAX_MESSAGES) throw TokenizerRequestTooLarge()
        val encoding = encodingForModel(model)
        var total = CHAT_REPLY_PADDING.toLong()
        messages.forEach { messageElement ->
            val message = messageElement
                .takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?: throw InvalidTokenizerRequest()
            total += TOKENS_PER_MESSAGE
            message.entrySet().forEach { (key, value) ->
                val text = value
                    .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?: throw InvalidTokenizerRequest()
                total += encoding.countTokens(text)
                if (key == "name") total += TOKENS_PER_NAME
                if (total > MAX_COUNTED_TOKENS) throw TokenizerRequestTooLarge()
            }
        }
        HttpResponse.json(200, JsonObject().apply { addProperty("token_count", total) }.toString())
    }

    fun bias(model: String, bodyText: String): HttpResponse = respond {
        requireUtf8Budget(bodyText, MAX_REQUEST_BYTES)
        val entries = JsonParser.parseString(bodyText)
            .takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?: throw InvalidTokenizerRequest()
        if (entries.size() > MAX_BIAS_ENTRIES) throw TokenizerRequestTooLarge()
        val encoding = encodingForModel(model)
        val result = JsonObject()
        entries.forEach { entryElement ->
            val entry = entryElement
                .takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?: throw InvalidTokenizerRequest()
            val text = entry.requiredString("text")
            val value = entry.get("value").requiredBiasValue()
            if (text.isBlank()) return@forEach
            requireUtf8Budget(text, MAX_TEXT_BYTES)
            val tokens = encoding.encode(text)
            if (tokens.size() > MAX_TOKEN_IDS) throw TokenizerRequestTooLarge()
            tokens.toArray().forEach { token -> result.add(token.toString(), value.deepCopy()) }
            if (result.size() > MAX_BIAS_TOKENS) throw TokenizerRequestTooLarge()
        }
        HttpResponse.json(200, result.toString())
    }

    internal fun encodingTypeForModel(model: String): EncodingType {
        val normalized = model.trim().lowercase().substringAfterLast('/')
        return if (
            normalized.startsWith("gpt-4o") ||
            normalized.startsWith("chatgpt-4o") ||
            normalized.startsWith("gpt-4.1") ||
            normalized.startsWith("gpt-4.5") ||
            normalized.startsWith("gpt-5") ||
            normalized == "o1" || normalized.startsWith("o1-") ||
            normalized == "o3" || normalized.startsWith("o3-") ||
            normalized == "o4" || normalized.startsWith("o4-")
        ) {
            EncodingType.O200K_BASE
        } else {
            EncodingType.CL100K_BASE
        }
    }

    private fun encodingForModel(model: String): Encoding = when (encodingTypeForModel(model)) {
        EncodingType.O200K_BASE -> o200k
        else -> cl100k
    }

    private fun parseObject(bodyText: String): JsonObject = JsonParser.parseString(bodyText)
        .takeIf(JsonElement::isJsonObject)
        ?.asJsonObject
        ?: throw InvalidTokenizerRequest()

    private fun JsonObject.requiredString(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?: throw InvalidTokenizerRequest()

    private fun JsonElement.requiredTokenId(): Int {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) throw InvalidTokenizerRequest()
        return try {
            BigDecimal(asString).intValueExact().also {
                if (it < 0) throw InvalidTokenizerRequest()
            }
        } catch (_: ArithmeticException) {
            throw InvalidTokenizerRequest()
        } catch (_: NumberFormatException) {
            throw InvalidTokenizerRequest()
        }
    }

    private fun JsonElement?.requiredBiasValue(): JsonPrimitive {
        if (this == null || !isJsonPrimitive || !asJsonPrimitive.isNumber) throw InvalidTokenizerRequest()
        val value = try {
            BigDecimal(asString)
        } catch (_: NumberFormatException) {
            throw InvalidTokenizerRequest()
        }
        if (value < MIN_BIAS || value > MAX_BIAS) throw InvalidTokenizerRequest()
        return JsonPrimitive(value)
    }

    private fun requireUtf8Budget(value: String, maxBytes: Int) {
        if (value.length > maxBytes || value.toByteArray(StandardCharsets.UTF_8).size > maxBytes) {
            throw TokenizerRequestTooLarge()
        }
    }

    private fun singleToken(token: Int): IntArrayList = IntArrayList(1).apply { add(token) }

    private inline fun respond(block: () -> HttpResponse): HttpResponse = try {
        block()
    } catch (_: TokenizerRequestTooLarge) {
        HttpResponse.json(413, """{"error":"tokenizer_request_too_large"}""")
    } catch (_: JsonParseException) {
        invalidRequest()
    } catch (_: InvalidTokenizerRequest) {
        invalidRequest()
    } catch (_: UnsupportedOperationException) {
        invalidRequest()
    } catch (_: IllegalArgumentException) {
        invalidRequest()
    }

    private fun invalidRequest(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_tokenizer_request"}""")

    private class InvalidTokenizerRequest : RuntimeException()
    private class TokenizerRequestTooLarge : RuntimeException()

    private companion object {
        const val TOKENS_PER_MESSAGE = 3
        const val TOKENS_PER_NAME = 1
        const val CHAT_REPLY_PADDING = 3
        const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        const val MAX_TOKEN_IDS = 524_288
        const val MAX_MESSAGES = 8_192
        const val MAX_BIAS_ENTRIES = 4_096
        const val MAX_BIAS_TOKENS = 262_144
        const val MAX_COUNTED_TOKENS = 1_048_576
        val MIN_BIAS = BigDecimal("-100")
        val MAX_BIAS = BigDecimal("100")
    }
}
