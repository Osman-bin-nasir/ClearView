package com.muddassir.clearview.goodpost.data

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Outcome of starting phone verification. */
sealed interface StartResult {
    /** An SMS is on its way; the user must type the code. */
    data class CodeSent(val verificationId: String) : StartResult

    /**
     * Instant verification: Google confirmed ownership without a code.
     * Common on a device holding the SIM, and it must still go through the
     * backend — a credential is not a session.
     */
    data class AutoVerified(val credential: PhoneAuthCredential) : StartResult

    data class Failed(val code: String) : StartResult
}

/** Outcome of exchanging a credential for a Good Post session. */
sealed interface AuthResult {
    data class SignedIn(val session: GoodPostSession) : AuthResult

    /** The number is verified but has no account yet — collect a name/email. */
    data class NeedsRegistration(val idToken: String) : AuthResult

    /**
     * [code] is a server error code (`phone_banned`, `otp_rate_limited`, …),
     * `unreachable` for a transport failure, or `not_configured` when the build
     * has no backend URL.
     */
    data class Failed(val code: String) : AuthResult
}

/**
 * Good Post authentication (§2, §3, §19).
 *
 * The division of labour, stated once so it is not re-derived per screen:
 *
 *  - **Firebase** proves the user controls the mobile number. Nothing else.
 *  - **The ClearView backend** decides whether that number may have an account,
 *    issues the session, and is the only authority on bans.
 *  - **This class** glues them together and never treats a local success as a
 *    real one: §36 forbids reporting follow/react/post/upload as succeeded
 *    before the server confirms, and the same rule starts here — a Firebase
 *    credential alone signs nobody in.
 */
