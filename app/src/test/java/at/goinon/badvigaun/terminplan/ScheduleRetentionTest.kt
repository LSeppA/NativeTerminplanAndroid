package at.goinon.badvigaun.terminplan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleRetentionTest {
    private val today = 1_800_000_000_000L

    @Test fun appointmentWithoutEndRemainsForItsWholeDay() {
        val item = ScheduleItem(today, 540, null, null, "Ausgabe")
        val afterStart = item.startTime!! + 60 * 60_000L

        assertTrue(shouldKeepStoredItem(item, afterStart, today))
    }

    @Test fun appointmentWithEndIsRemovedAfterThatEnd() {
        val item = ScheduleItem(today, 540, 600, null, "Therapie")
        val afterEnd = item.endTime!! + 1

        assertFalse(shouldKeepStoredItem(item, afterEnd, today))
    }

    @Test fun informationFromAnEarlierDayIsRemoved() {
        val yesterday = today - 24 * 60 * 60_000L
        val item = ScheduleItem(yesterday, null, null, null, "Hinweis", information = true)

        assertFalse(shouldKeepStoredItem(item, today, today))
    }
}
