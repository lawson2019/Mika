package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.core.trigger.ScheduleEdit
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.core.trigger.edited
import com.wangzan101.mika.core.trigger.normalizeClockTimes
import com.wangzan101.mika.core.trigger.toTrigger
import com.wangzan101.mika.core.trigger.upcoming
import com.wangzan101.mika.task.ExactAlarmGuide
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import java.time.DayOfWeek
import java.time.Instant
import com.wangzan101.mika.i18n.uiLocale
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.UUID

/**
 * 已保存的定时任务:分「进行中」「已停用」两段,每条三行(做什么、什么时候与下次、上次结果),右边开关。
 * 点一行升起操作面板(看对话、立即执行、编辑、删除);页面上「新建定时任务」直接手工建一条。
 * 页面只读账本,修改后让调度器重新布置系统闹钟。
 */
@Composable
fun PlansScreen(context: Context, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    BackHandler(onBack = onBack)
    var snapshot by remember { mutableStateOf(TriggerRuntime.snapshot(context)) }
    var editing by remember { mutableStateOf<TriggerDefinition?>(null) }
    var deleting by remember { mutableStateOf<TriggerDefinition?>(null) }
    var acting by remember { mutableStateOf<TriggerDefinition?>(null) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var segment by rememberSaveable { mutableIntStateOf(0) }
    // 从系统设置回来时重读:刚开了权限,提示就该消失
    var exact by remember { mutableStateOf(TriggerRuntime.canScheduleExactly(context)) }
    LifecycleResumeEffect(Unit) {
        exact = TriggerRuntime.canScheduleExactly(context)
        onPauseOrDispose {}
    }
    fun refresh() { snapshot = TriggerRuntime.snapshot(context) }
    fun act(block: () -> Unit) {
        runCatching { block(); refresh(); error = null }
            .onFailure { error = it.message ?: tr(R.string.op_failed) }
    }

    val (running, stopped) = snapshot.triggers.sortedBy { it.nextAtMs }.partition { it.enabled }
    val shown = if (segment == 0) running else stopped

    SubPage(tr(R.string.drawer_plans), onBack) {
        if (!exact) {
            StateCard(
                MikaIcons.Alarm, ExactAlarmGuide.TITLE, tr(R.string.plans_alarm_body),
                tone = TileTone.Warn,
            ) { MikaButton(tr(R.string.turn_on_go), { act { context.startActivity(TriggerRuntime.exactAlarmSettings(context)) } }, kind = ButtonKind.Primary, size = ButtonSize.Small) }
        }
        SegmentedControl(
            listOf(tr(R.string.plans_running_n, running.size), tr(R.string.plans_stopped_n, stopped.size)), segment, { segment = it },
            tags = listOf("plans-segment-running", "plans-segment-stopped"),
        )
        MikaButton(tr(R.string.plans_add), { creating = true },
            Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Tonal, size = ButtonSize.Medium)
        error?.let { Text(it, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
        if (shown.isEmpty()) {
            GroupLabel("")
            EmptyNote(
                if (snapshot.triggers.isEmpty()) tr(R.string.plans_empty)
                else if (segment == 0) tr(R.string.plans_none_running) else tr(R.string.plans_none_stopped),
            )
        } else {
            GroupLabel(trPlural(R.plurals.settings_count_tasks, shown.size))
            GroupCard {
                shown.forEachIndexed { i, plan ->
                    val last = snapshot.windows.filter { it.triggerId == plan.id }.maxByOrNull { it.dueAtMs }
                    PlanRow(plan, last, first = i == 0, onClick = { acting = plan }, onToggle = { on -> act { TriggerRuntime.setEnabled(context, plan.id, on) } })
                }
            }
        }
        FooterNote(tr(R.string.plans_footer))
    }

    acting?.let { plan ->
        ActionSheet(
            plan.reminderText ?: plan.goal,
            listOf(
                SheetAction(tr(R.string.plans_open_chat)) { onOpenConversation(plan.threadId) },
                SheetAction(tr(R.string.plans_run_now)) { act { TriggerRuntime.runNow(context, plan.id) } },
                SheetAction(tr(R.string.plans_edit)) { editing = plan },
                SheetAction(tr(R.string.confirm_delete), destructive = true) { deleting = plan },
            ),
            onDismiss = { acting = null },
        )
    }

    editing?.let { plan ->
        PlanEditDialog(plan, onDismiss = { editing = null }) { edit ->
            runCatching { TriggerRuntime.edit(context, plan.id, edit) }
                .onSuccess { updated ->
                    if (updated == null) error = tr(R.string.plans_edit_invalid)
                    else {
                        refresh()
                        error = null
                        editing = null
                    }
                }
                .onFailure { error = it.message ?: tr(R.string.op_failed) }
        }
    }
    deleting?.let { plan ->
        MikaDialog(onDismissRequest = { deleting = null }, title = { Text(tr(R.string.plans_delete_title)) },
            text = { Text(plan.goal) },
            confirmButton = { TextButton(onClick = { act { TriggerRuntime.remove(context, plan.id) }; deleting = null }) {
                Text(tr(R.string.confirm_delete), color = MikaTheme.colors.bad)
            } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr(R.string.cancel)) } })
    }

    if (creating) {
        PlanCreateDialog(onDismiss = { creating = false }) { edit ->
            creating = false
            // 结果回到用户此刻所在的对话;没有就为这条任务单开一个
            val store = ConversationStore.shared(context)
            val threadId = when (val chat = store.currentChat()) {
                is CurrentChat.Open -> chat.id
                else -> store.newConversation()
            }
            val trigger = edit.toTrigger(UUID.randomUUID().toString(), threadId, System.currentTimeMillis())
            if (trigger == null) error = tr(R.string.plans_edit_invalid)
            else act { TriggerRuntime.schedule(context, trigger) }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanEditDialog(plan: TriggerDefinition, onDismiss: () -> Unit, onSave: (ScheduleEdit) -> Unit) {
    var goal by remember(plan.id) { mutableStateOf(plan.goal) }
    var condition by remember(plan.id) { mutableStateOf(plan.conditionGoal.orEmpty()) }
    var reminder by remember(plan.id) { mutableStateOf(plan.reminderText.orEmpty()) }
    var timesText by remember(plan.id) { mutableStateOf(plan.clockTimes.joinToString(", ")) }
    var days by remember(plan.id) { mutableStateOf(plan.calendarDays.toSet()) }
    var intervalText by remember(plan.id) {
        mutableStateOf(plan.intervalMs?.div(60_000)?.toString().orEmpty())
    }
    var atText by remember(plan.id) { mutableStateOf(formatPlanTime(plan.nextAtMs)) }
    val initialAt = remember(plan.id) { formatPlanTime(plan.nextAtMs) }
    val now = System.currentTimeMillis()
    val clockTimes = parsePlanTimes(timesText)
    val bothSet = condition.isNotBlank() && reminder.isNotBlank()
    val edit = if (bothSet) null else {
        planEdit(plan, goal, condition, reminder, clockTimes, days, intervalText, atText, initialAt)
    }
    val candidate = edit?.let { plan.edited(it, now) }
    val preview = candidate?.let { upcoming(it, now, 3).filter { t -> t > now } }.orEmpty()
    val problem = when {
        bothSet -> tr(R.string.plans_edit_conflict)
        edit == null || candidate == null -> tr(R.string.plans_edit_invalid)
        else -> null
    }
    val ready = edit != null && candidate != null
    val hint = MikaType.Caption
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.plans_edit_title)) },
        text = {
            KeyboardDismissArea {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth().keepsKeyboard(), label = { Text(tr(R.string.plans_edit_goal)) }, maxLines = 3)
                    OutlinedTextField(reminder, { reminder = it }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_reminder)) }, maxLines = 3)
                    OutlinedTextField(condition, { condition = it }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_condition)) }, maxLines = 3)
                    OutlinedTextField(timesText, { timesText = it }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_times)) }, singleLine = true)
                    if (clockTimes?.isNotEmpty() == true) {
                        WeekdayChips(days) { days = it }
                    }
                    if (clockTimes?.isEmpty() == true) {
                        OutlinedTextField(intervalText, { intervalText = it.filter(Char::isDigit).take(6) },
                            Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_interval)) }, singleLine = true)
                    }
                    OutlinedTextField(atText, { atText = it }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_label)) }, singleLine = true)
                    if (preview.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(tr(R.string.plans_preview), style = hint, color = MikaTheme.colors.ink3)
                        preview.forEachIndexed { index, t ->
                            Text("${index + 1}  ${formatPlanTime(t)}", style = hint, color = MikaTheme.colors.ink3)
                        }
                    }
                    problem?.let { Text(it, Modifier.padding(top = 8.dp), style = hint, color = MikaTheme.colors.bad) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (ready && edit != null) onSave(edit) }, enabled = ready) { Text(tr(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/** 逗号/分号/空白分隔的时刻表;非法返回 null,空文本返回空表。 */
private fun parsePlanTimes(text: String): List<String>? = normalizeClockTimes(
    text.split(',', '，', ';', '；', ' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() },
)

/** 按表单内容拼出 [ScheduleEdit];只填有变化的项,保留没改的字段。 */
private fun planEdit(
    plan: TriggerDefinition,
    goal: String,
    condition: String,
    reminder: String,
    clockTimes: List<String>?,
    days: Set<Int>,
    intervalText: String,
    atText: String,
    initialAt: String,
): ScheduleEdit? {
    if (clockTimes == null) return null
    if (goal.isBlank()) return null
    if (condition.isNotBlank() && reminder.isNotBlank()) return null
    val currentMinutes = plan.intervalMs?.div(60_000)
    val minutes = when {
        clockTimes.isNotEmpty() -> if (currentMinutes != null) 0L else null
        intervalText.isBlank() -> if (currentMinutes != null) 0L else null
        else -> intervalText.trim().toLongOrNull() ?: return null
    }
    if (minutes != null && minutes !in 0..525_600) return null
    val switchToInterval = minutes != null && minutes > 0
    val times = if (switchToInterval) emptyList() else clockTimes
    val atGiven = atText.trim() != initialAt
    val atMs = if (!atGiven) plan.nextAtMs else try {
        LocalDateTime.parse(atText.trim(), PLAN_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        return null
    }
    return ScheduleEdit(
        goal = goal.trim().takeIf { it != plan.goal },
        condition = condition.trim().takeIf { it != plan.conditionGoal.orEmpty() },
        reminder = reminder.trim().takeIf { it != plan.reminderText.orEmpty() },
        atMs = atMs.takeIf { atGiven && it != plan.nextAtMs },
        repeatTimes = times.takeIf { it != plan.clockTimes },
        weekdays = if (times.isNotEmpty() && days.sorted() != plan.calendarDays) days.sorted() else null,
        intervalMinutes = minutes?.takeIf { it != currentMinutes },
    )
}


/** 新建任务的重复方式:按钟点(可挑星期)、按间隔、只跑一次。 */
private const val KIND_TIMES = 0
private const val KIND_INTERVAL = 1
private const val KIND_ONCE = 2
/** 预览占位编号:真正落盘前会换成随机 id 和实际对话。 */
private const val PREVIEW_ID = "preview"

/**
 * 按新建表单拼出 [ScheduleEdit];内容或时刻不合法返回 null。
 * 排期只选一种:钟点(至少一个 HH:mm)、间隔(1..525600 分钟)、一次性(本地时间,过去与否交给 [toTrigger] 把关)。
 */
internal fun planCreateEdit(
    goal: String,
    condition: String,
    reminder: String,
    kind: Int,
    clockTimes: List<String>?,
    days: Set<Int>,
    intervalText: String,
    atText: String,
): ScheduleEdit? {
    if (goal.isBlank()) return null
    if (condition.isNotBlank() && reminder.isNotBlank()) return null
    val content = ScheduleEdit(
        goal = goal.trim(),
        condition = condition.trim().takeIf { it.isNotEmpty() },
        reminder = reminder.trim().takeIf { it.isNotEmpty() },
    )
    return when (kind) {
        KIND_TIMES -> {
            val times = clockTimes ?: return null
            if (times.isEmpty()) return null
            content.copy(repeatTimes = times, weekdays = days.sorted().takeIf { it.isNotEmpty() })
        }
        KIND_INTERVAL -> {
            val minutes = intervalText.trim().toLongOrNull() ?: return null
            if (minutes !in 1..525_600) return null
            content.copy(intervalMinutes = minutes)
        }
        KIND_ONCE -> {
            val at = try {
                LocalDateTime.parse(atText.trim(), PLAN_TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: Exception) {
                return null
            }
            content.copy(atMs = at)
        }
        else -> null
    }
}

/** 新建定时任务:先挑重复方式,再按方式填时刻;保存前用预览核对,校验通过才允许保存。 */
@Composable
private fun PlanCreateDialog(onDismiss: () -> Unit, onSave: (ScheduleEdit) -> Unit) {
    var goal by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf("") }
    var reminder by remember { mutableStateOf("") }
    var kind by rememberSaveable { mutableIntStateOf(KIND_TIMES) }
    var timesText by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(setOf<Int>()) }
    var intervalText by remember { mutableStateOf("") }
    var atText by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(false) }
    fun change(state: () -> Unit) { touched = true; state() }

    val now = System.currentTimeMillis()
    val clockTimes = parsePlanTimes(timesText)
    val edit = planCreateEdit(goal, condition, reminder, kind, clockTimes, days, intervalText, atText)
    val candidate = edit?.toTrigger(PREVIEW_ID, PREVIEW_ID, now)
    val preview = candidate?.let { upcoming(it, now, 3).filter { t -> t > now } }.orEmpty()
    val problem = when {
        !touched -> null
        condition.isNotBlank() && reminder.isNotBlank() -> tr(R.string.plans_edit_conflict)
        edit == null || candidate == null -> tr(R.string.plans_edit_invalid)
        else -> null
    }
    val hint = MikaType.Caption
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.plans_add_title)) },
        text = {
            KeyboardDismissArea {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(goal, { change { goal = it } }, Modifier.fillMaxWidth().keepsKeyboard(), label = { Text(tr(R.string.plans_edit_goal)) }, maxLines = 3)
                    OutlinedTextField(reminder, { change { reminder = it } }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_reminder)) }, maxLines = 3)
                    OutlinedTextField(condition, { change { condition = it } }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_condition)) }, maxLines = 3)
                    SegmentedControl(
                        listOf(tr(R.string.plans_add_kind_times), tr(R.string.plans_add_kind_interval), tr(R.string.plans_add_kind_once)),
                        kind, { change { kind = it } },
                        modifier = Modifier.padding(top = 8.dp),
                        tags = listOf("plans-create-kind-times", "plans-create-kind-interval", "plans-create-kind-once"),
                    )
                    when (kind) {
                        KIND_TIMES -> {
                            OutlinedTextField(timesText, { change { timesText = it } }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_times)) }, singleLine = true)
                            if (clockTimes?.isNotEmpty() == true) {
                                WeekdayChips(days) { change { days = it } }
                            }
                        }
                        KIND_INTERVAL -> OutlinedTextField(intervalText, { change { intervalText = it.filter(Char::isDigit).take(6) } },
                            Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_edit_interval)) }, singleLine = true)
                        else -> OutlinedTextField(atText, { change { atText = it } }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard(), label = { Text(tr(R.string.plans_add_once)) }, singleLine = true)
                    }
                    if (preview.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(tr(R.string.plans_preview), style = hint, color = MikaTheme.colors.ink3)
                        preview.forEachIndexed { index, t ->
                            Text("${index + 1}  ${formatPlanTime(t)}", style = hint, color = MikaTheme.colors.ink3)
                        }
                    }
                    problem?.let { Text(it, Modifier.padding(top = 8.dp), style = hint, color = MikaTheme.colors.bad) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (candidate != null && edit != null) onSave(edit) }, enabled = candidate != null) { Text(tr(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.cancel)) } },
    )
}

