package com.muddassir.clearview.goodpost.data

import org.json.JSONObject

/**
 * A signed-in Good Post session, exactly as the backend issued it.
 *
 * Only Good Post uses this. The rest of ClearView keeps working with no
 * account at all (§2) — there is no global login, and nothing here is read
 * outside the Good Post tab.
 */
data class GoodPostSession(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis at which [accessToken] stops being accepted. */
    val expiresAtEpochMs: Long,
    val userId: String,
    val displayName: String,
    val email: String
)

/** The caller's own account, never anyone else's (§38). */
data class GoodPostAccount(
    val id: String,
    val displayName: String,
    val email: String,
    val status: String
)

/**
 * Turning backend JSON into a [GoodPostSession], and back for storage.
 *
 * Kept as pure functions on purpose: this is the contract boundary with the
 * server and the one place a field rename silently signs every user out, so it
 * is unit-tested directly rather than only through the UI.
 */
internal object GoodPostSessionCodec {

    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_AT = "expires_at_ms"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_NAME = "display_name"
    private const val KEY_EMAIL = "email"

    /** Used when the server omits `expiresIn`; mirrors the backend default. */
    const val DEFAULT_TTL_SECONDS: Long = 900L

    /**
     * Refresh slightly BEFORE the token actually dies. Without this slack a
     * token that is valid when the request is built can expire in flight, and
     * the user sees a spurious 401 on an otherwise fine connection.
     */
    const val EXPIRY_SKEW_MS: Long = 60_000L

    fun encode(session: GoodPostSession): String = JSONObject().apply {
        put(KEY_ACCESS, session.accessToken)
        put(KEY_REFRESH, session.refreshToken)
        put(KEY_EXPIRES_AT, session.expiresAtEpochMs)
        put(KEY_USER_ID, session.userId)
        put(KEY_NAME, session.displayName)
        put(KEY_EMAIL, session.email)
    }.toString()

    /**
     * Decode a stored session, or null if anything is missing or malformed.
     * A partially written blob must read as "signed out", never as a session
     * with an empty token that then 401s on every screen.
     */
    fun decode(raw: String?): GoodPostSession? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            val access = json.optString(KEY_ACCESS)
            val refresh = json.optString(KEY_REFRESH)
            val userId = json.optString(KEY_USER_ID)
            if (access.isBlank() || refresh.isBlank() || userId.isBlank()) return null

            GoodPostSession(
                accessToken = access,
                refreshToken = refresh,
                expiresAtEpochMs = json.optLong(KEY_EXPIRES_AT, 0L),
                userId = userId,
                displayName = json.optString(KEY_NAME),
                email = json.optString(KEY_EMAIL)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** True when the access token is past its usable life (with skew). */
    fun isExpired(
        session: GoodPostSession,
        nowMs: Long,
        skewMs: Long = EXPIRY_SKEW_MS
    ): Boolean = session.expiresAtEpochMs - skewMs <= nowMs

    /**
     * Parse the `{ accessToken, refreshToken, expiresIn, user }` body returned
     * by /register, /signin and /refresh. Returns null rather than throwing so
     * an unexpected payload surfaces as a retryable failure, not a crash.
     */
    fun fromAuthResponse(body: JSONObject, nowMs: Long): GoodPostSession? {
        val access = body.optString("accessToken")
        val refresh = body.optString("refreshToken")
        val user = body.optJSONObject("user") ?: return null
        val userId = user.optString("id")

        if (access.isBlank() || refresh.isBlank() || userId.isBlank()) return null

        val ttlSeconds = body.optLong("expiresIn", 0L).let {
            if (it > 0L) it else DEFAULT_TTL_SECONDS
        }

        return GoodPostSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMs = nowMs + ttlSeconds * 1000L,
            userId = userId,
            displayName = user.optString("displayName"),
            email = user.optString("email")
        )
    }

    /** Parse the `{ user }` body returned by /auth/me. */
    fun accountFromResponse(body: JSONObject): GoodPostAccount? {
        val user = body.optJSONObject("user") ?: return null
        val id = user.optString("id")
        if (id.isBlank()) return null

        return GoodPostAccount(
            id = id,
            displayName = user.optString("displayName"),
            email = user.optString("email"),
            status = user.optString("status")
        )
    }
}
