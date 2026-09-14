package com.muddassir.clearview.media.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The helpers that decide where a feed item is rendered: Instagram Reels and
 * videos must behave like YouTube LONG videos (feed row, resume, Continue
 * Watching, offline audio) while Instagram photos/carousels are stills, and
 * only YouTube Shorts may enter the Shorts row / vertical viewer.
 */
class MediaVideoInstagramTest {

    private fun video(
        videoId: String,
        platform: MediaPlatform = MediaPlatform.YOUTUBE,
        type: InstagramMediaType? = null,
        isShort: Boolean = false,
        mediaUrl: String? = null,
        instagramUrl: String? = null
    ) = MediaVideo(
        videoId = videoId,
        title = "t",
        channelId = "c",
        channelName = "c",
        publishedAtEpochMillis = 0L,
        thumbnailUrl = "",
        isShort = isShort,
        platform = platform,
        instagramType = type,
        mediaUrl = mediaUrl,
        instagramUrl = instagramUrl
    )

    @Test
    fun `an instagram reel is a video, never a short`() {
        // The parsers store `isShort = true` for Reels (Instagram's own word
        // for a short clip) — the app must still treat it as a normal video.
        val reel = video(
            videoId = "ig_CxYz123",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.REEL,
            isShort = true,
            instagramUrl = "https://www.instagram.com/reel/CxYz123/"
        )
        assertTrue(reel.isInstagram)
        assertTrue(reel.isInstagramVideo)
        assertFalse(reel.isInstagramImage)
        assertFalse(reel.isShortsEntry)
    }

    @Test
    fun `an instagram video post with a direct mp4 is a video`() {
        val post = video(
            videoId = "ig_Vid42",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.VIDEO,
            isShort = true,
            mediaUrl = "https://scontent.cdninstagram.com/v/t50/vid42.mp4"
        )
        assertTrue(post.isInstagramVideo)
        assertFalse(post.isInstagramImage)
    }

    @Test
    fun `an instagram photo is a still, even without a type`() {
        val photo = video(
            videoId = "ig_Photo7",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.IMAGE
        )
        val carousel = video(
            videoId = "ig_Car9",
            platform = MediaPlatform.INSTAGRAM,
            type = InstagramMediaType.CAROUSEL
        )
        assertTrue(photo.isInstagramImage)
        assertTrue(carousel.isInstagramImage)
        assertFalse(photo.isInstagramVideo)
        assertFalse(carousel.isShortsEntry)
    }

    @Test
    fun `an untagged ig_ id is still recognised as instagram`() {
        // Older caches / manually added posts only carry the id prefix.
        val legacy = video(videoId = "ig_Legacy1", type = InstagramMediaType.REEL)
        assertTrue(legacy.isInstagram)
        assertTrue(legacy.isInstagramVideo)
        assertFalse(legacy.isShortsEntry)
    }

    @Test
    fun `youtube shorts stay shorts and youtube videos stay videos`() {
        val short = video(videoId = "abc", isShort = true)
        val long = video(videoId = "def")
        assertTrue(short.isShortsEntry)
        assertFalse(short.isInstagram)
        assertFalse(short.isInstagramImage)
        assertFalse(long.isShortsEntry)
    }
}
