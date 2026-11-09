package app.pumping.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

/** Only committed user edits enter this history; derived values never do. */
enum class TimeField { START, END, DURATION }

data class QuickLogTiming(
    val start: Long,
    val end: Long,
    val seconds: Long,
    val history: List<TimeField> = listOf(TimeField.END, TimeField.DURATION),
) {
    fun edit(field: TimeField, value: Long): QuickLogTiming {
        val recent = (history.filterNot { it == field } + field).takeLast(2)
        var a = if (field == TimeField.START) value else start
        var b = if (field == TimeField.END) value else end
        var d = if (field == TimeField.DURATION) value else seconds
        when (TimeField.entries.first { it !in recent }) {
            TimeField.START -> a = Math.subtractExact(b, Math.multiplyExact(d, 1000))
            TimeField.END -> b = Math.addExact(a, Math.multiplyExact(d, 1000))
            TimeField.DURATION -> d = Math.subtractExact(b, a) / 1000
        }
        require(a > 0 && b > a && d > 0 && d <= (b - a) / 1000) {
            "End must follow Start and duration must be positive."
        }
        return QuickLogTiming(a, b, d, recent)
    }
}

fun durationSeconds(text: String): Long {
    val seconds = text.toBigDecimalOrNull()?.multiply(BigDecimal(60))
        ?.setScale(0, RoundingMode.DOWN)?.longValueExact()
        ?: throw IllegalArgumentException("Enter a valid duration")
    require(seconds > 0) { "Duration must be at least one second" }
    return seconds
}

/** Retain the original offset in an overlapping hour; resolve a nonexistent time forward. */
fun resolveEditorTime(date: LocalDate, hour: Int, minute: Int, zone: ZoneId, offset: ZoneOffset): Long =
    ZonedDateTime.ofLocal(date.atTime(hour, minute), zone, offset).toInstant().toEpochMilli()
