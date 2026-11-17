package com.deskwoot.sdk.model

/**
 * ISO 8601 times as the server writes them (`toISOString()`, UTC)
 * to and from epoch milliseconds. Written by hand because java.time needs
 * API 26 and SimpleDateFormat is neither thread safe nor strict.
 */
internal object IsoTime {
    private val pattern = Regex(
        "^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?([Zz]|[+-]\\d{2}(?::?\\d{2})?)?$",
    )

    /** Epoch milliseconds, or null for anything that is not a real ISO time. */
    fun parse(text: String?): Long? {
        val match = pattern.find(text?.trim() ?: return null) ?: return null
        val g = match.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        val days = daysFromCivil(year, month, day)
        // A day that does not exist (February 30) comes back as another date.
        if (civilFromDays(days) != Triple(year, month, day)) return null
        val fraction = g[7]
        val millis = if (fraction.isEmpty()) 0 else (fraction + "00").substring(0, 3).toInt()
        val offsetMinutes = offsetMinutes(g[8])
        val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1_000L + millis
    }

    /** `2026-11-16T09:41:00.000Z`, the shape the server reads back (`lastReadAt`). */
    fun format(epochMillis: Long): String {
        val totalSeconds = epochMillis.floorDiv(1_000L)
        val millis = epochMillis.mod(1_000L).toInt()
        val days = totalSeconds.floorDiv(86_400L)
        val secondOfDay = totalSeconds.mod(86_400L).toInt()
        val (year, month, day) = civilFromDays(days)
        val hour = secondOfDay / 3_600
        val minute = secondOfDay / 60 % 60
        val second = secondOfDay % 60
        return buildString(24) {
            append(pad(year, 4)).append('-').append(pad(month, 2)).append('-').append(pad(day, 2))
            append('T').append(pad(hour, 2)).append(':').append(pad(minute, 2)).append(':').append(pad(second, 2))
            append('.').append(pad(millis, 3)).append('Z')
        }
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

    private fun offsetMinutes(zone: String): Long {
        if (zone.isEmpty() || zone == "Z" || zone == "z") return 0
        val sign = if (zone[0] == '-') -1 else 1
        val digits = zone.substring(1).replace(":", "")
        val hours = digits.substring(0, 2).toLong()
        val minutes = if (digits.length >= 4) digits.substring(2, 4).toLong() else 0
        return sign * (hours * 60 + minutes)
    }

    // Days since 1970-01-01 for a proleptic Gregorian date, and back
    // (Howard Hinnant's civil calendar algorithms).
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yearOfEra = y - era * 400
        val monthFromMarch = (month + 9) % 12
        val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
        val z = daysSinceEpoch + 719_468
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthFromMarch = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * monthFromMarch + 2) / 5 + 1).toInt()
        val month = (if (monthFromMarch < 10) monthFromMarch + 3 else monthFromMarch - 9).toInt()
        val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }
}
