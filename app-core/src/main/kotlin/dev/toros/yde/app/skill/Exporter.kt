package dev.toros.yde.app.skill

import dev.toros.yde.app.core.ViewCell
import dev.toros.yde.app.core.Views
import dev.toros.yde.app.core.Workspace
import dev.toros.yde.app.format.Table
import dev.toros.yde.app.format.WriteOptions
import dev.toros.yde.app.text.LineMerge
import dev.toros.yde.semantic.SemanticExpression
import dev.toros.yde.semantic.SemanticTransform
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.getAt
import dev.toros.yde.yaml.model.scalarText
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.yaml.YamlIO
import java.nio.charset.CharacterCodingException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * エクスポート（R5-03）: 対象文書（glob）を行×列に展開し、xlsx / csv / markdown / json / html の派生成果物を生成する。
 * `source.view` 指定時は `_views` のビュー定義を行×列の源にする（R9-05。「ビューで見えている表をそのまま帳票へ」）。
 * `build` 指定時は表を通さず、行ごとにひな型で値を組み立てて木のまま書く（json。並べ方は output.mode・番号の位置・既存の object への書き込み）。
 * 出力は原本からいつでも再生成できる派生物であり、YAMLへの出力は定義段階で拒否される（原本主義）。
 * 書き出し先は定義の output.file。実行のときにフォルダを選べば、そのフォルダへ同じファイル名で書く（ワークスペースの外も可。
 * 定義に絶対パスを書かないため。ワークスペースの中なら予約ディレクトリ・隠しディレクトリへは書かない）。
 * `include` の定義は、束ねた書き出しを同じ書き出し先へまとめて書く（全部を計画してから書く。どれかが誤りなら何も書かない）。
 */
object Exporter {

    data class Result(val file: Path, val rel: String, val rows: Int, val changed: Boolean = true)

    /**
     * 書き出しの計画（まだ書かない）: 書く先・中身・今あるファイルの中身。rel は表示用（ワークスペースの中なら相対パス、外なら絶対パス）。
     */
    class Plan(val file: Path, val rel: String, val rows: Int, val bytes: ByteArray, val previous: ByteArray?) {
        val changed: Boolean get() = previous == null || !previous.contentEquals(bytes)

        /** 今あるファイルとの行の差分（文字として読めないときは null）。 */
        fun lineDiff(): List<LineMerge.Hunk>? {
            val before = previous ?: return null
            fun lines(b: ByteArray) = runCatching { String(b, Charsets.UTF_8) }.getOrNull()?.let(LineMerge::splitLines)
            return LineMerge.diff(lines(before) ?: return null, lines(bytes) ?: return null)
        }
    }

    fun run(ws: Workspace, skill: SkillDef, outDir: Path? = null): Result = write(listOf(plan(ws, skill, outDir))).single()

    /**
     * 書き出しの計画を全部作る: ふつうの定義なら 1 つ、まとめて書き出す定義（include）なら束ねた順に 1 つずつ（書き出し先は共通）。
     * どれか 1 つでも誤りがあれば例外にし、何も書かない（途中まで書き換わった書き出し先を残さない）。書き出し先が重なっても誤り。
     */
    fun plans(ws: Workspace, skill: SkillDef, outDir: Path? = null): List<Plan> {
        val def = skill.export ?: error("skill「${skill.id}」は export ではありません")
        if (!def.isBundle) return listOf(plan(ws, skill, outDir))
        val loaded = Skills.load(ws.root)
        val plans = def.include.map { id ->
            val part = loaded.skill(id) ?: error("include の「$id」が見つかりません")
            runCatching { plan(ws, part, outDir) }.getOrElse { error("$id: ${it.message}") }
        }
        plans.groupBy { it.file }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { file ->
            error("書き出し先が重なっています: ${plans.first { it.file == file }.rel}")
        }
        return plans
    }

