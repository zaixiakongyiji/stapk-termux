package com.stapk.mobile.nativeadapter

import okhttp3.Call
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ProviderStreamTerminal(val diagnosticValue: String) {
    COMPLETED("completed"),
    CANCELED("canceled"),
    READ_ERROR("read_error")
}

internal class ProviderResponseStream(
    private val call: Call,
    private val response: Response,
    private val onTerminal: (ProviderStreamTerminal, IOException?) -> Unit = { _, _ -> }
) : FilterInputStream(requireNotNull(response.body).byteStream()) {
    private val closed = AtomicBoolean(false)
    private val terminalReported = AtomicBoolean(false)

    override fun read(): Int = readWithTerminal { super.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        readWithTerminal { super.read(buffer, offset, length) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        reportTerminal(ProviderStreamTerminal.CANCELED)
        try {
            super.close()
        } finally {
            try {
                response.close()
            } finally {
                call.cancel()
            }
        }
    }

    private fun readWithTerminal(read: () -> Int): Int = try {
        read().also { count ->
            if (count < 0) reportTerminal(ProviderStreamTerminal.COMPLETED)
        }
    } catch (exception: IOException) {
        reportTerminal(ProviderStreamTerminal.READ_ERROR, exception)
        throw exception
    }

    private fun reportTerminal(terminal: ProviderStreamTerminal, error: IOException? = null) {
        if (!terminalReported.compareAndSet(false, true)) return
        runCatching { onTerminal(terminal, error) }
    }
}
