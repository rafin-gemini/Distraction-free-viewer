package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.UrlPreprocessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context matches app name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("DistractionFreeViewer", appName)
    }

    @Test
    fun `youtube watch url is rewritten to embed form`() {
        val input = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val processed = UrlPreprocessor.process(input)

        assertNotNull(processed)
        assertTrue(processed!!.isYouTube)
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1&rel=0&modestbranding=1&playsinline=1&iv_load_policy=3", processed.targetUrl)
        assertEquals("youtube.com", processed.allowedHost)
    }

    @Test
    fun `youtu be url is rewritten to embed form`() {
        val input = "https://youtu.be/dQw4w9WgXcQ"
        val processed = UrlPreprocessor.process(input)

        assertNotNull(processed)
        assertTrue(processed!!.isYouTube)
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1&rel=0&modestbranding=1&playsinline=1&iv_load_policy=3", processed.targetUrl)
    }

    @Test
    fun `youtube shorts url is rewritten to embed form`() {
        val input = "https://www.youtube.com/shorts/aqz-KE-bpKQ"
        val processed = UrlPreprocessor.process(input)

        assertNotNull(processed)
        assertTrue(processed!!.isYouTube)
        assertEquals("https://www.youtube.com/embed/aqz-KE-bpKQ?autoplay=1&rel=0&modestbranding=1&playsinline=1&iv_load_policy=3", processed.targetUrl)
    }

    @Test
    fun `youtube timestamp is converted to start param`() {
        val input = "https://youtu.be/dQw4w9WgXcQ?t=90"
        val processed = UrlPreprocessor.process(input)

        assertNotNull(processed)
        assertTrue(processed!!.targetUrl.contains("&start=90"))
    }

    @Test
    fun `extract url from shared message text`() {
        val sharedText = "Hey! Check out this talk: https://www.youtube.com/watch?v=dQw4w9WgXcQ, it was awesome!"
        val processed = UrlPreprocessor.process(sharedText)

        assertNotNull(processed)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", processed!!.originalExtractedUrl)
        assertTrue(processed.targetUrl.startsWith("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun `non youtube url is loaded as is`() {
        val input = "https://x.com/AndroidDev/status/123456789"
        val processed = UrlPreprocessor.process(input)

        assertNotNull(processed)
        assertEquals(false, processed!!.isYouTube)
        assertEquals("https://x.com/AndroidDev/status/123456789", processed.targetUrl)
        assertEquals("x.com", processed.allowedHost)
    }
}
