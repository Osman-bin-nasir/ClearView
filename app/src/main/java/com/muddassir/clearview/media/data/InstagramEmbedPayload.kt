package com.muddassir.clearview.media.data

import org.json.JSONObject
import org.json.JSONTokener

/**
 * Pure, JVM-testable rules for reading a rendered Instagram embed: what counts
 * as a playable stream URL, what counts as a decodable image URL, and how the
 * WebView's probe result is decoded.
 *
 * Deliberately free of Android dependencies — the WebView plumbing lives in
 * [InstagramEmbedResolver], and this keeps the parsing rules verifiable by real
 * unit tests instead of only by launching the app.
 */
object InstagramEmbedPayload {

    /** A resolved embed: the playable mp4 (+ the post's poster, when known). */
    data class ParsedVideo(val videoUrl: String, val posterUrl: String?)

    /**
     * Decodes one probe result. `evaluateJavascript` returns the JS value as
     * JSON, so a string return arrives quoted-and-escaped; the inner JSON is
     * parsed afterwards. Returns null when the page had not rendered a playable
     * video yet (or rendered one we can't use).
     */
    fun parseProbe(raw: String?): ParsedVideo? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val inner = runCatching {
            (JSONTokener(raw).nextValue() as? String).orEmpty()
        }.getOrNull().orEmpty()
        if (inner.isBlank()) return null
        val obj = runCatching { JSONObject(inner) }.getOrNull() ?: return null
        val src = obj.optString("s", "")
        if (!isPlayableUrl(src)) return null
        val poster = obj.optString("p", "")
        return ParsedVideo(src, poster.takeIf { isImageUrl(it) })
    }

    /** A directly playable progressive media URL (never a blob:/data: URL). */
    fun isPlayableUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        val lower = url.lowercase()
        return lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".webm") ||
            lower.contains("/video/") || lower.contains("video_url")
    }

    /** True for a URL that is plausibly a decodable image (never a page/video). */
    fun isImageUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        val lower = url.lowercase()
        // Meta's CDN serves stills AND videos from the same hosts, so media
        // type is decided by the URL itself before any host is trusted.
        if (isPlayableUrl(lower)) return false
        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
            lower.contains(".webp") || lower.contains(".heic")
        ) {
            return true
        }
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrNull() ?: return false
        return host.contains("cdninstagram") || host.contains("fbcdn") ||
            host.contains("ytimg") || host.contains("ggpht")
    }

    /**
     * True for a URL that cannot possibly decode as an image, so an image loader
     * can reject it before making a request. Deliberately narrow: only URLs we
     * can be certain about are rejected (an Instagram HTML permalink such as
     * `instagram.com/p/<code>/media?size=l`, which some RSS bridges put in an
     * image slot, or a non-http scheme) — every other host is still attempted,
     * so an unusual CDN is never broken by a guess.
     */
    fun isNonImageUrl(url: String): Boolean {
        if (!url.startsWith("http")) return true
        val lower = url.lowercase()
        if (isImageUrl(lower)) return false
        return lower.contains("instagram.com/p/") && lower.contains("/media")
    }
}
