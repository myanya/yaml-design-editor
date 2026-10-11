package dev.toros.yde.semantic.schema

import dev.toros.yde.yaml.model.DocumentId

/** file 型の値（ファイルの場所）の書き方の種類。 */
enum class FilePathKind(val label: String) {
    /** 接頭辞なし＝ワークスペースのルートからの相対パス（索引・ビュー・skill と同じ書き方）。 */
    WORKSPACE("ワークスペースのルートから"),
    /** `./` か `../` で始まる＝その文書のあるフォルダからの相対パス（Markdown のリンクと同じ規則）。 */
    DOCUMENT("この文書の場所から"),
    /** ドライブ文字（`C:/`）・`/`・`file:` で始まる＝絶対パス。 */
    ABSOLUTE("絶対パス"),
}

/**
 * file 型の値（ファイルの場所）の純ロジック（文字だけを見る。ファイルの存在や許可は workspace-core の検査、
 * 実際の場所への解決はアプリ）。区切りは `/` で、`\` は読むときに直す。
 */
object FilePath {
    /** 画面に描ける画像の拡張子（小文字）。 */
    val IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg")

    private val drive = Regex("^[A-Za-z]:/")

    fun normalize(text: String): String = text.trim().replace('\\', '/')

    fun isAbsolute(text: String): Boolean {
        val t = normalize(text)
        return t.startsWith("/") || t.startsWith("file:") || drive.containsMatchIn(t)
    }

    fun kindOf(text: String): FilePathKind {
        val t = normalize(text)
        return when {
            t == "." || t == ".." || t.startsWith("./") || t.startsWith("../") -> FilePathKind.DOCUMENT
            isAbsolute(t) -> FilePathKind.ABSOLUTE
            else -> FilePathKind.WORKSPACE
        }
    }

    /** 場所として読める形か（空でない・制御文字を含まない・フォルダで終わらない）。 */
    fun isWellFormed(text: String): Boolean {
        val t = normalize(text)
        return t.isNotEmpty() && t.none { it < ' ' } && !t.endsWith("/") && !t.endsWith("/.") && !t.endsWith("/..") &&
            t != "." && t != ".."
    }

    fun fileName(text: String): String = normalize(text).substringAfterLast('/')

    /** 拡張子（小文字・ドットなし）。無ければ null。 */
    fun extensionOf(text: String): String? {
        val name = fileName(text)
        val i = name.lastIndexOf('.')
        return if (i <= 0 || i == name.lastIndex) null else name.substring(i + 1).lowercase()
    }

    fun isImage(text: String): Boolean = extensionOf(text) in IMAGE_EXTENSIONS

    /**
     * ワークスペースのルートからの相対パスに直す（`.` と `..` を畳む）。絶対パスと、ルートの外へ出るものは null。
     * 文書相対（`./`・`../`）は、その文書（ワークスペース相対の文書ID）のあるフォルダを基準にする。
     */
    fun toWorkspaceRelative(text: String, document: DocumentId): String? {
        val t = normalize(text)
        val joined = when (kindOf(t)) {
            FilePathKind.ABSOLUTE -> return null
            FilePathKind.WORKSPACE -> t
            FilePathKind.DOCUMENT -> {
                val parent = document.value.replace('\\', '/').substringBeforeLast('/', "")
                if (parent.isEmpty()) t else "$parent/$t"
            }
        }
        return collapse(joined)
    }

    /** `.` と `..` を畳む。ルートの外へ出れば null。 */
    fun collapse(path: String): String? {
        val out = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (out.isEmpty()) return null else out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }

    /** 許された場所（絶対パスの接頭辞の一覧）のどれかに入るか（大文字小文字は区別しない）。 */
    fun underAny(absolute: String, roots: List<String>): Boolean {
        val a = normalize(absolute)
        return roots.any { r ->
            val n = normalize(r).trimEnd('/')
            n.isNotEmpty() && (a.equals(n, ignoreCase = true) || a.startsWith("$n/", ignoreCase = true))
        }
    }
}
