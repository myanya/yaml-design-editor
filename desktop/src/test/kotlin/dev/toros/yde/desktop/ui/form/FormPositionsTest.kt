package dev.toros.yde.desktop.ui.form

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.toros.yde.yaml.model.YPath
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormPositionsTest {
    @Test
    fun `ツリー移動の終了時にスクロール通知を待たず現在位置へ追従する`() = runBlocking {
        withTimeout(5_000) {
            val a = listOf("a")
            val b = listOf("b")
            val positions = FormPositions(ScrollState(0))
            positions.bounds[a] = 0f to 100f
            positions.bounds[b] = 100f to 300f
            val moving = mutableStateOf(false)
            val events = Channel<YPath?>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                positions.observeActivePath({ setOf(a, b) }, { moving.value }).collect { events.send(it) }
            }
            try {
                assertEquals(a, events.receive())
                moving.value = true
                Snapshot.sendApplyNotifications()
                assertNull(events.receive())
                positions.scroll.scrollTo(120)
                Snapshot.sendApplyNotifications()
                moving.value = false
                Snapshot.sendApplyNotifications()
                assertEquals(b, events.receive())
                // 明示選択のあとも、上方向・下方向の手動スクロールで選び直す。
                positions.scroll.scrollTo(0)
                Snapshot.sendApplyNotifications()
                assertEquals(a, events.receive())
                positions.scroll.scrollTo(150)
                Snapshot.sendApplyNotifications()
                assertEquals(b, events.receive())
            } finally { job.cancelAndJoin() }
        }
    }

    @Test
    fun `項目の初回配置と折り畳みによる再配置でも選択を更新する`() = runBlocking {
        withTimeout(5_000) {
            val a = listOf("a")
            val b = listOf("b")
            val positions = FormPositions(ScrollState(0))
            val events = Channel<YPath?>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                positions.observeActivePath({ setOf(a, b) }, { false }).collect { events.send(it) }
            }
            try {
                assertNull(events.receive())
                positions.bounds[a] = 0f to 100f
                positions.bounds[b] = 100f to 300f
                Snapshot.sendApplyNotifications()
                assertEquals(a, events.receive())
                positions.bounds[a] = 0f to 10f
                positions.bounds[b] = 10f to 210f
                Snapshot.sendApplyNotifications()
                assertEquals(b, events.receive())
                assertEquals(0, positions.scroll.value)
            } finally { job.cancelAndJoin() }
        }
    }
}
