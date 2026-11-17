package com.deskwoot.sdk.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Reads org.json values by their real type. org.json's own opt helpers
// coerce: on Android optString turns a JSON null into the text "null" and a
// number into its digits, which a server field must never become. Every
// read in the SDK goes through these.

internal fun JSONObject.string(key: String): String? = opt(key) as? String

/** A string field trimmed, or null when it is missing, not a string or blank. */
internal fun JSONObject.text(key: String): String? = string(key)?.trim()?.takeIf { it.isNotEmpty() }

/** True only for a JSON `true` (a missing field or any other value is false). */
internal fun JSONObject.isTrue(key: String): Boolean = opt(key) == true

internal fun JSONObject.boolean(key: String): Boolean? = opt(key) as? Boolean

internal fun JSONObject.int(key: String): Int? {
    val n = opt(key) as? Number ?: return null
    val d = n.toDouble()
    return if (d.isNaN() || d.isInfinite()) null else n.toInt()
}

internal fun JSONObject.long(key: String): Long? {
    val n = opt(key) as? Number ?: return null
    val d = n.toDouble()
    return if (d.isNaN() || d.isInfinite()) null else n.toLong()
}

internal fun JSONObject.obj(key: String): JSONObject? = opt(key) as? JSONObject

internal fun JSONObject.array(key: String): JSONArray? = opt(key) as? JSONArray

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { opt(it) as? JSONObject }

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { opt(it) as? String }

/**
 * Parses a JSON object, or null for empty text, other JSON, no JSON at all,
 * or a document [JsonGuard] refuses. Every text from the network or the
 * disk goes through here.
 */
internal fun parseJsonObject(text: String?): JSONObject? {
    if (text.isNullOrBlank() || !JsonGuard.accepts(text)) return null
    return try {
        JSONObject(text)
    } catch (_: JSONException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * A check of the raw text before Android's org.json sees it. That parser
 * recurses without a depth limit (deep nesting throws StackOverflowError,
 * an Error nothing catches), is lenient (unquoted words, `NaN` and
 * `Infinity` in an array, whose `toString()` then returns null) and has no
 * size limit per element. This scan is iterative and accepts only strict
 * JSON: at most [MAX_DEPTH] levels, at most [MAX_TOKENS] structural tokens,
 * bare words only `true`, `false` and `null`.
 */
internal object JsonGuard {
    const val MAX_DEPTH: Int = 64
    const val MAX_TOKENS: Int = 200_000

    fun accepts(text: String): Boolean {
        var depth = 0
        var tokens = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '"' -> {
                    // Skip the string, escapes included.
                    i++
                    while (i < n && text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    if (i >= n) return false
                }
                c == '{' || c == '[' -> {
                    depth++
                    tokens++
                    if (depth > MAX_DEPTH) return false
                }
                c == '}' || c == ']' -> {
                    depth--
                    if (depth < 0) return false
                }
                c == ',' || c == ':' -> tokens++
                c == ' ' || c == '\n' || c == '\r' || c == '\t' -> Unit
                c == '-' || c == '+' || c == '.' || c in '0'..'9' -> Unit
                c.isLetter() -> {
                    val start = i
                    while (i + 1 < n && text[i + 1].isLetterOrDigit()) i++
                    when (text.substring(start, i + 1)) {
                        "true", "false", "null" -> Unit
                        // eE inside a number such as 1e5 sits next to digits.
                        "e", "E" -> if (start == 0 || !(text[start - 1].isDigit() || text[start - 1] == '.')) return false
                        else -> if (!isExponent(text, start, i)) return false
                    }
                }
                else -> return false
            }
            if (tokens > MAX_TOKENS) return false
            i++
        }
        return depth == 0
    }

    /** `1e5` scans as the word `e5` after a digit: an exponent, not a bare word. */
    private fun isExponent(text: String, start: Int, end: Int): Boolean {
        val word = text.substring(start, end + 1)
        if (word.isEmpty() || (word[0] != 'e' && word[0] != 'E')) return false
        if (start == 0 || !(text[start - 1].isDigit() || text[start - 1] == '.')) return false
        return word.drop(1).all { it.isDigit() }
    }
}

/** Puts [value] as JSON null when it is null, so the key is sent (`conversationId: null`). */
/**
 * The object as JSON text. Android's `toString()` returns null instead of
 * throwing when a number is not finite; the guard keeps such numbers out,
 * and this keeps a null from ever reaching a non null field.
 */
internal fun JSONObject.rawText(): String {
    val text: String? = toString()
    return text ?: "{}"
}

internal fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

internal fun jsonArrayOf(items: Iterable<Any?>): JSONArray {
    val array = JSONArray()
    for (item in items) array.put(item ?: JSONObject.NULL)
    return array
}
