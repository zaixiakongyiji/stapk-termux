package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AtomicFileStore private constructor(
    private val quarantineDir: File,
    private val temporaryFileWriter: (File, ByteArray) -> Unit,
    private val quarantineMover: (File, File) -> Unit,
    private val diagnosticLogger: DiagnosticLogger?
) {
    constructor(quarantineDir: File, diagnosticLogger: DiagnosticLogger? = null) : this(
        quarantineDir,
        { file, bytes -> writeAndSync(file, bytes) },
        { source, target -> moveReplacing(source, target) },
        diagnosticLogger
    )
    private val allowedRoot = requireNotNull(quarantineDir.canonicalFile.parentFile) {
        "Quarantine directory must have a parent"
    }

    fun writeText(target: File, text: String) = writeBytes(target, text.toByteArray(Charsets.UTF_8))

    fun writeText(target: File, serializer: () -> String) = writeText(target, serializer())

    fun writeBytes(target: File, bytes: ByteArray) {
        val canonicalTarget = allowedTarget(target)
        val lock = locks.getOrPut(canonicalTarget.path) { ReentrantLock() }
        lock.withLock {
            val parent = requireNotNull(canonicalTarget.parentFile)
            check(parent.exists() || parent.mkdirs()) { "Unable to create parent directory" }
            val temporary = File.createTempFile("${canonicalTarget.name}.", ".tmp", parent)
            try {
                temporaryFileWriter(temporary, bytes)
                moveReplacing(temporary, canonicalTarget)
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }

    fun readJsonObject(target: File): JsonObject? {
        val canonicalTarget = allowedTarget(target)
        if (!canonicalTarget.isFile) return null
        return try {
            JsonParser.parseString(canonicalTarget.readText()).asJsonObject
        } catch (_: Exception) {
            quarantine(canonicalTarget, "invalid_json")
            null
        }
    }

    fun quarantine(target: File, reason: String): File {
        val canonicalTarget = allowedTarget(target)
        require(canonicalTarget.isFile) { "Quarantine target must exist" }
        val relative = allowedRoot.toPath().relativize(canonicalTarget.toPath()).toString()
        val batch = File(quarantineDir.canonicalFile, "${System.currentTimeMillis()}-${quarantineSequence.incrementAndGet()}")
        val destination = File(batch, relative)
        val destinationParent = requireNotNull(destination.parentFile)
        check(destinationParent.exists() || destinationParent.mkdirs()) {
            "Unable to create quarantine directory"
        }
        writeDiagnostic(File(batch, "diagnostic.json"), reason, relative)
        quarantineMover(canonicalTarget, destination)
        runCatching {
            diagnosticLogger?.event(
                DiagnosticArea.STORAGE,
                reason,
                mapOf(
                    "file" to canonicalTarget.name,
                    "sha256" to sha256(destination)
                )
            )
        }
        return destination
    }

    private fun writeDiagnostic(target: File, reason: String, relative: String) {
        val body =
            JsonObject().apply {
                addProperty("reason", reason)
                addProperty("source", relative.replace(File.separatorChar, '/'))
            }.toString().toByteArray(Charsets.UTF_8)
        val temporary = File.createTempFile("diagnostic-", ".tmp", requireNotNull(target.parentFile))
        try {
            writeAndSync(temporary, body)
            moveReplacing(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    private fun allowedTarget(target: File): File {
        val canonicalTarget = target.canonicalFile
        val rootPrefix = "${allowedRoot.path}${File.separator}"
        require(canonicalTarget.path.startsWith(rootPrefix)) { "Target escapes app private root" }
        return canonicalTarget
    }

    internal companion object {
        val locks = ConcurrentHashMap<String, ReentrantLock>()
        val quarantineSequence = AtomicLong()

        fun writeAndSync(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }

        internal fun forTesting(quarantineDir: File, writer: (File, ByteArray) -> Unit): AtomicFileStore =
            AtomicFileStore(quarantineDir, writer, { source, target -> moveReplacing(source, target) }, null)

        internal fun forTestingWithQuarantineMover(
            quarantineDir: File,
            mover: (File, File) -> Unit
        ): AtomicFileStore =
            AtomicFileStore(quarantineDir, { file, bytes -> writeAndSync(file, bytes) }, mover, null)

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }

        private fun moveReplacing(source: File, target: File) {
            if (!target.exists()) {
                if (!source.renameTo(target)) throw IOException("Unable to replace ${target.name}")
                return
            }
            val backup = File.createTempFile("${target.name}.", ".bak", requireNotNull(target.parentFile))
            if (!backup.delete()) throw IOException("Unable to prepare replacement backup")
            if (!target.renameTo(backup)) throw IOException("Unable to preserve ${target.name}")
            try {
                if (!source.renameTo(target)) throw IOException("Unable to replace ${target.name}")
            } catch (error: Throwable) {
                if (!backup.renameTo(target)) throw IOException("Unable to restore ${target.name}", error)
                throw error
            } finally {
                if (backup.exists()) backup.delete()
            }
        }
    }
}
