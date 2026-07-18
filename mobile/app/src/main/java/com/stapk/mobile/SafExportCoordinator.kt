package com.stapk.mobile

import android.content.Intent
import com.stapk.mobile.nativeadapter.ExportMetadata
import com.stapk.mobile.nativeadapter.ExportTicket
import java.io.FileInputStream
import java.io.OutputStream

internal data class SafDocumentRequest(val fileName: String, val mimeType: String)

internal fun safDocumentRequest(fileName: String, mimeType: String): SafDocumentRequest {
    require(ExportMetadata.isExport(fileName, mimeType)) { "Invalid export metadata" }
    return SafDocumentRequest(fileName, mimeType)
}

internal class PendingSafWriteQueue<T> {
    private var pending: T? = null

    fun enqueue(value: T) {
        check(pending == null) { "A SAF write is already pending" }
        pending = value
    }

    fun peek(): T? = pending

    fun takeIfReady(serviceAvailable: Boolean): T? {
        if (!serviceAvailable) return null
        return pending.also { pending = null }
    }
}

class SafExportCoordinator {
    fun createDocumentIntent(fileName: String, mimeType: String): Intent {
        val request = safDocumentRequest(fileName, mimeType)
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(request.mimeType)
            .putExtra(Intent.EXTRA_TITLE, request.fileName)
    }

    fun copy(ticket: ExportTicket, output: OutputStream): Long {
        require(ticket.file.isFile) { "Export file is unavailable" }
        var copied = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        FileInputStream(ticket.file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                copied += count
            }
        }
        output.flush()
        return copied
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
