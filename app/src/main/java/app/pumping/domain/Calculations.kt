package app.pumping.domain

import app.pumping.data.Session
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

object Volume {
    private val ounce = BigDecimal("29573.5295625")
    private val ml = BigDecimal(1000)
    const val MAX = 1_000_000_000L

    fun parse(text: String, unit: String): Long {
        val n =
            text.trim().replace(',', '.').toBigDecimalOrNull() ?: error("Enter a valid quantity")
        require(n.signum() >= 0) { "Quantity cannot be negative" }
        val value =
            n.multiply(if (unit == "oz") ounce else ml)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        require(value <= MAX) { "Quantity is too large" }
        return value
    }

    // Five decimals round-trip every integer microliter in either unit (oz step < 0.3 µL).
    // Converting through canonical storage prevents drift over repeated toggles.
    fun convertInput(text: String, from: String, to: String): String =
        if (text.isBlank()) text else format(parse(text, from), to, 5)

    fun format(value: Long, unit: String, decimals: Int = 2): String =
        BigDecimal(value)
            .divide(if (unit == "oz") ounce else ml, decimals, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}

fun interval(
    previous: Session,
    current: Session,
    reference: PumpReference = PumpReference.START,
): Long = current.start - (pumpReferenceTime(previous, reference) ?: previous.end)

fun dayOf(time: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

fun dayStart(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    day.atStartOfDay(zone).toInstant().toEpochMilli()

fun expiry(stored: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(stored).atZone(zone).plusDays(days.toLong()).toInstant().toEpochMilli()

fun nextMorning(now: Long, hour: Int): Long =
    dayOf(now).plusDays(1).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun nextReminder(end: Long, minutes: Int) = Math.addExact(end, minutes * 60_000L)

fun comparison(current: Long, previous: Long): Double? =
    if (previous == 0L) null else (current - previous).toDouble() * 100 / previous

fun streak(sessions: List<Session>, today: LocalDate = LocalDate.now()): Int {
    val days = sessions.map { dayOf(it.start) }.toSet()
    var date = if (today in days) today else today.minusDays(1)
    var count = 0
    while (date in days) {
        count++
        date = date.minusDays(1)
    }
    return count
}

data class Totals(val quantity: Long, val seconds: Long, val count: Int)

fun totals(sessions: List<Session>) =
    Totals(sessions.sumOf { it.volume }, sessions.sumOf { it.activeSeconds }, sessions.size)

fun validateSession(s: Session) {
    require(s.end > s.start) { "End must be after start" }
    require(s.activeSeconds > 0 && s.activeSeconds <= (s.end - s.start) / 1000) {
        "Active duration must fit inside start and end times"
    }
    require(s.volume in 0..Volume.MAX) { "Invalid volume" }
    require((s.left == null) == (s.right == null)) { "Both breast quantities are required" }
    if (s.left != null && s.right != null)
        require(s.left >= 0 && s.right >= 0 && s.volume == Math.addExact(s.left, s.right)) {
            "Total must equal left plus right"
        }
}

data class TimerState(
    val id: String = "",
    val start: Long = 0,
    val accumulated: Long = 0,
    val tick: Long = 0,
    val boot: Int = -1,
    val running: Boolean = false,
    val targetMinutes: Int = 15,
    val draftEnd: Long = 0,
) {
    fun elapsed(nowTick: Long, currentBoot: Int): Long =
        accumulated + if (running && currentBoot == boot && nowTick >= tick) nowTick - tick else 0
}