    /**
     * 計画どおりに書く（中身が変わるものだけ）。書く前に、どの書き出し先も計画のときから変わっていないことを確かめ、
     * 1 つでも変わっていれば何も書かない（確認している間に他のアプリが書き換えたときに、古い確認のまま上書きしない）。
     * 各ファイルは同じフォルダの一時ファイルに書いてから置き換える（途中で止まっても、書きかけのファイルを残さない）。
     */
    fun write(plans: List<Plan>): List<Result> {
        plans.forEach { plan ->
            val now = if (Files.isRegularFile(plan.file)) Files.readAllBytes(plan.file) else null
            val same = if (now == null || plan.previous == null) now == null && plan.previous == null else now.contentEquals(plan.previous)
            require(same) { "書き出し先が確認の後に変わりました: ${plan.rel}（もう一度書き出してください）" }
        }
        plans.filter { it.changed }.forEach { plan ->
            val dir = plan.file.toAbsolutePath().parent
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, ".${plan.file.fileName}.", ".tmp")
            try {
                Files.write(tmp, plan.bytes)
                try {
                    Files.move(tmp, plan.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, plan.file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
        return plans.map { Result(it.file, it.rel, it.rows, it.changed) }
    }

    fun plan(ws: Workspace, skill: SkillDef, outDir: Path? = null): Plan {
        val def = skill.export ?: error("skill「${skill.id}」は export ではありません")
        require(!def.isBundle) { "skill「${skill.id}」はまとめて書き出す定義です（束ねた定義ごとの計画は plans で作ります）" }
        val (file, rel) = destination(ws, def.outputFile, outDir)
        val previous = if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
        val fileName = file.fileName.toString()
        val (rows, bytes) = when {
            def.viewId != null -> fromView(ws, def, def.viewId).let { it.rows.size to Tables.encode(it, fileName, def.sheet) }
            def.build != null -> tree(ws, def, fileName, previous, rel)
            else -> fromMatch(ws, def).let { it.rows.size to Tables.encode(it, fileName, def.sheet) }
        }
        return Plan(file, rel, rows, bytes, previous)
    }

    /** 書き出し先: 実行のときに選んだフォルダがあれば、そこへ output.file のファイル名で。無ければワークスペースの中の output.file。 */
    private fun destination(ws: Workspace, outputFile: String, outDir: Path?): Pair<Path, String> {
        if (outDir == null) return ws.resolve(outputFile) to outputFile
        val file = outDir.toAbsolutePath().normalize().resolve(Path.of(outputFile.replace('\\', '/')).fileName.toString())
        val root = ws.root.toAbsolutePath().normalize()
        if (!file.startsWith(root)) return file to file.toString()
        val rel = root.relativize(file).toString().replace('\\', '/')
        require(rel.split('/').none { it.startsWith("_") || it.startsWith(".") }) { "予約ディレクトリ・隠しディレクトリへの出力はできません: $rel" }
        return file to rel
    }

    // ---------------------------------------------------------------- 表（列の定義）

    private fun fromMatch(ws: Workspace, def: ExportDef): Table {
        val rows = rowNodes(ws, def).map { (_, node) -> def.columns.map { column -> cellValue(node, column) } }
        return Table(def.columns.map { it.header }, rows)
    }

    /** 対象文書（glob。パスの順）の行: 1 文書 1 行か、rows の配列の要素ごとに 1 行。 */
    private fun rowNodes(ws: Workspace, def: ExportDef): List<Pair<String, YNode>> {
        val regex = globToRegex(def.match ?: error("source.match が必要です"))
        val files = ws.files.filter { regex.matches(ws.relPath(it)) }.sortedBy { ws.relPath(it) }
        if (files.isEmpty()) error("source.match「${def.match}」に一致する文書がありません")
        val rowsPath = parseYPath(def.rowsPath)
        return files.flatMap { file ->
            val root = runCatching { YamlIO.parse(Files.readString(file)) }.getOrNull() ?: return@flatMap emptyList()
            val nodes = if (def.rowsPath.isBlank()) listOf(root) else (getAt(root, rowsPath) as? YNode.Seq)?.items ?: emptyList()
            nodes.map { ws.relPath(file) to it }
        }
    }

    private fun cellValue(row: YNode, column: ExportColumn): Any? =
        when (val node = getAt(row, parseYPath(column.path))) {
            null -> null
            is YNode.Scalar -> (node.value as? String)?.trimEnd() ?: node.value
            is YNode.Seq -> node.items.joinToString(column.join) { item ->
                // 派生成果物にはコメントを出力しない（R8-01）
                (item as? YNode.Scalar)?.value?.toString() ?: YamlIO.dumpPlain(item).trim()
            }
            is YNode.Map -> YamlIO.dumpPlain(node).trim().lineSequence().joinToString(" ")
        }

    // ---------------------------------------------------------------- 木（build で組み立てる）

    /** 行ごとに build で値を組み立て、output の並べ方で 1 つの木にして書く形式の bytes にする。値の無い行は飛ばす。 */
    private fun tree(ws: Workspace, def: ExportDef, fileName: String, previous: ByteArray?, rel: String): Pair<Int, ByteArray> {
        val build = def.build!!
        val out = def.tree ?: TreeOutput()
        val built = rowNodes(ws, def).mapNotNull { (docRel, node) ->
            runCatching { SemanticTransform.run(build, node, DocumentId(docRel)).term }
                .getOrElse { error("$docRel: ${it.message}") }
        }
        val value: YNode = when (out.mode) {
            TreeMode.ARRAY -> if (out.index == null) YNode.Seq(built.toMutableList()) else arrange(built, out)
            TreeMode.VALUE -> {
                require(built.size == 1) { "mode: value はちょうど 1 つの値をファイル全体にします（組み立てた値は ${built.size} 件）" }
                built.single()
            }
            TreeMode.MERGE -> merge(built, previous ?: error("mode: merge は既存の JSON に書き込みます。書き出し先にファイルがありません: $rel"), fileName, rel)
        }
        return built.size to Tables.encodeTree(value, fileName, WriteOptions(style = out.style, finalNewline = out.finalNewline))
    }

    /** 番号の位置に並べる上限（誤った番号で巨大な配列を作らないため）。 */
    private const val MAX_INDEX = 1_000_000

    /** 番号の位置に並べる: 番号は組み立てた値から output.index で読む。空きの番号には output.fill（無ければ null）を置く。 */
    private fun arrange(built: List<YNode>, out: TreeOutput): YNode.Seq {
        val placed = sortedMapOf<Int, YNode>()
        built.forEach { value ->
            val raw = SemanticTransform.run(out.index!!, value).term
            val text = scalarText(raw)
            val n = text?.let(SemanticExpression::numberOf)?.takeIf { it.signum() >= 0 && it.stripTrailingZeros().scale() <= 0 }
                ?.let { runCatching { it.intValueExact() }.getOrNull() }
                ?: error("番号（output.index）が 0 以上の整数になりません: ${text ?: "（値なし）"}（${YamlIO.dumpPlain(value).trim().lineSequence().first().take(80)}）")
            require(n <= MAX_INDEX) { "番号（output.index）が大きすぎます: $n（上限 $MAX_INDEX）" }
            require(placed.put(n, value) == null) { "番号 $n の値が 2 つあります（output.index が重なっています）" }
        }
        val size = (placed.keys.lastOrNull() ?: -1) + 1
        val items = MutableList(size) { i ->
            placed[i] ?: out.fill?.let { fill ->
                SemanticTransform.run(fill, YNode.Map(), loop = mapOf("index" to i, "position" to i + 1)).term
            } ?: YNode.Scalar(null)
        }
        return YNode.Seq(items)
    }

    /** 既存の object に、組み立てた object の項目を書き込む（同じキーは置き換えて位置はそのまま・新しいキーは末尾に）。 */
    private fun merge(built: List<YNode>, previous: ByteArray, fileName: String, rel: String): YNode {
        val existing = try {
            Tables.decodeTree(previous, fileName)
        } catch (e: CharacterCodingException) {
            error("書き出し先の既存のファイルを UTF-8 として読めません: $rel")
        } catch (e: Exception) {
            error("書き出し先の既存のファイルを読めません: $rel（${e.message}）")
        }
        val target = existing as? YNode.Map ?: error("mode: merge の書き出し先は object の JSON である必要があります: $rel")
        built.forEach { value ->
            val entries = (value as? YNode.Map)?.entries ?: error("mode: merge では build が object を組み立てる必要があります")
            entries.forEach { (key, node) -> target.entries[key] = node }
        }
        return target
    }

    // ---------------------------------------------------------------- ビュー参照エクスポート（R9-05）

    /** ビュー定義（`_views`）を解決し、そのままの行×列を表にする。 */
    private fun fromView(ws: Workspace, def: ExportDef, viewId: String): Table {
        val loaded = Views.load(ws.root, ws.schemas)
        if (loaded.docView(viewId) != null) {
            error("ビュー「$viewId」は文書ビュー（kind: document）のためエクスポートできません（kind: grid のビューを指定してください）")
        }
        val view = loaded.view(viewId)
            ?: error("ビュー「$viewId」が見つかりません" +
                (loaded.errors.takeIf { it.isNotEmpty() }?.let { "（定義エラー: ${it.joinToString(" / ")}）" } ?: ""))
        val rows = Views.resolve(view, ws.index, ws::treeOf, ws::schemaFor)
        // クロス集計は列を索引から広げる（軸を値の式で広げたクロス集計は、解決した行の列から）
        val headers = listOf("id") + Views.columnsOf(Views.effective(view, ws.index), rows).map { it.label }
        return Table(headers, rows.map { row -> listOf<Any?>(row.id) + row.cells.map(::viewCellValue) })
    }

    /** ビューのセル値を表のセルへ落とす（バックリンク列は参照元IDの列挙）。 */
    private fun viewCellValue(cell: ViewCell): Any? {
        cell.backlinks?.let { backlinks ->
            return backlinks.joinToString(", ") { it.sourceId ?: it.document.value }.takeIf { it.isNotBlank() }
        }
        return when (val node = cell.value) {
            null -> null
            is YNode.Scalar -> (node.value as? String)?.trimEnd() ?: node.value
            is YNode.Seq -> node.items.joinToString(", ") { item ->
                (item as? YNode.Scalar)?.value?.toString() ?: YamlIO.dumpPlain(item).trim()
            }
            is YNode.Map -> YamlIO.dumpPlain(node).trim().lineSequence().joinToString(" ")
        }
    }
}
