package com.wangzan101.mika.ui

import com.wangzan101.mika.core.trigger.toTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新建定时任务表单的纯逻辑:三种排期只选其一,内容和时刻的校验与 [toTrigger] 对齐。 */
class PlansFormTest {
    private val now = System.currentTimeMillis()

    @Test
    fun clockTimesBuildARepeatingPlan() {
        val edit = planCreateEdit("早上看天气", "", "带伞", 0, listOf("21:30", "09:00"), setOf(1, 3), "", "")!!
        val trigger = edit.toTrigger("id-1", "thread-1", now)
        assertNotNull(trigger)
        trigger!!
        assertEquals(listOf("09:00", "21:30"), trigger.clockTimes)
        assertEquals(listOf(1, 3), trigger.calendarDays)
        assertEquals("带伞", trigger.reminderText)
        assertNull(trigger.conditionGoal)
        assertTrue("next run is in the future", trigger.nextAtMs > now)
    }

    @Test
    fun weekdaysOmittedMeansEveryDay() {
        val edit = planCreateEdit("喝水", "", "", 0, listOf("09:00"), emptySet(), "", "")!!
        val trigger = edit.toTrigger("id-1", "thread-1", now)!!
        assertTrue(trigger.calendarDays.isEmpty())
    }

    @Test
    fun invalidContentIsRejected() {
        assertNull("blank goal", planCreateEdit("  ", "", "", 0, listOf("09:00"), emptySet(), "", ""))
        assertNull("condition and reminder together",
            planCreateEdit("g", "只有降温才跑", "提醒文本", 0, listOf("09:00"), emptySet(), "", ""))
        assertNull("no times given", planCreateEdit("g", "", "", 0, emptyList(), emptySet(), "", ""))
        assertNull("unparsable times (parsePlanTimes returned null)", planCreateEdit("g", "", "", 0, null, emptySet(), "", ""))
        val raw = planCreateEdit("g", "", "", 0, listOf("25:00"), emptySet(), "", "")
        assertNotNull("form passes raw times on; toTrigger is the strict gate", raw)
        assertNull("toTrigger rejects malformed clock times", raw!!.toTrigger("id-1", "thread-1", now))
    }

    @Test
    fun intervalBuildsAMinuteRepeat() {
        val edit = planCreateEdit("查股价", "", "", 1, null, emptySet(), "30", "")!!
        val trigger = edit.toTrigger("id-1", "thread-1", now)!!
        assertEquals(1_800_000L, trigger.intervalMs)
        assertTrue(trigger.nextAtMs > now)
    }

    @Test
    fun intervalBounds() {
        assertNull("blank", planCreateEdit("g", "", "", 1, null, emptySet(), "", ""))
        assertNull("zero", planCreateEdit("g", "", "", 1, null, emptySet(), "0", ""))
        assertNull("too long", planCreateEdit("g", "", "", 1, null, emptySet(), "525601", ""))
    }

    @Test
    fun oneShotRunsOnceAtTheGivenTime() {
        val edit = planCreateEdit("交报告", "", "", 2, null, emptySet(), "", "2030-01-02 08:30")!!
        val trigger = edit.toTrigger("id-1", "thread-1", now)!!
        assertNull(trigger.intervalMs)
        assertTrue(trigger.clockTimes.isEmpty())
        val expected = java.time.LocalDateTime.parse("2030-01-02 08:30",
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, trigger.nextAtMs)
    }

    @Test
    fun oneShotInThePastOrMalformedIsRejected() {
        val past = planCreateEdit("g", "", "", 2, null, emptySet(), "", "2000-01-01 08:30")
        assertNotNull("form parses; toTrigger rejects past times", past)
        assertNull("past time rejected at creation", past!!.toTrigger("id-1", "thread-1", now))
        assertNull("malformed time", planCreateEdit("g", "", "", 2, null, emptySet(), "", "明天早上"))
    }
}
