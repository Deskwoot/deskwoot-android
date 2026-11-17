package com.deskwoot.sdk

import androidx.annotation.MainThread
import org.json.JSONObject

/**
 * The host app's signed in user, proven to Deskwoot by an HMAC the app's
 * own backend computes. The identity secret never ships in the app.
 *
 * @property identifier the app's stable user id, 1 to 128 characters,
 *   without leading or trailing spaces.
 * @property identifierHash hex HMAC-SHA256 of [identifier] with the inbox's
 *   identity secret.
 * @property attributes optional flat values (strings, numbers, booleans,
 *   null) shown to the team; trusted by the AI only with [attributesHash].
 * @property attributesHash hex HMAC-SHA256 over the signed attribute set.
 * @property attributesIssuedAt unix seconds the attribute set was signed;
 *   required with [attributesHash].
 */
public class DeskwootUser @JvmOverloads constructor(
    public val identifier: String,
    public val identifierHash: String,
    public val email: String? = null,
    public val name: String? = null,
    public val attributes: Map<String, Any?>? = null,
    public val attributesHash: String? = null,
    public val attributesIssuedAt: Long? = null,
) {
    /** The shape the server accepts; anything else is ignored before a request. */
    @get:JvmSynthetic
    internal val isValid: Boolean
        get() = identifier.trim().length in 1..128 && HASH.matches(identifierHash)

    /** The `user` object of `POST /api/widget/identity/verify`. */
    @JvmSynthetic
    internal fun toJson(): JSONObject {
        val json = JSONObject()
            .put("identifier", identifier.trim())
            .put("identifierHash", identifierHash)
        email?.let { json.put("email", it) }
        name?.let { json.put("name", it) }
        attributes?.let { map ->
            val flat = JSONObject()
            for ((key, value) in map) {
                when (value) {
                    null -> flat.put(key, JSONObject.NULL)
                    is String, is Boolean, is Int, is Long, is Short, is Byte -> flat.put(key, value)
                    is Number -> if (value.toDouble().isFinite()) flat.put(key, value)
                    // Nested values are not part of the flat attribute set; they are left out.
                    else -> Unit
                }
            }
            json.put("attributes", flat)
        }
        attributesHash?.let { json.put("attributesHash", it) }
        attributesIssuedAt?.let { json.put("attributesIssuedAt", it) }
        return json
    }

    override fun equals(other: Any?): Boolean =
        other is DeskwootUser &&
            identifier == other.identifier &&
            identifierHash == other.identifierHash &&
            email == other.email &&
            name == other.name &&
            attributes == other.attributes &&
            attributesHash == other.attributesHash &&
            attributesIssuedAt == other.attributesIssuedAt

    override fun hashCode(): Int =
        listOf(identifier, identifierHash, email, name, attributes, attributesHash, attributesIssuedAt).hashCode()

    /** Names the user only by id: the hash and personal fields stay out of logs. */
    override fun toString(): String = "DeskwootUser(identifier=$identifier)"

    private companion object {
        val HASH = Regex("^[0-9a-fA-F]{64}$")
    }
}

/** Told on the main thread whenever the number of replies the visitor has not seen changes. */
public fun interface UnreadCountListener {
    @MainThread
    public fun onUnreadCountChanged(count: Int)
}
