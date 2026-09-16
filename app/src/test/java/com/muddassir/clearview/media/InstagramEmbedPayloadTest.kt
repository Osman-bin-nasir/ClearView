package com.muddassir.clearview.media

import com.muddassir.clearview.media.data.InstagramEmbedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the Instagram playback fix: the embed page is now
 * client-rendered, so the playable URL is read from the RENDERED `<video>`
 * element — and only genuinely playable media may be handed to the native
 * player (a blob:, an HTML permalink or a still image must never reach it).
 */
class InstagramEmbedPayloadTest {

    private val mp4 =
        "https://instagram.fhyd2-3.fna.fbcdn.net/o1/v/t2/f2/m86/AQMSkdo.mp4?_nc_cat=111&oh=abc"

    private fun probe(src: String, poster: String = ""): String =
        // evaluateJavascript serializes the JS string return value as a JSON
        // string, whose content is itself JSON — exactly what the resolver sees.
        org.json.JSONObject.quote(
            org.json.JSONObject(mapOf("s" to src, "p" to poster)).toString()
        )

    @Test
    fun `parses the mp4 and poster from a rendered embed probe`() {
        val parsed = InstagramEmbedPayload.parseProbe(
            probe(mp4, "https://instagram.fhyd2-3.fna.fbcdn.net/v/t51/poster.jpg?stp=dst")
        )
        assertEquals(mp4, parsed?.videoUrl)
        assertEquals(
            "https://instagram.fhyd2-3.fna.fbcdn.net/v/t51/poster.jpg?stp=dst",
            parsed?.posterUrl
        )
    }

    @Test
    fun `poster is dropped when it is not an image`() {
        val parsed = InstagramEmbedPayload.parseProbe(probe(mp4, "https://www.instagram.com/p/x/"))
        assertEquals(mp4, parsed?.videoUrl)
        assertNull(parsed?.posterUrl)
    }

    @Test
    fun `an unrendered page (empty probe) yields nothing`() {
        assertNull(InstagramEmbedPayload.parseProbe(""))
        assertNull(InstagramEmbedPayload.parseProbe(null))
        assertNull(InstagramEmbedPayload.parseProbe("null"))
        assertNull(InstagramEmbedPayload.parseProbe("\"\""))
    }

    @Test
    fun `a blob url is never treated as the stream`() {
        assertNull(InstagramEmbedPayload.parseProbe(probe("blob:https://www.instagram.com/1234")))
    }

    @Test
    fun `an html permalink in the media slot is rejected`() {
        // The broken thumbnail/media URLs some RSS bridges hand out — decoding
        // them can only ever fail, so they must not be played or fetched.
        assertNull(
            InstagramEmbedPayload.parseProbe(
                probe("https://www.instagram.com/p/Db-PNm1Miby/media?size=l")
            )
        )
        assertTrue(
            InstagramEmbedPayload.isNonImageUrl(
                "https://www.instagram.com/p/Db-PNm1Miby/media?size=l"
            )
        )
    }

    @Test
    fun `playable urls are recognised, everything else is not`() {
        assertTrue(InstagramEmbedPayload.isPlayableUrl(mp4))
        assertTrue(InstagramEmbedPayload.isPlayableUrl("https://x.test/a/b.webm"))
        assertFalse(InstagramEmbedPayload.isPlayableUrl("https://x.test/page.html"))
        assertFalse(InstagramEmbedPayload.isPlayableUrl("blob:https://x.test/1"))
        assertFalse(InstagramEmbedPayload.isPlayableUrl(""))
    }

    @Test
    fun `image detection accepts real cdn images and rejects pages`() {
        assertTrue(InstagramEmbedPayload.isImageUrl("https://scontent.cdninstagram.com/v/a.jpg?x=1"))
        assertTrue(InstagramEmbedPayload.isImageUrl("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertTrue(InstagramEmbedPayload.isImageUrl("https://yt3.ggpht.com/avatar"))
        assertFalse(InstagramEmbedPayload.isImageUrl("https://www.instagram.com/p/abc/"))
        assertFalse(InstagramEmbedPayload.isImageUrl(mp4))
    }

    @Test
    fun `unknown hosts are still attempted as images`() {
        // A narrow guard: only URLs we can be certain about are rejected, so an
        // unusual CDN is never broken by a guess.
        assertFalse(InstagramEmbedPayload.isNonImageUrl("https://some.cdn.test/photo"))
        assertFalse(InstagramEmbedPayload.isNonImageUrl("https://i.ytimg.com/vi/a/hqdefault.jpg"))
        assertTrue(InstagramEmbedPayload.isNonImageUrl("not-a-url"))
    }
}
