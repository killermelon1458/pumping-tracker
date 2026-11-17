package app.pumping

import app.pumping.data.Settings
import app.pumping.domain.TimePickerStyle
import org.junit.Assert.*
import org.junit.Test

class TimePickerStyleTest {
    @Test fun missingInvalidAndOlderPreferencesDefaultToClockFace() {
        assertEquals(TimePickerStyle.CLOCK_FACE,Settings().timePickerStyle)
        for (raw in listOf(null,"","clock","unsupported","WHEELS","1"))
            assertEquals(TimePickerStyle.CLOCK_FACE,TimePickerStyle.from(raw))
    }
    @Test fun stableStoredNamesRoundTripBothPresentations() {
        for (style in TimePickerStyle.entries) assertEquals(style,TimePickerStyle.from(style.name))
        assertEquals(listOf("Clock face","Scroll wheels"),TimePickerStyle.entries.map { it.label })
    }
}
