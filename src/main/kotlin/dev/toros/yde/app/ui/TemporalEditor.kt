package dev.toros.yde.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.ValueSemantics
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ================================================================ 日付・時刻のピッカー（Material3 の DatePicker / TimeInput）

/**
 * 画面のピッカーと正準の文字列（ValueSemantics: yyyy-MM-dd・HH:mm(:ss)・yyyy-MM-dd HH:mm(:ss)）の橋渡し。
 * 値は文字列のまま（表記から意味を決めない。型が日付だと決める）。純ロジックで、画面は TemporalPickerButton。
 */
internal object TemporalPick {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** 値の日付の部分。日時なら前半。読めなければ null。 */
    fun datePart(kind: ValueSemantics, text: String): LocalDate? = when (kind) {
        ValueSemantics.DATE -> (kind.parse(text) as? LocalDate)
        ValueSemantics.DATETIME -> (kind.parse(text) as? LocalDateTime)?.toLocalDate()
        ValueSemantics.TIME -> null
    }

    /** 値の時刻の部分。日時なら後半。読めなければ null。 */
    fun timePart(kind: ValueSemantics, text: String): LocalTime? = when (kind) {
        ValueSemantics.TIME -> (kind.parse(text) as? LocalTime)
        ValueSemantics.DATETIME -> (kind.parse(text) as? LocalDateTime)?.toLocalTime()
        ValueSemantics.DATE -> null
    }

    /** 日付 → DatePicker の状態（UTC の深夜のエポックミリ秒）。 */
    fun toEpochMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** DatePicker の状態 → 日付。 */
    fun fromEpochMillis(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toLocalDate()

    /**
     * ピッカーの結果から正準の文字列を組み立てる。
     * - 日付: 選んだ日。未選択なら元の値の日付、それも無ければ空。
     * - 時刻: 時・分。元の値に秒があれば `:00` を保って形を変えない。
     * - 日時: 日付が未選択なら元の値の日付、それも無ければ `today`（「今」は引数で渡す）。
     */
    fun compose(kind: ValueSemantics, date: LocalDate?, time: LocalTime?, previous: String, today: LocalDate): String {
        val withSeconds = previous.trim().matches(Regex(".*\\d{2}:\\d{2}:\\d{2}$"))
        fun timeText(t: LocalTime): String = if (withSeconds) "%02d:%02d:00".format(t.hour, t.minute) else "%02d:%02d".format(t.hour, t.minute)
        return when (kind) {
            ValueSemantics.DATE -> (date ?: datePart(kind, previous))?.format(dateFormat) ?: ""
            ValueSemantics.TIME -> (time ?: timePart(kind, previous))?.let(::timeText) ?: ""
            ValueSemantics.DATETIME -> {
                val d = date ?: datePart(kind, previous) ?: today
                val t = time ?: timePart(kind, previous) ?: LocalTime.MIDNIGHT
                d.format(dateFormat) + " " + timeText(t)
            }
        }
    }
}

/**
 * 日付・時刻・日時の入力欄の右に置くボタン。押すとポップアップ（モーダルでない。外をクリックすると閉じる）に
 * カレンダーと時刻入力を出す。日付はクリックで即反映、時刻を含むときは「決定」で反映する。
 * 書込は通常の Slot 経路（FormCtx.setScalar）＝Undo・差分・閲覧専用の拒否が同じに効く。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemporalPickerButton(ctx: FormCtx, field: FieldDef, slot: Slot) {
    val kind = field.type.value ?: return
    var open by remember { mutableStateOf(false) }
    val below = with(LocalDensity.current) { 32.dp.roundToPx() }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(
                if (kind == ValueSemantics.TIME) Icons.Default.Schedule else Icons.Default.CalendarMonth,
                contentDescription = when (kind) {
                    ValueSemantics.DATE -> "カレンダーから日付を選ぶ"
                    ValueSemantics.TIME -> "時刻を入力する"
                    ValueSemantics.DATETIME -> "カレンダーと時刻から日時を選ぶ"
                },
                Modifier.size(16.dp), tint = Palette.SubText,
            )
        }
        if (open) {
            val current = (slot.get() as? dev.toros.yde.yaml.model.YNode.Scalar)?.value?.toString() ?: ""
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, below),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                TemporalPopup(kind, current, onPick = { text ->
                    ctx.setScalar(slot, text, field.type)
                    open = false
                }, onClose = { open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemporalPopup(kind: ValueSemantics, current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val initialDate = remember(current) { TemporalPick.datePart(kind, current) }
    val initialTime = remember(current) { TemporalPick.timePart(kind, current) }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialDate?.let(TemporalPick::toEpochMillis))
    val timeState = rememberTimePickerState(
        initialHour = initialTime?.hour ?: 9, initialMinute = initialTime?.minute ?: 0, is24Hour = true,
    )
    val today = LocalDate.now()
    fun picked(): String = TemporalPick.compose(
        kind,
        date = dateState.selectedDateMillis?.let(TemporalPick::fromEpochMillis),
        time = if (kind == ValueSemantics.DATE) null else LocalTime.of(timeState.hour, timeState.minute),
        previous = current, today = today,
    )
    // 日付だけの項目はカレンダーのクリックで即反映して閉じる（決定ボタンを押させない）
    if (kind == ValueSemantics.DATE) {
        val initialMillis = initialDate?.let(TemporalPick::toEpochMillis)
        LaunchedEffect(dateState.selectedDateMillis) {
            val ms = dateState.selectedDateMillis
            if (ms != null && ms != initialMillis) onPick(picked())
        }
    }
    Surface(
        color = Palette.Surface, shape = PanelShape, shadowElevation = 6.dp,
        modifier = Modifier.border(1.dp, Palette.Border, PanelShape),
    ) {
        Column(Modifier.padding(8.dp)) {
            if (kind != ValueSemantics.TIME) {
                DatePicker(
                    state = dateState, title = null, headline = null, showModeToggle = false,
                    modifier = Modifier.width(360.dp),
                )
            }
            if (kind != ValueSemantics.DATE) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("時刻", fontSize = 12.sp, color = Palette.SubText)
                    Spacer(Modifier.width(12.dp))
                    TimeInput(state = timeState)
                }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("キャンセル", fontSize = 12.sp) }
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { onPick(picked()) }) { Text("決定", fontSize = 12.sp) }
                }
            }
        }
    }
}
