package core

import edit.YamlOperation
import model.DocumentId
import model.YNode
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import schema.SchemaKind
import schema.WorkspaceLayout
import schema.globToRegex
import semantic.PlannedDocumentChange
import yaml.YamlIO
import java.nio.file.Files

/**
 * リファクタリングの**アプリ層影響解決**（B-1・RB-01。D-10 のアプリ層再接続）。
 * データ・スキーマ本体の計画生成はカーネル（semantic.SemanticRefactor ＝ session.planRefactoring）が行い、
 * ここは root にしか無い読み出しモデル——ビュー定義の SchemaDef 静的解決（Views.fieldRenameImpact）と
 * 宣言節・定義文書の言及スキャン——で計画を**補強**する: ビュー追随の追加変更（スカラー Set のみ）と、
 * 自動書換しない言及の警告。ビュー宣言のパースが SchemaDef 依存のため root 残置（Phase C-3 / D で再判断）。
 */
object RefactorImpact {

    /** 計画への補強: 追加の文書変更（ビュー追随。全てスカラー Set）と警告。 */
    data class Impact(val changes: List<PlannedDocumentChange>, val warnings: List<String>)

    /** フィールド一括改名（R25-01）のビュー追随＋言及警告。解決できない場合は追随なし（警告のみ）。 */
    fun fieldRename(ws: Workspace, schemaId: String, fieldPath: String, newName: String): Impact {
        val entry = ws.schemas.firstOrNull { it.id == schemaId } ?: return Impact(emptyList(), emptyList())
        val oldName = fieldPath.substringAfterLast('.').trim()
        val mentions = schemaSectionMentions(ws, oldName) + definitionDocMentions(ws, oldName)
        val target = resolveFieldDef(entry, fieldPath) ?: return Impact(emptyList(), mentions)
        val reachableIds = ws.schemas
            .filter { it.kind == SchemaKind.DOCUMENT && reaches(it) { f -> f === target } }
            .map { it.id }.toSet()
        val impact = Views.fieldRenameImpact(ws.root, ws.schemas, reachableIds, target, oldName, newName)
        val changes = impact.ops.entries.sortedBy { it.key.toString() }.mapNotNull { (file, ops) ->
            // fieldRenameImpact が生成するのはスカラー Set のみ（パス文字列・選別キーの書換）＝型付き操作へ1:1で写せる
            val operations = ops.mapNotNull { op ->
                (op as? PatchOp.Set)?.let { set ->
                    (set.value as? YNode.Scalar)?.let { YamlOperation.SetScalar(set.path, it.value) }
                }
            }
            if (operations.isEmpty()) null else PlannedDocumentChange(DocumentId(ws.relPath(file)), operations)
        }
        return Impact(changes, impact.warnings + mentions)
    }

    /** フィールド一括削除（R25-03）の言及警告（ビュー参照が壊れる旨＋宣言節・定義文書）。 */
    fun fieldDeleteWarnings(ws: Workspace, fieldPath: String): List<String> {
        val key = fieldPath.substringAfterLast('.').trim()
        if (key.isEmpty()) return emptyList()
        return Views.fieldMentionWarnings(ws.root, key, "削除によりビューの参照が壊れます・要修正") +
            schemaSectionMentions(ws, key) + definitionDocMentions(ws, key)
    }

    /** 文書移動・改名（R21-03）でビュー定義の対象から外れる警告（旧 DocMove.plan から移設）。 */
    fun moveWarnings(ws: Workspace, oldRel: String, newRel: String): List<String> {
        fun globMatches(glob: String?, rel: String): Boolean =
            glob != null && runCatching { globToRegex(glob).matches(rel) }.getOrDefault(false)
        fun leaves(glob: String?): Boolean = globMatches(glob, oldRel) && !globMatches(glob, newRel)
        val warnings = mutableListOf<String>()
        val viewDefs = Views.load(ws.root, ws.schemas)
        for (view in viewDefs.views) {
            val hits = mutableListOf<String>()
            if (view.newRow?.file == oldRel) hits += "newRow.file"
            if (view.columns.any { leaves(it.from.fileGlob) }) hits += "columns の fileGlob"
            if (leaves(view.orderBy?.fileGlob)) hits += "orderBy.fileGlob"
            if (leaves(view.detail?.source?.fileGlob)) hits += "detail.fileGlob"
            if (hits.isNotEmpty()) warnings += "ビュー「${view.id}」の ${hits.joinToString("・")} がこのファイルを対象にしなくなります"
        }
        for (docView in viewDefs.docViews) {
            if (leaves(docView.match)) warnings += "文書ビュー「${docView.id}」の match がこのファイルを対象にしなくなります"
        }
        return warnings
    }

    // ================================================================ スキーマ側の解決（読み取り専用の簡約版）

    /**
     * ドット区切りのフィールドパスを SchemaDef 上の FieldDef 実体へ解決する（旧 SchemaRefactor.resolveField の
     * 読み取り簡約版: 書換座標・兄弟検査は持たない）。多義・不在は null（追随なし・警告のみに落とす）。
     * カーネル側の計画生成が同じ規則で検査済みのため、ここで失敗しても計画自体は成立している。
     */
    private fun resolveFieldDef(entry: SchemaDef, fieldPath: String): FieldDef? {
        val segments = fieldPath.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var container = entry.root
        segments.forEachIndexed { i, name ->
            val found = childNamed(entry, descend(entry, container), name) ?: return null
            if (i == segments.lastIndex) return found
            container = found
        }
        return null
    }

