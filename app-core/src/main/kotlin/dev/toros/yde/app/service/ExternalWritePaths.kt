package dev.toros.yde.app.service

import dev.toros.yde.workspace.WorkspacePaths
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** 配置・文書権限の文字列判定を、リンクや Windows の大小文字別名で迂回させない。 */
internal fun checkExternalWritePaths(root: Path, ids: Collection<DocumentId>, layout: WorkspaceLayout) {
    val base = root.toAbsolutePath().normalize()
    val realRoot = base.toRealPath()
    val ignoresCase = base.resolve("a") == base.resolve("A")
    // 未作成の予約フォルダーでも別綴りで作成させない。配置の規則自体は WorkspaceLayout に集約する。
    val effectiveLayout = if (ignoresCase) layout.copy(configDir = layout.configDir?.lowercase(), ledger = layout.ledger.map { it.lowercase() }) else layout
    val targets = ids.distinct().map { id ->
        require(effectiveLayout.inLedger(if (ignoresCase) id.value.lowercase() else id.value)) { "変更先は業務データに限定してください" }
        require(!ignoresCase || id.value.split('/').none { it.endsWith('.') || it.endsWith(' ') }) { "末尾の空白やドットを使った別名は指定できません" }
        val target = WorkspacePaths.resolve(base, id.value, allowLinks = false)
        var existing = target
        while (!Files.exists(existing, NOFOLLOW_LINKS)) existing = existing.parent
        // Path.equals は Windows で大小文字を同一視するので、実在する綴りを文字列で照合する。
        require(existing.toRealPath().toString() == realRoot.resolve(base.relativize(existing)).toString()) {
            "変更先はディスク上の正確な相対パスで指定してください"
        }
        target
    }
    require(targets.toSet().size == targets.size) { "同じ変更先を別名で指定できません" }
}
