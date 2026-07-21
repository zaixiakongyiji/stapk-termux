package com.stapk.mobile.nativeadapter

import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHttpStreamingResponseTest {
    @Test
    fun `stream response keeps only the supplied body stream`() {
        val body = ByteArrayInputStream("data: first\n\n".toByteArray())

        val response = HttpResponse.stream(200, "text/event-stream", body)

        assertSame(body, response.bodyStream)
        assertNull(response.bodyText)
        assertNull(response.bodyBytes)
        assertNull(response.bodyFile)
    }

    @Test
    fun `response rejects text body combined with stream body`() {
        val body = ByteArrayInputStream(ByteArray(0))

        assertThrows(IllegalArgumentException::class.java) {
            HttpResponse(
                statusCode = 200,
                mimeType = "text/event-stream",
                bodyText = "data: first\n\n",
                bodyStream = body
            )
        }
    }

    @Test
    fun `generate streams first provider event through real loopback before provider finishes`() {
        val fixture = StreamingFixture("stapk-loopback-incremental")
        var connection: HttpURLConnection? = null
        try {
            fixture.startAndConfigure()
            connection = fixture.openGenerateConnection()

            assertTrue(fixture.provider.awaitFirstEventFlushed())
            assertTrue(fixture.provider.authorizationMatchesPlaceholder.get())
            assertTrue(fixture.provider.streamRequested.get())
            assertEquals(200, connection.responseCode)
            assertEquals("chunked", connection.getHeaderField("Transfer-Encoding"))
            assertNull(connection.getHeaderField("Content-Encoding"))

            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                assertTrue(reader.readLine().startsWith("data:"))
                assertFalse(fixture.provider.finished.get())

                fixture.provider.releaseTail()
                val remainingLines = buildList {
                    while (true) add(reader.readLine() ?: break)
                }

                assertTrue(remainingLines.contains(SECOND_EVENT.trimEnd()))
                assertTrue(remainingLines.contains("data: [DONE]"))
                assertTrue(fixture.provider.awaitFinished())
            }
        } finally {
            fixture.provider.releaseTail()
            connection?.disconnect()
            fixture.close()
        }
    }

    @Test
    fun `closing loopback stream releases provider connection and server still answers ping`() {
        val fixture = StreamingFixture("stapk-loopback-cancel", holdProviderOpen = true)
        var connection: HttpURLConnection? = null
        try {
            fixture.startAndConfigure()
            connection = fixture.openGenerateConnection()

            assertTrue(fixture.provider.awaitFirstEventFlushed())
            assertTrue(fixture.provider.authorizationMatchesPlaceholder.get())
            assertTrue(fixture.provider.streamRequested.get())
            assertEquals(200, connection.responseCode)
            val input = connection.inputStream.bufferedReader(StandardCharsets.UTF_8)
            assertTrue(input.readLine().startsWith("data:"))

            input.close()
            connection.disconnect()
            connection = null
            fixture.provider.releaseTail()

            assertTrue(fixture.provider.awaitConnectionEnded())
            assertFalse(fixture.provider.finished.get())
            assertEquals("{\"pong\":true}", fixture.postJson("/api/ping", "{}"))
        } finally {
            fixture.provider.releaseTail()
            connection?.disconnect()
            fixture.close()
        }
    }

    @Test
    fun `fixed JSON endpoint retains NanoHTTPD gzip strategy`() {
        val root = Files.createTempDirectory("stapk-fixed-json").toFile()
        val server = NativeHttpServer(NativeAdapterPaths(root))
        server.start()
        var connection: HttpURLConnection? = null
        try {
            connection = URL("http://127.0.0.1:${server.listeningPort}/api/ping")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.doOutput = true
            connection.outputStream.use { it.write("{}".toByteArray(StandardCharsets.UTF_8)) }

            assertEquals(200, connection.responseCode)
            assertEquals("gzip", connection.getHeaderField("Content-Encoding"))
            connection.inputStream.close()
        } finally {
            connection?.disconnect()
            server.stop()
            root.deleteRecursively()
        }
    }

    private class StreamingFixture(
        directoryPrefix: String,
        holdProviderOpen: Boolean = false
    ) : Closeable {
        private val root: File = Files.createTempDirectory(directoryPrefix).toFile()
        val provider = GatedSseProvider(holdProviderOpen)
        private val server = NativeHttpServer(NativeAdapterPaths(root))

        fun startAndConfigure() {
            provider.start()
            server.start()
            assertEquals(
                "secret configuration failed",
                200,
                postJsonResponse(
                    "/api/secrets/write",
                    """{"key":"api_key_custom","value":"$PLACEHOLDER_KEY","label":"Integration test"}"""
                ).first
            )
            assertEquals(
                "settings configuration failed",
                200,
                postJsonResponse(
                    "/api/settings/save",
                    """{"main_api":"openai","oai_settings":{"chat_completion_source":"custom","stream_openai":true,"custom_url":"${provider.baseUrl}"}}"""
                ).first
            )
            val settingsEnvelope = JsonParser.parseString(postJson("/api/settings/get", "{}")).asJsonObject
            val persistedSettings = JsonParser.parseString(settingsEnvelope.get("settings").asString).asJsonObject
            val openAiSettings = persistedSettings.getAsJsonObject("oai_settings")
            assertEquals("openai", persistedSettings.get("main_api").asString)
            assertEquals("custom", openAiSettings.get("chat_completion_source").asString)
            assertEquals(provider.baseUrl, openAiSettings.get("custom_url").asString)
            assertTrue(openAiSettings.get("stream_openai").asBoolean)
        }

        fun openGenerateConnection(): HttpURLConnection {
            val body = """{"chat_completion_source":"custom","custom_url":"${provider.baseUrl}","model":"integration-model","messages":[],"stream":true}"""
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            return (URL("http://127.0.0.1:${server.listeningPort}/api/backends/chat-completions/generate")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept-Encoding", "gzip")
                connectTimeout = SAFETY_TIMEOUT_MILLIS
                readTimeout = SAFETY_TIMEOUT_MILLIS
                outputStream.use { it.write(bytes) }
            }
        }

        fun postJson(path: String, body: String): String = postJsonResponse(path, body).second

        private fun postJsonResponse(path: String, body: String): Pair<Int, String> {
            val connection = openPost(path, body)
            return try {
                val status = connection.responseCode
                val responseBody = (if (status >= 400) connection.errorStream else connection.inputStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                status to responseBody
            } finally {
                connection.disconnect()
            }
        }

        private fun openPost(path: String, body: String): HttpURLConnection {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            return (URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(bytes) }
            }
        }

        override fun close() {
            server.stop()
            provider.close()
            root.deleteRecursively()
        }
    }

    private class GatedSseProvider(private val holdOpenAfterTail: Boolean) : Closeable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val socket = AtomicReference<Socket?>()
        private val workerFailure = AtomicReference<Throwable?>()
        private val firstEventFlushed = CountDownLatch(1)
        private val tailGate = CountDownLatch(1)
        private val providerFinished = CountDownLatch(1)
        private val connectionEnded = CountDownLatch(1)
        private val stopHoldingOpen = CountDownLatch(1)
        private var worker: Thread? = null
        val finished = AtomicBoolean(false)
        val authorizationMatchesPlaceholder = AtomicBoolean(false)
        val streamRequested = AtomicBoolean(false)
        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}/v1"

        fun start() {
            worker = thread(name = "gated-sse-provider", isDaemon = false) { serve() }
        }

        fun releaseTail() = tailGate.countDown()

        fun awaitFirstEventFlushed(): Boolean =
            firstEventFlushed.await(SAFETY_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)

        fun awaitFinished(): Boolean =
            providerFinished.await(SAFETY_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)

        fun awaitConnectionEnded(): Boolean =
            connectionEnded.await(SAFETY_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)

        private fun serve() {
            var disconnectWatcher: Thread? = null
            try {
                val accepted = serverSocket.accept()
                socket.set(accepted)
                val input = BufferedInputStream(accepted.getInputStream())
                val output = BufferedOutputStream(accepted.getOutputStream())
                readRequest(input)
                writeAscii(
                    output,
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "Connection: close\r\n\r\n"
                )
                writeChunk(output, FIRST_EVENT)
                output.flush()
                firstEventFlushed.countDown()

                disconnectWatcher = thread(name = "gated-sse-disconnect-watcher", isDaemon = false) {
                    try {
                        if (input.read() < 0) connectionEnded.countDown()
                    } catch (_: IOException) {
                        connectionEnded.countDown()
                    }
                }

                tailGate.await()
                if (holdOpenAfterTail) {
                    writeChunk(output, "data: ${"x".repeat(CANCELLATION_CHUNK_BYTES)}\n\n")
                    output.flush()
                    stopHoldingOpen.await()
                } else {
                    writeChunk(output, SECOND_EVENT)
                    writeChunk(output, "data: [DONE]\n\n")
                    writeAscii(output, "0\r\n\r\n")
                    output.flush()
                    finished.set(true)
                    providerFinished.countDown()
                }
            } catch (throwable: Throwable) {
                if (!serverSocket.isClosed && !(holdOpenAfterTail && throwable is IOException)) {
                    workerFailure.compareAndSet(null, throwable)
                }
            } finally {
                firstEventFlushed.countDown()
                socket.getAndSet(null)?.closeQuietly()
                disconnectWatcher?.join(SAFETY_TIMEOUT_MILLIS.toLong())
            }
        }

        private fun readRequest(input: BufferedInputStream) {
            val requestLine = readAsciiLine(input)
            check(requestLine.startsWith("POST /v1/chat/completions HTTP/1."))
            var contentLength = 0
            while (true) {
                val line = readAsciiLine(input)
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val name = line.substring(0, separator)
                    val value = line.substring(separator + 1).trim()
                    if (name.equals("Content-Length", ignoreCase = true)) contentLength = value.toInt()
                    if (name.equals("Authorization", ignoreCase = true)) {
                        authorizationMatchesPlaceholder.set(value == "Bearer $PLACEHOLDER_KEY")
                    }
                }
            }
            var remaining = contentLength
            val requestBody = ByteArray(contentLength)
            var offset = 0
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                check(count >= 0) { "Provider request ended before its declared body" }
                buffer.copyInto(requestBody, offset, 0, count)
                offset += count
                remaining -= count
            }
            val payload = JsonParser.parseString(requestBody.toString(StandardCharsets.UTF_8)).asJsonObject
            streamRequested.set(
                payload.get("stream")?.takeIf {
                    it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
                }?.asBoolean == true
            )
        }

        private fun readAsciiLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val value = input.read()
                check(value >= 0) { "Provider request ended before headers completed" }
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.add(value.toByte())
            }
            return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
        }

        private fun writeChunk(output: BufferedOutputStream, text: String) {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            writeAscii(output, "${bytes.size.toString(16)}\r\n")
            output.write(bytes)
            writeAscii(output, "\r\n")
        }

        private fun writeAscii(output: BufferedOutputStream, text: String) {
            output.write(text.toByteArray(StandardCharsets.US_ASCII))
        }

        override fun close() {
            tailGate.countDown()
            stopHoldingOpen.countDown()
            socket.getAndSet(null)?.closeQuietly()
            serverSocket.close()
            worker?.join(SAFETY_TIMEOUT_MILLIS.toLong())
            check(worker?.isAlive != true) { "Provider test thread did not stop" }
            workerFailure.get()?.let { throw AssertionError("Provider test server failed", it) }
        }

        private fun Socket.closeQuietly() {
            try {
                close()
            } catch (_: IOException) {
                // 测试清理阶段忽略重复关闭。
            }
        }
    }

    private companion object {
        const val SAFETY_TIMEOUT_MILLIS = 5_000
        const val CANCELLATION_CHUNK_BYTES = 1024 * 1024
        const val PLACEHOLDER_KEY = "integration-test-placeholder-key"
        const val FIRST_EVENT = "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n"
        const val SECOND_EVENT = "data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}\n\n"
    }
}
