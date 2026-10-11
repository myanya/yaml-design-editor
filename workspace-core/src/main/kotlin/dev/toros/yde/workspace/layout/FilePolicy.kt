package dev.toros.yde.workspace.layout

import dev.toros.yde.yaml.model.YNode

/** file 型の値に対する扱い（許す・許して警告・違反）。 */
enum class FilePolicyLevel(val id: String) {
    ALLOW("allow"), WARNING("warning"), ERROR("error");

    companion object {
        fun parse(raw: String?): FilePolicyLevel? = when (raw?.trim()?.lowercase()) {
            "allow" -> ALLOW
            "warning", "warn" -> WARNING
            "error" -> ERROR
            else -> null
        }
    }
}

/**
 * file 型の値（ファイルの場所）の許可（`.yde/workspace.yaml` の `files:`）。
 * - `outside`: ワークスペースの外（絶対パス・`..` でルートの外へ出る相対パス）をどう扱うか。既定は許して警告
 * - `allowedRoots`: 外でも許す場所（絶対パスの接頭辞の一覧。`/` 区切り）。ここに入る場所は外として扱わない
 * - `missing`: 無いファイル（場所は読めるが、その場所にファイルが存在しない）をどう扱うか。既定は違反
 * 読めない値（綴り違いなど）は既定に戻す（`ledger:` の読み取りと同じ流儀）。
 */
data class FilePolicy(
    val outside: FilePolicyLevel = FilePolicyLevel.WARNING,
    val allowedRoots: List<String> = emptyList(),
    val missing: FilePolicyLevel = FilePolicyLevel.ERROR,
) {
    companion object {
        val DEFAULT = FilePolicy()

        /** 構成ファイルのルートから `files:` を読む。無ければ既定。 */
        fun parse(root: YNode.Map?): FilePolicy {
            val m = root?.entries?.get("files") as? YNode.Map ?: return DEFAULT
            val roots = when (val v = m.entries["allowedRoots"]) {
                is YNode.Scalar -> listOfNotNull(v.value?.toString())
                is YNode.Seq -> v.items.mapNotNull { (it as? YNode.Scalar)?.value?.toString() }
                else -> emptyList()
            }.map { it.replace('\\', '/').trim().trimEnd('/') }.filter { it.isNotBlank() }
            return FilePolicy(
                outside = FilePolicyLevel.parse((m.entries["outside"] as? YNode.Scalar)?.value?.toString()) ?: DEFAULT.outside,
                allowedRoots = roots,
                missing = FilePolicyLevel.parse((m.entries["missing"] as? YNode.Scalar)?.value?.toString()) ?: DEFAULT.missing,
            )
        }
    }
}
