package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32

class CharacterCardCodecTest {
    private val codec = CharacterCardCodec()

    @Test
    fun `decodes supplied ccv3 png fixture`() {
        val bytes = fixture("cc7481f898a8e631.png")
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02X".format(byte) }

        val decoded = codec.decodePng(bytes)

        assertEquals("F65384B4AC03C5E39FE94669215AEAC52803278C0BFA42F5E56E3FBE71A428CD", checksum)
        assertEquals("png-ccv3", decoded.sourceFormat)
        assertEquals("珞蒹葭", decoded.json.getAsJsonObject("data").get("name").asString)
        assertArrayEquals(bytes, decoded.avatarBytes)
    }

    @Test
    fun `decode png prefers ccv3 metadata and validates both supported text chunks`() {
        val v2 = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"V2"}}"""
        val v3 = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"V3"}}"""
        val card = pngWithMetadata(
            textChunk("chara", Base64.getEncoder().encodeToString(v2.toByteArray())),
            textChunk("ccv3", Base64.getEncoder().encodeToString(v3.toByteArray()))
        )

        val decoded = codec.decodePng(card)

        assertEquals("png-ccv3", decoded.sourceFormat)
        assertEquals("V3", decoded.json.getAsJsonObject("data").get("name").asString)
        assertArrayEquals(card, decoded.avatarBytes)
    }

    @Test
    fun `encode png replaces card metadata without mutating caller json`() {
        val original = JsonObject().apply {
            addProperty("spec", "chara_card_v2")
            addProperty("spec_version", "2.0")
            add("data", JsonObject().apply {
                addProperty("name", "Alice")
                add("extensions", JsonObject().apply { addProperty("vendor", "kept") })
            })
        }
        val before = original.deepCopy()
        val base = pngWithMetadata(textChunk("chara", Base64.getEncoder().encodeToString("{}".toByteArray())))

        val encoded = codec.encodePng(base, original)
        val decoded = codec.decodePng(encoded)
        val chunks = readChunks(encoded)

        assertEquals(before, original)
        assertEquals(1, chunks.count { it.first == "tEXt" && textKeyword(it.second) == "chara" })
        assertEquals(1, chunks.count { it.first == "tEXt" && textKeyword(it.second) == "ccv3" })
        assertEquals("chara_card_v3", decoded.json.get("spec").asString)
        assertEquals("3.0", decoded.json.get("spec_version").asString)
        assertEquals("kept", decoded.json.getAsJsonObject("data").getAsJsonObject("extensions").get("vendor").asString)
    }

    @Test
    fun `decode png rejects crc mismatch missing metadata invalid base64 and invalid json`() {
        val crcMismatch = pngWithMetadata(
            textChunk("chara", Base64.getEncoder().encodeToString("{}".toByteArray()))
        ).also(::corruptFirstTextData)
        val invalidBase64 = pngWithMetadata(textChunk("chara", "%%%"))
        val invalidJson = pngWithMetadata(
            textChunk("chara", Base64.getEncoder().encodeToString("not-json".toByteArray()))
        )

        assertFails("crc") { codec.decodePng(crcMismatch) }
        assertFails("metadata") { codec.decodePng(BASE_PNG) }
        assertFails("base64") { codec.decodePng(invalidBase64) }
        assertFails("json") { codec.decodePng(invalidJson) }
    }

    @Test
    fun `decode png rejects truncated chunks and metadata larger than eight mib before allocation`() {
        val truncated = pngWithMetadata(
            textChunk("chara", Base64.getEncoder().encodeToString("{}".toByteArray()))
        ).copyOf(BASE_PNG.size + 7)
        val oversized = ByteArrayOutputStream().apply {
            write(PNG_SIGNATURE)
            writeInt(MAX_METADATA_BYTES + 1)
            write("tEXt".toByteArray(Charsets.US_ASCII))
        }.toByteArray()

        assertFails("truncated") { codec.decodePng(truncated) }
        assertFails("metadata") { codec.decodePng(oversized) }
    }

    @Test
    fun `decode json normalizes flat v1 fields without dropping foreign data`() {
        val decoded = codec.decodeJson(
            """{
                "name":"Legacy",
                "description":"Legacy description",
                "first_mes":"Hello",
                "creatorcomment":"Legacy notes",
                "character_book":{"name":"Embedded"},
                "vendor_top":{"id":7}
            }""".trimIndent().toByteArray()
        ).json

        assertEquals("chara_card_v2", decoded.get("spec").asString)
        assertEquals("2.0", decoded.get("spec_version").asString)
        assertEquals("Legacy", decoded.getAsJsonObject("data").get("name").asString)
        assertEquals("Legacy notes", decoded.getAsJsonObject("data").get("creator_notes").asString)
        assertEquals("Embedded", decoded.getAsJsonObject("data").getAsJsonObject("character_book").get("name").asString)
        assertEquals(7, decoded.getAsJsonObject("vendor_top").get("id").asInt)
    }

    @Test
    fun `decode json preserves v2 and v3 unknown fields and embedded character books`() {
        val v2 = codec.decodeJson(fixture("character-card-v2.json")).json
        val v3 = codec.decodeJson(fixture("character-card-v3.json")).json

        assertEquals("v2-top", v2.getAsJsonObject("vendor_top").get("marker").asString)
        assertEquals("v2-data", v2.getAsJsonObject("data").getAsJsonObject("vendor_data").get("marker").asString)
        assertEquals("v2-extension", v2.getAsJsonObject("data").getAsJsonObject("extensions")
            .getAsJsonObject("vendor_extension").get("marker").asString)
        assertEquals("V2 Book", v2.getAsJsonObject("data").getAsJsonObject("character_book").get("name").asString)
        assertEquals("chara_card_v3", v3.get("spec").asString)
        assertEquals("v3-extension", v3.getAsJsonObject("data").getAsJsonObject("extensions")
            .getAsJsonObject("vendor_extension").get("marker").asString)
        assertEquals("V3 Book", v3.getAsJsonObject("data").getAsJsonObject("character_book").get("name").asString)
    }

    @Test
    fun `json to png round trip preserves unknown extension exactly`() {
        val card = codec.decodeJson(fixture("character-card-v2.json")).json
        val extensionBefore = card.getAsJsonObject("data").getAsJsonObject("extensions")
            .getAsJsonObject("vendor_extension").deepCopy()

        val roundTripped = codec.decodePng(codec.encodePng(BASE_PNG, card)).json

        assertEquals(
            extensionBefore,
            roundTripped.getAsJsonObject("data").getAsJsonObject("extensions")
                .getAsJsonObject("vendor_extension")
        )
    }

    private fun pngWithMetadata(vararg metadata: ByteArray): ByteArray {
        val chunks = readChunks(BASE_PNG)
        return ByteArrayOutputStream().apply {
            write(PNG_SIGNATURE)
            chunks.forEach { (type, data) ->
                if (type == "IEND") metadata.forEach(::write)
                write(chunk(type, data))
            }
        }.toByteArray()
    }

    private fun textChunk(keyword: String, value: String): ByteArray =
        chunk("tEXt", keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1))

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value.toInt()
        return ByteArrayOutputStream().apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc)
        }.toByteArray()
    }

    private fun readChunks(png: ByteArray): List<Pair<String, ByteArray>> {
        assertTrue(png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var offset = PNG_SIGNATURE.size
        while (offset < png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            val type = png.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            val data = png.copyOfRange(offset + 8, offset + 8 + length)
            chunks += type to data
            offset += 12 + length
        }
        return chunks
    }

    private fun textKeyword(data: ByteArray): String =
        data.copyOfRange(0, data.indexOf(0).coerceAtLeast(0)).toString(Charsets.ISO_8859_1)

    private fun corruptFirstTextData(png: ByteArray) {
        var offset = PNG_SIGNATURE.size
        while (offset < png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            val type = png.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (type == "tEXt") {
                png[offset + 8] = (png[offset + 8].toInt() xor 1).toByte()
                return
            }
            offset += 12 + length
        }
        fail("Expected tEXt chunk")
    }

    private fun assertFails(expectedMessagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (exception: IllegalArgumentException) {
            assertTrue(exception.message.orEmpty().lowercase().contains(expectedMessagePart))
        }
    }

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/$name")
    ).use { it.readBytes() }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(ByteBuffer.allocate(4).putInt(value).array())
    }

    private companion object {
        const val MAX_METADATA_BYTES = 8 * 1024 * 1024
        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val BASE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    }
}
