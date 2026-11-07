package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.strArray
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.trigger.ScheduleEdit
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.nextCalendarAt
import com.wangzan101.mika.core.trigger.normalizeClockTimes
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val DAY_NAMES = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

internal object ScheduleTask : AgentTool {
    override val verb = Verb.ScheduleTask
    override val risk = Risk.External
    override val approvalRisk = ApprovalRisk(ActionRisk.External)
    override val offScreen = true
    override val logged = true
    override val def = ToolDef("schedule_task", Prompts.tool("schedule_task"), mapOf(
        "op" to str("create (default), list, edit, pause, resume, delete, run_now, or reschedule.",
            listOf("create", "list", "edit", "pause", "resume", "delete", "run_now", "reschedule")),
        "triggerId" to str("Plan ID returned by list or create; required for edit/pause/resume/delete/run_now/reschedule."),
        "goal" to str("What to do when the time arrives."),
        "at" to str("Optional first time as ISO 8601 with timezone. Required unless repeatAt or repeatTimes is given."),
        "intervalMinutes" to int("Optional repeat interval in minutes; omit for one time. When editing, 0 turns the interval off.", 0, 525600),
        "repeatAt" to str("Optional daily local clock time HH:mm, e.g. 09:00. Use for 'every day at 9' rather than a 1440-minute interval."),
        "repeatTimes" to strArray("Optional list of local clock times HH:mm for one plan that fires several times a day, e.g. 08:00, 12:30, 20:00.", 12),
        "repeatZone" to str("IANA time zone for repeatAt/repeatTimes, e.g. Asia/Shanghai; defaults to this device's zone."),
        "weekdays" to strArray("Optional days for repeatAt/repeatTimes: MON,TUE,WED,THU,FRI,SAT,SUN. Omit for every day.", 7),
        "condition" to str("Optional read only condition to check using web or sandbox before running the goal."),
        "reminder" to str("Optional fixed reminder text. If provided, deliver it directly at the scheduled time without running the model."),
        "whenIdle" to bool("If true, run only while no foreground task is active; the watchdog retries while busy."),
    ), emptyList())

