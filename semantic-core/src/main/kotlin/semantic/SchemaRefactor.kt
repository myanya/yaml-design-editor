package semantic

import edit.YamlOperation
import model.DocumentId
import model.YNode
import model.YPath
import model.getAt
import model.pretty
import model.scalarText
import schema.FieldDef
import schema.FieldType
import schema.SchemaKind
import schema.StructuralSchema

/**
 * スキーマ・リファクタリングのカーネル（P25。3層コア移行 RF-002b で core/SchemaRefactor.kt から
 * 薄切りで移植を開始。第1弾は renameField＝R25-01）。
 * フィールドの同定は FieldDef の**同一インスタンス**（`===`）。StructuralSchemaParser が定義を1回だけ
 * 生成し、`StructuralSchema.resolve`（浅いコピー）が子リストのインスタンスを保つことが前提（§4.35）。
 * ビュー定義・アクティビティ等の追随は行わない（6E 仮想文書の設計後に対応。警告で案内する）。
 */
object SemanticSchemaRefactor {

    // ================================================================ R25-01: フィールド改名

    fun renameField(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        documents: List<RefactorDocument>,
        request: RefactoringRequest.RenameField,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val newName = request.newName
        requireValidName(newName, "改名先のフィールド名")
        val entry = targetSchema(schemas, request.schemaId)
        val res = resolveField(schemas, entry, request.fieldPath)
        requireEditable(res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val target = res.site.def
        val oldName = target.key ?: error("フィールドのキーを特定できません: ${request.fieldPath}")
        require(newName != oldName) { "改名先が現在のフィールド名と同じです: $oldName" }
        require(newName !in res.siblings) { "同じ階層に「$newName」が既に定義されています（スキーマ側の衝突）" }
        verifyFieldNode(res.site, oldName, tree)

        val reachable = schemas.values.filter { it.kind == SchemaKind.DOCUMENT && reaches(it, schemas) { f -> f === target } }
        val reachableIds = reachable.map { it.id }.toSet()
        val builder = OpsBuilder()
        val conflicts = mutableListOf<String>()
        var occurrenceCount = 0
        var docFileCount = 0
        for (doc in documents) {
            val docSchema = doc.schemaId?.takeIf { it in reachableIds }?.let { schemas[it] } ?: continue
            val found = collectFieldHits(docSchema.structural, doc.root) { it === target }
            val fileConflicts = found.filter { hit ->
                (getAt(doc.root, hit.dropLast(1)) as? YNode.Map)?.entries?.containsKey(newName) == true
            }
            if (fileConflicts.isNotEmpty()) {
                conflicts += "${doc.id.value}（${fileConflicts.first().pretty()} など ${fileConflicts.size} 箇所）"
                continue
            }
            if (found.isEmpty()) continue
            occurrenceCount += found.size
            docFileCount++
            // 深い方から改名する（祖先キーの改名で子のパスが無効にならないように）
            builder.addAll(doc.id, found.sortedWith(pathDescending).map { YamlOperation.RenameKey(it, newName) })
        }
        if (conflicts.isNotEmpty()) {
            error(
                "改名先のキー「$newName」が既存文書に存在するため中止します（先に該当キーを整理してください）: " +
                    conflicts.take(5).joinToString("、") + (if (conflicts.size > 5) " 他${conflicts.size - 5}件" else "")
            )
        }

        // スキーマ側: フィールドキーの書換（＋ルート直下フィールドなら phases.key の追随）
        val definitionSource = res.site.module.source
            ?: error("フィールドの定義元スキーマに書き換え先の文書がありません: ${res.site.module.id}")
        builder.add(definitionSource, YamlOperation.SetScalar(res.site.path + "key", newName))
        var schemaFollowups = 1
        for (s in reachable) {
            if (s.structural.phases?.key != oldName || !isRootField(s.structural, target)) continue
            val source = s.source ?: continue
            val phasesNode = tree(source)?.let { getAt(it, listOf("phases")) } as? YNode.Map ?: continue
            if (scalarText(phasesNode.entries["key"]) != oldName) continue  // リスト省略形（key 暗黙）は対象外
            builder.add(source, YamlOperation.SetScalar(listOf("phases", "key"), newName))
            schemaFollowups++
        }

        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = listOf(
                "ビュー定義・アクティビティ等の「$oldName」への言及は追随しません（6E 仮想文書の設計後に対応。該当があれば手動で更新してください）",
            ),
            summary = listOf(
                "スキーマ「${request.schemaId}」のフィールド「$oldName」→「$newName」",
                "文書 $docFileCount ファイル・$occurrenceCount 箇所のキーを改名（順序・コメント保持）",
                "スキーマ定義 $schemaFollowups 箇所を追随",
            ) + (if (reachable.size > 1) listOf("影響スキーマ: ${reachable.joinToString("、") { it.id }}") else emptyList()),
        )
    }

    // ================================================================ スキーマ側の解決（Site 機構）

    /** スキーマ定義上の実体1件: 保持モジュール・そのスキーマ文書内のYAML座標・FieldDef インスタンス。 */
    private data class Site(val module: SchemaSemantics, val path: YPath, val def: FieldDef)

