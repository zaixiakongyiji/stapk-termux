package com.stapk.mobile

import android.webkit.JavascriptInterface
import com.stapk.mobile.nativeadapter.ExportMetadata
import java.security.SecureRandom
import java.util.Base64

internal data class PendingSafExport(
    val token: String,
    val fileName: String,
    val mimeType: String
)

class StapkFileBridge(
    private val sessionNonce: String,
    private val onExport: (token: String, fileName: String, mimeType: String) -> Unit
) {
    @JavascriptInterface
    fun saveExport(nonce: String?, token: String?, fileName: String?, mimeType: String?) {
        if (nonce != sessionNonce) return
        if (token == null || fileName == null || mimeType == null) return
        if (!ExportMetadata.isToken(token)) return
        if (!ExportMetadata.isExport(fileName, mimeType)) return
        onExport(token, fileName, mimeType)
    }
}

internal fun createBridgeSessionNonce(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun bridgeNonceScript(nonce: String): String {
    require(ExportMetadata.isToken(nonce)) { "Invalid bridge nonce" }
    return "Object.defineProperty(window, 'stapkBridgeNonce', {" +
        "value: '$nonce', writable: false, configurable: false, enumerable: false});"
}
