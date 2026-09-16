package com.muddassir.clearview.goodpost.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire/storage contract for a Good Post session.
 *
 * Every assertion here is about a failure mode that would otherwise be
 * invisible until a user hit it: a field rename that signs everyone out, a
 * missing token that stores a half-session, or an expiry that is checked
 * without slack and 401s a request that was valid when it was built.
 */
class GoodPostSessionCodecTest {

    private fun session(
        expiresAt: Long = 1_000_000L,
        access: String = "access-token",
        refresh: String = "refresh-token",
        userId: String = "user-1"
    ) = GoodPostSession(
        accessToken = access,
        refreshToken = refresh,
        expiresAtEpochMs = expiresAt,
        userId = userId,
        displayName = "Ayesha",
        email = "ayesha@example.test"
    )

    // ── Storage round trip ──────────────────────────────────────────────

    @Test
    fun `a stored session survives an encode-decode round trip`() {
        val original = session()

        val decoded = GoodPostSessionCodec.decode(GoodPostSessionCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `reads back as signed out rather than throwing`() {
        assertNull(GoodPostSessionCodec.decode(null))
        assertNull(GoodPostSessionCodec.decode(""))
        assertNull(GoodPostSessionCodec.decode("   "))
        assertNull(GoodPostSessionCodec.decode("not json at all"))
        assertNull(GoodPostSessionCodec.decode("[]"))
    }

    @Test
    fun `rejects a stored blob missing any token`() {
        // A partially written blob must never become a "session" with an empty
        // token, which would 401 on every screen instead of prompting sign-in.
        val noAccess = JSONObject().apply {
            put("access_token", "")
            put("refresh_token", "r")
            put("user_id", "u")
        }
        val noRefresh = JSONObject().apply {
            put("access_token", "a")
            put("refresh_token", "")
            put("user_id", "u")
        }
        val noUser = JSONObject().apply {
            put("access_token", "a")
            put("refresh_token", "r")
            put("user_id", "")
        }

        assertNull(GoodPostSessionCodec.decode(noAccess.toString()))
        assertNull(GoodPostSessionCodec.decode(noRefresh.toString()))
        assertNull(GoodPostSessionCodec.decode(noUser.toString()))
    }

    // ── Expiry ──────────────────────────────────────────────────────────

    @Test
    fun `treats a token inside the skew window as already expired`() {
        val now = 1_000_000L

        // Expires in 30s, but the skew is 60s: a request built now could still
        // land after it dies, so it must be refreshed first.
        assertTrue(GoodPostSessionCodec.isExpired(session(expiresAt = now + 30_000), now))

        // Expires in 5 minutes: comfortably outside the skew.
        assertFalse(GoodPostSessionCodec.isExpired(session(expiresAt = now + 300_000), now))
    }

    @Test
    fun `treats an already-dead token as expired`() {
        val now = 1_000_000L
        assertTrue(GoodPostSessionCodec.isExpired(session(expiresAt = now - 1), now))
    }

    // ── Server response parsing ─────────────────────────────────────────

    @Test
    fun `parses the auth response into a session`() {
        val now = 1_000_000L
        val body = JSONObject(
            """
            {
              "accessToken": "a",
              "refreshToken": "r",
              "expiresIn": 900,
              "user": { "id": "u1", "displayName": "Ayesha", "email": "a@example.test" }
            }
            """.trimIndent()
        )

        val parsed = GoodPostSessionCodec.fromAuthResponse(body, now)

        assertNotNull(parsed)
        assertEquals("a", parsed?.accessToken)
        assertEquals("r", parsed?.refreshToken)
        assertEquals("u1", parsed?.userId)
        assertEquals("Ayesha", parsed?.displayName)
        assertEquals(now + 900_000L, parsed?.expiresAtEpochMs)
    }

    @Test
    fun `falls back to the server default ttl when expiresIn is absent`() {
        val now = 1_000_000L
        val body = JSONObject(
            """{"accessToken":"a","refreshToken":"r","user":{"id":"u1"}}"""
        )

        val parsed = GoodPostSessionCodec.fromAuthResponse(body, now)

        // A zero or missing ttl would create a session that is expired on
        // arrival, so the access token would never be usable.
        assertEquals(now + GoodPostSessionCodec.DEFAULT_TTL_SECONDS * 1000L, parsed?.expiresAtEpochMs)
    }

    @Test
    fun `refuses a response that cannot be a session`() {
        val now = 1_000_000L

        // No tokens at all.
        assertNull(GoodPostSessionCodec.fromAuthResponse(JSONObject("""{"user":{"id":"u"}}"""), now))
        // No user object.
        assertNull(
            GoodPostSessionCodec.fromAuthResponse(
                JSONObject("""{"accessToken":"a","refreshToken":"r"}"""),
                now
            )
        )
        // User with no id.
        assertNull(
            GoodPostSessionCodec.fromAuthResponse(
                JSONObject("""{"accessToken":"a","refreshToken":"r","user":{}}"""),
                now
            )
        )
    }

    @Test
    fun `parses the account payload from me`() {
        val body = JSONObject(
            """{"user":{"id":"u1","displayName":"Ayesha","email":"a@example.test","status":"active"}}"""
        )

        val account = GoodPostSessionCodec.accountFromResponse(body)

        assertEquals("u1", account?.id)
        assertEquals("active", account?.status)
    }

    @Test
    fun `refuses an account payload with no user`() {
        assertNull(GoodPostSessionCodec.accountFromResponse(JSONObject("""{}""")))
        assertNull(GoodPostSessionCodec.accountFromResponse(JSONObject("""{"user":{}}""")))
    }

    // ── HTTP error mapping ──────────────────────────────────────────────

    @Test
    fun `prefers the server error code over a status guess`() {
        assertEquals(
            "phone_banned",
            errorCodeFrom(403, """{"error":"phone_banned"}""")
        )
        assertEquals(
            "refresh_token_reused",
            errorCodeFrom(401, """{"error":"refresh_token_reused","detail":"..."}""")
        )
    }

    @Test
    fun `falls back to the status when the body is absent or not json`() {
        // A proxy's HTML 502 and a bodyless 429 are both real; neither should
        // produce an empty code that matches no branch in the UI.
        assertEquals("server_error", errorCodeFrom(502, "<html>Bad Gateway</html>"))
        assertEquals("rate_limited", errorCodeFrom(429, null))
        assertEquals("unauthorized", errorCodeFrom(401, ""))
        assertEquals("http_error", errorCodeFrom(418, "{}"))
    }

    @Test
    fun `ignores a blank error code in an otherwise valid body`() {
        assertEquals("not_found", errorCodeFrom(404, """{"error":""}"""))
    }
}
