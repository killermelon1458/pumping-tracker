package app.pumping

import app.pumping.domain.*
import org.junit.Assert.*
import org.junit.Test

class EditorArrangementTest {
    private val kind = EditorKind.PUMP
    @Test fun absentAndInvalidPreferencesUseStableDefaults() {
        for (raw in listOf(null,"","garbage","2|notes|timing","1|a|b|c","1|"+"x".repeat(5000)+"|"))
            assertEquals(EditorArrangement.default(kind),EditorArrangement.decode(kind,raw))
    }
    @Test fun stableIdsIgnoreUnknownsAndDuplicatesAndDiscoverNewSections() {
        val result=EditorArrangement.decode(kind,"1|notes,future,notes,timing|future,notes,timing")
        assertEquals(listOf("notes","timing","power","breastfeeding","pump","milk","stash","reminder"),result.order)
        assertEquals(setOf("notes"),result.hidden)
    }
    @Test fun requiredSectionsCannotBeHidden() {
        for (type in EditorKind.entries) for (section in type.sections.filter{it.required})
            assertFalse(section.id in EditorArrangement.default(type).visible(type,section.id,false).hidden)
    }
    @Test fun everyOptionalSectionCanBeRestored() {
        for (type in EditorKind.entries) for (section in type.sections.filterNot{it.required}) {
            val hidden=EditorArrangement.default(type).visible(type,section.id,false)
            assertTrue(section.id in hidden.hidden)
            assertEquals(EditorArrangement.default(type),hidden.visible(type,section.id,true))
        }
    }
    @Test fun moveUsesIdentityAndKeepsEverySectionExactlyOnce() {
        val initial=EditorArrangement.default(kind)
        val moved=initial.move("notes",0)
        assertEquals("notes",moved.order.first());assertEquals(initial.order.toSet(),moved.order.toSet())
        assertEquals(initial,moved.move("notes",moved.order.lastIndex))
        assertEquals(initial,initial.move("missing",0))
    }
    @Test fun outOfBoundsMovesClampSafely() {
        val initial=EditorArrangement.default(kind)
        assertEquals("notes",initial.move("notes",-5).order.first())
        assertEquals("timing",initial.move("timing",100).order.last())
    }
    @Test fun encodingRoundTripsEveryEditorWithDeterministicHiddenOrder() {
        for (type in EditorKind.entries) {
            val original=EditorArrangement.default(type).move("notes",0).visible(type,"notes",false)
            assertEquals(original,EditorArrangement.decode(type,original.encode()))
            assertEquals(original.encode(),EditorArrangement.decode(type,original.encode()).encode())
        }
    }
    @Test fun layoutsAreIndependentAcrossEditorDefinitions() {
        val pump=EditorArrangement.default(kind).visible(kind,"breastfeeding",false)
        assertEquals(EditorArrangement.default(EditorKind.BAG),EditorArrangement.decode(EditorKind.BAG,null))
        assertTrue("breastfeeding" in pump.hidden)
        assertTrue(EditorArrangement.decode(EditorKind.USAGE,pump.encode()).hidden.isEmpty())
    }
}
