package com.wangzan101.mika.core.trigger

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 编辑定时任务;字段为 null 表示不改,condition/reminder 传空串表示清空。 */
data class ScheduleEdit(
    val goal: String? = null,
    val condition: String? = null,
    val reminder: String? = null,
    val atMs: Long? = null,
    val repeatTimes: List<String>? = null,
    val repeatZone: String? = null,
    val weekdays: List<Int>? = null,
    val intervalMinutes: Long? = null,
    val idleOnly: Boolean? = null,
)

/** 严格校验 HH:mm 的当地钟点,去重排序,上限 12 个;非法返回 null。 */
fun normalizeClockTimes(raw: List<String>): List<String>? {
    val out = LinkedHashSet<String>()
    for (item in raw) {
        val text = item.trim()
        if (text.isEmpty()) return null
        val time = try {
            LocalTime.parse(text)
        } catch (e: Exception) {
            return null
        }
        if (CALENDAR_CLOCK.format(time) != text) return null
        out += text
    }
    if (out.size > 12) return null
    return out.sorted()
}

private fun parseZone(id: String): String? = try {
    ZoneId.of(id).id
} catch (e: Exception) {
    null
}

/**
 * 按 [edit] 生成一条新的计划;null 表示内容或时刻不合法。
 * 排期三种:[repeatTimes] 非空按当地钟点重复,[intervalMinutes] 大于 0 按毫秒间隔,[atMs] 只运行一次;
 * 都不给是错的。钟点表里的时区默认用 [defaultZone]。
 */
fun ScheduleEdit.toTrigger(
    id: String,
    threadId: String,
    nowMs: Long,
    defaultZone: String = ZoneId.systemDefault().id,
): TriggerDefinition? {
    if (id.isBlank() || threadId.isBlank()) return null
    val newGoal = goal?.trim().orEmpty()
    if (newGoal.isBlank() || newGoal.length > 4000) return null

    val newCondition = condition?.trim().orEmpty()
    val newReminder = reminder?.trim().orEmpty()
    if (newCondition.isNotEmpty() && newReminder.isNotEmpty()) return null
    if (newCondition.length > 2000 || newReminder.length > 2000) return null

    val at = atMs
    if (at != null && at <= 0) return null

    val times = repeatTimes?.let { normalizeClockTimes(it) ?: return null }.orEmpty()
    val intervalGiven = intervalMinutes
    if (intervalGiven != null && intervalGiven !in 0..525_600) return null
    if (intervalGiven != null && intervalGiven > 0 && times.isNotEmpty()) return null

    val interval = when {
        times.isNotEmpty() -> null
        intervalGiven != null && intervalGiven > 0 -> intervalGiven * 60_000
        else -> null
    }

    var zone: String? = null
    var days: List<Int> = emptyList()
    if (times.isNotEmpty()) {
        zone = repeatZone?.let { parseZone(it) ?: return null } ?: parseZone(defaultZone) ?: return null
        days = weekdays.orEmpty()
        if (days.any { it !in 1..7 }) return null
    } else if (weekdays?.isNotEmpty() == true) {
        return null
    }

    val next = when {
        times.isNotEmpty() -> {
            val anchor = if (at != null) maxOf(nowMs, at - 1) else nowMs
            nextCalendarAt(times, zone!!, days, anchor)
        }
        interval != null -> nowMs + interval
        at != null -> {
            if (at <= nowMs) return null
            at
        }
        else -> return null
    }

    return TriggerDefinition(
        id = id,
        threadId = threadId,
        goal = newGoal,
        nextAtMs = next,
        intervalMs = interval,
        conditionGoal = newCondition.ifEmpty { null },
        reminderText = newReminder.ifEmpty { null },
        idleOnly = idleOnly ?: false,
        calendarTime = times.singleOrNull(),
        calendarZone = zone,
        calendarDays = days,
        calendarTimes = if (times.size > 1) times else emptyList(),
    )
}

