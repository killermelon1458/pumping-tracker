package app.pumping.domain

import java.io.Serializable
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.*

/** Replace only the chosen local component, retaining seconds, milliseconds and overlap offset. */
fun editorDate(value: Long, date: LocalDate, zone: ZoneId): Long {
    val old = Instant.ofEpochMilli(value).atZone(zone)
    return ZonedDateTime.ofLocal(date.atTime(old.toLocalTime()), zone, old.offset).toInstant().toEpochMilli()
}
fun editorTime(value: Long, hour: Int, minute: Int, zone: ZoneId): Long {
    val old = Instant.ofEpochMilli(value).atZone(zone)
    val time = old.toLocalTime().withHour(hour).withMinute(minute)
    return ZonedDateTime.ofLocal(old.toLocalDate().atTime(time), zone, old.offset).toInstant().toEpochMilli()
}

/** Native wheel callbacks report adjacent steps, including wrap. Only 11/12 crosses a period. */
fun wheelHour24(current: Int, old: Int, next: Int, clock24: Boolean): Int {
    require(current in 0..23)
    if (clock24) { require(next in 0..23); return next }
    require(old in 1..12 && next in 1..12)
    val period = (current / 12) xor if ((old == 11 && next == 12) || (old == 12 && next == 11)) 1 else 0
    return next % 12 + period * 12
}

/** Private activity saved-state only; never persisted to the database or backup, never logged. */
data class EditorSnapshot private constructor(val fingerprint: String) : Serializable {
    constructor(values: List<String>) : this(snapshotFingerprint(values))
}
private fun snapshotFingerprint(values: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (value in values) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
}
fun normalizedSeconds(text: String): String =
    runCatching { "seconds:${durationSeconds(text)}" }.getOrElse { "draft:$text" }
fun normalizedVolume(text: String, unit: String): String =
    if (text.isEmpty()) "blank" else runCatching { "volume:${Volume.parse(text, unit)}" }.getOrElse { "draft:$text" }
