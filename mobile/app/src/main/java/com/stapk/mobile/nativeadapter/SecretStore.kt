package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class StoredSecret(
    val id: String,
    val label: String,
    val value: String,
    val active: Boolean
)

open class SecretStore(private val paths: NativeAdapterPaths) {
    private val file get() = File(paths.secretsDir, "openai-compatible.json")

    open fun write(key: String, value: String, label: String): String? {
        return withFileLock {
            if (key !in SUPPORTED_KEYS) return@withFileLock null
            if (value.isBlank()) {
                deleteLocked(key, null)
                return@withFileLock null
            }
            val secrets = readSecrets()
            val records = recordsFor(secrets, key)
            records.indices.forEach { index -> records[index] = records[index].copy(active = false) }
            val id = UUID.randomUUID().toString()
            records += StoredSecret(id, label.ifBlank { key }, value, active = true)
            secrets.add(key, records.toJsonArray())
            writeSecrets(secrets)
            id
        }
    }

    fun load(key: String): StoredSecret? = withFileLock {
        recordsFor(readSecrets(), key).firstOrNull { it.active }
    }

    fun delete(key: String, id: String?): Boolean = withFileLock { deleteLocked(key, id) }

    private fun deleteLocked(key: String, id: String?): Boolean {
        if (key !in SUPPORTED_KEYS) return false
        val secrets = readSecrets()
        val records = recordsFor(secrets, key)
        val targetIndex = when {
            id != null -> records.indexOfFirst { it.id == id }
            else -> records.indexOfFirst { it.active }
        }
        if (targetIndex < 0) return false
        records.removeAt(targetIndex)
        ensureActive(records)
        if (records.isEmpty()) {
            secrets.remove(key)
        } else {
            secrets.add(key, records.toJsonArray())
        }
        writeSecrets(secrets)
        return true
    }

    fun isSupported(key: String): Boolean = key in SUPPORTED_KEYS

    fun readStateJson(): String = withFileLock {
        val secrets = readSecrets()
        val state = JsonObject()
        SUPPORTED_KEYS.forEach { key ->
            val records = recordsFor(secrets, key)
            if (records.isNotEmpty()) {
                state.add(key, com.google.gson.JsonArray().apply {
                    records.forEach { secret ->
                        add(JsonObject().apply {
                            addProperty("id", secret.id)
                            addProperty("label", secret.label)
                            addProperty("value", REDACTED_VALUE)
                            addProperty("active", secret.active)
                        })
                    }
                })
            }
        }
        state.toString()
    }

    private fun <T> withFileLock(action: () -> T): T =
        locks.getOrPut(file.canonicalFile.path) { ReentrantLock() }.withLock(action)

    private fun recordsFor(secrets: JsonObject, key: String): MutableList<StoredSecret> {
        val raw = secrets.get(key) ?: return mutableListOf()
        val entries = when {
            raw.isJsonArray -> raw.asJsonArray.toList()
            raw.isJsonObject -> listOf(raw)
            else -> emptyList()
        }
        val records = entries.mapNotNull { entry ->
            entry.takeIf { it.isJsonObject }?.asJsonObject?.toStoredSecret(key)
        }.toMutableList()
        ensureActive(records)
        return records
    }

    private fun ensureActive(records: MutableList<StoredSecret>) {
        if (records.isNotEmpty() && records.none { it.active }) {
            records[0] = records[0].copy(active = true)
        }
    }

    private fun JsonObject.toStoredSecret(key: String): StoredSecret? {
        val value = stringValue("value") ?: return null
        if (value.isBlank()) return null
        return StoredSecret(
            id = stringValue("id")?.ifBlank { UUID.randomUUID().toString() } ?: UUID.randomUUID().toString(),
            label = stringValue("label")?.ifBlank { key } ?: key,
            value = value,
            active = get("active")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
        )
    }

    private fun MutableList<StoredSecret>.toJsonArray() = com.google.gson.JsonArray().apply {
        this@toJsonArray.forEach { secret ->
            add(JsonObject().apply {
                addProperty("id", secret.id)
                addProperty("label", secret.label)
                addProperty("value", secret.value)
                addProperty("active", secret.active)
            })
        }
    }

    private fun JsonObject.stringValue(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun writeSecrets(secrets: JsonObject) {
        paths.secretsDir.mkdirs()
        file.writeText(secrets.toString())
    }

    private fun readSecrets(): JsonObject {
        if (!file.exists()) return JsonObject()
        return runCatching { JsonParser.parseString(file.readText()).asJsonObject }
            .getOrElse { JsonObject() }
    }

    private companion object {
        const val OPENAI_KEY = "api_key_openai"
        const val EMBEDDING_KEY = "api_key_embedding"
        const val REDACTED_VALUE = "********"
        val SUPPORTED_KEYS = setOf(OPENAI_KEY, "api_key_custom", EMBEDDING_KEY)
        val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
