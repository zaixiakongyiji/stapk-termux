package com.stapk.mobile.nativeadapter

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32

data class DecodedCharacterCard(
    val json: JsonObject,
    val avatarBytes: ByteArray?,
    val sourceFormat: String
)

class CharacterCardCodec {
    private val gson = Gson()

    fun decodeJson(bytes: ByteArray): DecodedCharacterCard =
        DecodedCharacterCard(parseJson(bytes), null, "json")

    fun decodePng(bytes: ByteArray): DecodedCharacterCard {
        val chunks = parseChunks(bytes)
        val metadata = chunks
            .mapNotNull(::cardMetadata)
            .let { values -> values.lastOrNull { it.first == CCV3_KEY } ?: values.lastOrNull { it.first == CHARA_KEY } }
            ?: throw IllegalArgumentException("PNG character metadata is missing")
        val decoded = try {
            Base64.getDecoder().decode(metadata.second)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("PNG character metadata has invalid base64")
        }
        require(decoded.size <= MAX_METADATA_BYTES) { "PNG character metadata is too large" }
        return DecodedCharacterCard(
            json = parseJson(decoded),
            avatarBytes = bytes.copyOf(),
            sourceFormat = "png-${metadata.first}"
        )
    }

    fun encodePng(baseImage: ByteArray, card: JsonObject): ByteArray {
        val chunks = parseChunks(baseImage)
        val compatibleJson = gson.toJson(card)
        val v3 = card.deepCopy().apply {
            addProperty("spec", "chara_card_v3")
            addProperty("spec_version", "3.0")
        }
        val metadata = listOf(
            textChunk(CHARA_KEY, compatibleJson),
            textChunk(CCV3_KEY, gson.toJson(v3))
        )

        return ByteArrayOutputStream().apply {
            write(PNG_SIGNATURE)
            chunks.forEach { chunk ->
                if (chunk.type == IEND) metadata.forEach(::write)
                if (!chunk.isCardMetadata()) writeChunk(chunk.type, chunk.data)
            }
        }.toByteArray()
    }

    private fun parseJson(bytes: ByteArray): JsonObject = try {
        normalize(JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject)
    } catch (_: JsonParseException) {
        throw IllegalArgumentException("Character metadata contains invalid json")
    } catch (_: IllegalStateException) {
        throw IllegalArgumentException("Character metadata contains invalid json")
    }

    private fun normalize(card: JsonObject): JsonObject {
        if (!card.has("spec")) card.addProperty("spec", "chara_card_v2")
        if (!card.has("spec_version")) card.addProperty("spec_version", "2.0")
        val data = when {
            !card.has("data") -> JsonObject().also { card.add("data", it) }
            card.get("data").isJsonObject -> card.getAsJsonObject("data")
            else -> throw IllegalArgumentException("Character metadata data field must be an object")
        }

        TOP_LEVEL_TO_DATA.forEach { (topLevel, dataKey) ->
            copyIfMissing(card, topLevel, data, dataKey)
        }
        DATA_TO_TOP_LEVEL.forEach { (dataKey, topLevel) ->
            copyIfMissing(data, dataKey, card, topLevel)
        }
        return card
    }

    private fun copyIfMissing(source: JsonObject, sourceKey: String, target: JsonObject, targetKey: String) {
        if (!target.has(targetKey) && source.has(sourceKey)) {
            target.add(targetKey, source.get(sourceKey).deepCopy())
        }
    }

