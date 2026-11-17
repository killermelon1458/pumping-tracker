package app.pumping.domain

/** Device-local presentation only; never a session/backup field. */
enum class TimePickerStyle(val label: String) {
    CLOCK_FACE("Clock face"),
    SCROLL_WHEELS("Scroll wheels");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: CLOCK_FACE
    }
}
