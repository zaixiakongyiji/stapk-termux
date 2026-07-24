package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

class ExtensionDirectoryQuarantine(
    private val paths: NativeAdapterPaths,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uuid: () -> UUID = UUID::randomUUID,
    private val directoryMover: (File, File) -> Unit = ::moveDirectoryAtomically,
    private val symbolicLinkChecker: (File) -> Boolean = { Files.isSymbolicLink(it.toPath()) }
) {
    private val store = AtomicFileStore(paths.quarantineDir)

    fun move(source: File, reason: String, operation: String): File {
        val sourcePath = source.toPath().toAbsolutePath().normalize()
        val extensionsPath = paths.extensionsDir.toPath().toAbsolutePath().normalize()
        require(sourcePath.parent == extensionsPath) {
            "Extension quarantine source must be a direct extension child"
        }
        val originalSource = sourcePath.toFile()
        require(!symbolicLinkChecker(originalSource)) { "Extension quarantine source must not be a symbolic link" }
        require(Files.isDirectory(sourcePath, NOFOLLOW_LINKS)) {
            "Extension quarantine source must be a directory"
        }
        require(reason.isNotBlank()) { "Extension quarantine reason is required" }
        require(operation.isNotBlank()) { "Extension quarantine operation is required" }
        val timestamp = clock()
        require(timestamp >= 0) { "Extension quarantine timestamp must not be negative" }
        val batch = paths.quarantineDir.resolve("extensions/$timestamp-${uuid()}")
        check(batch.mkdirs()) { "Unable to create extension quarantine batch" }
        val destination = batch.resolve(originalSource.name)
        val diagnostic = JsonObject().apply {
            addProperty("reason", reason)
            addProperty("source", originalSource.name)
            addProperty("operation", operation)
            addProperty("timestamp", timestamp)
        }
        store.writeText(batch.resolve("diagnostic.json"), diagnostic.toString())
        directoryMover(originalSource, destination)
        return destination
    }
}
