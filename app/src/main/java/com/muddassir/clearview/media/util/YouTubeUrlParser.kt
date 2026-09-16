package com.muddassir.clearview.media.util

/**
 * What a parsed YouTube URL points at. The KIND matters beyond the id: a
 * `/shorts/` link is a vertical Short (the player treats it as such), while
 * `/live/` is a broadcast — the app previously threw that information away and
 * marked every manually added video as a long (non-Short) video.
 */
enum class YouTubeUrlKind {
    /** `/watch?v=ID`, `music.youtube.com/watch?v=ID`, `?v=ID`. */
    WATCH,

    /** `/shorts/ID` — a vertical Short. */
    SHORT,

    /** `/live/ID` — a live broadcast (or its VOD after the stream ends). */
    LIVE,

    /** `/embed/ID`, `/v/ID`. */
    EMBED,

    /** `youtu.be/ID`. */
    SHORT_LINK,

    /** The bare 11-character id, pasted on its own. */
    BARE_ID
}

/**
 * A YouTube video reference: the id, what kind of URL it came from, and the
 * `t=` / `start=` offset (seconds) when the link carried one.
 */
data class YouTubeRef(
    val videoId: String,
    val kind: YouTubeUrlKind,
    /** Start offset in seconds from `?t=` / `&start=`, or 0 when absent. */
    val startSeconds: Int = 0
) {
    /** True for a `/shorts/` link. */
    val isShort: Boolean get() = kind == YouTubeUrlKind.SHORT
}

/**
 * THE single YouTube URL parser for the app.
 *
 * The old implementation was four independent regexes with no host check, and
 * it did not know `/live/` at all — so
 * `https://www.youtube.com/live/iP2PlX4ZAAY?si=ckmlfXyTB5npP41y` (the form
 * YouTube's own Share sheet produces for a broadcast) was rejected by
 * "Add video by URL". It also matched `?v=` on ANY host, so
 * `https://example.com/page?v=xxxxxxxxxxx` looked like a YouTube video.
 *
 * This parser splits the URL into host / path / query, validates the host
 * against the real YouTube domains, and understands every link form YouTube
 * hands out:
 *
 *  - `youtube.com/watch?v=ID` (also `m.`, `music.`, `www.`, and `?v=ID` with
 *    the parameters in any order, plus `youtu.be/ID`)
 *  - `youtube.com/shorts/ID`
 *  - `youtube.com/live/ID`
 *  - `youtube.com/embed/ID`, `youtube.com/v/ID`
 *  - `youtube-nocookie.com/embed/ID`
 *  - a bare 11-character id
 *  - a whole paragraph containing a link ("Watch this: <url>") — the first
 *    YouTube URL inside it is used
 *
 * Query parameters YouTube adds (`?si=`, `&feature=share`, `&list=`, …) are
 * ignored; `t=` / `start=` are decoded, including the `1h2m3s` form. Pure
 * string logic — unit-testable on the JVM (no Android `Uri`).
 */
fun parseYouTubeRef(input: String?): YouTubeRef? {
    val text = input?.trim().orEmpty()
    if (text.isEmpty()) return null
    // A bare id is a valid input on its own ("dQw4w9WgXcQ").
    if (VIDEO_ID.matches(text)) return YouTubeRef(text, YouTubeUrlKind.BARE_ID)
    parseUrl(text)?.let { return it }
    // Pasted share text ("Watch this: https://youtu.be/ID") — parse the first
    // URL inside it instead of rejecting the whole field.
    URL_IN_TEXT.find(text)?.let { return parseUrl(it.value) }
    return null
}

/**
 * Backwards-compatible id-only entry point used across the app (the same
 * centralised parser — never a second regex).
 */
fun extractYouTubeVideoId(input: String): String? = parseYouTubeRef(input)?.videoId

/** Hosts YouTube serves video pages from. */
private val YOUTUBE_HOSTS = setOf(
    "youtube.com",
    "youtu.be",
    "youtube-nocookie.com",
    "youtubekids.com"
)

private const val VIDEO_ID_LENGTH = 11
private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{$VIDEO_ID_LENGTH}$")
private val URL_IN_TEXT = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

