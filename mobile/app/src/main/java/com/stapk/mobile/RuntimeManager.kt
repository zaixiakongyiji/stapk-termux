package com.stapk.mobile

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class RuntimeManager(context: Context) {
    companion object {
        private const val TAG = "RuntimeManager"
    }

    private val appContext = context.applicationContext

    private val runtimeDir: File
        get() = File(appContext.filesDir, "runtime")

    private val binDir: File
        get() = File(runtimeDir, "bin")

    private val libDir: File
        get() = File(runtimeDir, "lib")

    fun extractRuntimeIfNeeded(force: Boolean = false) {
        val flagFile = File(runtimeDir, "extracted.flag")
        if (runtimeDir.exists() && flagFile.exists() && !force) {
            Log.d(TAG, "Runtime already exists")
            return
        }
        
        Log.d(TAG, "Extracting runtime-poc.zip...")
        runtimeDir.mkdirs()
        binDir.mkdirs()
        libDir.mkdirs()

        try {
            appContext.assets.open("runtime-poc.zip").use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(appContext.filesDir, entry.name)
                        if (!outFile.canonicalPath.startsWith(appContext.filesDir.canonicalPath + File.separator)) {
                            throw SecurityException("Zip Slip vulnerability detected: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { output ->
                                zis.copyTo(output)
                            }
                            if (entry.name.contains("bin/")) {
                                outFile.setExecutable(true, false)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            flagFile.createNewFile()
            Log.d(TAG, "Extraction complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract runtime", e)
            throw e
        }

        // Deploy dummy-server.js
        try {
            val serverFile = File(appContext.filesDir, "dummy-server.js")
            if (!serverFile.exists() || force) {
                appContext.assets.open("dummy-server.js").use { input ->
                    FileOutputStream(serverFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Deployed dummy-server.js")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy dummy-server.js", e)
        }

        // Deploy dummy xdg-open
        try {
            val xdgOpen = File(binDir, "xdg-open")
            if (!xdgOpen.exists() || force) {
                FileOutputStream(xdgOpen).use { output ->
                    output.write("#!/system/bin/sh\nexit 0\n".toByteArray())
                }
                xdgOpen.setExecutable(true, false)
                Log.d(TAG, "Deployed dummy xdg-open")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy dummy xdg-open", e)
        }
    }

    fun runNodeCommand(vararg args: String): String {
        val nodeExe = File(binDir, "node")
        if (!nodeExe.exists()) {
            return "Error: node executable not found at ${nodeExe.absolutePath}"
        }

        val command = mutableListOf(nodeExe.absolutePath)
        command.addAll(args)

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(appContext.filesDir)
        
        val env = processBuilder.environment()
        val ldLibraryPath = env["LD_LIBRARY_PATH"] ?: ""
        // Prepend our libDir to LD_LIBRARY_PATH
        env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:$ldLibraryPath"
        env["TMPDIR"] = appContext.cacheDir.absolutePath
        val path = env["PATH"] ?: ""
        env["PATH"] = "${binDir.absolutePath}:$path"
        
        processBuilder.redirectErrorStream(true)

        return try {
            val process = processBuilder.start()
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return output.append("\nError: node process timed out").toString()
                }
            } else {
                process.waitFor()
            }
            output.toString()
        } catch (e: Exception) {
            "Error executing node: ${e.message}\n${Log.getStackTraceString(e)}"
        }
    }

    fun deployPayloadIfNeeded(force: Boolean = false) {
        val payloadFlag = File(appContext.filesDir, "payload_extracted.flag")
        if (payloadFlag.exists() && !force) {
            Log.d(TAG, "Payload already extracted")
            return
        }

        val tarGzFile = File(appContext.filesDir, "payload.tgz")
        try {
            Log.d(TAG, "Copying payload.tgz to filesDir...")
            appContext.assets.open("payload.tgz").use { input ->
                FileOutputStream(tarGzFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "Extracting payload.tgz...")
            val pb = ProcessBuilder("tar", "-xzf", "payload.tgz")
            pb.directory(appContext.filesDir)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.d(TAG, "Payload extraction complete")
                payloadFlag.createNewFile()
            } else {
                Log.e(TAG, "tar failed with exit code $exitCode\n$output")
                throw Exception("Payload extraction failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deploying payload", e)
            throw e
        } finally {
            if (tarGzFile.exists()) {
                tarGzFile.delete() // Clean up to save space
            }
        }
    }

    private var serverProcess: Process? = null

    fun startSillyTavern() {
        if (serverProcess?.isAlive == true) {
            Log.d(TAG, "SillyTavern is already running")
            return
        }

        val nodeExe = File(binDir, "node")
        val stDir = File(appContext.filesDir, "SillyTavern")
        val serverScript = File(stDir, "server.js")
        
        if (!nodeExe.exists() || !serverScript.exists()) {
            Log.e(TAG, "Cannot start SillyTavern: files missing. node exists: ${nodeExe.exists()}, server.js exists: ${serverScript.exists()}")
            return
        }

        val command = listOf(nodeExe.absolutePath, "server.js")
        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(stDir)
        
        val env = processBuilder.environment()
        val ldLibraryPath = env["LD_LIBRARY_PATH"] ?: ""
        env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:$ldLibraryPath"
        env["TMPDIR"] = appContext.cacheDir.absolutePath
        val path = env["PATH"] ?: ""
        env["PATH"] = "${binDir.absolutePath}:$path"
        env["HOME"] = appContext.filesDir.absolutePath // Critical for npm/node modules
        
        processBuilder.redirectErrorStream(true)

        try {
            serverProcess = processBuilder.start()
            Log.d(TAG, "SillyTavern process started")
            
            // Read output in a background thread to prevent blocking
            Thread {
                val p = serverProcess
                if (p != null) {
                    try {
                        BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                Log.d(TAG, "SillyTavern: $line")
                            }
                        }
                    } catch (e: Exception) {
                        // ignore stream closed exceptions
                    }
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SillyTavern", e)
        }
    }

    fun stopSillyTavern() {
        serverProcess?.let {
            if (it.isAlive) {
                it.destroy()
                Log.d(TAG, "SillyTavern process stopped")
            }
        }
        serverProcess = null
    }

    fun backupData(outputStream: java.io.OutputStream): Boolean {
        val sillyTavernDir = File(appContext.filesDir, "SillyTavern")
        val dataDir = File(sillyTavernDir, "data")
        val configYaml = File(sillyTavernDir, "config.yaml")
        val extensionsDir = File(sillyTavernDir, "public/scripts/extensions/third-party")

        try {
            java.util.zip.ZipOutputStream(outputStream).use { zos ->
                if (configYaml.exists()) {
                    zos.putNextEntry(java.util.zip.ZipEntry("config.yaml"))
                    configYaml.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                
                if (dataDir.exists() && dataDir.isDirectory) {
                    dataDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val entryName = "data/" + file.relativeTo(dataDir).path.replace('\\', '/')
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                if (extensionsDir.exists() && extensionsDir.isDirectory) {
                    extensionsDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val entryName = "public/scripts/extensions/third-party/" + file.relativeTo(extensionsDir).path.replace('\\', '/')
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            return false
        }
    }

    fun restoreData(inputStream: java.io.InputStream): Boolean {
        val sillyTavernDir = File(appContext.filesDir, "SillyTavern")
        try {
            // Delete old data before restoring to ensure true state
            File(sillyTavernDir, "data").deleteRecursively()
            File(sillyTavernDir, "public/scripts/extensions/third-party").deleteRecursively()
            File(sillyTavernDir, "config.yaml").delete()

            java.util.zip.ZipInputStream(inputStream).use { zis ->
                var entry: java.util.zip.ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(sillyTavernDir, entry.name)
                        // Prevent zip slip vulnerability
                        if (!outFile.canonicalPath.startsWith(sillyTavernDir.canonicalPath)) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        
                        // Ensure we only restore valid paths
                        if (entry.name == "config.yaml" || entry.name.startsWith("data/") || entry.name.startsWith("public/scripts/extensions/third-party/")) {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { zis.copyTo(it) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            return false
        }
    }
}