    /** 解決したフィールドと、同じ階層の兄弟キー（改名先の衝突検査用）。 */
    private class Target(val site: Site, val siblings: Set<String>)

    /** フィールド解決の結果: 実体・兄弟キー。 */
    private class Resolution(val site: Site, val siblings: Set<String>)

    private fun targetSchema(schemas: Map<String, SchemaSemantics>, schemaId: String): SchemaSemantics {
        val entry = schemas[schemaId] ?: error("スキーマ「$schemaId」が見つかりません")
        require(entry.kind != SchemaKind.RELATIONS) { "関係レジストリにフィールド定義はありません: $schemaId" }
        require(entry.kind != SchemaKind.LIBRARY) {
            "library の定義は、その定義を使う文書スキーマのフィールドパスで指定してください（定義側が書き換わり、全使用側の文書が対象になります）"
        }
        requireEditable(entry, "スキーマ「$schemaId」")
        return entry
    }

    private fun requireEditable(s: SchemaSemantics, label: String) {
        require(s.source != null) { "$label は組込み・派生スキーマのため変更できません" }
    }

    private fun requireValidName(name: String, label: String) {
        require(name.isNotBlank()) { "${label}を指定してください" }
        require(name.none { it in ".[]#" || it.isWhitespace() }) { "${label}に . [ ] # や空白は使えません: $name" }
    }

    /** スキーマYAMLの実体（フィールド定義の map）を確認する。解決結果と文書がずれていたら中止する安全確認。 */
    private fun verifyFieldNode(site: Site, expectedKey: String, tree: (DocumentId) -> YNode?) {
        val source = site.module.source ?: error("スキーマ「${site.module.id}」に書き換え先の文書がありません")
        val node = tree(source)?.let { getAt(it, site.path) } as? YNode.Map
            ?: error("スキーマ定義の実体が見つかりません: ${source.value} の ${site.path.pretty()}")
        require(scalarText(node.entries["key"]) == expectedKey) {
            "スキーマ定義の実体（${site.path.pretty()}）のキーが「$expectedKey」と一致しません"
        }
    }

