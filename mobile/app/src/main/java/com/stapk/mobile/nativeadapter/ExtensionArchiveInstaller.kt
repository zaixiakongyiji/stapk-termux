package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class ExtensionArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxSingleFileBytes: Long = 32L * 1024L * 1024L,
    val maxExpandedBytes: Long = 128L * 1024L * 1024L
)

class ExtensionArchiveInstaller(
    private val paths: NativeAdapterPaths,
    private val limits: ExtensionArchiveLimits = ExtensionArchiveLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun prepare(release: ExtensionRelease, replacing: ExtensionRecord? = null): PreparedExtension {
        var staging: File? = null
        var prepared = false
        try {
            val record = createRecord(release, replacing)
            if (replacing != null && !replacing.repositoryUrl.equals(record.repositoryUrl, ignoreCase = true)) {
                throw InvalidExtensionArchiveException("Update repository does not match installed extension")
            }
            val root = paths.extensionsDir.apply { mkdirs() }
            staging = root.resolve(".stapk-txn-${UUID.randomUUID()}.installing")
            if (!staging.mkdirs() && !staging.isDirectory) {
                throw InvalidExtensionArchiveException("Unable to create extension staging directory")
            }
            extractArchive(release, staging)
            validateManifest(staging)
            SafePath.child(staging, SIDECAR_FILE).writeText(ExtensionRecordCodec.encode(record), Charsets.UTF_8)
            return PreparedExtension(record, staging).also { prepared = true }
        } catch (exception: ExtensionSourceException) {
            throw exception
        } catch (exception: InvalidExtensionArchiveException) {
            throw exception
        } catch (exception: Exception) {
            throw InvalidExtensionArchiveException("Unable to prepare extension archive", exception)
        } finally {
            release.archive.close()
            if (!prepared) staging?.takeIf(File::exists)?.deleteRecursively()
        }
    }

    private fun createRecord(release: ExtensionRelease, replacing: ExtensionRecord?): ExtensionRecord {
        val now = clock()
        return ExtensionRecord(
            folderName = replacing?.folderName ?: release.repository.repository,
            repositoryUrl = release.repository.canonicalUrl,
            owner = release.repository.owner,
            repository = release.repository.repository,
            branch = release.branch,
            commitSha = release.commitSha,
            installedAt = replacing?.installedAt ?: now,
            updatedAt = now
        )
    }

    private fun extractArchive(release: ExtensionRelease, staging: File) {
        var archiveRoot: String? = null
        var entryCount = 0
        var expandedBytes = 0L
        val targets = mutableSetOf<String>()
        try {
            ArchiveInputStream(release.archive.byteStream()).buffered().use { input ->
                ZipInputStream(input).use { zip ->
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
                        if (segments.drop(1).size > MAX_RELATIVE_PATH_SEGMENTS) {
                            throw InvalidExtensionArchiveException("Extension archive path is too deep")
                        }
                        val relativePath = segments.drop(1).joinToString("/")
                        if (relativePath == SIDECAR_FILE) {
                            throw InvalidExtensionArchiveException("Extension archive contains a reserved sidecar")
                        }
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
            }
        } catch (exception: ArchiveReadException) {
            throw ExtensionArchiveTransportException(exception.cause as IOException)
        } catch (exception: InvalidExtensionArchiveException) {
            throw exception
        } catch (exception: ZipException) {
            throw InvalidExtensionArchiveException("Extension archive is not a valid ZIP", exception)
        } catch (exception: IOException) {
            throw InvalidExtensionArchiveException("Extension archive is not a valid ZIP", exception)
        } catch (exception: Exception) {
            throw InvalidExtensionArchiveException("Extension archive is not a valid ZIP", exception)
        }
        if (archiveRoot == null) throw InvalidExtensionArchiveException("Extension archive is empty")
    }

    private fun validateManifest(staging: File) {
        val manifestFile = SafePath.child(staging, "manifest.json")
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
        assets.forEach { asset -> requireExistingPath(staging, asset, "Extension manifest asset") }
        validateRequires(manifest)
        validateI18n(staging, manifest)
    }

    private fun validateRequires(manifest: JsonObject) {
        if (!manifest.has("requires")) return
        val requires = manifest.get("requires")
        if (!requires.isJsonArray) {
            throw InvalidExtensionArchiveException("Extension manifest requires must be an array")
        }
        requires.asJsonArray.forEach { module ->
            if (!module.isJsonPrimitive || !module.asJsonPrimitive.isString) {
                throw InvalidExtensionArchiveException("Extension manifest requires entries must be strings")
            }
            if (module.asString.isNotEmpty()) {
                throw InvalidExtensionArchiveException("Extension manifest requires modules are not supported")
            }
        }
    }

    private fun validateI18n(staging: File, manifest: JsonObject) {
        if (!manifest.has("i18n")) return
        val i18n = manifest.get("i18n")
        if (!i18n.isJsonObject) {
            throw InvalidExtensionArchiveException("Extension manifest i18n must be an object")
        }
        i18n.asJsonObject.entrySet().forEach { (_, value) ->
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString || value.asString.isBlank()) {
                throw InvalidExtensionArchiveException("Extension manifest locale path must be a file path")
            }
            requireExistingPath(staging, value.asString, "Extension manifest locale")
        }
    }

    private fun requireExistingPath(staging: File, relativePath: String, description: String) {
        val target = try {
            SafePath.child(staging, relativePath)
        } catch (exception: IllegalArgumentException) {
            throw InvalidExtensionArchiveException("$description escapes its directory", exception)
        }
        if (!target.isFile) throw InvalidExtensionArchiveException("$description is missing")
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw InvalidExtensionArchiveException("Extension manifest $name must be a file path")
        }
        return value.asString.takeIf(String::isNotBlank)
    }

    private class ArchiveInputStream(input: InputStream) : FilterInputStream(input) {
        override fun read(): Int = try {
            super.read()
        } catch (exception: IOException) {
            throw ArchiveReadException(exception)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
            super.read(buffer, offset, length)
        } catch (exception: IOException) {
            throw ArchiveReadException(exception)
        }
    }

    private class ArchiveReadException(cause: IOException) : IOException(cause)

    private companion object {
        const val SIDECAR_FILE = ".stapk-extension.json"
        const val MAX_RELATIVE_PATH_SEGMENTS = 24
    }
}