/** 星期多选 chips;只在按钟点重复时出现。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Text(tr(R.string.plans_edit_weekdays), Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ink3)
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (1..7).forEach { day ->
            SelectChip(DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, uiLocale()), day in days, {
                onChange(if (day in days) days - day else days + day)
            }, small = true)
        }
    }
}


@Composable
private fun PlanRow(plan: TriggerDefinition, last: TriggerWindow?, first: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val status = when {
        plan.enabled -> tr(R.string.drawer_running)
        last?.state == TriggerWindowState.Delivered -> tr(R.string.plans_done)
        last?.state == TriggerWindowState.Skipped -> tr(R.string.plans_checked)
        last?.state == TriggerWindowState.Failed -> tr(R.string.plans_check_failed)
        last?.state == TriggerWindowState.Unknown -> tr(R.string.plans_result_pending)
        else -> tr(R.string.chat_paused)
    }
    val repeat = when {
        plan.clockTimes.isNotEmpty() -> {
            val days = if (plan.calendarDays.isEmpty()) tr(R.string.plans_every_day) else plan.calendarDays.joinToString(tr(R.string.list_sep)) {
                if (it in 1..7) DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, uiLocale()) else "?"
            }
            "$days ${plan.clockTimes.joinToString(tr(R.string.list_sep))} (${plan.calendarZone})"
        }
        plan.intervalMs != null -> trPlural(R.plurals.plans_every_minutes, (plan.intervalMs!! / 60_000).toInt())
        else -> tr(R.string.plans_once)
    }
    val when_ = if (plan.enabled) tr(R.string.plans_next, repeat, formatPlanTime(plan.nextAtMs)) else "$repeat · $status"
    val subtitle = plan.conditionGoal?.let { tr(R.string.plans_condition, it, when_) } ?: when_
    val c = MikaTheme.colors
    val delivered = last?.state == TriggerWindowState.Delivered
    // 一次性的任务做完后不能再打开;循环的和从没跑过的可以
    val canToggle = plan.enabled || plan.intervalMs != null || plan.clockTimes.isNotEmpty() || last == null
    ListRow(
        null, plan.reminderText ?: plan.goal, subtitle, first = first,
        detail = last?.let { tr(R.string.plans_last, formatPlanTime(it.dueAtMs), windowLabel(it.state)) } ?: tr(R.string.plans_never_run),
        detailColor = if (delivered) c.ok else c.ink3,
        onClick = onClick,
    ) { MikaSwitch(plan.enabled, onToggle, enabled = canToggle) }
}

private val PLAN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun formatPlanTime(ms: Long): String = PLAN_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun windowLabel(state: TriggerWindowState): String = when (state) {
    TriggerWindowState.Delivered -> tr(R.string.plans_delivered)
    TriggerWindowState.Skipped -> tr(R.string.plans_unchanged)
    TriggerWindowState.Failed -> tr(R.string.plans_check_failed)
    TriggerWindowState.Unknown -> tr(R.string.plans_result_pending)
    TriggerWindowState.Claimed, TriggerWindowState.Dispatched, TriggerWindowState.ActionDispatched -> tr(R.string.plans_running_now)
}
