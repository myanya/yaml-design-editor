package dev.toros.yde.desktop

/** 受信箱は各機構の状態の射影。画面がない間もトレイと同じ件数を読む。 */
fun AppState.pendingInboxCount(): Int {
    automationVersion // 自動実行の通知を Compose / snapshotFlow から購読する。
    return (automation?.pendingSnapshot()?.size ?: 0) + conflictedDocs().size +
        (if (runController?.pendingQuestion != null) 1 else 0) + (externalWork?.pendingCount ?: 0)
}

fun AppState.openInbox() {
    showSpace(UiSpace.RUNS)
    memory.runsTab = 0
    memory.runsReview = null
    memory.externalWorkReview = null
}
