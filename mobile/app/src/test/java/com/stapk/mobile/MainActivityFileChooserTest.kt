package com.stapk.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityFileChooserTest {
    @Test
    fun `Xiaomi extra stream URI reaches WebView upload callback`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val received = CallbackCapture()
        val uri = Uri.parse("content://com.android.fileexplorer.fileprovider/download/card.json")

        assertTrue(showFileChooser(webView, received))
        deliverFileChooserResult(
            activity,
            data = Intent().putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))
        )

        assertEquals(1, received.calls.get())
        assertArrayEquals(arrayOf(uri), received.value.get())
    }

    @Test
    fun `unreadable selected URI returns null and explains the failure`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val received = CallbackCapture()

        assertTrue(showFileChooser(webView, received))
        val missingFile = File(activity.cacheDir, "missing-${System.nanoTime()}.json")
        deliverFileChooserResult(activity, data = Intent().setData(Uri.fromFile(missingFile)))

        assertEquals(1, received.calls.get())
        assertNull(received.value.get())
        assertTrue(
            ShadowToast.getTextOfLatestToast().toString()
                .contains("无法读取所选文件，请重新选择或检查文件访问权限")
        )
    }

    @Test
    fun `missing chooser params rejects and clears the callback`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val rejected = CallbackCapture()

        assertFalse(showFileChooser(webView, rejected, null))
        assertEquals(1, rejected.calls.get())
        assertNull(rejected.value.get())

        val accepted = CallbackCapture()
        val readable = createReadableFileUri(activity)
        assertTrue(showFileChooser(webView, accepted))
        deliverFileChooserResult(activity, data = Intent().setData(readable))

        assertEquals(1, rejected.calls.get())
        assertEquals(1, accepted.calls.get())
        assertArrayEquals(arrayOf(readable), accepted.value.get())
    }

    @Test
    fun `chooser intent exception rejects and clears the callback`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val rejected = CallbackCapture()

        assertFalse(showFileChooser(webView, rejected, ThrowingFileChooserParams()))
        assertEquals(1, rejected.calls.get())
        assertNull(rejected.value.get())

        val accepted = CallbackCapture()
        val readable = createReadableFileUri(activity)
        assertTrue(showFileChooser(webView, accepted))
        deliverFileChooserResult(activity, data = Intent().setData(readable))

        assertEquals(1, rejected.calls.get())
        assertEquals(1, accepted.calls.get())
        assertArrayEquals(arrayOf(readable), accepted.value.get())
    }

    @Test
    fun `activity not found rejects and clears the callback`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val rejected = CallbackCapture()
        val shadowApplication = shadowOf(RuntimeEnvironment.getApplication())

        shadowApplication.checkActivities(true)
        try {
            assertFalse(showFileChooser(webView, rejected, UnresolvableFileChooserParams()))
        } finally {
            shadowApplication.checkActivities(false)
        }

        assertEquals(1, rejected.calls.get())
        assertNull(rejected.value.get())
    }

    @Test
    fun `second chooser cancels old callback once and receives the result exclusively`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val oldCallback = CallbackCapture()
        val newCallback = CallbackCapture()
        val readable = createReadableFileUri(activity)

        assertTrue(showFileChooser(webView, oldCallback))
        assertTrue(showFileChooser(webView, newCallback))

        assertEquals(1, oldCallback.calls.get())
        assertNull(oldCallback.value.get())
        assertEquals(0, newCallback.calls.get())

        deliverFileChooserResult(activity, data = Intent().setData(readable))

        assertEquals(1, oldCallback.calls.get())
        assertEquals(1, newCallback.calls.get())
        assertArrayEquals(arrayOf(readable), newCallback.value.get())
    }

    @Test
    @Config(sdk = [32])
    fun `malformed chooser result completes callback once and does not retain it`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val received = CallbackCapture()

        assertTrue(showFileChooser(webView, received))
        deliverFileChooserResult(activity, data = ThrowingClipDataIntent())

        assertEquals(1, received.calls.get())
        assertNull(received.value.get())
        assertTrue(
            ShadowToast.getTextOfLatestToast().toString()
                .contains("无法读取所选文件，请重新选择或检查文件访问权限")
        )

        deliverFileChooserResult(activity, data = Intent().setData(createReadableFileUri(activity)))
        assertEquals(1, received.calls.get())
    }

    @Test
    fun `cancelled chooser result returns null without unreadable toast`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val webView = activity.findViewById<WebView>(R.id.webView)
        val received = CallbackCapture()
        val toastBefore = ShadowToast.getTextOfLatestToast()

        assertTrue(showFileChooser(webView, received))
        deliverFileChooserResult(activity, resultCode = Activity.RESULT_CANCELED, data = null)

        assertEquals(1, received.calls.get())
        assertNull(received.value.get())
        assertEquals(toastBefore, ShadowToast.getTextOfLatestToast())
    }

    private fun showFileChooser(
        webView: WebView,
        received: CallbackCapture,
        fileChooserParams: WebChromeClient.FileChooserParams? = TestFileChooserParams()
    ): Boolean = shadowOf(webView).webChromeClient.onShowFileChooser(
        webView,
        received.callback,
        fileChooserParams
    )

    private fun deliverFileChooserResult(
        activity: MainActivity,
        resultCode: Int = Activity.RESULT_OK,
        data: Intent?
    ) {
        Activity::class.java.getDeclaredMethod(
            "onActivityResult",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Intent::class.java
        ).apply { isAccessible = true }.invoke(activity, 1001, resultCode, data)
    }

    private fun createReadableFileUri(activity: MainActivity): Uri = Uri.fromFile(
        File.createTempFile("chooser-", ".json", activity.cacheDir).apply { writeText("{}") }
    )

    private class CallbackCapture {
        val calls = AtomicInteger()
        val value = AtomicReference<Array<Uri>?>(arrayOf(Uri.EMPTY))
        val callback = ValueCallback<Array<Uri>> { uris ->
            calls.incrementAndGet()
            value.set(uris)
        }
    }

    private open class TestFileChooserParams : WebChromeClient.FileChooserParams() {
        override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT).setType("application/json")

        override fun getAcceptTypes(): Array<String> = arrayOf("application/json")

        override fun isCaptureEnabled(): Boolean = false

        override fun getFilenameHint(): String? = null

        override fun getMode(): Int = MODE_OPEN

        override fun getTitle(): CharSequence? = null
    }

    private class ThrowingFileChooserParams : TestFileChooserParams() {
        override fun createIntent(): Intent = throw IllegalStateException("chooser unavailable")
    }

    private class UnresolvableFileChooserParams : TestFileChooserParams() {
        override fun createIntent(): Intent = Intent().setComponent(
            ComponentName("missing.package", "missing.package.MissingActivity")
        )
    }

    private class ThrowingClipDataIntent : Intent() {
        override fun getClipData(): ClipData? = throw IllegalStateException("malformed clip data")
    }
}
