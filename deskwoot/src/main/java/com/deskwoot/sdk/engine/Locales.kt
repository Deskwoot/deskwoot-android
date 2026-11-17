package com.deskwoot.sdk.engine

import com.deskwoot.sdk.model.FieldProblem

/** Language tags as the widget API reads them. */
internal object Locales {
    private val SEND_TAG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$")

    /**
     * The tag a send carries as `widgetLocale`. The server ignores anything
     * outside `^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}$`, so `auto` and
     * `pt_BR` would be lost: underscores become hyphens
     * and extra subtags are dropped until the tag fits.
     */
    fun sendTag(tag: String?): String? {
        var parts = tag?.trim()?.replace('_', '-')?.split('-')?.filter { it.isNotEmpty() } ?: return null
        while (parts.isNotEmpty()) {
            val candidate = parts.joinToString("-")
            if (SEND_TAG.matches(candidate)) return candidate
            parts = parts.dropLast(1)
        }
        return null
    }

    /** The UI language: the inbox's fixed language wins, else the app's, else English. */
    fun effective(inboxLocale: String?, appLocale: String?): String =
        inboxLocale ?: appLocale?.trim()?.takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) } ?: "en"
}

/**
 * The visitor email check the web form and email step use, so the native steps
 * refuse what the server would refuse.
 */
internal object EmailCheck {
    private val BLOCKED_DOMAINS = setOf(
        "test.com", "test.de", "test.org", "test.net", "test.io",
        "example.com", "example.org", "example.net",
        "localhost", "local.dev", "local.com",
        "mailinator.com", "mailinator.org", "yopmail.com",
        "guerrillamail.com", "guerrillamail.de",
        "tempmail.com", "tempmail.org", "10minutemail.com",
        "trash-mail.com", "throwaway.email", "fake.com", "fake.de",
    )
    private val LOCAL = Regex("^[a-z0-9._%+-]+$", RegexOption.IGNORE_CASE)
    private val DOMAIN = Regex("^[a-z0-9.-]+\\.[a-z]{2,}$", RegexOption.IGNORE_CASE)
    private val TEST_TLD = Regex("^test\\.[a-z]{2,}$", RegexOption.IGNORE_CASE)

    /** Null when the address is acceptable. */
    fun check(raw: String): FieldProblem? {
        val value = raw.trim()
        val at = value.indexOf('@')
        if (at <= 0 || at == value.length - 1) return FieldProblem.INVALID_EMAIL
        val local = value.substring(0, at)
        val domain = value.substring(at + 1).lowercase()
        if (!LOCAL.matches(local) || !DOMAIN.matches(domain)) return FieldProblem.INVALID_EMAIL
        if (domain in BLOCKED_DOMAINS || TEST_TLD.matches(domain)) return FieldProblem.BLOCKED_EMAIL
        return null
    }
}
