package at.goinon.badvigaun.terminplan

import org.junit.Assert.assertEquals
import org.junit.Test

class AppointmentDisplayTest {
    private val first = ScheduleItem(1_800_000_000_000L, 540, 600, null, "Erster Termin")
    private val second = ScheduleItem(1_800_000_000_000L, 660, 720, null, "Zweiter Termin")
    private val third = ScheduleItem(1_800_000_000_000L, 780, 840, null, "Dritter Termin")

    @Test fun currentAppointmentRemainsVisibleUntilFiveMinutesBeforeEnd() {
        val tenMinutesAfterStart = first.startTime!! + 10 * 60_000L
        assertEquals(first, appointmentForDisplay(listOf(first, second), tenMinutesAfterStart))
        assertEquals(first.endTime!! - 5 * 60_000L, nextAppointmentDisplayChange(listOf(first, second), tenMinutesAfterStart))
    }

    @Test fun advancesFiveMinutesBeforeEnd() {
        val fiveMinutesBeforeEnd = first.endTime!! - 5 * 60_000L
        assertEquals(second, appointmentForDisplay(listOf(first, second, third), fiveMinutesBeforeEnd))
        assertEquals(first.endTime!! + 1, nextAppointmentDisplayChange(listOf(first, second, third), fiveMinutesBeforeEnd))
    }

    @Test fun keepsLastAppointmentAndSchedulesItsRemovalWhenThereIsNoFollowingAppointment() {
        val fiveMinutesBeforeEnd = first.endTime!! - 5 * 60_000L
        assertEquals(first, appointmentForDisplay(listOf(first), fiveMinutesBeforeEnd))
        assertEquals(first.endTime!! + 1, nextAppointmentDisplayChange(listOf(first), fiveMinutesBeforeEnd))
    }

    @Test fun countsAppointmentsThatStartAtTheSameTime() {
        val parallel = first.copy(title = "Paralleler Termin")
        assertEquals(2, concurrentAppointmentCount(listOf(first, parallel, second), first))
    }
}
