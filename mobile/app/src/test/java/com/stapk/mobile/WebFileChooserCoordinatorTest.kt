package com.stapk.mobile

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebFileChooserCoordinatorTest {
    @Test
    fun `multiple chooser retains expanded json MIME types`() {
        val intent = prepareWebFileChooserIntent(
            Intent(Intent.ACTION_GET_CONTENT).setType("application/json"),
            arrayOf(".json, .jsonl"),
            allowMultiple = true
        )

        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertArrayEquals(
            arrayOf("application/json", "application/x-ndjson", "application/octet-stream"),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        )
    }

    @Test
    fun `chooser intent uses open document and preserves accepted MIME types`() {
        val intent = prepareWebFileChooserIntent(
            Intent(Intent.ACTION_GET_CONTENT).setType("image/png"),
            arrayOf("image/png"),
            allowMultiple = false
        )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("image/png", intent.type)
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `xiaomi ArrayList Uri in extra stream is accepted`() {
        val uri = Uri.parse("content://com.android.fileexplorer.fileprovider/download/card.png")
        val data = Intent().putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))

        assertArrayEquals(
            arrayOf(uri),
            parseWebFileChooserResult(Activity.RESULT_OK, data, standardResult = null)
        )
    }

    @Test
    @Config(sdk = [32])
    fun `legacy Android also reads ArrayList Uri from extra stream`() {
        val uri = Uri.parse("content://com.android.fileexplorer.fileprovider/download/legacy.png")
        val data = Intent().putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))

        assertArrayEquals(
            arrayOf(uri),
            parseWebFileChooserResult(Activity.RESULT_OK, data, standardResult = null)
        )
    }

    @Test
    @Config(sdk = [32])
    fun `legacy Android ignores null in ArrayList extra stream`() {
        val uri = Uri.parse("content://documents/legacy-null.json")
        @Suppress("UNCHECKED_CAST")
        val streamValues = arrayListOf<Parcelable?>(uri, null) as ArrayList<Parcelable>
        val data = Intent().putParcelableArrayListExtra(Intent.EXTRA_STREAM, streamValues)

        assertArrayEquals(
            arrayOf(uri),
            parseWebFileChooserResult(Activity.RESULT_OK, data, standardResult = null)
        )
    }

    @Test
    @Config(sdk = [32])
    fun `legacy Android ignores non Uri Parcelable in ArrayList extra stream`() {
        val uri = Uri.parse("content://documents/legacy-mixed.json")
        val data = Intent().putParcelableArrayListExtra(
            Intent.EXTRA_STREAM,
            arrayListOf<Parcelable>(uri, Intent("malformed-stream-value"))
        )

        assertArrayEquals(
            arrayOf(uri),
            parseWebFileChooserResult(Activity.RESULT_OK, data, standardResult = null)
        )
    }

    @Test
    @Config(sdk = [32])
    fun `legacy Android also reads single Uri from extra stream`() {
        val uri = Uri.parse("content://documents/legacy-single.json")
        val data = Intent().putExtra(Intent.EXTRA_STREAM, uri)

        assertArrayEquals(
            arrayOf(uri),
            parseWebFileChooserResult(Activity.RESULT_OK, data, standardResult = null)
        )
    }

    @Test
    fun `all supported sources are merged and deduplicated`() {
        val first = Uri.parse("content://documents/first.json")
        val second = Uri.parse("content://documents/second.json")
        val clipData = ClipData.newRawUri("first", first).apply {
            addItem(ClipData.Item(second))
        }
        val data = Intent().apply {
            this.data = first
            this.clipData = clipData
            putExtra(Intent.EXTRA_STREAM, second)
        }

        assertArrayEquals(
            arrayOf(first, second),
            parseWebFileChooserResult(Activity.RESULT_OK, data, arrayOf(first))
        )
    }

    @Test
    fun `cancel and unsupported schemes return no files`() {
        assertNull(parseWebFileChooserResult(Activity.RESULT_CANCELED, null, null))
        assertNull(
            parseWebFileChooserResult(
                Activity.RESULT_OK,
                Intent().setData(Uri.parse("https://example.com/card.png")),
                null
            )
        )
    }

    @Test
    fun `unreadable Uri values are removed`() {
        val readable = Uri.parse("content://documents/readable.json")
        val blocked = Uri.parse("content://documents/blocked.json")

        assertArrayEquals(
            arrayOf(readable),
            filterReadableFileChooserUris(arrayOf(readable, blocked)) { it == readable }
        )
        assertNull(filterReadableFileChooserUris(arrayOf(blocked)) { false })
    }
}
