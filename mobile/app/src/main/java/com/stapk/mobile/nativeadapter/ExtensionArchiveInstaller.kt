package com.stapk.mobile.nativeadapter

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import java.util.zip.ZipInputStream

data class ExtensionArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxSingleFileBytes: Long = 32L * 1024L * 1024L,
    val maxExpandedBytes: Long = 128L * 1024L * 1024L
)

class ExtensionArchiveInstaller(
    private val paths: NativeAdapterPaths,
    private val limits: ExtensionArchiveLimits = ExtensionArchiveLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val directoryMover: (File, File) -> Unit = ::moveDirectory
) {
    fun install(release: ExtensionRelease, replacing: ExtensionRecord? = null): InstalledExtension {
        val folderName = replacing?.folderName ?: release.repository.repository
        val now = clock()
        val record = ExtensionRecord(
            folderName = folderName,
            repositoryUrl = release.repository.canonicalUrl,
            owner = release.repository.owner,
            repository = release.repository.repository,
            branch = release.branch,
            commitSha = release.commitSha,
            installedAt = replacing?.installedAt ?: now,
            updatedAt = now
        )
        if (replacing != null && !replacing.repositoryUrl.equals(record.repositoryUrl, ignoreCase = true)) {
            release.archive.close()
            throw InvalidExtensionArchiveException("Update repository does not match installed extension")
        }

        val root = paths.extensionsDir.apply { mkdirs() }
        val target = root.resolve(folderName)
        val staging = root.resolve(".$folderName.installing-${UUID.randomUUID()}")
        val previous = root.resolve(".$folderName.previous")
        try {
            if (replacing == null && target.exists()) {
                throw InvalidExtensionArchiveException("Extension directory already exists")
            }
            if (replacing != null && !target.isDirectory) {
                throw InvalidExtensionArchiveException("Installed extension directory is missing")
            }
            staging.mkdirs()
            extractArchive(release, staging)
            validateManifest(staging)

            if (previous.exists() && !previous.deleteRecursively()) {
                throw InvalidExtensionArchiveException("Unable to remove previous extension directory")
            }
            if (target.exists()) directoryMover(target, previous)
            try {
                directoryMover(staging, target)
            } catch (exception: Exception) {
                if (target.exists()) target.deleteRecursively()
                if (previous.exists()) directoryMover(previous, target)
                throw exception
            }
            if (previous.exists() && !previous.deleteRecursively()) {
                throw InvalidExtensionArchiveException("Unable to clean previous extension directory")
            }
            return InstalledExtension(record, target)
        } catch (exception: InvalidExtensionArchiveException) {
            throw exception
        } catch (exception: Exception) {
            throw InvalidExtensionArchiveException("Unable to install extension archive", exception)
        } finally {
            release.archive.close()
            if (staging.exists()) staging.deleteRecursively()
            if (previous.exists() && !target.exists()) {
                runCatching { directoryMover(previous, target) }
            }
        }
    }

    private fun extractArchive(release: ExtensionRelease, staging: File) {
        var archiveRoot: String? = null
        var entryCount = 0
        var expandedBytes = 0L
        val targets = mutableSetOf<String>()
        try {
            ZipInputStream(release.archive.byteStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > limits.maxEntries) {
                        throw InvalidExtensionArchiveException("Extension archive has too many entries")
                    }
                    val normalized = try {
                        SafePath.zipEntry(entry.name.trimEnd('/', '\\'))
                    } catch (exception: IllegalArgumentException) {
                        throw InvalidExtensionArchiveException("Extension archive contains an unsafe path", exception)
                    }
                    val segments = normalized.split('/')
                    val currentRoot = segments.first()
                    if (archiveRoot == null) archiveRoot = currentRoot
                    if (archiveRoot != currentRoot) {
                        throw InvalidExtensionArchiveException("Extension archive must contain one root directory")
                    }
                    if (segments.size == 1) {
                        if (!entry.isDirectory) {
                            throw InvalidExtensionArchiveException("Extension archive root must be a directory")
                        }
                        zip.closeEntry()
                        continue
                    }
                    val relativePath = segments.drop(1).joinToString("/")
                    if (!targets.add(relativePath)) {
                        throw InvalidExtensionArchiveException("Extension archive contains duplicate paths")
                    }
                    val destination = try {
                        SafePath.child(staging, relativePath)
                    } catch (exception: IllegalArgumentException) {
                        throw InvalidExtensionArchiveException("Extension archive contains an unsafe path", exception)
                    }
                    if (entry.isDirectory) {
                        if (!destination.mkdirs() && !destination.isDirectory) {
                            throw IOException("Unable to create extension directory")
                        }
                    } else {
                        destination.parentFile?.mkdirs()
                        var fileBytes = 0L
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                fileBytes += count
                                expandedBytes += count
                                if (fileBytes > limits.maxSingleFileBytes || expandedBytes > limits.maxExpandedBytes) {
                                    throw InvalidExtensionArchiveException("Extension archive exceeds expanded size limits")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (exception: InvalidExtensionArchiveException) {
            throw exception
        } catch (exception: Exception) {
            throw InvalidExtensionArchiveException("Extension archive is not a valid ZIP", exception)
        }
        if (archiveRoot == null) throw InvalidExtensionArchiveException("Extension archive is empty")
    }

    private fun validateManifest(staging: File): JsonObject {
        val manifestFile = staging.resolve("manifest.json")
        if (!manifestFile.isFile) throw InvalidExtensionArchiveException("Extension manifest is missing")
        val manifest = try {
            JsonParser.parseString(manifestFile.readText()).asJsonObject
        } catch (exception: Exception) {
            throw InvalidExtensionArchiveException("Extension manifest is invalid", exception)
        }
        if (manifest.has("server")) {
            throw InvalidExtensionArchiveException("Server extensions are not supported")
        }
        val assets = listOf("js", "css").mapNotNull { name -> manifest.optionalString(name) }
        if (assets.isEmpty()) throw InvalidExtensionArchiveException("Extension manifest has no client assets")
        assets.forEach { asset ->
            val target = try {
                SafePath.child(staging, asset)
            } catch (exception: IllegalArgumentException) {
                throw InvalidExtensionArchiveException("Extension manifest asset escapes its directory", exception)
            }
            if (!target.isFile) throw InvalidExtensionArchiveException("Extension manifest asset is missing")
        }
        return manifest
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw InvalidExtensionArchiveException("Extension manifest $name must be a file path")
        }
        return value.asString.takeIf(String::isNotBlank)
    }

    private companion object {
        fun moveDirectory(source: File, target: File) {
            target.parentFile?.mkdirs()
            try {
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath())
            }
        }
    }
}
