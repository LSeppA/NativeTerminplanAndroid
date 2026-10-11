package at.goinon.badvigaun.terminplan

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeScannedDaysTest {
    @Test fun importReplacesOnlyScannedDays() {
        val monday = 1_000_000_000_000L
        val tuesday = monday + 86_400_000L
        val oldMonday = ScheduleItem(monday, 540, 570, null, "Alter Montag")
        val oldTuesday = ScheduleItem(tuesday, 600, 630, null, "Alter Dienstag")
        val information = ScheduleItem(monday, null, null, null, "Alte Information", information = true)
        val newMonday = ScheduleItem(monday, 660, 690, null, "Neuer Montag")

        val result = mergeScannedDays(listOf(oldMonday, information, oldTuesday), listOf(newMonday), now = 0L)

        assertEquals(listOf(newMonday, oldTuesday), result)
    }

    @Test fun emptyScanDoesNotDeleteSavedDays() {
        val saved = listOf(ScheduleItem(1_000_000_000_000L, 540, null, null, "Gespeichert"))
        assertEquals(saved, mergeScannedDays(saved, emptyList(), now = 0L))
    }
}
