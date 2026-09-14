package com.muddassir.clearview.media.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves direct playable .mp4 video stream URLs for public Instagram posts and Reels
 * completely on-device without login, cookies, or account sessions.
 *
 * Uses Instagram's public embed endpoints (which Meta maintains for public web embedding).
 */
object InstagramStreamResolver {

    private const val TAG = "InstagramStreamResolver"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 12_000

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Resolves the direct .mp4 media stream URL for [shortcodeOrUrl].
     * Returns null if the post is an image post or resolution fails.
     *
     * Both the /p/ and /reel/ embed pages are tried, in both their captioned
     * and plain forms: the page that carries the video JSON varies by post
     * type, and older/newer embeds differ in which one they render.
     */
    suspend fun resolveStreamUrl(shortcodeOrUrl: String): String? = withContext(Dispatchers.IO) {
        val shortcode = extractShortcode(shortcodeOrUrl)
        if (shortcode.isBlank()) return@withContext null

        val candidateUrls = listOf(
            "https://www.instagram.com/p/$shortcode/embed/captioned/",
            "https://www.instagram.com/p/$shortcode/embed/",
            "https://www.instagram.com/reel/$shortcode/embed/captioned/",
            "https://www.instagram.com/reel/$shortcode/embed/"
        )

        for (candidateUrl in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(candidateUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

                    findStreamInPayload(html)?.let { clean ->
                        Log.d(TAG, "Resolved stream for $shortcode from $candidateUrl")
                        return@withContext clean
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed resolving stream for $shortcode from $candidateUrl: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }

        null
    }

    /**
     * The payload patterns that carry the post's mp4 URL, most specific first.
     * Public so the resolution rules are unit-testable without network access.
     * Returns the unescaped http(s) .mp4 URL, or null when none is present.
     */
    internal fun findStreamInPayload(html: String): String? {
        if (html.isBlank()) return null
        for (regex in STREAM_PATTERNS) {
            val match = regex.find(html)?.groupValues?.get(1) ?: continue
            val clean = unescapeUrl(match)
            if (clean.startsWith("http") && clean.contains(".mp4", ignoreCase = true)) {
                return clean
            }
        }
        // Last resort: a bare .mp4 CDN link, with no key in front of it.
        val raw = RAW_MP4.find(html)?.value?.let { unescapeUrl(it) }
        if (!raw.isNullOrBlank() && raw.startsWith("http")) return raw
        return null
    }

    private val STREAM_PATTERNS = listOf(
        // Embedded JSON: "video_url":"https:\/\/...mp4..."
        Regex(""""video_url"\s*:\s*"([^"]+)""""),
        // JSON-LD / og metadata: "contentUrl":"...mp4" or og:video content.
        Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
        Regex("""property=["']og:video["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        // video_versions / progressive download entries: "url":"...mp4"
        Regex(""""url"\s*:\s*"([^"]+\.mp4[^"]*)"""", RegexOption.IGNORE_CASE),
        // HTML5 <video src> / <source src>
        Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    )

    // A bare CDN link, with or without the JSON-style `\/` escaping. The
    // backslashes are ALLOWED in the character classes here (the match is
    // unescaped afterwards) — excluding them is what made the old pattern miss
    // every escaped URL.
    private val RAW_MP4 = Regex(
        """https?:(?:\\?/){2}[^\s"'<>]+?\.mp4[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )

    fun extractShortcode(input: String): String {
        val trimmed = input.trim().removePrefix("ig_")
        if (!trimmed.contains("/")) {
            // Already a shortcode (e.g. C_abc123)
            return trimmed.substringBefore('?').substringBefore('#')
        }
        val patterns = listOf(
            Regex("""instagram\.com/(?:p|reel|tv)/([^/?#&]+)"""),
            Regex("""instagr\.am/(?:p|reel|tv)/([^/?#&]+)""")
        )
        for (p in patterns) {
            val match = p.find(trimmed)
            if (match != null) return match.groupValues[1]
        }
        return trimmed.substringAfterLast('/').substringBefore('?').substringBefore('#')
    }

    private fun unescapeUrl(raw: String): String {
        return raw.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .trim()
    }
}
