package com.wangzan101.mika.core.trigger

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleEditTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

    private val daily = TriggerDefinition(
        "plan", "thread", "喝水", now + 60_000,
        reminderText = "喝水", calendarTime = "09:00", calendarZone = "Asia/Shanghai",
    )

    @Test fun contentOnlyEditKeepsScheduleAndDisabledState() {
        val paused = daily.copy(enabled = false, nextAtMs = now - 1_000)
        val edited = paused.edited(ScheduleEdit(goal = "多喝水", reminder = "该喝水了"), now)!!
        assertEquals("多喝水", edited.goal)
        assertEquals("该喝水了", edited.reminderText)
        assertFalse(edited.enabled)
        assertEquals(now - 1_000, edited.nextAtMs)
        assertEquals("09:00", edited.calendarTime)
    }

    @Test fun changingConditionClearsMetFlagAndReminderTakesItsPlace() {
        val conditional = daily.copy(reminderText = null, conditionGoal = "下雨", lastConditionMet = true)
        val changed = conditional.edited(ScheduleEdit(condition = "天晴"), now)!!
        assertEquals("天晴", changed.conditionGoal)
        assertNull(changed.lastConditionMet)
        val swapped = conditional.edited(ScheduleEdit(reminder = "带伞"), now)!!
        assertNull(swapped.conditionGoal)
        assertEquals("带伞", swapped.reminderText)
        assertNull(swapped.lastConditionMet)
    }

    @Test fun emptyStringsClearReminderAndCondition() {
        val cleared = daily.edited(ScheduleEdit(reminder = ""), now)!!
        assertNull(cleared.reminderText)
        val conditional = daily.copy(reminderText = null, conditionGoal = "下雨")
        assertNull(conditional.edited(ScheduleEdit(condition = " "), now)!!.conditionGoal)
    }

    @Test fun reminderAndConditionTogetherIsRejected() {
        assertNull(daily.edited(ScheduleEdit(condition = "下雨", reminder = "带伞"), now))
    }

    @Test fun invalidContentIsRejected() {
        assertNull(daily.edited(ScheduleEdit(goal = "  "), now))
        assertNull(daily.edited(ScheduleEdit(goal = "x".repeat(4001)), now))
        assertNull(daily.edited(ScheduleEdit(condition = "x".repeat(2001)), now))
    }

    @Test fun severalTimesNormalizeAndReplaceTheClock() {
        val edited = daily.edited(ScheduleEdit(repeatTimes = listOf("20:00", "08:00", "12:30", "08:00")), now)!!
        assertEquals(listOf("08:00", "12:30", "20:00"), edited.clockTimes)
        assertEquals(listOf("08:00", "12:30", "20:00"), edited.calendarTimes)
        assertNull(edited.calendarTime)
        assertEquals("Asia/Shanghai", edited.calendarZone)
        assertTrue(edited.enabled)
        assertEquals(
            nextCalendarAt(listOf("08:00", "12:30", "20:00"), "Asia/Shanghai", emptyList(), now),
            edited.nextAtMs,
        )
    }

    @Test fun strictTimesAreRequired() {
        for (bad in listOf("9:00", "09:00:00", "25:00", "09:60", "", "nine")) {
            assertNull("expected $bad to be rejected", daily.edited(ScheduleEdit(repeatTimes = listOf(bad)), now))
        }
        assertEquals(listOf("00:00", "23:59"), normalizeClockTimes(listOf("23:59", "00:00")))
        assertNull(normalizeClockTimes((0..12).map { "%02d:00".format(it) }))
    }

    @Test fun intervalAndTimesAreMutuallyExclusiveAndZeroClears() {
        assertNull(daily.edited(ScheduleEdit(intervalMinutes = 30, repeatTimes = listOf("09:00")), now))
        val interval = daily.edited(ScheduleEdit(intervalMinutes = 30), now)!!
        assertEquals(1_800_000L, interval.intervalMs)
        assertTrue(interval.clockTimes.isEmpty())
        assertNull(interval.calendarZone)
        assertEquals(now + 1_800_000L, interval.nextAtMs)
        val off = interval.edited(ScheduleEdit(intervalMinutes = 0, atMs = now + 5_000), now)!!
        assertNull(off.intervalMs)
        assertEquals(now + 5_000L, off.nextAtMs)
    }

    @Test fun emptyTimesDropTheClockSchedule() {
        val edited = daily.edited(ScheduleEdit(repeatTimes = emptyList()), now)!!
        assertTrue(edited.clockTimes.isEmpty())
        assertNull(edited.calendarZone)
        assertTrue(edited.calendarDays.isEmpty())
        assertEquals(daily.nextAtMs, edited.nextAtMs)
    }

    @Test fun localAtOnlyMovesTheSingleClockTime() {
        val at = Instant.parse("2026-11-04T02:30:00Z").toEpochMilli()
        val edited = daily.edited(ScheduleEdit(atMs = at), now)!!
        assertEquals("10:30", edited.calendarTime)
        assertEquals(at, edited.nextAtMs)
        assertTrue(edited.enabled)
    }

    @Test fun localAtOnlyForSeveralTimesMovesJustTheNextRun() {
        val multi = TriggerDefinition("plan", "thread", "喝水", now + 60_000,
            calendarZone = "Asia/Shanghai", calendarTimes = listOf("08:00", "12:30", "20:00"))
        val at = Instant.parse("2026-11-04T02:30:00Z").toEpochMilli()
        val edited = multi.edited(ScheduleEdit(atMs = at), now)!!
        assertEquals(at, edited.nextAtMs)
        assertEquals(listOf("08:00", "12:30", "20:00"), edited.calendarTimes)
        assertNull(edited.calendarTime)
    }

    @Test fun atAnchorsTheFirstTimeThenTheClockTableTakesOver() {
        val at = Instant.parse("2026-11-04T02:30:00Z").toEpochMilli()
        val edited = daily.edited(ScheduleEdit(repeatTimes = listOf("08:00", "18:00"), atMs = at), now)!!
        assertEquals(
            nextCalendarAt(listOf("08:00", "18:00"), "Asia/Shanghai", emptyList(), at - 1),
            edited.nextAtMs,
        )
    }

    @Test fun pastAtIsRejectedForOneShotsAndIntervalRestarts() {
        val once = TriggerDefinition("once", "thread", "do", now + 60_000)
        assertNull(once.edited(ScheduleEdit(atMs = now - 1), now))
        val moved = once.edited(ScheduleEdit(intervalMinutes = 10), now)!!
        assertEquals(now + 600_000L, moved.nextAtMs)
    }

    @Test fun weekdaysAndZoneAreValidatedForClockSchedules() {
        assertNull(daily.edited(ScheduleEdit(weekdays = listOf(0)), now))
        assertNull(daily.edited(ScheduleEdit(repeatZone = "Mars/Olympus"), now))
        assertNull(TriggerDefinition("once", "thread", "do", now + 1_000)
            .edited(ScheduleEdit(weekdays = listOf(1)), now))
        val edited = daily.edited(ScheduleEdit(repeatTimes = listOf("08:00"), weekdays = listOf(1, 3, 5),
            repeatZone = "America/New_York"), now)!!
        assertEquals(listOf(1, 3, 5), edited.calendarDays)
        assertEquals("America/New_York", edited.calendarZone)
        assertEquals(nextCalendarAt(listOf("08:00"), "America/New_York", listOf(1, 3, 5), now), edited.nextAtMs)
    }

    @Test fun scheduleEditsWakePausedPlansButContentEditsDoNot() {
        val paused = daily.copy(enabled = false, nextAtMs = now + 60_000)
        assertFalse(paused.edited(ScheduleEdit(goal = "多喝水"), now)!!.enabled)
        assertTrue(paused.edited(ScheduleEdit(repeatTimes = listOf("08:00")), now)!!.enabled)
        assertFalse(paused.edited(ScheduleEdit(idleOnly = true), now)!!.enabled)
    }

    @Test fun reschedulingSeveralTimesKeepsTheWholeTable() {
        val multi = TriggerDefinition("plan", "thread", "喝水", now + 60_000,
            calendarZone = "Asia/Shanghai", calendarTimes = listOf("08:00", "12:30", "20:00"))
        val at = Instant.parse("2026-11-04T02:30:00Z").toEpochMilli()
        val moved = multi.rescheduledTo(at)
        assertEquals(at, moved.nextAtMs)
        assertEquals(listOf("08:00", "12:30", "20:00"), moved.calendarTimes)
        assertNull(moved.calendarTime)
    }
}
