package com.muddassir.clearview.media.ui

import com.muddassir.clearview.media.model.InstagramMediaType
import com.muddassir.clearview.media.model.MediaPlatform
import com.muddassir.clearview.media.model.MediaVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The share URL must be the item's own canonical link: a clean Instagram
 * permalink (no tracking query params, never a YouTube fallback) for Instagram
 * items, and the standard YouTube watch URL for everything else.
 */
class ShareUrlTest {

    private fun video(
        videoId: String,
        platform: MediaPlatform = MediaPlatform.YOUTUBE,
        type: InstagramMediaType? = null,
        instagramUrl: String? = null
    ) = MediaVideo(
        videoId = videoId,
        title = "t",
        channelId = "c",
        channelName = "c",
        publishedAtEpochMillis = 0L,
        thumbnailUrl = "",
        platform = platform,
        instagramType = type,
        instagramUrl = instagramUrl
    )

    @Test
    fun `instagram reel shares a clean canonical url without tracking params`() {
        val url = shareUrlFor(
            video(
                videoId = "ig_CxYz123",
                platform = MediaPlatform.INSTAGRAM,
                type = InstagramMediaType.REEL,
                instagramUrl =
                    "https://www.instagram.com/reel/CxYz123/?igsh=abc123&utm_source=ig_web_copy_link"
            )
        )
        assertEquals("https://www.instagram.com/reel/CxYz123/", url)
        assertFalse(url.contains("igsh"))
        assertFalse(url.contains("utm_source"))
        assertFalse(url.contains("youtube"))
    }

    @Test
    fun `instagram image post shares the p permalink`() {
        val url = shareUrlFor(
            video(
                videoId = "ig_Post99",
                platform = MediaPlatform.INSTAGRAM,
                type = InstagramMediaType.IMAGE,
                instagramUrl = "https://www.instagram.com/p/Post99/?igsh=xyz"
            )
        )
        assertEquals("https://www.instagram.com/p/Post99/", url)
    }

    @Test
    fun `instagram item without an explicit url derives the shortcode from the id`() {
        val url = shareUrlFor(
            video(
                videoId = "ig_FromId55",
                platform = MediaPlatform.INSTAGRAM,
                type = InstagramMediaType.VIDEO
            )
        )
        assertEquals("https://www.instagram.com/p/FromId55/", url)
    }

    @Test
    fun `youtube videos keep the watch url`() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            shareUrlFor(video(videoId = "dQw4w9WgXcQ"))
        )
    }
}
