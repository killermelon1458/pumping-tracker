package app.pumping.data

import android.util.JsonReader
import android.util.JsonToken
import app.pumping.domain.Volume
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.*
import java.util.Base64
import org.json.*

object LegacyFormat {
    const val MAX_BYTES = 20_000_000
    const val MAX_RECORDS = 100_000
    private val key = "breastmilk2024".toByteArray(Charsets.UTF_8)

    fun decode(text: String): JSONObject {
        require(text.length <= MAX_BYTES) { "Backup exceeds 20 MB" }
        val bytes =
            try {
                Base64.getDecoder().decode(text.trim())
            } catch (_: IllegalArgumentException) {
                error(
                    "Invalid Base64 .pumping file. Select an original backup or a Pumping JSON backup."
                )
            }
        val plain =
            ByteArray(bytes.size) { i -> (bytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
        val json =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plain))
                    .toString()
            } catch (_: Exception) {
                error("This file does not contain a valid UTF-8 .pumping backup.")
            }
        val root = parseJson(json)
        validateRoot(root)
        return root
    }

    fun encode(root: JSONObject): String {
        validateRoot(root)
        val plain = serialize(root).toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_BYTES * 3 / 4) {
            "Legacy export exceeds 20 MB; use a native backup."
        }
        return Base64.getEncoder()
            .encodeToString(
                ByteArray(plain.size) { i ->
                    (plain[i].toInt() xor key[i % key.size].toInt()).toByte()
                }
            )
    }

    private val decimalFields =
        setOf(
            "left",
            "right",
            "stashAmount",
            "volume",
            "formula_cost_per_100ml",
            "reminder_custom_interval",
        )

    private fun serialize(value: Any?, field: String = ""): String =
        when (value) {
            is JSONObject ->
                value.keys().asSequence().joinToString(prefix = "{", postfix = "}") { k ->
                    JSONObject.quote(k) + ":" + serialize(value.get(k), k)
                }
            is JSONArray ->
                (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
                    serialize(value.get(it))
                }
            is String -> JSONObject.quote(value)
            is Number -> {
                val n =
                    value.toString().toBigDecimalOrNull()
                        ?: error("Cannot export a non-finite number.")
                val text = n.toPlainString()
                if (field in decimalFields && '.' !in text) "$text.0" else text
            }
            null,
            JSONObject.NULL -> "null"
            else -> value.toString()
        }

    fun parseJson(text: String): JSONObject {
        require(text.length <= MAX_BYTES) { "Backup exceeds 20 MB." }
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else
                when (c) {
                    '"' -> quoted = true
                    '[',
                    '{' -> {
                        depth++
                        require(depth <= 64) { "Backup JSON is nested too deeply." }
                    }
                    ']',
                    '}' -> depth--
                }
        }
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                require(reader.peek() == JsonToken.BEGIN_OBJECT) {
                    "Backup root must be a JSON object."
                }
                fun value() {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                reader.nextName()
                                value()
                            }
                            reader.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            while (reader.hasNext()) value()
                            reader.endArray()
                        }
                        JsonToken.STRING,
                        JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> error("Invalid JSON value.")
                    }
                }
                value()
                require(reader.peek() == JsonToken.END_DOCUMENT)
            }
            return JSONObject(text)
        } catch (_: Exception) {
            error("Backup content is not valid JSON.")
        }
    }

    fun validateRoot(root: JSONObject) {
        require(root.opt("app") == "breastmilk") { "Not an original breastmilk .pumping backup." }
        require(root.opt("version") == "1.0") {
            "Unsupported .pumping version. Only string version 1.0 is supported."
        }
        local(root.opt("export_date"))
        val data = root.optJSONObject("data") ?: error("Backup data object is missing.")
        listOf("pump_records", "stash_records").forEach { k ->
            val array = data.optJSONArray(k) ?: error("Backup record collections are missing.")
            require(array.length() <= MAX_RECORDS) { "Backup contains too many records." }
        }
        require(root.optJSONObject("settings") != null) { "Backup settings object is missing." }
    }

    fun local(value: Any?): LocalDateTime =
        try {
            require(value is String)
            LocalDateTime.parse(value)
        } catch (_: Exception) {
            error(
                "Backup has an invalid local timestamp; offsets and UTC suffixes are not supported."
            )
        }

    fun instant(value: Any?, zone: ZoneId): Long {
        val local = local(value)
        require(zone.rules.getValidOffsets(local).isNotEmpty()) {
            "A source timestamp falls in a daylight-saving gap in the chosen time zone. Choose the original time zone."
        }
        return local.atZone(zone).toInstant().toEpochMilli()
    }

    fun id(value: Any?): String {
        require(value is String && value.isNotBlank() && value.length <= 256) {
            "Backup IDs must be nonempty strings."
        }
        return value
    }

    fun number(value: Any?): BigDecimal {
        require(value is Number) { "Backup quantities must be numbers." }
        val number =
            value.toString().toBigDecimalOrNull() ?: error("Backup has a non-finite quantity.")
        require(
            number >= BigDecimal.ZERO && number <= BigDecimal(Volume.MAX).divide(BigDecimal(1000))
        ) {
            "Backup quantity is negative or too large."
        }
        return number
    }

    fun microliters(value: Any?): Long =
        number(value).multiply(BigDecimal(1000)).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun integer(value: Any?, min: Int, max: Int): Int {
        require(value is Number) { "Backup field must be a whole number." }
        val n =
            try {
                BigDecimal(value.toString()).intValueExact()
            } catch (_: Exception) {
                error("Backup field must be a whole number.")
            }
        require(n in min..max) { "Backup integer is outside its supported range." }
        return n
    }

    fun text(j: JSONObject, key: String): String {
        val v = j.opt(key)
        require(v is String) { "Backup text fields must be strings." }
        return v
    }

    fun records(root: JSONObject, key: String): List<JSONObject> {
        val a = root.getJSONObject("data").getJSONArray(key)
        return (0 until a.length()).map {
            a.optJSONObject(it) ?: error("Backup contains an invalid record.")
        }
    }

    fun equivalent(a: String, b: String): Boolean =
        canonical(JSONObject(a)) == canonical(JSONObject(b))

    private fun canonical(v: Any?): String =
        when (v) {
            is JSONObject ->
                v.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") {
                    JSONObject.quote(it) + ":" + canonical(v.get(it))
                }
            is JSONArray ->
                (0 until v.length()).joinToString(prefix = "[", postfix = "]") {
                    canonical(v.get(it))
                }
            is Number -> BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
            is String -> JSONObject.quote(v)
            else -> v.toString()
        }
}