/**
 * 按 [edit] 生成新的计划;null 表示内容或时刻不合法。
 * 只改内容时保留原来的下次时间与启停状态,即使那一次已经过期;改了排期则重新启用并重算下次时间。
 */
fun TriggerDefinition.edited(edit: ScheduleEdit, nowMs: Long): TriggerDefinition? {
    val newGoal = edit.goal?.trim() ?: goal
    if (newGoal.isBlank() || newGoal.length > 4000) return null

    val givenCondition = edit.condition?.trim()
    val givenReminder = edit.reminder?.trim()
    if (!givenCondition.isNullOrEmpty() && !givenReminder.isNullOrEmpty()) return null
    if ((givenCondition?.length ?: 0) > 2000 || (givenReminder?.length ?: 0) > 2000) return null

    var newCondition = conditionGoal
    var newReminder = reminderText
    if (givenCondition != null) newCondition = givenCondition.ifEmpty { null }
    if (givenReminder != null) newReminder = givenReminder.ifEmpty { null }
    if (!givenCondition.isNullOrEmpty()) newReminder = null
    if (!givenReminder.isNullOrEmpty()) newCondition = null

    val at = edit.atMs
    if (at != null && at <= 0) return null

    val givenTimes = edit.repeatTimes?.let { normalizeClockTimes(it) ?: return null }
    val intervalGiven = edit.intervalMinutes
    if (intervalGiven != null && intervalGiven !in 0..525_600) return null
    if (intervalGiven != null && intervalGiven > 0 && givenTimes?.isNotEmpty() == true) return null

    val times = when {
        intervalGiven != null && intervalGiven > 0 -> emptyList()
        givenTimes != null -> givenTimes
        else -> clockTimes
    }
    val interval = when {
        times.isNotEmpty() -> null
        intervalGiven != null -> if (intervalGiven == 0L) null else intervalGiven * 60_000
        else -> intervalMs
    }

    var zone: String? = null
    var days: List<Int> = emptyList()
    if (times.isNotEmpty()) {
        zone = edit.repeatZone?.let { parseZone(it) ?: return null } ?: calendarZone ?: ZoneId.systemDefault().id
        days = edit.weekdays ?: calendarDays
        if (days.any { it !in 1..7 }) return null
    } else if (edit.weekdays?.isNotEmpty() == true) {
        return null
    }

    val scheduleTouched = at != null || givenTimes != null || intervalGiven != null ||
        (times.isNotEmpty() && (edit.repeatZone != null || edit.weekdays != null))

    val content = copy(
        goal = newGoal,
        conditionGoal = newCondition,
        reminderText = newReminder,
        lastConditionMet = if (newCondition != conditionGoal) null else lastConditionMet,
        idleOnly = edit.idleOnly ?: idleOnly,
    )
    if (!scheduleTouched) return content

    val atOnly = at != null && givenTimes == null && intervalGiven == null &&
        edit.repeatZone == null && edit.weekdays == null
    var newTimes = times
    val next: Long
    when {
        atOnly -> {
            next = at!!
            if (newTimes.isEmpty() && next <= nowMs) return null
            if (newTimes.size == 1) {
                newTimes = listOf(CALENDAR_CLOCK.format(Instant.ofEpochMilli(next).atZone(ZoneId.of(zone!!))))
            }
        }
        newTimes.isNotEmpty() -> {
            val anchor = if (at != null) maxOf(nowMs, at - 1) else nowMs
            next = nextCalendarAt(newTimes, zone!!, days, anchor)
        }
        at != null -> {
            if (at <= nowMs) return null
            next = at
        }
        interval != null -> next = nowMs + interval
        else -> {
            if (nextAtMs <= nowMs) return null
            next = nextAtMs
        }
    }
    return content.copy(
        enabled = true,
        nextAtMs = next,
        intervalMs = interval,
        calendarTime = newTimes.singleOrNull(),
        calendarTimes = if (newTimes.size > 1) newTimes else emptyList(),
        calendarZone = zone,
        calendarDays = days,
    )
}
