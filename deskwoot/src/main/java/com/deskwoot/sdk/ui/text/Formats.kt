package com.deskwoot.sdk.ui.text

import com.deskwoot.sdk.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Dates and times as the web messenger words them, in the session's language. Every
 * format of the native SDK uses that language, also the visitor's own
 * bubble time and the article date, which the web formats in the device
 * language: one chat, one language.
 *
 * [patterns] turns a date skeleton into the language's pattern (on Android
 * `DateFormat.getBestDateTimePattern`), so this class runs in JVM tests.
 * Main thread only: it keeps its formatters, which are not thread safe.
 */
internal class Formats(
    private val texts: Texts,
    private val zone: TimeZone = TimeZone.getDefault(),
    private val patterns: (skeleton: String, locale: Locale) -> String,
) {
    private val locale: Locale = texts.language.locale

    /** Whether the language writes a 12 hour clock. */
    val twelveHour: Boolean by lazy {
        val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern().orEmpty()
        pattern.contains('h') || pattern.contains('K')
    }

    /** "9:14 PM" on a 12 hour clock, "09:14" on a 24 hour one; nothing for an unknown time. */
    fun clock(epochMillis: Long): String {
        if (epochMillis <= 0L) return ""
        return format(epochMillis, if (twelveHour) "hmm" else "HHmm", zone)
    }

    /** The day line: "Today", "Yesterday", else day and long month, with the year when it is not this year. */
    fun dayLine(epochMillis: Long, now: Long): String {
        val days = dayDifference(epochMillis, now)
        return when (days) {
            0 -> texts.t(R.string.dw_today)
            1 -> texts.t(R.string.dw_yesterday)
            else -> format(epochMillis, if (sameYear(epochMillis, now)) "MMMMd" else "yMMMMd", zone)
        }
    }

    /** A row of the list: "just now", "{n}m ago", the clock today, else day and short month. */
    fun listTime(epochMillis: Long, now: Long): String {
        if (epochMillis <= 0L) return ""
        val seconds = (now - epochMillis) / 1_000
        return when {
            seconds < 60 -> texts.t(R.string.dw_time_just_now)
            seconds < 3_600 -> texts.t(R.string.dw_time_minutes_ago, (seconds / 60).toString())
            dayDifference(epochMillis, now) == 0 -> clock(epochMillis)
            else -> format(epochMillis, if (sameYear(epochMillis, now)) "MMMd" else "yMMMd", zone)
        }
    }

    /** "just now", "{n}m ago", "{n}h ago", "{n}d ago" (the card above the app). */
    fun ago(epochMillis: Long, now: Long): String {
        val seconds = ((now - epochMillis) / 1_000).coerceAtLeast(0)
        return when {
            seconds < 60 -> texts.t(R.string.dw_time_just_now)
            seconds < 3_600 -> texts.t(R.string.dw_time_minutes_ago, (seconds / 60).toString())
            seconds < 86_400 -> texts.t(R.string.dw_time_hours_ago, (seconds / 3_600).toString())
            else -> texts.t(R.string.dw_time_days_ago, (seconds / 86_400).toString())
        }
    }

    /** "Conversation closed · 14 Oct, 09:14". */
    fun closedDivider(epochMillis: Long?, now: Long): String {
        val label = texts.t(R.string.dw_chat_closed)
        if (epochMillis == null || epochMillis <= 0L) return label
        val hour = if (twelveHour) "hmm" else "HHmm"
        val date = format(epochMillis, (if (sameYear(epochMillis, now)) "MMMd" else "yMMMd") + hour, zone)
        return "$label · $date"
    }

    /** A delivery day `YYYY-MM-DD` as short weekday, day and short month, computed in UTC. */
    fun deliveryDay(day: String): String {
        val parts = day.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) return day
        val utc = TimeZone.getTimeZone("UTC")
        val calendar = Calendar.getInstance(utc).apply {
            clear()
            set(parts[0], parts[1] - 1, parts[2], 12, 0, 0)
        }
        return format(calendar.timeInMillis, "EEEMMMd", utc)
    }

    /** "Updated 3d ago" up to 14 days, then a date. */
    fun updated(epochMillis: Long, now: Long): String {
        val seconds = ((now - epochMillis) / 1_000).coerceAtLeast(0)
        val text = when {
            seconds < 60 -> texts.t(R.string.dw_time_just_now)
            seconds < 3_600 -> texts.t(R.string.dw_time_minutes_ago, (seconds / 60).toString())
            seconds < 86_400 -> texts.t(R.string.dw_time_hours_ago, (seconds / 3_600).toString())
            seconds < 14 * 86_400 -> texts.t(R.string.dw_time_days_ago, (seconds / 86_400).toString())
            else -> format(epochMillis, "yMMMd", zone)
        }
        return texts.t(R.string.dw_updated, text)
    }

    /** Calendar days between [epochMillis] and [now] in the device's time zone (0 = the same day). */
    fun dayDifference(epochMillis: Long, now: Long): Int = (dayNumber(now) - dayNumber(epochMillis)).toInt()

    fun sameDay(a: Long, b: Long): Boolean = dayNumber(a) == dayNumber(b)

    /** The calendar day of [epochMillis] in the device's time zone, as a number (a stable key for a day line). */
    fun dayKey(epochMillis: Long): Long = dayNumber(epochMillis)

    private fun dayNumber(epochMillis: Long): Long {
        val offset = zone.getOffset(epochMillis)
        return (epochMillis + offset).floorDiv(86_400_000L)
    }

    private fun sameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance(zone).apply { timeInMillis = a }
        val cb = Calendar.getInstance(zone).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }

    /** One formatter per skeleton and zone: a thread of 500 rows formats 500 times per state, on the main thread. */
    private val formatters = HashMap<String, SimpleDateFormat>()

    private fun format(epochMillis: Long, skeleton: String, timeZone: TimeZone): String {
        val format = formatters.getOrPut(skeleton + "|" + timeZone.id) {
            SimpleDateFormat(patterns(skeleton, locale), locale).also { it.timeZone = timeZone }
        }
        return westernDigits(format.format(Date(epochMillis)))
    }
}

/**
 * Times and dates in Western digits, as the web widget shows them: it
 * formats with `Intl` for plain `ar`, which current ICU data (CLDR 42 and
 * later) resolves to the Latin numbering system ("1:57 م"); older Android
 * versions carry older data and would write Arabic Indic digits.
 */
internal fun westernDigits(text: String): String {
    if (text.none { it in '\u0660'..'\u0669' || it in '\u06F0'..'\u06F9' }) return text
    val out = StringBuilder(text.length)
    for (ch in text) {
        out.append(
            when (ch) {
                in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                else -> ch
            },
        )
    }
    return out.toString()
}
