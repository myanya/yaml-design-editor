package dev.toros.yde.app.ui

import java.awt.EventQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

/** Desktop の UI 状態を EDT へ返す。提供モジュールがない Dispatchers.Main には依存しない。 */
internal object DesktopUiDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !EventQueue.isDispatchThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) = EventQueue.invokeLater(block)
}
