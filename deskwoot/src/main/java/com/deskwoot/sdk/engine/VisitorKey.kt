package com.deskwoot.sdk.engine

import java.security.MessageDigest

/**
 * The key a push names its visitor by: the first 16 characters of the lower
 * case hex SHA-256 of the UTF-8 bytes of the bearer the device was
 * registered with. The server computes the same key and sends it in each
 * push (`visitor`), so the SDK can tell a push for its own visitor from one
 * for a visitor this device forgot, without the bearer leaving the store.
 */
internal object VisitorKey {
    const val LENGTH: Int = 16

    fun of(bearer: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bearer.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.substring(0, LENGTH)
    }

    /** A key as a push carries it, trimmed and in lower case; null for none. */
    fun clean(key: String?): String? = key?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** Whose push a visitor key names. */
internal enum class PushVisitor {
    /** The visitor the session runs as (or starts as, or takes back now that the app named them). */
    ACTIVE,

    /** The stored verified user, still dormant: this launch has seen neither identify nor logout. */
    DORMANT,

    /** A visitor this device forgot, or a push without a key. */
    FORGOTTEN,
}

/**
 * What the visitor key of a push is compared with, as the session knew it at
 * one moment. Immutable, so any thread may read it; every key is already a
 * [VisitorKey].
 */
internal class PushKeys(
    /** The visitor's store was read in this process; before that [stored] says nothing. */
    val storeRead: Boolean,
    /** The bearer the session runs as (never a placeholder). */
    val active: String? = null,
    /** Earlier bearers of the signed in user in use, which a verification of this process replaced. */
    val theirs: Set<String> = emptySet(),
    /** The stored bearer. */
    val stored: String? = null,
    /** The verified user [stored] belongs to; null for an anonymous visitor. */
    val storedUser: String? = null,
    /** The user identify named in this launch; null before any identify and after a logout. */
    val named: String? = null,
    /** Identify or logout came in this launch. */
    val spoke: Boolean = false,
    /** The session decided who the visitor is at its start. */
    val decided: Boolean = false,
) {
    fun whose(key: String): PushVisitor {
        if (key == active || key in theirs) return PushVisitor.ACTIVE
        if (stored == null || key != stored) return PushVisitor.FORGOTTEN
        val user = storedUser
        return when {
            // The app named that very user: the stored identity is theirs and is taken back.
            user != null && named == user -> PushVisitor.ACTIVE
            user != null -> if (spoke) PushVisitor.FORGOTTEN else PushVisitor.DORMANT
            // The stored anonymous visitor, as the session starts with no user named.
            !decided && !spoke -> PushVisitor.ACTIVE
            else -> PushVisitor.FORGOTTEN
        }
    }

    /** The same, with the store as this thread read it ([bearer] and [user] of the stored record). */
    fun withStored(bearer: String?, user: String?): PushKeys =
        PushKeys(true, active, theirs, bearer?.let(VisitorKey::of), user, named, spoke, decided)
}
