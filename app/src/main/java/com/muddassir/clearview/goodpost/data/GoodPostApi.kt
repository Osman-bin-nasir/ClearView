package com.muddassir.clearview.goodpost.data

import android.util.Log
import com.muddassir.clearview.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The outcome of a backend call.
 *
 * [Failed] and [Unreachable] are deliberately distinct. "The server said no"
 * and "we never reached the server" need different words in front of a user,
 * and §36 requires the app not to claim success it has not been told about —
 * which starts with not conflating a refusal with an outage.
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** The server answered with a machine-readable error code. */
    data class Failed(val status: Int, val code: String) : ApiResult<Nothing>
    /** No usable answer: offline, DNS, TLS, timeout, or an unparseable body. */
    data object Unreachable : ApiResult<Nothing>
}

/**
 * Maps a non-2xx response to the error code the UI branches on.
 *
 * Pure, so it is unit-tested directly. Two rules it enforces: a body carrying
 * an `error` code is always preferred over a guess from the status line, and
 * when there is no body the status is still turned into something meaningful
 * rather than an empty string that would silently match no branch.
 */
internal fun errorCodeFrom(status: Int, rawBody: String?): String {
    if (!rawBody.isNullOrBlank()) {
        try {
            val code = JSONObject(rawBody).optString("error")
            if (code.isNotBlank()) return code
        } catch (e: Exception) {
            // Not JSON — a proxy error page, an HTML 502, and so on.
        }
    }
    return when (status) {
        400 -> "invalid_request"
        401 -> "unauthorized"
        403 -> "forbidden"
        404 -> "not_found"
        408 -> "timeout"
        413 -> "payload_too_large"
        429 -> "rate_limited"
        else -> if (status >= 500) "server_error" else "http_error"
    }
}

/**
 * Good Post authentication client (§35: `HttpURLConnection` + coroutines +
 * `org.json`, the pattern already used by `ClearViewBackendClient`,
 * `MediaRepository` and `QuranApi`).
 *
 * No Retrofit, OkHttp or serialization library is introduced — §35 asks for
 * the existing stack, and this endpoint set is small enough that the shared
 * convention costs nothing.
 *
 * Targets `/api/v1/auth` on its OWN base URL, not the Block tab's moderation
 * backend: the two are separate services with separate deploy lifecycles, and
 * the version prefix keeps the two meanings of "channel" from colliding.
 */
class GoodPostApi(
    private val baseUrl: () -> String = { BuildConfig.GOODPOST_BASE_URL }
) {

    private companion object {
        const val TAG = "GoodPostApi"
        const val AUTH_PATH = "/api/v1/auth"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 512_000
    }

    /** True when a backend URL has been configured for this build. */
    val isConfigured: Boolean get() = baseUrl().isNotBlank()

    // ── Endpoints ───────────────────────────────────────────────────────

    /**
     * Register the intent to verify a number and take one slot out of the
     * hourly allowance. Called BEFORE Firebase sends the SMS, so a banned or
     * rate-limited number never costs an SMS.
     */
    suspend fun requestOtp(phone: String, purpose: String): ApiResult<Unit> {
        val body = JSONObject().apply {
            put("phone", phone)
            put("purpose", purpose)
        }
        return when (val result = call("POST", "/otp/request", body)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    suspend fun signIn(idToken: String, deviceLabel: String?): ApiResult<GoodPostSession> {
        val body = JSONObject().apply { put("idToken", idToken) }
        return authCall("/signin", body, deviceLabel)
    }

    suspend fun register(
        idToken: String,
        displayName: String,
        email: String,
        deviceLabel: String?
    ): ApiResult<GoodPostSession> {
        val body = JSONObject().apply {
            put("idToken", idToken)
            put("displayName", displayName)
            put("email", email)
        }
        return authCall("/register", body, deviceLabel)
    }

    suspend fun refresh(refreshToken: String, deviceLabel: String?): ApiResult<GoodPostSession> {
        val body = JSONObject().apply { put("refreshToken", refreshToken) }
        return authCall("/refresh", body, deviceLabel)
    }

    /** Validate a stored token and read the account back. */
    suspend fun me(accessToken: String): ApiResult<GoodPostAccount> =
        withContext(Dispatchers.IO) {
            when (val result = call("GET", "/me", null, accessToken)) {
                is ApiResult.Ok ->
                    GoodPostSessionCodec.accountFromResponse(result.value)
                        ?.let { ApiResult.Ok(it) }
                        ?: ApiResult.Unreachable
                is ApiResult.Failed -> result
                ApiResult.Unreachable -> ApiResult.Unreachable
            }
        }

    suspend fun logout(accessToken: String, refreshToken: String): ApiResult<Unit> {
        val body = JSONObject().apply {
            put("refreshToken", refreshToken)
            put("all", false)
        }
        return when (val result = call("POST", "/logout", body, accessToken)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private suspend fun authCall(
        path: String,
        body: JSONObject,
        deviceLabel: String?
    ): ApiResult<GoodPostSession> = withContext(Dispatchers.IO) {
        when (val result = call("POST", path, body, null, deviceLabel)) {
            is ApiResult.Ok ->
                GoodPostSessionCodec.fromAuthResponse(result.value, System.currentTimeMillis())
                    ?.let { ApiResult.Ok(it) }
                    // A 2xx we cannot parse is a backend contract break, not a
                    // success. Reported as unreachable so the caller retries
                    // rather than storing a half-session.
                    ?: ApiResult.Unreachable
            is ApiResult.Failed -> result
            ApiResult.Unreachable -> ApiResult.Unreachable
        }
    }

    private fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        bearer: String? = null,
        deviceLabel: String? = null
    ): ApiResult<JSONObject> {
        val root = baseUrl().trimEnd('/')
        if (root.isBlank()) return ApiResult.Unreachable

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(root + AUTH_PATH + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ClearView-Android")
                bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                deviceLabel?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("X-Device-Label", it)
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val status = conn.responseCode
            if (status in 200..299) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                    .take(MAX_RESPONSE_BYTES)
                return ApiResult.Ok(JSONObject(text))
            }

            // Read the error body so the server's code wins over a status guess.
            val errorText = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?.take(MAX_RESPONSE_BYTES)

            return ApiResult.Failed(status, errorCodeFrom(status, errorText))
        } catch (e: Exception) {
            // Never log the request body — it carries the ID token.
            Log.d(TAG, "$method $path failed: ${e.javaClass.simpleName}")
            return ApiResult.Unreachable
        } finally {
            conn?.disconnect()
        }
    }
}