    private fun parseChunks(png: ByteArray): List<PngChunk> {
        require(png.size >= PNG_SIGNATURE.size && png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            "Invalid PNG signature"
        }
        val chunks = mutableListOf<PngChunk>()
        var offset = PNG_SIGNATURE.size
        var sawIend = false
        while (offset < png.size) {
            require(!sawIend) { "PNG contains trailing data" }
            require(png.size - offset >= CHUNK_HEADER_BYTES) { "PNG contains a truncated chunk" }
            val unsignedLength = ByteBuffer.wrap(png, offset, 4).int.toLong() and 0xffffffffL
            require(unsignedLength <= Int.MAX_VALUE) { "PNG chunk is too large" }
            val length = unsignedLength.toInt()
            val typeStart = offset + 4
            val dataStart = typeStart + 4
            val typeBytes = png.copyOfRange(typeStart, dataStart)
            require(typeBytes.all { byte -> byte.toInt().toChar() in 'A'..'Z' || byte.toInt().toChar() in 'a'..'z' }) {
                "PNG chunk type is invalid"
            }
            val type = typeBytes.toString(Charsets.US_ASCII)
            if (type == TEXT && length > MAX_METADATA_BYTES) {
                throw IllegalArgumentException("PNG character metadata is too large")
            }
            val required = CHUNK_OVERHEAD.toLong() + unsignedLength
            require(required <= png.size.toLong() - offset) { "PNG contains a truncated chunk" }
            val dataEnd = dataStart + length
            val expectedCrc = ByteBuffer.wrap(png, dataEnd, 4).int.toLong() and 0xffffffffL
            val crc = CRC32().apply {
                update(png, typeStart, 4)
                update(png, dataStart, length)
            }.value
            require(crc == expectedCrc) { "PNG chunk crc mismatch" }
            val data = png.copyOfRange(dataStart, dataEnd)
            chunks += PngChunk(type, data)
            offset += length + CHUNK_OVERHEAD
            if (type == IEND) sawIend = true
        }
        require(chunks.firstOrNull()?.type == IHDR) { "PNG IHDR chunk is missing" }
        require(sawIend) { "PNG IEND chunk is missing" }
        return chunks
    }

    private fun cardMetadata(chunk: PngChunk): Pair<String, String>? {
        if (chunk.type != TEXT) return null
        val separator = chunk.data.indexOf(0)
        if (separator <= 0) return null
        val keyword = chunk.data.copyOfRange(0, separator).toString(Charsets.ISO_8859_1)
        if (keyword != CHARA_KEY && keyword != CCV3_KEY) return null
        val value = chunk.data.copyOfRange(separator + 1, chunk.data.size).toString(Charsets.ISO_8859_1)
        return keyword to value
    }

    private fun PngChunk.isCardMetadata(): Boolean = cardMetadata(this) != null

    private fun textChunk(keyword: String, json: String): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        val data = keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + encoded.toByteArray(Charsets.ISO_8859_1)
        require(data.size <= MAX_METADATA_BYTES) { "PNG character metadata is too large" }
        return ByteArrayOutputStream().apply { writeChunk(TEXT, data) }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        write(ByteBuffer.allocate(4).putInt(data.size).array())
        write(typeBytes)
        write(data)
        write(ByteBuffer.allocate(4).putInt(crc).array())
    }

    private data class PngChunk(val type: String, val data: ByteArray)

    private companion object {
        const val MAX_METADATA_BYTES = 8 * 1024 * 1024
        const val CHUNK_HEADER_BYTES = 8
        const val CHUNK_OVERHEAD = 12
        const val IHDR = "IHDR"
        const val IEND = "IEND"
        const val TEXT = "tEXt"
        const val CHARA_KEY = "chara"
        const val CCV3_KEY = "ccv3"
        val TOP_LEVEL_TO_DATA = linkedMapOf(
            "name" to "name",
            "description" to "description",
            "personality" to "personality",
            "scenario" to "scenario",
            "first_mes" to "first_mes",
            "mes_example" to "mes_example",
            "creatorcomment" to "creator_notes",
            "system_prompt" to "system_prompt",
            "post_history_instructions" to "post_history_instructions",
            "creator" to "creator",
            "character_version" to "character_version",
            "alternate_greetings" to "alternate_greetings",
            "tags" to "tags",
            "extensions" to "extensions",
            "character_book" to "character_book"
        )
        val DATA_TO_TOP_LEVEL = linkedMapOf(
            "name" to "name",
            "description" to "description",
            "personality" to "personality",
            "scenario" to "scenario",
            "first_mes" to "first_mes",
            "mes_example" to "mes_example",
            "creator_notes" to "creatorcomment",
            "tags" to "tags"
        )
        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    }
}
