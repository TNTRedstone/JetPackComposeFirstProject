package com.calvinsteck.equineevents.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DreamParkEvent(
        val title: String,
        val rawDateString: String,
        val startDate: Date,
        val endDate: Date?, // null if single day event
        val link: String,
        val isStarred: Boolean = false
) {
    val uniqueId: String by lazy { "${title}_${rawDateString}_${link}".hashCode().toString() }

    val formattedDateString: String by lazy {
        val calendar = Calendar.getInstance()
        calendar.time = startDate
        val currentYear = calendar.get(Calendar.YEAR)
        val eventYear = Calendar.getInstance().apply { time = startDate }.get(Calendar.YEAR)
        val showYear = eventYear != currentYear
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.getDefault()).format(startDate)
        val monthDay = SimpleDateFormat("MMMM d", Locale.getDefault()).format(startDate)
        val yearString = if (showYear) ", $eventYear" else ""

        if (endDate != null && endDate != startDate) {
            val endCal = Calendar.getInstance().apply { time = endDate }
            val endDayOfWeek = SimpleDateFormat("EEEE", Locale.getDefault()).format(endDate)
            val endMonthDay = SimpleDateFormat("MMMM d", Locale.getDefault()).format(endDate)
            val endYear = endCal.get(Calendar.YEAR)
            val endYearString = if (showYear || endYear != currentYear) ", $endYear" else ""
            "$dayOfWeek, $monthDay$yearString - $endDayOfWeek, $endMonthDay$endYearString"
        } else {
            "$dayOfWeek, $monthDay$yearString"
        }
    }
}

object EventDateParser {
    private val monthMap =
            mapOf(
                    "january" to Calendar.JANUARY,
                    "jan" to Calendar.JANUARY,
                    "february" to Calendar.FEBRUARY,
                    "feb" to Calendar.FEBRUARY,
                    "march" to Calendar.MARCH,
                    "mar" to Calendar.MARCH,
                    "april" to Calendar.APRIL,
                    "apr" to Calendar.APRIL,
                    "may" to Calendar.MAY,
                    "june" to Calendar.JUNE,
                    "jun" to Calendar.JUNE,
                    "july" to Calendar.JULY,
                    "jul" to Calendar.JULY,
                    "august" to Calendar.AUGUST,
                    "aug" to Calendar.AUGUST,
                    "september" to Calendar.SEPTEMBER,
                    "sep" to Calendar.SEPTEMBER,
                    "sept" to Calendar.SEPTEMBER,
                    "october" to Calendar.OCTOBER,
                    "oct" to Calendar.OCTOBER,
                    "november" to Calendar.NOVEMBER,
                    "nov" to Calendar.NOVEMBER,
                    "december" to Calendar.DECEMBER,
                    "dec" to Calendar.DECEMBER
            )

    fun parseDateString(dateString: String): Pair<Date, Date?> {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val cleanDateString = dateString.trim()

        return try {
            if (cleanDateString.contains(" - ")) {
                val parts = cleanDateString.split(" - ")
                val startYear = extractYear(parts[0]) ?: extractYear(parts[1]) ?: currentYear
                val endYear = extractYear(parts[1]) ?: startYear
                val startDate = parseDate(parts[0].trim(), startYear)
                val endDate = parseDate(parts[1].trim(), endYear)
                Pair(startDate, endDate)
            } else {
                val year = extractYear(cleanDateString) ?: currentYear
                val date = parseDate(cleanDateString, year)
                Pair(date, null)
            }
        } catch (e: Exception) {
            val fallbackDate = Date()
            Pair(fallbackDate, null)
        }
    }

    private fun extractYear(dateString: String): Int? {
        val yearRegex = Regex("\\b(20\\d{2})\\b")
        val match = yearRegex.find(dateString)
        return match?.value?.toIntOrNull()
    }

    private fun parseDate(dateString: String, defaultYear: Int): Date {
        // Handles formats: 'May 16, 2026', 'May 16', 'May 16,', 'May 16, 2026'
        val parts = dateString.trim().replace(",", "").split(" ")
        if (parts.size < 2) throw IllegalArgumentException("Invalid date format: $dateString")

        val monthName = parts[0].lowercase()
        val day =
                parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid day: ${parts[1]}")
        val year = parts.getOrNull(2)?.toIntOrNull() ?: defaultYear
        val calendar = Calendar.getInstance()
        calendar.set(
                year,
                monthMap[monthName] ?: throw IllegalArgumentException("Unknown month: $monthName"),
                day,
                0,
                0,
                0
        )
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.time
    }
}