    /** ドット区切りのフィールドパスを、スキーマ定義上の実体へ解決する。 */
    private fun resolveField(
        schemas: Map<String, SchemaSemantics>,
        entry: SchemaSemantics,
        fieldPath: String,
    ): Resolution {
        val segments = fieldPath.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "フィールドパスを指定してください（例 details.priority）" }
        var container = Site(entry, listOf("root"), entry.structural.root)
        var result: Resolution? = null
        segments.forEachIndexed { i, name ->
            container = descendContainers(schemas, entry, container)
            val found = lookupChild(schemas, entry, container, name, fieldPath)
            if (i == segments.lastIndex) result = Resolution(found.site, found.siblings)
            else container = found.site
        }
        return result!!
    }

    /**
     * `$ref` の連鎖を辿り、実体定義のサイトと「実効 cases を持つサイト」（連鎖上の最初の非空 cases）を返す。
     */
    private fun derefChain(
        schemas: Map<String, SchemaSemantics>,
        entry: SchemaSemantics,
        site: Site,
    ): Pair<Site, Site?> {
        var current = site
        var casesSite: Site? = null
        val seen = mutableSetOf<String>()
        while (true) {
            if (casesSite == null && current.def.cases.isNotEmpty()) casesSite = current
            if (current.def.ref == null) break
            val (key, targetDef) = entry.structural.definitionTarget(current.def) ?: break
            if (!seen.add(key.toString())) break
            val module = schemas[key.moduleId] ?: error("定義モジュール「${key.moduleId}」が見つかりません")
            current = Site(module, listOf("definitions", key.name), targetDef)
        }
        return current to casesSite
    }

    /** `$ref` を辿りながら array の item / map の value へ暗黙に降りる（object・スカラーに到達するまで）。 */
    private fun descendContainers(
        schemas: Map<String, SchemaSemantics>,
        entry: SchemaSemantics,
        site: Site,
    ): Site {
        var current = site
        while (true) {
            val (real, _) = derefChain(schemas, entry, current)
            current = when (real.def.type) {
                FieldType.ARRAY -> Site(real.module, real.path + "item", real.def.item ?: return real)
                FieldType.MAP -> Site(real.module, real.path + "value", real.def.value ?: return real)
                else -> return current
            }
        }
    }

    /** object コンテナから名前で子フィールドを引く（fields と cases 由来キーの両方。多義は拒否）。 */
    private fun lookupChild(
        schemas: Map<String, SchemaSemantics>,
        entry: SchemaSemantics,
        container: Site,
        name: String,
        fieldPath: String,
    ): Target {
        val (real, _) = derefChain(schemas, entry, container)
        require(real.def.type == FieldType.OBJECT) {
            "「$name」の親が object ではありません（${real.def.type.id}）: $fieldPath"
        }
        val candidates = mutableListOf<Site>()
        val siblings = mutableSetOf<String>()
        real.def.fields.forEachIndexed { i, f ->
            f.key?.let(siblings::add)
            if (f.key == name) candidates += Site(real.module, real.path + "fields" + i, f)
        }
        // enum 子フィールドの cases 由来の兄弟キー（判別共用体。effectiveFields の静的近似＝全ケースの和）
        real.def.fields.forEachIndexed { i, f ->
            val (_, casesSite) = derefChain(schemas, entry, Site(real.module, real.path + "fields" + i, f))
            val cases = casesSite ?: return@forEachIndexed
            cases.def.cases.forEach { (caseValue, list) ->
                list.forEachIndexed { j, cf ->
                    cf.key?.let(siblings::add)
                    if (cf.key == name) candidates += Site(cases.module, cases.path + "cases" + caseValue + j, cf)
                }
            }
        }
        require(candidates.isNotEmpty()) {
            "フィールド「$name」が見つかりません（$fieldPath）。この階層のフィールド: ${siblings.sorted().joinToString(", ")}"
        }
        require(candidates.size == 1) {
            "フィールド「$name」が複数の case で定義されているため特定できません（$fieldPath）。スキーマのソースで直接編集してください"
        }
        return Target(candidates.single(), siblings)
    }

    /** スキーマの走査で条件に合うフィールドへ到達できるか（$ref 共有の影響判定）。 */
    private fun reaches(
        s: SchemaSemantics,
        schemas: Map<String, SchemaSemantics>,
        predicate: (FieldDef) -> Boolean,
    ): Boolean {
        var found = false
        val seen = mutableSetOf<String>()
        fun walk(f: FieldDef) {
            if (found) return
            if (predicate(f)) {
                found = true
                return
            }
            val located = s.structural.definitionTarget(f)
            if (located != null && !seen.add(located.first.toString())) return
            val r = s.structural.resolve(f) ?: f
            r.fields.forEach(::walk)
            r.item?.let(::walk)
            r.value?.let(::walk)
            r.cases.values.flatten().forEach(::walk)
        }
        walk(s.structural.root)
        return found
    }

    /** target がスキーマのルート直下フィールドか（phases.key の追随判定）。 */
    private fun isRootField(s: StructuralSchema, target: FieldDef): Boolean =
        (s.resolve(s.root) ?: s.root).fields.any { it === target }

    // ================================================================ 文書側の列挙

    /**
     * 文書ツリーをスキーマ誘導で走査し、条件に合うフィールドに対応するデータキーの座標を集める。
     * cases は effectiveFields（現在の enum 値で実効なフィールドだけ）を通すため、
     * 同名キーを持つ別 case の値を誤って対象にしない。
     */
    private fun collectFieldHits(s: StructuralSchema, root: YNode, predicate: (FieldDef) -> Boolean): List<YPath> {
        val hits = mutableListOf<YPath>()
        fun walk(def: FieldDef, node: YNode, path: YPath) {
            val eff = s.resolve(def) ?: def
            when (eff.type) {
                FieldType.OBJECT -> {
                    val m = node as? YNode.Map ?: return
                    s.effectiveFields(eff.fields, m).fields.forEach { f ->
                        val k = f.key ?: return@forEach
                        val child = m.entries[k] ?: return@forEach
                        if (predicate(f)) hits += path + k
                        walk(f, child, path + k)
                    }
                }
                FieldType.ARRAY -> {
                    val seq = node as? YNode.Seq ?: return
                    val item = eff.item ?: return
                    seq.items.forEachIndexed { i, child -> walk(item, child, path + i) }
                }
                FieldType.MAP -> {
                    val m = node as? YNode.Map ?: return
                    val value = eff.value ?: return
                    m.entries.forEach { (k, child) -> walk(value, child, path + k) }
                }
                else -> Unit
            }
        }
        walk(s.root, root, emptyList())
        return hits
    }

    /** 同一文書内の削除・改名を深い方から行うためのパス降順（数値セグメントは数値比較）。 */
    private val pathDescending = Comparator<YPath> { x, y ->
        for (i in 0 until minOf(x.size, y.size)) {
            val sx = x[i]
            val sy = y[i]
            val c = if (sx is Int && sy is Int) sx.compareTo(sy) else sx.toString().compareTo(sy.toString())
            if (c != 0) return@Comparator -c
        }
        -(x.size.compareTo(y.size))
    }

    /** 文書単位の操作を集める（文書ID順）。 */
    private class OpsBuilder {
        private val byDoc = LinkedHashMap<DocumentId, MutableList<YamlOperation>>()

        fun add(document: DocumentId, op: YamlOperation) {
            byDoc.getOrPut(document) { mutableListOf() } += op
        }

        fun addAll(document: DocumentId, ops: List<YamlOperation>) {
            if (ops.isNotEmpty()) byDoc.getOrPut(document) { mutableListOf() } += ops
        }

        fun toChanges(): List<PlannedDocumentChange> =
            byDoc.entries.sortedBy { it.key.value }.map { (document, ops) -> PlannedDocumentChange(document, ops) }
    }
}
