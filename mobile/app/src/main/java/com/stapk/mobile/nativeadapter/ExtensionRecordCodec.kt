package com.stapk.mobile.nativeadapter

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ExtensionRecordCodec {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val fieldNames = setOf(
        "folderName", "repositoryUrl", "owner", "repository", "branch", "commitSha", "installedAt", "updatedAt"
    )

    fun encode(record: ExtensionRecord): String = gson.toJson(JsonObject().apply {
        addProperty("folderName", record.folderName)
        addProperty("repositoryUrl", record.repositoryUrl)
        addProperty("owner", record.owner)
        addProperty("repository", record.repository)
        addProperty("branch", record.branch)
        addProperty("commitSha", record.commitSha)
        addProperty("installedAt", record.installedAt)
        addProperty("updatedAt", record.updatedAt)
    })

    fun decode(text: String): ExtensionRecord = try {
        decode(JsonParser.parseString(text))
    } catch (exception: IllegalArgumentException) {
        throw exception
    } catch (exception: Exception) {
        throw IllegalArgumentException("Invalid extension record", exception)
    }

    fun decode(element: JsonElement): ExtensionRecord {
        val source = try {
            element.asJsonObject
        } catch (exception: Exception) {
            throw IllegalArgumentException("Extension record must be an object", exception)
        }
        require(source.keySet() == fieldNames) { "Extension record fields are invalid" }
        return try {
            ExtensionRecord(
                folderName = source.requiredString("folderName"),
                repositoryUrl = source.requiredString("repositoryUrl"),
                owner = source.requiredString("owner"),
                repository = source.requiredString("repository"),
                branch = source.requiredString("branch"),
                commitSha = source.requiredString("commitSha"),
                installedAt = source.requiredLong("installedAt"),
                updatedAt = source.requiredLong("updatedAt")
            )
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid extension record", exception)
        }
    }

    private fun JsonObject.requiredString(name: String): String = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Invalid extension record $name")

    private fun JsonObject.requiredLong(name: String): Long {
        val value = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?: throw IllegalArgumentException("Invalid extension record $name")
        return value.asString.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid extension record $name")
    }
}
