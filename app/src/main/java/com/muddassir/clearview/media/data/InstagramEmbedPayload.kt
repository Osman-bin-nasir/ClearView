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
     * can be certain about are rejected (a non-http scheme, or an Instagram
     * PAGE) — every other host is still attempted, so an unusual CDN is never
     * broken by a guess.
     *
     * The post media endpoint is explicitly NOT rejected: it is not a web page
     * at all, it redirects straight to the post's JPEG (see [mediaEndpointUrl]).
     * Rejecting it was the reason Instagram Reels had no thumbnails.
     */
    fun isNonImageUrl(url: String): Boolean {
        if (!url.startsWith("http")) return true
        val lower = url.lowercase()
        if (isImageUrl(lower)) return false
        if (isMediaEndpoint(lower)) return false
        return lower.contains("instagram.com/")
    }

    /**
     * The post's still image, as served by Instagram itself:
     * `instagram.com/p/<code>/media/?size=l`.
     *
     * That endpoint is a pure image redirect — `media?size=l` answers 301 to
     * the slash form, which answers 302 to the post's own JPEG on Meta's CDN
     * (`…fbcdn.net/…_n.jpg`, verified by hand). For a Reel that JPEG is its
     * first frame, so it doubles as the video poster.
     *
     * This is the ONLY poster URL that exists for every public post, and the
     * RSS bridges use exactly it (and nothing else) as a Reel's
     * `<video poster=…>`. Building it from the shortcode is therefore the
     * fallback that guarantees every Instagram item has a thumbnail even when
     * its source omitted one. Null when [shortcode] is not a plausible
     * shortcode.
     */
    fun mediaEndpointUrl(shortcode: String?): String? {
        val code = shortcode?.trim().orEmpty()
        if (!SHORTCODE.matches(code)) return null
        return "https://www.instagram.com/p/$code/media/?size=l"
    }

    /**
     * True for a Meta CDN image URL carrying a SIGNED, TIME-LIMITED grant
     * (`oh=`/`oe=`/`_nc_ohc`) — the URLs Instagram hands out in its feeds.
     *
     * They stop working when the signature expires, answering
     * `403 URL signature mismatch` (measured on a cached carousel tile), which
     * is why a post can show a broken/blank thumbnail days after it was
     * cached even though nothing about the post changed. The stable
     * [mediaEndpointUrl] mints a fresh signature on every request instead.
     */
    fun isExpiringImageUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        val lower = url.lowercase()
        val host = runCatching { java.net.URL(url).host.lowercase() }.getOrNull() ?: return false
        if (!host.contains("cdninstagram") && !host.contains("fbcdn")) return false
        return lower.contains("oh=") || lower.contains("oe=") || lower.contains("_nc_ohc")
    }

    /**
     * The URL an image loader should actually fetch for [shortcode]:
     *  1. the source's own thumbnail when it is a real image that will NOT
     *     expire (normalised to the canonical form so one redirect is skipped);
     *  2. otherwise the stable media endpoint, which mints a FRESH signature
     *     every time — this is what keeps Instagram photos and carousels
     *     loading after their feed-supplied CDN signature has expired;
     *  3. otherwise the raw URL (unknown hosts are still attempted rather than
     *     discarded).
     *
     * Empty only when the source had nothing usable AND [shortcode] is
     * unusable.
     */
    fun thumbnailFor(shortcode: String?, rawThumbnail: String?): String {
        val raw = rawThumbnail?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            if (isImageUrl(raw) && !isExpiringImageUrl(raw)) return normaliseMediaEndpoint(raw)
            mediaEndpointUrl(shortcode)?.let { return it }
            if (!isNonImageUrl(raw)) return normaliseMediaEndpoint(raw)
        }
        return mediaEndpointUrl(shortcode).orEmpty()
    }

    /** A plausible Instagram shortcode (post/reel id). */
    private val SHORTCODE = Regex("^[A-Za-z0-9_-]{5,20}$")

    private fun isMediaEndpoint(lowerUrl: String): Boolean =
        lowerUrl.contains("instagram.com/") && lowerUrl.contains("/media")

    /** `…/p/<code>/media?size=l` -> `…/p/<code>/media/?size=l` (one hop less). */
    private fun normaliseMediaEndpoint(url: String): String {
        val lower = url.lowercase()
        if (!isMediaEndpoint(lower) || lower.contains("/media/?")) return url
        return url.replace("/media?", "/media/?")
    }
}
