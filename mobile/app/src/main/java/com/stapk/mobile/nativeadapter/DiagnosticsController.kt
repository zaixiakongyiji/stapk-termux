package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticsController(
    private val paths: NativeAdapterPaths,
    private val logger: DiagnosticLogger,
    private val exportStore: ExportStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun summary(): HttpResponse {
        val counts = linkedMapOf<DiagnosticArea, Int>()
        var lastErrorAt = 0L
        logger.logFiles().forEach { file ->
            file.useLines { lines ->
                lines.forEach lineLoop@{ line ->
                    val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                        ?: return@lineLoop
                    val area = event.get("area")?.asString
                        ?.let { runCatching { DiagnosticArea.valueOf(it) }.getOrNull() }
                        ?: return@lineLoop
                    val timestamp = event.get("timestamp")?.asLong ?: return@lineLoop
                    counts[area] = counts.getOrDefault(area, 0) + 1
                    lastErrorAt = maxOf(lastErrorAt, timestamp)
                }
            }
        }
        val body = JsonObject().apply {
            add("counts", JsonObject().apply {
                counts.toSortedMap(compareBy(DiagnosticArea::name)).forEach { (area, count) ->
                    addProperty(area.name, count)
                }
            })
            addProperty("lastErrorAt", lastErrorAt)
            addProperty("quarantineFiles", quarantineFileCount())
        }
        return HttpResponse.json(200, body.toString())
    }

    fun export(): HttpResponse = try {
        val ticket = exportStore.create(EXPORT_FILE_NAME, EXPORT_MIME_TYPE) { target ->
            writeArchive(target)
        }
        HttpResponse.file(ticket.file, ticket.fileName, ticket.token, ticket.mimeType)
    } catch (_: ExportQuotaExceededException) {
        HttpResponse.json(429, """{"error":"export_quota_exceeded"}""")
    } catch (_: Exception) {
        HttpResponse.json(500, """{"error":"diagnostics_export_failed"}""")
    }

    private fun writeArchive(target: File) {
        val logs = logger.logFiles()
        val metadata = listOf(
            paths.webManifestFile to "metadata/stapk-web-manifest.json",
            paths.webDir.resolve("transform-report.json") to "metadata/transform-report.json"
        ).filter { (file, _) -> file.isFile }
        val entries = buildList {
            logs.forEach { file -> add(file to "logs/${file.name}") }
            addAll(metadata)
        }
        val manifest = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("createdAt", clock())
            addProperty("containsUserContent", false)
        }.toString().toByteArray(Charsets.UTF_8)

        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.writeEntry("manifest.json", manifest)
            entries.forEach { (file, name) -> zip.writeEntry(name, file.readBytes()) }
        }
    }

    private fun quarantineFileCount(): Int = paths.quarantineDir
        .takeIf(File::isDirectory)
        ?.walkTopDown()
        ?.count { it.isFile && it.name != "diagnostic.json" }
        ?: 0

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private companion object {
        const val EXPORT_FILE_NAME = "stapk-diagnostics.zip"
        const val EXPORT_MIME_TYPE = "application/zip"
    }
}
