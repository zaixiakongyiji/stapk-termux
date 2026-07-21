package com.stapk.mobile.nativeadapter

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class ProviderResponseStreamTest {
    @Test
    fun `EOF reports completed exactly once`() {
        val fixture = fixture(ByteArrayInputStream("data: done\n\n".toByteArray()))
        val terminals = mutableListOf<Pair<ProviderStreamTerminal, IOException?>>()
        val stream = ProviderResponseStream(fixture.call, fixture.response) { terminal, error ->
            terminals += terminal to error
        }

        stream.readBytes()
        stream.close()
        stream.close()

        assertEquals(listOf(ProviderStreamTerminal.COMPLETED), terminals.map { it.first })
        assertEquals(null, terminals.single().second)
        assertTrue(fixture.call.isCanceled())
    }

    @Test
    fun `close before EOF reports canceled once and still cancels after close failure`() {
        val fixture = fixture(ThrowingCloseInputStream("data: pending\n\n".toByteArray()))
        val terminals = mutableListOf<Pair<ProviderStreamTerminal, IOException?>>()
        val stream = ProviderResponseStream(fixture.call, fixture.response) { terminal, error ->
            terminals += terminal to error
        }

        assertTrue(stream.read() >= 0)
        assertThrows(IOException::class.java) { stream.close() }
        stream.close()

        assertEquals(listOf(ProviderStreamTerminal.CANCELED), terminals.map { it.first })
        assertEquals(null, terminals.single().second)
        assertTrue(fixture.call.isCanceled())
    }

    @Test
    fun `read exception reports read error once and close still cancels call`() {
        val failure = IOException("simulated provider read failure")
        val fixture = fixture(FailingReadInputStream(failure))
        val terminals = mutableListOf<Pair<ProviderStreamTerminal, IOException?>>()
        val stream = ProviderResponseStream(fixture.call, fixture.response) { terminal, error ->
            terminals += terminal to error
        }

        assertEquals('x'.code, stream.read())
        assertThrows(IOException::class.java) { stream.read() }
        stream.close()
        stream.close()

        assertEquals(listOf(ProviderStreamTerminal.READ_ERROR), terminals.map { it.first })
        assertEquals(failure, terminals.single().second)
        assertTrue(fixture.call.isCanceled())
    }

    private fun fixture(input: InputStream): Fixture {
        val request = Request.Builder().url("http://127.0.0.1/provider-stream-test").build()
        val call = OkHttpClient().newCall(request)
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(InputStreamResponseBody(input))
            .build()
        return Fixture(call, response)
    }

    private data class Fixture(
        val call: okhttp3.Call,
        val response: Response
    )

    private class InputStreamResponseBody(input: InputStream) : ResponseBody() {
        private val bufferedSource = input.source().buffer()

        override fun contentType() = "text/event-stream".toMediaType()

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = bufferedSource
    }

    private class ThrowingCloseInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun close() {
            throw IOException("simulated close failure")
        }
    }

    private class FailingReadInputStream(
        private val failure: IOException
    ) : InputStream() {
        private var emitted = false

        override fun read(): Int {
            if (!emitted) {
                emitted = true
                return 'x'.code
            }
            throw failure
        }
    }
}