class GoodPostAuthRepository(
    context: Context,
    private val api: GoodPostApi = GoodPostApi()
) {

    private companion object {
        const val TAG = "GoodPostAuth"
        const val SMS_TIMEOUT_SECONDS = 60L

        /** Challenge purposes the backend accepts (mirrors its CHECK clause). */
        const val PURPOSE_SIGN_IN = "signin"
        const val PURPOSE_REGISTER = "register"

        /**
         * What the server must be told when signing in, so the account's device
         * list is readable. Not an identifier — just a manufacturer/model pair.
         */
        val DEVICE_LABEL: String =
            listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
    }

    private val store = SecureTokenStore(context)
    private val firebaseAuth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** False when this build has no Good Post backend URL configured. */
    val isConfigured: Boolean get() = api.isConfigured

    fun currentSession(): GoodPostSession? = store.load()

    // ── Step 1: start verification ──────────────────────────────────────

    /**
     * Claim an OTP allowance, then let Firebase send the SMS.
     *
     * The order matters and is not an implementation detail: our endpoint runs
     * first so a banned or rate-limited number is refused BEFORE an SMS is
     * paid for, and so the server-side challenge exists by the time sign-in is
     * attempted. Firebase cannot see the platform ban; the backend cannot send
     * an SMS.
     */
    suspend fun startVerification(activity: Activity, phoneE164: String): StartResult {
        if (!api.isConfigured) return StartResult.Failed("not_configured")

        when (val otp = api.requestOtp(phoneE164, PURPOSE_SIGN_IN)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Failed -> return StartResult.Failed(otp.code)
            ApiResult.Unreachable -> return StartResult.Failed("unreachable")
        }

        return suspendCancellableCoroutine { continuation ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    if (continuation.isActive) {
                        continuation.resume(StartResult.AutoVerified(credential))
                    }
                }

                override fun onVerificationFailed(exception: FirebaseException) {
                    Log.w(TAG, "Phone verification failed: ${exception.javaClass.simpleName}")
                    if (continuation.isActive) {
                        continuation.resume(StartResult.Failed(classifyFirebaseFailure(exception)))
                    }
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    // resume() is idempotent here because isActive is checked:
                    // the SDK can deliver completion and code-sent on the same
                    // flow, and a second resume would throw.
                    if (continuation.isActive) {
                        continuation.resume(StartResult.CodeSent(verificationId))
                    }
                }
            }

            val options = PhoneAuthOptions.newBuilder(firebaseAuth)
                .setPhoneNumber(phoneE164)
                .setTimeout(SMS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // Required: reCAPTCHA runs in the Activity when Play Integrity
                // cannot confirm the app.
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()

            PhoneAuthProvider.verifyPhoneNumber(options)
        }
    }

    // ── Step 2: exchange the credential for a session ────────────────────

    /** Submit a typed code. */
    suspend fun submitCode(
        phoneE164: String,
        verificationId: String,
        code: String
    ): AuthResult {
        val credential = try {
            PhoneAuthProvider.getCredential(verificationId, code)
        } catch (e: Exception) {
            // Thrown for a blank or malformed verification id, not for a
            // wrong code — a wrong code produces a credential that fails later.
            Log.w(TAG, "Could not build a credential from the supplied code")
            return AuthResult.Failed("invalid_code")
        }
        return exchangeCredential(credential)
    }

    /** Complete an automatic verification that never needed a code. */
    suspend fun exchangeCredential(credential: PhoneAuthCredential): AuthResult {
        val idToken = idTokenFrom(credential) ?: return AuthResult.Failed("verification_failed")

        return when (val result = api.signIn(idToken, DEVICE_LABEL)) {
            is ApiResult.Ok -> persist(result.value)
            is ApiResult.Failed ->
                // An unknown number is not an error — it is the registration
                // prompt. Every other refusal (banned, suspended, throttled) is
                // passed through for the UI to explain.
                if (result.code == "account_not_found") {
                    AuthResult.NeedsRegistration(idToken)
                } else {
                    AuthResult.Failed(result.code)
                }
            ApiResult.Unreachable -> AuthResult.Failed("unreachable")
        }
    }

    /**
     * Create the account for a verified number.
     *
     * A second challenge is claimed with purpose `register` before the call.
     * No additional SMS is sent — the number is already verified — but the
     * registration endpoint requires a challenge of its own purpose, so the
     * ledger records one. It costs a slot of the hourly allowance and nothing
     * else.
     */
    suspend fun completeRegistration(
        phoneE164: String,
        idToken: String,
        displayName: String,
        email: String
    ): AuthResult {
        when (val otp = api.requestOtp(phoneE164, PURPOSE_REGISTER)) {
            is ApiResult.Ok -> Unit
            is ApiResult.Failed -> return AuthResult.Failed(otp.code)
            ApiResult.Unreachable -> return AuthResult.Failed("unreachable")
        }

        return when (val result = api.register(idToken, displayName.trim(), email.trim(), DEVICE_LABEL)) {
            is ApiResult.Ok -> persist(result.value)
            is ApiResult.Failed -> AuthResult.Failed(result.code)
            ApiResult.Unreachable -> AuthResult.Failed("unreachable")
        }
    }

    // ── Session lifecycle ───────────────────────────────────────────────

    /**
     * A valid session, refreshing first if the access token is spent.
     *
     * Returns null when the user must sign in again. The critical distinction
     * is between the two failure kinds: a server refusal clears the stored
     * session (it is genuinely dead, and `refresh_token_reused` means it was
     * revoked for suspicion), whereas being offline must NOT — §36 wants
     * already-fetched content to stay readable with no network, and logging
     * someone out for entering a tunnel would discard a working credential.
     */
    suspend fun validSession(): GoodPostSession? {
        val stored = store.load() ?: return null
        if (!GoodPostSessionCodec.isExpired(stored, System.currentTimeMillis())) return stored

        return when (val refreshed = api.refresh(stored.refreshToken, DEVICE_LABEL)) {
            is ApiResult.Ok -> {
                store.save(refreshed.value)
                refreshed.value
            }
            is ApiResult.Failed -> {
                Log.i(TAG, "Session refresh refused (${refreshed.code}); signing out")
                store.clear()
                null
            }
            ApiResult.Unreachable -> stored
        }
    }

    /**
     * The account behind the stored session, or null if it is gone.
     * This is what the Good Post gate calls on entry, so a session revoked
     * server-side (a ban, a forced logout, reuse detection) is noticed at once
     * instead of at the next write.
     */
    suspend fun restoreAccount(): GoodPostAccount? {
        val session = validSession() ?: return null

        return when (val result = api.me(session.accessToken)) {
            is ApiResult.Ok -> result.value
            is ApiResult.Failed -> {
                if (result.status == 401 || result.status == 403) {
                    store.clear()
                    null
                } else {
                    // 5xx or a proxy hiccup: keep the session and let the
                    // screen show a retryable error rather than logging out.
                    null
                }
            }
            ApiResult.Unreachable -> null
        }
    }

    /** Sign out locally, and tell the server so the session row is revoked. */
    suspend fun signOut() {
        val session = store.load()
        store.clear()
        if (session != null) {
            api.logout(session.accessToken, session.refreshToken)
        }
        try {
            firebaseAuth.signOut()
        } catch (e: Exception) {
            Log.d(TAG, "Firebase sign-out skipped: ${e.message}")
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun persist(session: GoodPostSession): AuthResult {
        store.save(session)
        return AuthResult.SignedIn(session)
    }

    /**
     * Sign in to Firebase with the credential, then read the ID token the
     * backend will verify. The Firebase user is intentionally left signed in:
     * it is the device's record that this number was verified, and the app's
     * own tokens remain the source of truth for everything else.
     */
    private suspend fun idTokenFrom(credential: PhoneAuthCredential): String? = try {
        firebaseAuth.signInWithCredential(credential).await()
        firebaseAuth.currentUser?.getIdToken(false)?.await()?.token
    } catch (e: Exception) {
        Log.w(TAG, "Firebase sign-in failed: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Turn an SDK failure into a code the UI can word.
     *
     * Firebase's `errorCode` strings are not shown to users as-is; only the
     * distinction that changes the instruction is kept — a wrong code is
     * retryable, a quota-exceeded number is not, and anything else is
     * reported generically rather than leaking SDK detail.
     */
    private fun classifyFirebaseFailure(exception: FirebaseException): String {
        // The callback hands back the FirebaseException base type, which does
        // NOT carry a code — only the FirebaseAuthException branch does. The
        // concrete type is what tells us whether retrying is even possible.
        val errorCode = (exception as? FirebaseAuthException)?.errorCode

        return when {
            exception is FirebaseAuthInvalidCredentialsException -> "invalid_code"
            errorCode?.contains("quota", ignoreCase = true) == true -> "sms_quota_exceeded"
            errorCode?.contains("too-many-requests", ignoreCase = true) == true -> "rate_limited"
            else -> "verification_failed"
        }
    }

    /**
     * Bridge a Play Services [Task] into a suspend function.
     *
     * Hand-written rather than pulling in `kotlinx-coroutines-play-services`
     * for two call sites, and because the cancellation behaviour we want is
     * trivial here: these are one-shot calls that resolve quickly.
     */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val failure = task.exception
            when {
                failure != null -> continuation.resumeWithException(failure)
                task.result != null -> continuation.resume(task.result as T)
                else -> continuation.resumeWithException(IllegalStateException("empty result"))
            }
        }
    }
}
