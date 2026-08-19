package schema

/**
 * glob パターンを正規表現に変換する（OS 非依存。パス区切りは '/' 前提）。
 * 対応: `**`（区切りを跨ぐ任意）, `*`（区切りを含まない任意）, `?`（1文字）, `{a,b}`（選択）
 * （3層コア移行 Phase 6D で Schema.kt から移動。SchemaDef.matches・DeriveDef・DerivationSelector・
 * WorkspaceLayout 等が共有する純粋関数）
 */
fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var braceDepth = 0
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i]) {
            '*' -> {
                if (i + 1 < glob.length && glob[i + 1] == '*') {
                    sb.append(".*")
                    i++
                    // "**/" は末尾のスラッシュごと吸収（"a/**/b" が "a/b" にもマッチするように）
                    if (i + 1 < glob.length && glob[i + 1] == '/') {
                        sb.setLength(sb.length - 2)
                        sb.append("(?:.*/)?")
                        i++
                    }
                } else {
                    sb.append("[^/]*")
                }
            }
            '?' -> sb.append("[^/]")
            '{' -> { sb.append('('); braceDepth++ }
            '}' -> { sb.append(')'); if (braceDepth > 0) braceDepth-- }
            ',' -> if (braceDepth > 0) sb.append('|') else sb.append(',')
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
        i++
    }
    sb.append('$')
    return Regex(sb.toString())
}