private fun isYouTubeHost(host: String): Boolean {
    val h = host.lowercase()
    return YOUTUBE_HOSTS.any { h == it || h.endsWith(".$it") }
}

private fun parseUrl(raw: String): YouTubeRef? {
    // Drop the scheme when there is one ("youtube.com/watch?v=…" is accepted
    // without it, which is how some apps copy links).
    val withoutScheme = raw.substringAfter("://", missingDelimiterValue = raw)
    val hostEnd = withoutScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val hostRaw = if (hostEnd < 0) withoutScheme else withoutScheme.substring(0, hostEnd)
    val rest = if (hostEnd < 0) "" else withoutScheme.substring(hostEnd)

    // A host has to look like one. When the input carries a host it MUST be a
    // YouTube domain (so example.com/watch?v=… is rejected); when it doesn't
    // (a bare "watch?v=ID") the whole string is treated as path + query.
    val hasHost = hostRaw.contains('.')
    if (hasHost && !isYouTubeHost(hostRaw)) return null
    val target = if (hasHost) rest else withoutScheme

    val path = target.substringBefore('?').substringBefore('#')
    val query = target.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    val segments = path.split('/').filter { it.isNotEmpty() }

    // youtu.be/<id> — the id is the first path segment.
    if (hasHost && (hostRaw.equals("youtu.be", true) || hostRaw.lowercase().endsWith(".youtu.be"))) {
        val id = segments.firstOrNull() ?: return null
        return ref(id, YouTubeUrlKind.SHORT_LINK, query)
    }

    val match: Pair<String?, YouTubeUrlKind>? = when (segments.firstOrNull()?.lowercase()) {
        "watch" -> queryParam(query, "v")?.let { it to YouTubeUrlKind.WATCH }
        "shorts" -> segments.getOrNull(1)?.let { it to YouTubeUrlKind.SHORT }
        "live" -> segments.getOrNull(1)?.let { it to YouTubeUrlKind.LIVE }
        "embed" -> segments.getOrNull(1)?.let { it to YouTubeUrlKind.EMBED }
        "v", "e" -> segments.getOrNull(1)?.let { it to YouTubeUrlKind.EMBED }
        // No recognised path: a video id in the query is still a watch link
        // (e.g. youtube.com/?v=ID). Anything else (a channel, a playlist, a
        // handle) has no video id and is rejected.
        else -> queryParam(query, "v")?.let { it to YouTubeUrlKind.WATCH }
    }
    val candidate = match?.first ?: return null
    return ref(candidate, match.second, query)
}

/** Builds the reference, rejecting anything that is not a real video id. */
private fun ref(candidate: String?, kind: YouTubeUrlKind, query: String): YouTubeRef? {
    val id = candidate?.trim().orEmpty()
    if (!VIDEO_ID.matches(id)) return null
    return YouTubeRef(id, kind, parseStartSeconds(query))
}

/** First value of [name] in an already-split `a=1&b=2` query string. */
private fun queryParam(query: String, name: String): String? {
    if (query.isEmpty()) return null
    return query.split('&')
        .firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.takeIf { it.isNotEmpty() }
}

/**
 * `t` / `start` as seconds: `t=90`, `t=90s`, `t=1m30s`, `t=1h2m3s`, `start=90`.
 * Anything unparseable means "no offset" (0) rather than a rejected link.
 */
private fun parseStartSeconds(query: String): Int {
    val raw = queryParam(query, "t") ?: queryParam(query, "start") ?: return 0
    if (raw.all { it.isDigit() }) return raw.toIntOrNull()?.coerceAtLeast(0) ?: 0
    var total = 0
    var current = 0
    var sawUnit = false
    for (ch in raw) {
        when {
            ch.isDigit() -> current = current * 10 + (ch - '0')
            ch == 'h' -> { total += current * 3600; current = 0; sawUnit = true }
            ch == 'm' -> { total += current * 60; current = 0; sawUnit = true }
            ch == 's' -> { total += current; current = 0; sawUnit = true }
            else -> return 0
        }
    }
    return if (sawUnit) total else 0
}
