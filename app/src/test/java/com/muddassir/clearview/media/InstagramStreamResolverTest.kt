package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.InstagramStreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Instagram embed pages carry the post's direct mp4 in several different
 * shapes depending on the page variant and the post type. Every shape must
 * resolve — this is what lets the player stream the video natively instead of
 * falling back to the (black-screen) embedded WebView.
 */
class InstagramStreamResolverTest {

    private val mp4 = "https://scontent.cdninstagram.com/o1/v/t16/f1/m86/reel.mp4"

    @Test
    fun `reads the video_url json field, unescaping slashes and ampersands`() {
        val html = """
            <script>
              window.__data = {"video_url":"https:\/\/scontent.cdninstagram.com\/o1\/v\/t16\/f1\/m86\/reel.mp4\u0026_nc_ht=ig","x":1};
            </script>
        """.trimIndent()
        assertEquals(mp4 + "&_nc_ht=ig", InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `reads the contentUrl json-ld field`() {
        val html = """{"@type":"VideoObject","contentUrl":"${mp4}?efg=1"}"""
        assertEquals("$mp4?efg=1", InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `reads an og video meta tag`() {
        val html = """<meta property="og:video" content="$mp4">"""
        assertEquals(mp4, InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `reads a video_versions style url field`() {
        val html = """{"video_versions":[{"type":101,"url":"${mp4}"}]}"""
        assertEquals(mp4, InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `reads a plain video or source tag`() {
        assertEquals(
            mp4,
            InstagramStreamResolver.findStreamInPayload("<video class=\"x\" src=\"$mp4\" controls></video>")
        )
        assertEquals(
            mp4,
            InstagramStreamResolver.findStreamInPayload("<video><source src='$mp4' type='video/mp4'></video>")
        )
    }

    @Test
    fun `falls back to a bare mp4 link`() {
        val html = """<div data-src="https:\/\/scontent.cdninstagram.com\/v\/bare.mp4"></div>"""
        assertEquals(
            "https://scontent.cdninstagram.com/v/bare.mp4",
            InstagramStreamResolver.findStreamInPayload(html)
        )
    }

    @Test
    fun `an image-only payload never resolves to a stream`() {
        val html = """
            <meta property="og:image" content="https://scontent.cdninstagram.com/photo.jpg">
            <img src="https://scontent.cdninstagram.com/photo2.jpg">
        """.trimIndent()
        assertNull(InstagramStreamResolver.findStreamInPayload(html))
    }

    @Test
    fun `a blob source is rejected so the bare-link fallback is not fooled`() {
        assertNull(InstagramStreamResolver.findStreamInPayload("<video src=\"blob:https://www.instagram.com/x\"></video>"))
        assertNull(InstagramStreamResolver.findStreamInPayload(""))
    }

    @Test
    fun `shortcode extraction handles every permalink shape and a bare shortcode`() {
        assertEquals(
            "CxYz123",
            InstagramStreamResolver.extractShortcode("https://www.instagram.com/reel/CxYz123/?igsh=abc")
        )
        assertEquals(
            "CxYz123",
            InstagramStreamResolver.extractShortcode("https://www.instagram.com/p/CxYz123/")
        )
        assertEquals(
            "CxYz123",
            InstagramStreamResolver.extractShortcode("https://instagr.am/p/CxYz123/")
        )
        // A stored id already carries the `ig_` prefix — the shortcode drops it.
        assertEquals("CxYz123", InstagramStreamResolver.extractShortcode("ig_CxYz123"))
    }
}
