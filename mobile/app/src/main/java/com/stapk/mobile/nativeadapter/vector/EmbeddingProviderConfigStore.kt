package com.stapk.mobile.nativeadapter.vector

import com.google.gson.JsonObject
import com.stapk.mobile.nativeadapter.AtomicFileStore
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import com.stapk.mobile.nativeadapter.SecretStore
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class EmbeddingProviderType(
    val wireName: String,
    val sourceId: String,
    val secretKey: String
) {
    OPENAI("openai", "openai", "api_key_openai"),
    STAPK_OPENAI_COMPATIBLE("stapk_openai_compatible", "stapk_openai_compatible", "api_key_embedding")
}

data class EmbeddingProviderConfig(
    val type: EmbeddingProviderType,
    val baseUrl: String,
    val model: String
)

data class EmbeddingProviderSnapshot(
    val config: EmbeddingProviderConfig,
    val apiKey: String,
    val normalizedBaseUrl: String,
    val endpointFingerprint: String,
    val modelFingerprint: String
)

class EmbeddingProviderConfigStore(
    private val paths: NativeAdapterPaths,
    private val secretStore: SecretStore,
    private val atomicStore: AtomicFileStore
) {
    fun load(): EmbeddingProviderConfig {
        val configurationExisted = paths.embeddingProviderConfigFile.isFile
        val input = atomicStore.readJsonObject(paths.embeddingProviderConfigFile)
        if (input == null) {
            if (configurationExisted) throw invalidRequest()
            return DEFAULT_CONFIG
        }
        return parse(input)
    }

    fun save(config: EmbeddingProviderConfig, apiKeyUpdate: String?): EmbeddingProviderConfig {
        return withConfigurationLock {
            val normalized = normalize(config)
            apiKeyUpdate?.let { secretStore.write(normalized.type.secretKey, it, "Embedding") }
            atomicStore.writeText(paths.embeddingProviderConfigFile) {
                JsonObject().apply {
                    addProperty("type", normalized.type.wireName)
                    addProperty("baseUrl", normalized.baseUrl)
                    addProperty("model", normalized.model)
                }.toString()
            }
            normalized
        }
    }

    fun snapshot(): EmbeddingProviderSnapshot = withConfigurationLock {
        val config = load().copy()
        val apiKey = secretStore.load(config.type.secretKey)?.value
            ?.takeIf(String::isNotBlank)
            ?.let { String(it.toCharArray()) }
            ?: throw EmbeddingFailure(401, "embedding_key_missing")
        EmbeddingProviderSnapshot(
            config,
            apiKey,
            config.baseUrl,
            config.baseUrl.sha256(),
            config.model.sha256()
        )
    }

    fun keyConfigured(type: EmbeddingProviderType): Boolean =
        secretStore.load(type.secretKey)?.value?.isNotBlank() == true

    private fun parse(input: JsonObject): EmbeddingProviderConfig = try {
        val type = input.requiredString("type")?.let(::typeFromWireName) ?: throw invalidRequest()
        val baseUrl = input.requiredString("baseUrl") ?: throw invalidRequest()
        val model = input.requiredString("model") ?: throw invalidRequest()
        normalize(EmbeddingProviderConfig(type, baseUrl, model))
    } catch (_: IllegalArgumentException) {
        throw invalidRequest()
    }

    private fun normalize(config: EmbeddingProviderConfig): EmbeddingProviderConfig {
        val baseUrl = if (config.type == EmbeddingProviderType.OPENAI) {
            OPENAI_BASE_URL
        } else {
            normalizeBaseUrl(config.baseUrl)
        }
        val model = config.model.trim()
        require(model.isNotEmpty() && model.length <= MAX_MODEL_LENGTH && model.none(Char::isISOControl)) {
            "embedding_model_invalid"
        }
        return EmbeddingProviderConfig(config.type, baseUrl, model)
    }

    private fun typeFromWireName(wireName: String): EmbeddingProviderType? =
        EmbeddingProviderType.values().firstOrNull { it.wireName == wireName }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty() || trimmed.length > MAX_BASE_URL_LENGTH) {
            throw IllegalArgumentException("embedding_base_url_invalid")
        }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("embedding_base_url_invalid")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val loopback = host?.removePrefix("[")?.removeSuffix("]") in LOOPBACK_HOSTS
        if (
            !uri.isAbsolute || host == null || uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            (scheme != "https" && !(scheme == "http" && loopback))
        ) {
            throw IllegalArgumentException("embedding_base_url_invalid")
        }
        return trimmed
    }

    private fun JsonObject.requiredString(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun invalidRequest(): EmbeddingFailure = EmbeddingFailure(400, "vector_invalid_request")

    private fun <T> withConfigurationLock(action: () -> T): T =
        locks.getOrPut(paths.embeddingProviderConfigFile.canonicalFile.path) { ReentrantLock() }.withLock(action)

    private companion object {
        const val MAX_BASE_URL_LENGTH = 2048
        const val MAX_MODEL_LENGTH = 256
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        val DEFAULT_CONFIG = EmbeddingProviderConfig(
            EmbeddingProviderType.OPENAI,
            OPENAI_BASE_URL,
            "text-embedding-3-small"
        )
        val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
