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
    fun `the media endpoint is not playable but IS an image`() {
        // Instagram's media endpoint is NOT a stream...
        assertNull(
            InstagramEmbedPayload.parseProbe(
                probe("https://www.instagram.com/p/Db-PNm1Miby/media?size=l")
            )
        )
        // ...but it is also NOT a web page: it redirects straight to the post's
        // JPEG. Rejecting it as "an HTML permalink" is exactly why Reels had no
        // thumbnail, so it must be allowed through to the image loader.
        assertFalse(
            InstagramEmbedPayload.isNonImageUrl(
                "https://www.instagram.com/p/Db-PNm1Miby/media?size=l"
            )
        )
        // A real Instagram page (no /media) is still rejected.
        assertTrue(InstagramEmbedPayload.isNonImageUrl("https://www.instagram.com/p/Db-PNm1Miby/"))
        assertTrue(InstagramEmbedPayload.isNonImageUrl("https://www.instagram.com/maherzainofficial/"))
    }

    @Test
    fun `the media endpoint url is built from the shortcode`() {
        assertEquals(
            "https://www.instagram.com/p/Db-PNm1Miby/media/?size=l",
            InstagramEmbedPayload.mediaEndpointUrl("Db-PNm1Miby")
        )
        assertNull(InstagramEmbedPayload.mediaEndpointUrl(""))
        assertNull(InstagramEmbedPayload.mediaEndpointUrl("not a code"))
    }

    @Test
    fun `expiring meta cdn urls are replaced by the stable media endpoint`() {
        // Measured on the device: this exact shape (signed with oh/oe) answered
        // 403 "URL signature mismatch" once its signature expired, which left
        // an Instagram carousel tile permanently blank.
        val expired = "https://scontent.cdninstagram.com/v/t51.82787-15/755351982_186038.jpg" +
            "?stp=dst-jpegr_e35_p1080x1080_tt6&_nc_cat=103&ig_cache_key=Mz&oh=00_AQJj&oe=6AB01466"
        assertTrue(InstagramEmbedPayload.isExpiringImageUrl(expired))
        assertEquals(
            "https://www.instagram.com/p/Db-PNm1Miby/media/?size=l",
            InstagramEmbedPayload.thumbnailFor("Db-PNm1Miby", expired)
        )
        // The same URL WITHOUT a signature is still a usable still, and is kept.
        val unsigned = "https://scontent.cdninstagram.com/v/a.jpg?x=1"
        assertFalse(InstagramEmbedPayload.isExpiringImageUrl(unsigned))
        assertEquals(unsigned, InstagramEmbedPayload.thumbnailFor("Db-PNm1Miby", unsigned))
        // Non-Meta hosts are never treated as expiring (we can't know better).
        assertFalse(InstagramEmbedPayload.isExpiringImageUrl("https://i.ytimg.com/vi/a/hq.jpg?oh=1"))
        assertEquals(
            "https://i.ytimg.com/vi/a/hq.jpg?oh=1",
            InstagramEmbedPayload.thumbnailFor("Db-PNm1Miby", "https://i.ytimg.com/vi/a/hq.jpg?oh=1")
        )
    }

    @Test
    fun `thumbnailFor keeps real images and falls back to the endpoint`() {
        // A real, non-expiring CDN thumbnail is kept verbatim.
        assertEquals(
            "https://scontent.cdninstagram.com/v/a.jpg?x=1",
            InstagramEmbedPayload.thumbnailFor("Db-PNm1Miby", "https://scontent.cdninstagram.com/v/a.jpg?x=1")
        )
        // The bridge's slash-less endpoint form is normalised (one hop less).
        assertEquals(
            "https://www.instagram.com/p/Db-PNm1Miby/media/?size=l",
            InstagramEmbedPayload.thumbnailFor(
                "Db-PNm1Miby",
                "https://www.instagram.com/p/Db-PNm1Miby/media?size=l"
            )
        )
        // No thumbnail at all -> the post's own endpoint, so a Reel is never
        // left posterless.
        assertEquals(
            "https://www.instagram.com/p/Db-PNm1Miby/media/?size=l",
            InstagramEmbedPayload.thumbnailFor("Db-PNm1Miby", "")
        )
        assertEquals(
            "https://www.instagram.com/p/Db-PNm1Miby/media/?size=l",
            InstagramEmbedPayload.thumbnailFor("Db-PNm1Miby", null)
        )
        // Nothing to work with at all.
        assertEquals("", InstagramEmbedPayload.thumbnailFor("", ""))
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
