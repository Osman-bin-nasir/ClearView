package com.muddassir.clearview.media.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlParserTest {

    @Test
    fun `extracts id from watch urls`() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM")
        )
        // Parameter order must not matter.
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/watch?app=desktop&feature=share&v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun `extracts id from youtu dot be and shorts`() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ")
        )
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("https://www.youtube.com/v/dQw4w9WgXcQ"))
    }

    /**
     * The regression this parser was written for: YouTube's Share sheet gives
     * `/live/<id>?si=…` for a broadcast, which the old regex set did not know —
     * "Add video by URL" rejected a perfectly good link.
     */
    @Test
    fun `extracts id from live urls`() {
        assertEquals(
            "iP2PlX4ZAAY",
            extractYouTubeVideoId("https://www.youtube.com/live/iP2PlX4ZAAY?si=ckmlfXyTB5npP41y")
        )
        assertEquals(
            "iP2PlX4ZAAY",
            extractYouTubeVideoId("https://m.youtube.com/live/iP2PlX4ZAAY")
        )
        val ref = parseYouTubeRef("https://www.youtube.com/live/iP2PlX4ZAAY?si=ckmlfXyTB5npP41y")
        assertEquals(YouTubeUrlKind.LIVE, ref?.kind)
        assertEquals("iP2PlX4ZAAY", ref?.videoId)
    }

    @Test
    fun `extracts bare eleven character ids`() {
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractYouTubeVideoId("  dQw4w9WgXcQ  "))
        assertEquals(YouTubeUrlKind.BARE_ID, parseYouTubeRef("dQw4w9WgXcQ")?.kind)
    }

    @Test
    fun `reports the kind of every link form`() {
        assertEquals(
            YouTubeUrlKind.WATCH,
            parseYouTubeRef("https://www.youtube.com/watch?v=dQw4w9WgXcQ")?.kind
        )
        assertEquals(
            YouTubeUrlKind.WATCH,
            parseYouTubeRef("https://www.youtube.com/?v=dQw4w9WgXcQ")?.kind
        )
        assertEquals(
            YouTubeUrlKind.SHORT,
            parseYouTubeRef("https://www.youtube.com/shorts/dQw4w9WgXcQ")?.kind
        )
        assertEquals(
            YouTubeUrlKind.SHORT_LINK,
            parseYouTubeRef("https://youtu.be/dQw4w9WgXcQ")?.kind
        )
        assertEquals(
            YouTubeUrlKind.EMBED,
            parseYouTubeRef("https://www.youtube.com/embed/dQw4w9WgXcQ")?.kind
        )
        // Only a /shorts/ link is a Short.
        assertTrue(parseYouTubeRef("https://www.youtube.com/shorts/dQw4w9WgXcQ")!!.isShort)
        assertTrue(!parseYouTubeRef("https://youtu.be/dQw4w9WgXcQ")!!.isShort)
    }

    @Test
    fun `decodes start offsets`() {
        assertEquals(30, parseYouTubeRef("https://youtu.be/dQw4w9WgXcQ?t=30")?.startSeconds)
        assertEquals(30, parseYouTubeRef("https://youtu.be/dQw4w9WgXcQ?t=30s")?.startSeconds)
        assertEquals(90, parseYouTubeRef("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s")?.startSeconds)
        assertEquals(
            3723,
            parseYouTubeRef("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1h2m3s")?.startSeconds
        )
        assertEquals(
            90,
            parseYouTubeRef("https://www.youtube.com/watch?v=dQw4w9WgXcQ&start=90")?.startSeconds
        )
        // No offset, or an unparseable one, simply means "from the beginning".
        assertEquals(0, parseYouTubeRef("https://youtu.be/dQw4w9WgXcQ")?.startSeconds)
        assertEquals(0, parseYouTubeRef("https://youtu.be/dQw4w9WgXcQ?t=bogus")?.startSeconds)
    }

    @Test
    fun `accepts links pasted with surrounding text`() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("Watch this: https://youtu.be/dQw4w9WgXcQ — it's great")
        )
    }

    @Test
    fun `accepts scheme-less urls`() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("www.youtube.com/shorts/dQw4w9WgXcQ")
        )
    }

    @Test
    fun `returns null for garbage`() {
        assertNull(extractYouTubeVideoId(""))
        assertNull(extractYouTubeVideoId("   "))
        assertNull(parseYouTubeRef(null))
        assertNull(extractYouTubeVideoId("not a url"))
        assertNull(extractYouTubeVideoId("https://example.com/foo"))
        assertNull(extractYouTubeVideoId("https://www.youtube.com/channel/UCabc"))
        assertNull(extractYouTubeVideoId("short"))
    }

    /**
     * The old `[?&]v=` regex matched on ANY host, so a non-YouTube page with a
     * `v` parameter was treated as a YouTube video.
     */
    @Test
    fun `rejects a v parameter on a non-youtube host`() {
        assertNull(extractYouTubeVideoId("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(extractYouTubeVideoId("https://vimeo.com/page?v=dQw4w9WgXcQ"))
        assertNull(extractYouTubeVideoId("https://notyoutube.com.evil.test/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `rejects ids of the wrong length`() {
        assertNull(extractYouTubeVideoId("https://www.youtube.com/watch?v=tooshort"))
        assertNull(extractYouTubeVideoId("https://www.youtube.com/shorts/waytoolongvideoid"))
        assertNull(extractYouTubeVideoId("https://youtu.be/"))
    }

    @Test
    fun `rejects playlist and channel urls`() {
        assertNull(extractYouTubeVideoId("https://www.youtube.com/playlist?list=PL1234567890"))
        assertNull(extractYouTubeVideoId("https://www.youtube.com/@somechannel"))
        assertNull(extractYouTubeVideoId("https://youtu.be/"))
    }
}
