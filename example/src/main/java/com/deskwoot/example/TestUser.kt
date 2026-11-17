package com.deskwoot.example

import android.content.Context
import android.os.StrictMode
import com.deskwoot.sdk.DeskwootUser
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The example's signed in test user, as an app keeps its own signed in user:
 * whether somebody is signed in, and whether the app names them to the SDK
 * in `Application.onCreate` (the switch lets a device check cover a tap with
 * and without it).
 */
object TestUser {
    private const val PREFS = "example_user"
    private const val IDENTIFY_AT_START = "identify_at_start"

    /**
     * FOR LOCAL TESTS ONLY. A real app never holds the identity secret: its
     * backend computes identifierHash = hex(HMAC-SHA256(secret, identifier))
     * and hands it to the app after sign in, and the app stores it with its
     * own session.
     */
    fun deskwootUser(): DeskwootUser {
        val identifier = "user_4711"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.LOCAL_IDENTITY_SECRET.toByteArray(), "HmacSHA256"))
        val hash = mac.doFinal(identifier.toByteArray()).joinToString("") { "%02x".format(it) }
        return DeskwootUser(identifier = identifier, identifierHash = hash, email = BuildConfig.TEST_EMAIL.ifEmpty { null }, name = "Anna Example")
    }

    /** The app names its signed in user to the SDK right after setup in `Application.onCreate`. */
    fun identifiesAtStart(context: Context): Boolean {
        // The app's own small read at its start (the first one also creates the preferences folder); StrictMode in
        // the example watches the SDK, not this line.
        val policy = StrictMode.allowThreadDiskWrites()
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(IDENTIFY_AT_START, false)
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    /** Set by identify (on) and logout (off), and by the example's switch. */
    fun setIdentifiesAtStart(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(IDENTIFY_AT_START, on).apply()
    }
}
