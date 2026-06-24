package com.stapk.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.app.Activity
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.net.Uri
import android.webkit.ValueCallback
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var tvLog: TextView
    private lateinit var webView: WebView
    private lateinit var topControls: View
    private lateinit var btnFullscreenOverlay: Button
    private lateinit var btnBackup: Button
    private lateinit var btnRestore: Button
    private lateinit var btnManageExtensions: Button
    private lateinit var btnStartServer: Button
    private lateinit var btnOpenBrowser: Button
    private lateinit var runtimeManager: RuntimeManager
    private val executor = Executors.newSingleThreadExecutor()
    private var isServerRunning = false
    private var isFullscreen = false
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val BACKUP_REQUEST_CODE = 1002
    private val RESTORE_REQUEST_CODE = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (android.os.Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1004)
            }
        }

        tvLog = findViewById(R.id.tvLog)
        webView = findViewById(R.id.webView)
        topControls = findViewById(R.id.topControls)
        btnFullscreenOverlay = findViewById(R.id.btnFullscreenOverlay)
        btnBackup = findViewById(R.id.btnBackup)
        btnRestore = findViewById(R.id.btnRestore)
        btnManageExtensions = findViewById(R.id.btnManageExtensions)
        btnStartServer = findViewById(R.id.btnStartServer)
        btnOpenBrowser = findViewById(R.id.btnOpenBrowser)
        
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.databaseEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = TavernWebViewClient()
        
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                
                val intent = fileChooserParams?.createIntent()
                try {
                    if (intent != null) {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    }
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }
        }
        
        webView.addJavascriptInterface(BlobDownloader(), "AndroidDownloader")
        
        runtimeManager = RuntimeManager(this)

        btnFullscreenOverlay.setOnClickListener {
            topControls.visibility = View.GONE
            btnFullscreenOverlay.visibility = View.GONE
            isFullscreen = true
        }

        findViewById<Button>(R.id.btnRun).setOnClickListener {
            runCommandAsync("--version")
        }

        findViewById<Button>(R.id.btnRunEnv).setOnClickListener {
            runCommandAsync("-e", "console.log(process.versions)")
        }

        btnManageExtensions.setOnClickListener {
            executor.execute {
                val extensionsDir = java.io.File(filesDir, "SillyTavern/public/scripts/extensions/third-party")
                if (!extensionsDir.exists() || !extensionsDir.isDirectory) {
                    runOnUiThread { appendLog("No extensions directory found.") }
                    return@execute
                }
                val extensions = extensionsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
                if (extensions.isEmpty()) {
                    runOnUiThread { appendLog("No broken extensions found.") }
                    return@execute
                }
                var deletedCount = 0
                extensionsDir.listFiles()?.forEach { dir ->
                    if (dir.isDirectory && (dir.listFiles()?.isEmpty() == true || dir.listFiles() == null)) {
                        dir.deleteRecursively()
                        deletedCount++
                        runOnUiThread { appendLog("Deleted empty/broken extension: ${dir.name}") }
                    }
                }
                if (deletedCount == 0) {
                    runOnUiThread { appendLog("Extensions are healthy.") }
                } else {
                    runOnUiThread { appendLog("Cleaned up $deletedCount broken extension(s).") }
                }
            }
        }

        btnStartServer.setOnClickListener {
            if (!isServerRunning) {
                appendLog("Starting SillyTavern server...")
                runtimeManager.startSillyTavern()
                isServerRunning = true
                btnStartServer.text = "Stop Server"
                pollServer(btnOpenBrowser)
            } else {
                appendLog("Stopping server...")
                runtimeManager.stopSillyTavern()
                isServerRunning = false
                btnStartServer.text = "Start Server"
                btnOpenBrowser.isEnabled = false
            }
        }

        btnOpenBrowser.setOnClickListener {
            if (isServerRunning) {
                val serviceIntent = Intent(this, KeepAliveService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8000"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        btnBackup.setOnClickListener {
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val dateStr = dateFormat.format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "stAPK_Backup_$dateStr.zip")
            }
            startActivityForResult(intent, BACKUP_REQUEST_CODE)
        }

        btnRestore.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            startActivityForResult(intent, RESTORE_REQUEST_CODE)
        }
        
        appendLog("Initializing RuntimeManager...")
        executor.execute {
            try {
                runtimeManager.extractRuntimeIfNeeded()
                runOnUiThread { appendLog("Runtime ready. Extracting payload...") }
                runtimeManager.deployPayloadIfNeeded()
                runOnUiThread { appendLog("Payload ready.") }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Initialization failed: ${e.message}") }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        stopService(serviceIntent)
    }

    override fun onBackPressed() {
        if (isFullscreen) {
            topControls.visibility = View.VISIBLE
            btnFullscreenOverlay.visibility = View.VISIBLE
            isFullscreen = false
        } else {
            super.onBackPressed()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (fileUploadCallback == null) return
            if (resultCode == Activity.RESULT_OK && data != null) {
                val uris = if (data.clipData != null) {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                } else if (data.data != null) {
                    arrayOf(data.data!!)
                } else null
                fileUploadCallback?.onReceiveValue(uris)
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        } else if (requestCode == BACKUP_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                executor.execute {
                    runOnUiThread { appendLog("Starting backup...") }
                    try {
                        contentResolver.openOutputStream(data.data!!)?.use { outStream ->
                            val success = runtimeManager.backupData(outStream)
                            runOnUiThread { 
                                if (success) appendLog("Backup saved successfully!")
                                else appendLog("Backup failed.")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { appendLog("Error writing backup: ${e.message}") }
                    }
                }
            } else {
                appendLog("Backup cancelled.")
            }
        } else if (requestCode == RESTORE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                executor.execute {
                    runOnUiThread { appendLog("Starting restore...") }
                    try {
                        contentResolver.openInputStream(data.data!!)?.use { inStream ->
                            val success = runtimeManager.restoreData(inStream)
                            runOnUiThread { 
                                if (success) appendLog("Restore complete! Please start server.")
                                else appendLog("Restore failed.")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { appendLog("Error reading backup: ${e.message}") }
                    }
                }
            } else {
                appendLog("Restore cancelled.")
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    inner class BlobDownloader {
        @android.webkit.JavascriptInterface
        fun getBase64FromBlobData(base64Data: String, mimeType: String, fileName: String) {
            try {
                val base64 = base64Data.replaceFirst("^data:[^;]*;base64,".toRegex(), "")
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                
                var file = java.io.File(downloadsDir, fileName)
                var counter = 1
                val nameWithoutExt = file.nameWithoutExtension
                val ext = file.extension
                while (file.exists()) {
                    file = java.io.File(downloadsDir, "${nameWithoutExt}_$counter.$ext")
                    counter++
                }

                java.io.FileOutputStream(file).use { it.write(bytes) }
                runOnUiThread {
                    appendLog("File downloaded to: ${file.absolutePath}")
                    android.widget.Toast.makeText(this@MainActivity, "Saved: ${file.name} to Downloads", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Download failed: ${e.message}") }
            }
        }
    }

    private fun pollServer(btnOpenBrowser: Button) {
        Thread {
            var attempts = 0
            var success = false
            while (attempts < 300 && isServerRunning && !success) { // SillyTavern takes longer to start
                try {
                    val url = URL("http://127.0.0.1:8000")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 1000
                    conn.readTimeout = 1000
                    val code = conn.responseCode
                    if (code == 200) {
                        success = true
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.v("MainActivity", "Poll failed: ${e.message}")
                }
                if (!success) {
                    Thread.sleep(1000)
                    attempts++
                }
            }
            runOnUiThread {
                if (success) {
                    appendLog("Server is up! Loading UI...")
                    btnOpenBrowser.isEnabled = true
                    webView.loadUrl("http://127.0.0.1:8000")
                    btnFullscreenOverlay.visibility = View.VISIBLE
                } else if (isServerRunning) {
                    appendLog("Failed to reach server after 300 seconds.")
                }
            }
        }.start()
    }

    private fun runCommandAsync(vararg args: String) {
        appendLog("Executing: node ${args.joinToString(" ")}")
        executor.execute {
            val result = runtimeManager.runNodeCommand(*args)
            runOnUiThread {
                appendLog(result)
            }
        }
    }

    private fun appendLog(text: String) {
        tvLog.append("\n$text")
    }

    override fun onDestroy() {
        super.onDestroy()
        runtimeManager.stopSillyTavern()
        executor.shutdown()
    }
}