    override fun available(ctx: ToolContext) = ctx.config.scheduleTrigger != null || ctx.config.scheduleAdmin != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val thread = ctx.config.conversationId ?: return ToolExecution(okObj { put("scheduled", false); put("reason", "no_thread") })
        val op = a.str("op") ?: "create"
        if (op != "create") {
            val admin = ctx.config.scheduleAdmin ?: return ToolExecution(okObj { put("ok", false); put("reason", "unavailable") })
            if (op == "list") return ToolExecution(okObj {
                put("plans", buildJsonArray { admin.list(thread).forEach { plan ->
                    add(buildJsonObject {
                        put("id", plan.id); put("threadId", plan.threadId); put("goal", plan.goal); put("enabled", plan.enabled)
                        put("nextAtMs", plan.nextAtMs); put("nextAt", Instant.ofEpochMilli(plan.nextAtMs).toString())
                        plan.reminderText?.let { put("reminder", it) }
                        plan.intervalMs?.let { put("intervalMinutes", it / 60_000) }
                        val times = plan.clockTimes
                        if (times.size > 1) put("repeatTimes", buildJsonArray { times.forEach { add(JsonPrimitive(it)) } })
                        else times.firstOrNull()?.let { put("repeatAt", it) }
                        plan.calendarZone?.let { put("repeatZone", it) }
                        plan.calendarDays.takeIf { it.isNotEmpty() }?.let { days ->
                            put("weekdays", buildJsonArray { days.forEach { add(JsonPrimitive(DAY_NAMES[it - 1])) } })
                        }
                        plan.conditionGoal?.let { put("condition", it) }
                    })
                } })
            })
            val id = a.str("triggerId") ?: return ToolExecution(okObj { put("changed", false); put("reason", "missing_trigger_id") })
            val changed = when (op) {
                "pause" -> admin.setEnabled(thread, id, false)
                "resume" -> admin.setEnabled(thread, id, true)
                "delete" -> admin.remove(thread, id)
                "run_now" -> admin.runNow(thread, id)
                "reschedule" -> {
                    val at = runCatching { Instant.parse(a.str("at") ?: error("missing time")).toEpochMilli() }.getOrNull()
                        ?: return ToolExecution(okObj { put("changed", false); put("reason", "invalid_time") })
                    if (at <= System.currentTimeMillis()) return ToolExecution(okObj { put("changed", false); put("reason", "time_not_future") })
                    admin.reschedule(thread, id, at)
                }
                "edit" -> return editPlan(a, admin, thread, id)
                else -> return ToolExecution(okObj { put("changed", false); put("reason", "invalid_op") })
            }
            return ToolExecution(okObj {
                put("changed", changed)
                // 计划在、却没改成:只有恢复认领过(跑过或正在跑)的一次性计划会这样
                if (!changed) put("reason", if (op == "resume" && admin.list(thread).any { it.id == id }) "already_ran" else "plan_not_found")
            })
        }
        val goal = a.str("goal", max = 4000) ?: return ToolExecution(okObj { put("scheduled", false); put("reason", "missing_goal") })
        val first = a.str("at")
        val repeatAt = a.str("repeatAt")
        val rawTimes = listOfNotNull(repeatAt) + a.strList("repeatTimes", 0, 12).orEmpty()
        val times = if (rawTimes.isEmpty()) null
            else normalizeClockTimes(rawTimes) ?: return ToolExecution(okObj { put("scheduled", false); put("reason", "invalid_time") })
        val interval = a.int("intervalMinutes", 1, 525600)?.toLong()?.times(60_000)
        if (times != null && interval != null) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "repeat_and_interval_conflict")
        })
        val zone = a.str("repeatZone") ?: ZoneId.systemDefault().id
        val days = a.strList("weekdays", 1, 7)?.map { day ->
            DAY_NAMES.indexOf(day.uppercase()) + 1
        }.orEmpty()
        if (days.any { it == 0 } || (times == null && days.isNotEmpty())) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "invalid_weekdays")
        })
        val at = runCatching {
            val firstMs = first?.let { Instant.parse(it).toEpochMilli() }
            if (times == null) firstMs ?: error("missing time")
            else {
                ZoneId.of(zone)
                nextCalendarAt(times, zone, days, maxOf(System.currentTimeMillis(), (firstMs ?: 0) - 1))
            }
        }.getOrElse { return ToolExecution(okObj { put("scheduled", false); put("reason", "invalid_time") }) }
        val condition = a.str("condition", max = 2000)
        val reminder = a.str("reminder", max = 2000)
        if (reminder != null && condition != null) return ToolExecution(okObj {
            put("scheduled", false); put("reason", "reminder_and_condition_conflict")
        })
        val trigger = TriggerDefinition(UUID.randomUUID().toString(), thread, goal, at, interval, condition,
            idleOnly = a.bool("whenIdle", false), reminderText = reminder,
            calendarTime = times?.singleOrNull(), calendarZone = if (times != null) zone else null, calendarDays = days,
            calendarTimes = if (times != null && times.size > 1) times else emptyList())
        val create = ctx.config.scheduleTrigger ?: return ToolExecution(okObj {
            put("scheduled", false); put("reason", "unavailable")
        })
        val saved = create(trigger)
        return ToolExecution(okObj {
            put("scheduled", true); put("triggerId", trigger.id); put("atMs", at)
            if (!saved.onTime) {
                put("exactAlarm", false)
                put("userNotified", saved.userNotified)
                put("message", "Saved, but the phone has not granted Mika the alarms & reminders permission, so this task may run late or not start at all. " +
                    (if (saved.userNotified) "Mika has just shown the user a notification that opens the setting. "
                    else "The user has not been shown a prompt for it this time. ") +
                    "Tell the user plainly that scheduled tasks need this permission and that it can be turned on from Mika's scheduled tasks page.")
            }
        })
    }

    /** 编辑已有计划;只改内容时保留原来的下次时间,改了排期则重新启用并重算。 */
    private fun editPlan(a: Args, admin: ScheduleAdmin, thread: String, id: String): ToolExecution {
        val times = a.strList("repeatTimes", 0, 12)?.let {
            normalizeClockTimes(it) ?: return ToolExecution(okObj { put("changed", false); put("reason", "invalid_time") })
        }
        val days = a.strList("weekdays", 0, 7)?.map { day -> DAY_NAMES.indexOf(day.uppercase()) + 1 }
        if (days != null && days.any { it == 0 }) return ToolExecution(okObj { put("changed", false); put("reason", "invalid_weekdays") })
        val at = a.str("at")?.let { text ->
            runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
                ?: return ToolExecution(okObj { put("changed", false); put("reason", "invalid_time") })
        }
        val edit = ScheduleEdit(
            goal = a.str("goal", max = 4000),
            condition = a.str("condition", min = 0, max = 2000),
            reminder = a.str("reminder", min = 0, max = 2000),
            atMs = at,
            repeatTimes = times,
            repeatZone = a.str("repeatZone"),
            weekdays = days,
            intervalMinutes = a.int("intervalMinutes", 0, 525600)?.toLong(),
            idleOnly = if (a.o.containsKey("whenIdle")) a.bool("whenIdle", false) else null,
        )
        if (edit == ScheduleEdit()) return ToolExecution(okObj { put("changed", false); put("reason", "nothing_to_edit") })
        val updated = admin.edit(thread, id, edit) ?: return ToolExecution(okObj {
            put("changed", false)
            put("reason", if (admin.list(thread).any { it.id == id }) "invalid_edit" else "plan_not_found")
        })
        return ToolExecution(okObj {
            put("changed", true); put("triggerId", updated.id)
            put("nextAtMs", updated.nextAtMs); put("nextAt", Instant.ofEpochMilli(updated.nextAtMs).toString())
        })
    }
}
