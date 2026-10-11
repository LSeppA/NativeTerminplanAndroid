package at.goinon.badvigaun.terminplan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleParserTest {
    @Test fun ignoresMarkerAndAllFollowingLines() {
        val result = ScheduleParser.parse(
            listOf(
                "Montag, 21.09.2026",
                "09:00 - 10:00 Krafttraining",
                "Sehr verehrte Patientin,",
                "Dienstag, 22.09.2026",
                "11:00 - 12:00 Dieser Termin darf nicht importiert werden"
            )
        )

        assertEquals(1, result.size)
        assertEquals("Krafttraining", result.single().title)
    }

    @Test fun markerIsRecognizedCaseInsensitivelyWithExtraSpaces() {
        val result = ScheduleParser.parse(
            listOf(
                "21.09.2026",
                "SEHR   VEREHRTE Damen und Herren",
                "09:00 Dieser Termin darf nicht importiert werden"
            )
        )

        assertEquals(emptyList<ScheduleItem>(), result)
    }

    @Test fun alternateGreetingAlsoEndsTheSchedule() {
        val result = ScheduleParser.parse(
            listOf("21.09.2026", "09:00 Erster Termin", "Sehr geehrte Patientin", "10:00 Nicht importieren")
        )

        assertEquals(1, result.size)
        assertEquals("Erster Termin", result.single().title)
    }

    @Test fun slashBeforePositionTwentyNineRemainsInTitle() {
        val result = ScheduleParser.parse(
            listOf("21.09.2026", "09:00 Therapie A/B Gruppe")
        )

        assertEquals("Therapie A/B Gruppe", result.single().title)
    }

    @Test fun slashAfterPositionTwentyEightEndsTitle() {
        val result = ScheduleParser.parse(
            listOf("21.09.2026", "09:00 Sehr lange Therapieeinheit ABC / interner Zusatz")
        )

        assertEquals("Sehr lange Therapieeinheit ABC", result.single().title)
    }
    @Test fun rejectsInvalidClockComponents() {
        for (time in listOf("09:75", "24:00", "09:00 - 10:75", "09:00 - 24:00")) {
            val result = ScheduleParser.parse(listOf("21.09.2026", "$time Therapie"))
            assertEquals(time, emptyList<ScheduleItem>(), result)
        }
    }

    @Test fun rejectsEndBeforeStart() {
        val result = ScheduleParser.parse(listOf("21.09.2026", "10:00 - 09:00 Therapie"))
        assertEquals(emptyList<ScheduleItem>(), result)
    }

    @Test fun acceptsClockBoundaries() {
        val result = ScheduleParser.parse(listOf("21.09.2026", "00:00 - 23:59 Therapie"))
        assertEquals(0, result.single().start)
        assertEquals(1439, result.single().end)
    }

    @Test fun invalidAppointmentDoesNotAttachDurationToPreviousAppointment() {
        val result = ScheduleParser.parse(listOf(
            "21.09.2026", "09:00 Erster Termin", "10:75 Ungültiger Termin", "30 Minuten",
            "11:00 - 12:00 Nächster Termin"
        ))
        val appointments = result.filterNot { it.information }
        assertEquals(2, appointments.size)
        assertEquals(null, appointments.first().duration)
        assertEquals(660, appointments.last().start)
    }

    @Test fun removesRoomCodesFromAppointmentsAndInformationRows() {
        val result = ScheduleParser.parse(listOf(
            "21.09.2026",
            "09:00 - 09:30 Ultraschall (Z0302) 30 Minuten",
            "Bitte bei Anmeldung melden (A1234)"
        ))

        assertEquals("Ultraschall", result[0].title)
        assertEquals(30, result[0].duration)
        assertEquals("Bitte bei Anmeldung melden", result[1].title)
        assertEquals(true, result[1].information)
    }

    @Test fun recognizesAbbreviatedDurations() {
        val variants = listOf("25 Min", "25 Min.", "25 Minute", "25 Minuten")
        variants.forEach { durationText ->
            val result = ScheduleParser.parse(listOf("21.09.2026", "09:00 Massage $durationText"))
            assertEquals(durationText, 25, result.single().duration)
            assertEquals(durationText, "Massage", result.single().title)
        }
    }
}
