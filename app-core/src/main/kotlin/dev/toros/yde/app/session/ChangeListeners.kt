package dev.toros.yde.app.session

/** 所有スレッドで配る通知。購読中の追加・解除は次の通知から反映する。 */
internal class ChangeListeners<T> {
    private val listeners = linkedSetOf<(T) -> Unit>()
    fun observe(listener: (T) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    fun emit(value: T) { listeners.toList().forEach { it(value) } }
}