    /** `$ref` の最終実体（定義インスタンス）。子リストのインスタンス保持が前提（§4.35）。 */
    private fun deref(entry: SchemaDef, field: FieldDef): FieldDef {
        var current = field
        val seen = mutableSetOf<String>()
        while (current.ref != null) {
            val (key, targetDef) = entry.definitionTarget(current) ?: break
            if (!seen.add(key.toString())) break
            current = targetDef
        }
        return current
    }

    /** `$ref` を辿りながら array の item / map の value へ暗黙に降りる（object・スカラーに到達するまで）。 */
    private fun descend(entry: SchemaDef, field: FieldDef): FieldDef {
        var current = field
        while (true) {
            val real = deref(entry, current)
            current = when (real.type) {
                FieldType.ARRAY -> real.item ?: return real
                FieldType.MAP -> real.value ?: return real
                else -> return real
            }
        }
    }

    /** `$ref` 連鎖上の最初の非空 cases（SchemaDef.resolve の合成規則と同じ）。 */
    private fun casesOf(entry: SchemaDef, field: FieldDef): Map<String, List<FieldDef>> {
        var current = field
        val seen = mutableSetOf<String>()
        while (true) {
            if (current.cases.isNotEmpty()) return current.cases
            val next = current.ref?.let { entry.definitionTarget(current) }
                ?.takeIf { seen.add(it.first.toString()) }?.second ?: return emptyMap()
            current = next
        }
    }

    /** object コンテナから名前で子フィールドを引く（fields と cases 由来キーの両方。多義は null）。 */
    private fun childNamed(entry: SchemaDef, container: FieldDef, name: String): FieldDef? {
        val real = deref(entry, container)
        if (real.type != FieldType.OBJECT) return null
        val direct = real.fields.filter { it.key == name }
        val fromCases = real.fields.flatMap { f -> casesOf(entry, f).values.flatten().filter { it.key == name } }
        return (direct + fromCases).singleOrNull()
    }

    /** target へ到達できるか（fields / item / value / cases を `$ref` 解決しつつ走査）。 */
    private fun reaches(s: SchemaDef, predicate: (FieldDef) -> Boolean): Boolean {
        var found = false
        val seen = mutableSetOf<String>()
        fun walk(f: FieldDef) {
            if (found) return
            if (predicate(f)) {
                found = true
                return
            }
            val located = s.definitionTarget(f)
            if (located != null && !seen.add(located.first.toString())) return
            val r = s.resolve(f) ?: f
            r.fields.forEach(::walk)
            r.item?.let(::walk)
            r.value?.let(::walk)
            r.cases.values.flatten().forEach(::walk)
        }
        walk(s.root)
        return found
    }

    // ================================================================ 言及スキャン（旧 SchemaRefactor から移設）

    /** ワークスペース内の実ファイルに定義されたスキーマか（組込み・derive 派生の除外）。 */
    private fun isEditableSchema(ws: Workspace, s: SchemaDef): Boolean =
        s.source.startsWith(ws.root) &&
            runCatching { ws.layout.zoneOf(ws.relPath(s.source)) }.getOrNull() == WorkspaceLayout.Zone.SCHEMAS

    /**
     * スキーマ内の宣言節（consistency / constraints / coverage / derive）と entity.owns の言及スキャン。
     * 自動書換の対象外のため、語境界つきの近似で言及箇所を列挙し対応判断は人間に委ねる。
     */
    private fun schemaSectionMentions(ws: Workspace, token: String): List<String> {
        val out = mutableListOf<String>()
        val re = tokenRegex(token)
        for (s in ws.schemas) {
            if (!isEditableSchema(ws, s)) continue
            val raw = runCatching { YamlIO.parseToRaw(Files.readString(s.source)) }.getOrNull() as? Map<*, *> ?: continue
            for (section in listOf("consistency", "constraints", "coverage", "derive")) {
                val sub = raw[section] ?: continue
                val text = runCatching { YamlIO.dumpPlain(YamlIO.fromAny(sub)) }.getOrNull() ?: continue
                if (re.containsMatchIn(text)) out += "スキーマ「${s.id}」の $section に「$token」の言及があります（自動書換の対象外・要確認）"
            }
            if (s.entities.any { it.owns?.contains(token) == true }) {
                out += "スキーマ「${s.id}」の entity.owns に「$token」があります（自動書換の対象外・要確認）"
            }
        }
        return out
    }

    /** 定義文書（アクティビティ・エージェント・skill・評価）の全文スキャン。 */
    private fun definitionDocMentions(ws: Workspace, token: String): List<String> {
        val re = tokenRegex(token)
        val mentioned = mutableListOf<String>()
        for (dir in listOf(ws.layout.activitiesDir, ws.layout.agentsDir, ws.layout.skillsDir, ws.layout.evalsDir)) {
            val d = ws.root.resolve(dir)
            if (!Files.isDirectory(d)) continue
            Files.list(d).use { stream ->
                stream.filter { p ->
                    val n = p.fileName.toString()
                    Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"))
                }.forEach { f ->
                    val text = runCatching { Files.readString(f) }.getOrNull() ?: return@forEach
                    if (re.containsMatchIn(text)) mentioned += ws.relPath(f)
                }
            }
        }
        if (mentioned.isEmpty()) return emptyList()
        return listOf(
            "定義文書に「$token」の言及があります（自動書換の対象外・要確認）: " +
                mentioned.take(5).joinToString("、") + (if (mentioned.size > 5) " 他${mentioned.size - 5}件" else "")
        )
    }

    private fun tokenRegex(token: String): Regex =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(token) + "(?![A-Za-z0-9_])")
}
