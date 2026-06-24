package com.stapk.mobile

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class TavernWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return false // Let WebView load the URL directly
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(
            """
            document.addEventListener('click', function(e) {
                var target = e.target.closest('a');
                if (target && target.hasAttribute('download') && target.href.startsWith('blob:')) {
                    var fileName = target.getAttribute('download');
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', target.href, true);
                    xhr.responseType = 'blob';
                    xhr.onload = function(e) {
                        if (this.status == 200) {
                            var reader = new FileReader();
                            reader.readAsDataURL(this.response);
                            reader.onloadend = function() {
                                AndroidDownloader.getBase64FromBlobData(reader.result, '', fileName);
                            }
                        }
                    };
                    xhr.send();
                    e.preventDefault();
                    e.stopPropagation();
                }
            }, true);
            """.trimIndent(), null
        )
    }
}
