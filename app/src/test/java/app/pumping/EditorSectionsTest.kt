package app.pumping

import app.pumping.domain.*
import org.junit.Assert.*
import org.junit.Test

class EditorSectionsTest {
    @Test fun everyEditorHasStableUniqueIndependentSections() {
        for (kind in EditorKind.entries) {
            assertEquals(kind.sections.size, kind.sections.map { it.id }.distinct().size)
            assertTrue(kind.sections.all { it.id.matches(Regex("[a-z]+")) })
        }
        assertEquals(setOf("timing","milk"), EditorKind.PUMP.sections.filter { it.required }.map { it.id }.toSet())
        assertEquals(setOf("timing","milk","pump","power","breastfeeding","stash","reminder","notes"), EditorKind.PUMP.sections.map { it.id }.toSet())
        assertEquals(4, EditorKind.BAG.sections.count { it.required })
        assertEquals(4, EditorKind.USAGE.sections.count { it.required })
    }
}
