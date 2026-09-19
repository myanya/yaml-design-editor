package semantic

import edit.YamlOperation
import model.DocumentId
import model.YNode
import model.YPath
import model.deepCopy
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

        // 文書側: Query＝対象フィールドのデータキー／Predicate＝改名先キーが同じ親に無いこと（あれば中止）／Edit＝RenameKey
        val reach = SemanticRewrite.reach(schemas) { f -> f === target }
        val reachable = reach.schemas
        val builder = SemanticRewrite.PlanOps()
        val conflicts = mutableListOf<String>()
        val tally = SemanticRewrite.rewriteDocuments(reach.documents(documents), builder) { doc, docSchema ->
            val found = SemanticRewrite.fieldHits(docSchema.structural, doc.root) { it === target }
            val fileConflicts = found.filter { hit ->
                (getAt(doc.root, hit.dropLast(1)) as? YNode.Map)?.entries?.containsKey(newName) == true
            }
            if (fileConflicts.isNotEmpty()) {
                conflicts += "${doc.id.value}（${fileConflicts.first().pretty()} など ${fileConflicts.size} 箇所）"
                return@rewriteDocuments emptyList()
            }
            // 深い方から改名する（祖先キーの改名で子のパスが無効にならないように）
            found.sortedWith(SemanticRewrite.pathDescending).map { YamlOperation.RenameKey(it, newName) }
        }
        if (conflicts.isNotEmpty()) {
            error(
                "改名先のキー「$newName」が既存文書に存在するため中止します（先に該当キーを整理してください）: " +
                    SemanticRewrite.listTop(conflicts)
            )
        }

        // スキーマ側: フィールドキーの書換（＋ルート直下フィールドなら phases.key の追随）
        val definitionSource = sourceOf(res.site.module)
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
            warnings = nonFollowupWarning(oldName),
            summary = listOf(
                "スキーマ「${request.schemaId}」のフィールド「$oldName」→「$newName」",
                "文書 ${tally.files} ファイル・${tally.edits} 箇所のキーを改名（順序・コメント保持）",
                "スキーマ定義 $schemaFollowups 箇所を追随",
            ) + (if (reachable.size > 1) listOf("影響スキーマ: ${reachable.joinToString("、") { it.id }}") else emptyList()),
        )
    }

    // ================================================================ R25-02: フィールドの親子移動

    fun moveField(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        documents: List<RefactorDocument>,
        request: RefactoringRequest.MoveField,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (schemaId, fieldPath, destPath, deleteEmptyParents) = request
        val entry = targetSchema(schemas, schemaId)
        val src = resolveField(schemas, entry, fieldPath)
        require("cases" !in src.site.path) { "cases 由来のフィールドの移動は未対応です（スキーマのソースで編集してください）" }
        requireEditable(src.site.module, "フィールドの定義元（${src.site.module.id}）")
        val target = src.site.def
        val key = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        verifyFieldNode(src.site, key, tree)
        val dst = resolveContainer(schemas, entry, destPath)

        // 共有定義の境界と要素コンテキストの検査
        val srcInDef = src.site.path.firstOrNull() == "definitions"
        val dstInDef = dst.objectSite.path.firstOrNull() == "definitions"
        if (srcInDef || dstInDef) {
            require(
                srcInDef && dstInDef && src.site.module.id == dst.objectSite.module.id &&
                    src.site.path.getOrNull(1) == dst.objectSite.path.getOrNull(1)
            ) { "共有定義（definitions）をまたぐ移動はできません（他の使用箇所の文書が壊れるため）" }
        } else {
            require(src.site.module.id == dst.objectSite.module.id) { "別スキーマへの移動はできません" }
        }
        require(src.crossings == dst.crossings) {
            "配列・map の境界をまたぐ移動はできません（移動元と移動先が同じ要素コンテキストにありません）"
        }
        require(
            !(dst.objectSite.path.size >= src.site.path.size &&
                dst.objectSite.path.subList(0, src.site.path.size) == src.site.path)
        ) { "フィールド自身の配下へは移動できません" }
        val srcSeqPath = src.site.path.dropLast(1)
        require(srcSeqPath != dst.objectSite.path + "fields") {
            "同じ親への移動です（並び順の変更はスキーマ設計UIの上下ボタンを使ってください）"
        }
        require(key !in childKeysOf(schemas, entry, dst.objectSite)) { "移動先に同名のフィールド「$key」が既にあります" }

        val reach = SemanticRewrite.reach(schemas) { f -> f === target }
        val reachable = reach.schemas
        for (s in reachable) {
            require(!(s.structural.phases?.key == key && isRootField(s.structural, target) && dst.objectSite.path != listOf<Any>("root"))) {
                "フェーズ申告キー「$key」はルート直下に必要なため移動できません（スキーマ「${s.id}」の phases）"
            }
        }

        // 文書側: 値の移動（中間 object の生成・空親の削除・衝突検査）。場所ごとの操作が独立でない
        // （中間生成 → Move → 空親の削除の列）ため、操作列は moveDataOps が組み立てる（Rewrite の戦略側）
        val builder = SemanticRewrite.PlanOps()
        val conflicts = mutableListOf<String>()
        var moved = 0
        val tally = SemanticRewrite.rewriteDocuments(reach.documents(documents), builder) { doc, docSchema ->
            val (ops, fileConflicts) =
                moveDataOps(docSchema.structural, doc.root, target, src.tailKeys.size, dst.tailKeys, key, deleteEmptyParents)
            if (fileConflicts.isNotEmpty()) {
                conflicts += "${doc.id.value}（${fileConflicts.first()} など ${fileConflicts.size} 件）"
                return@rewriteDocuments emptyList()
            }
            moved += ops.count { it is YamlOperation.Move }
            ops
        }
        if (conflicts.isNotEmpty()) {
            error("移動先に既存の値があるため中止します（先に該当キーを整理してください）: " + SemanticRewrite.listTop(conflicts))
        }

        // スキーマ側: 定義の移動（移動先の fields が無ければ生成。同一ファイル内の削除によるインデックスずれを調整）
        val definitionSource = sourceOf(src.site.module)
        val moduleTree = tree(definitionSource) ?: error("スキーマ文書が読めません: ${definitionSource.value}")
        val dstObjNode = getAt(moduleTree, dst.objectSite.path) as? YNode.Map
            ?: error("移動先の実体が見つかりません: ${dst.objectSite.path.pretty()}")
        val dstFields = dstObjNode.entries["fields"] as? YNode.Seq
        if (dstFields == null) builder.add(definitionSource, YamlOperation.Replace(dst.objectSite.path + "fields", YNode.Seq()))
        val srcIdx = src.site.path.last() as Int
        val adjustedDst = adjustAfterDelete(dst.objectSite.path, srcSeqPath, srcIdx)
        builder.add(definitionSource, YamlOperation.Move(src.site.path, adjustedDst + "fields" + (dstFields?.items?.size ?: 0)))

        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = listOf("移動によるビュー等の「$key」参照の自動追随は対象外・要修正") + nonFollowupWarning(key),
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$fieldPath」→「${if (destPath.isBlank() || destPath == ".") "（ルート）" else destPath}」配下へ移動",
                "文書 ${tally.files} ファイル・$moved 箇所の値を移動" +
                    (if (deleteEmptyParents) "（空になった親 object は削除）" else ""),
            ) + (if (reachable.size > 1) listOf("影響スキーマ: ${reachable.joinToString("、") { it.id }}") else emptyList()),
        )
    }

    // ================================================================ R25-02: スキーマ順への整列

    fun alignOrder(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        documents: List<RefactorDocument>,
        request: RefactoringRequest.AlignOrder,
    ): RefactoringPlan {
        val schemaId = request.schemaId
        val entry = schemas[schemaId] ?: error("スキーマ「$schemaId」が見つかりません")
        require(entry.kind == SchemaKind.DOCUMENT) { "文書スキーマを指定してください: $schemaId" }
        require(entry.source != null) { "組込みスキーマは対象外です: $schemaId" }
        // Query＝このスキーマの文書の全 object ノード／Edit＝現在のキー順とスキーマ順が違う object への ReorderKeys
        // （操作は場所の関数——キー集合とフィールド順から計算する）。スキーマ自体は変更しない
        val builder = SemanticRewrite.PlanOps()
        val targets = documents.filter { it.schemaId == schemaId }.map { it to entry }
        val tally = SemanticRewrite.rewriteDocuments(targets, builder) { doc, docSchema ->
            val ops = mutableListOf<YamlOperation>()
            SemanticRewrite.walkDocument(docSchema.structural, doc.root, onObject = { path, m, fs ->
                val current = m.entries.keys.toList()
                val head = if (path.isEmpty() && "_document" in m.entries) listOf("_document") else emptyList()
                val known = fs.mapNotNull { it.key }.filter { it in m.entries && it !in head }.distinct()
                val desired = head + known + current.filter { it !in head && it !in known }
                if (desired != current) ops += YamlOperation.ReorderKeys(path, desired)
            })
            ops
        }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = emptyList(),
            summary = listOf(
                "スキーマ「$schemaId」の文書をスキーマ順へ整列",
                "文書 ${tally.files} ファイル・${tally.edits} 要素のキー順を並べ替え（値・コメント保持。未知キーは末尾に相対順で残す）",
            ),
        )
    }

    // ================================================================ R25-03: enum選択肢の改名

    fun renameEnumValue(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        documents: List<RefactorDocument>,
        request: RefactoringRequest.RenameEnumValue,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (schemaId, fieldPath, oldValue, newValue) = request
        require(newValue.isNotBlank()) { "改名先の値を指定してください" }
        require(newValue != oldValue) { "改名先が現在の値と同じです: $oldValue" }
        val entry = targetSchema(schemas, schemaId)
        val res = resolveField(schemas, entry, fieldPath)
        val (owner, casesSite) = derefChain(schemas, entry, res.site)
        requireEditable(owner.module, "選択肢の定義元（${owner.module.id}）")
        require(owner.def.type == FieldType.ENUM) { "enum 型ではありません（${owner.def.type.id}）: $fieldPath" }
        require(oldValue in owner.def.values) { "選択肢に「$oldValue」がありません（選択肢: ${owner.def.values.joinToString(", ")}）" }
        require(newValue !in owner.def.values) { "選択肢「$newValue」は既にあります" }

        val builder = SemanticRewrite.PlanOps()
        // 選択肢の実体（文字列 or リッチ選択肢）
        val ownerSource = sourceOf(owner.module)
        val ownerTree = tree(ownerSource) ?: error("スキーマ文書が読めません: ${ownerSource.value}")
        val valuesSeq = getAt(ownerTree, owner.path + "values") as? YNode.Seq
            ?: error("values の実体が見つかりません: ${owner.path.pretty()}")
        val idx = valuesSeq.items.indexOfFirst { item ->
            scalarText(item) == oldValue || scalarText((item as? YNode.Map)?.entries?.get("value")) == oldValue
        }
        require(idx >= 0) { "values の実体に「$oldValue」が見つかりません" }
        val valuePath = if (valuesSeq.items[idx] is YNode.Map) owner.path + "values" + idx + "value" else owner.path + "values" + idx
        builder.add(ownerSource, YamlOperation.SetScalar(valuePath, newValue))
        // cases のキー（実効 cases を持つサイト）
        if (casesSite != null && oldValue in casesSite.def.cases) {
            builder.add(sourceOf(casesSite.module), YamlOperation.RenameKey(casesSite.path + "cases" + oldValue, newValue))
        }
        // default（使用サイト・定義サイトの両方を確認）
        val sites = listOf(res.site, owner).distinctBy { (it.module.source?.value ?: it.module.id) + it.path.pretty() }
        for (site in sites) {
            val source = site.module.source ?: continue
            val node = tree(source)?.let { getAt(it, site.path) } as? YNode.Map ?: continue
            if (scalarText(node.entries["default"]) == oldValue) {
                builder.add(source, YamlOperation.SetScalar(site.path + "default", newValue))
            }
        }
        // フェーズ申告フィールドなら order / requiredFrom / activeFrom を追随（フェーズ名の改名にあたる）
        val target = res.site.def
        val fieldKey = target.key
        val reach = SemanticRewrite.reach(schemas) { f -> finalDef(entry, f) === owner.def || f === target }
        for (s in reach.schemas) {
            if (s.structural.phases?.key != fieldKey || !isRootField(s.structural, target)) continue
            val source = s.source ?: continue
            val sTree = tree(source) ?: continue
            when (val ph = (sTree as? YNode.Map)?.entries?.get("phases")) {
                is YNode.Map -> (ph.entries["order"] as? YNode.Seq)?.items?.forEachIndexed { i, v ->
                    if (scalarText(v) == oldValue) builder.add(source, YamlOperation.SetScalar(listOf("phases", "order", i), newValue))
                }
                is YNode.Seq -> ph.items.forEachIndexed { i, v ->
                    if (scalarText(v) == oldValue) builder.add(source, YamlOperation.SetScalar(listOf("phases", i), newValue))
                }
                else -> Unit
            }
            SemanticRewrite.walkEntries(sTree) { path, entryKey, value ->
                if (entryKey in setOf("requiredFrom", "activeFrom") && scalarText(value) == oldValue) {
                    builder.add(source, YamlOperation.SetScalar(path + entryKey, newValue))
                }
            }
        }
        // 文書の現在値: Query＝対象 enum フィールドのデータキー／Predicate＝値が旧選択肢であること／Edit＝SetScalar
        val tally = SemanticRewrite.rewriteDocuments(reach.documents(documents), builder) { doc, docSchema ->
            SemanticRewrite.fieldHits(docSchema.structural, doc.root) { finalDef(docSchema, it) === owner.def || it === target }
                .filter { scalarText(getAt(doc.root, it)) == oldValue }
                .map { YamlOperation.SetScalar(it, newValue) }
        }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = nonFollowupWarning(oldValue),
            summary = listOf(
                "スキーマ「$schemaId」の「$fieldPath」の選択肢「$oldValue」→「$newValue」",
                "文書 ${tally.files} ファイル・${tally.edits} 箇所の値を書き換え",
            ),
        )
    }

    // ================================================================ R25-03: フィールド削除

    fun deleteField(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        documents: List<RefactorDocument>,
        request: RefactoringRequest.DeleteField,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (schemaId, fieldPath) = request
        val entry = targetSchema(schemas, schemaId)
        val res = resolveField(schemas, entry, fieldPath)
        requireEditable(res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val target = res.site.def
        val key = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val (real, _) = derefChain(schemas, entry, res.site)
        require(real.def.type != FieldType.ID) { "ID型フィールドは削除できません（参照・エンティティが壊れます。要素の削除は entity delete を使ってください）" }
        verifyFieldNode(res.site, key, tree)
        val reach = SemanticRewrite.reach(schemas) { f -> f === target }
        val reachable = reach.schemas
        for (s in reachable) {
            require(!(s.structural.phases?.key == key && isRootField(s.structural, target))) {
                "フェーズ申告キー「$key」は削除できません（スキーマ「${s.id}」の phases が参照しています）"
            }
        }

        // 文書側: Query＝対象フィールドのデータキー／Predicate なし（全件）／Edit＝Remove（深い方から）
        val builder = SemanticRewrite.PlanOps()
        var valueCount = 0
        val tally = SemanticRewrite.rewriteDocuments(reach.documents(documents), builder) { doc, docSchema ->
            val hits = SemanticRewrite.fieldHits(docSchema.structural, doc.root) { it === target }
            valueCount += hits.count { hit ->
                when (val node = getAt(doc.root, hit)) {
                    is YNode.Scalar -> !scalarText(node).isNullOrBlank()
                    is YNode.Map -> node.entries.isNotEmpty()
                    is YNode.Seq -> node.items.isNotEmpty()
                    else -> false
                }
            }
            hits.sortedWith(SemanticRewrite.pathDescending).map { YamlOperation.Remove(it) }
        }
        // スキーマ側: 定義の削除と entity.owns の掃除
        val definitionSource = sourceOf(res.site.module)
        builder.add(definitionSource, YamlOperation.Remove(res.site.path))
        for (s in reachable) {
            val source = s.source ?: continue
            val sTree = tree(source) ?: continue
            ownsEntryPaths(sTree, key).sortedWith(SemanticRewrite.pathDescending).forEach { p ->
                builder.add(source, YamlOperation.Remove(p))
            }
        }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = listOf("削除によりビュー等の「$key」への参照は壊れます・要修正") + nonFollowupWarning(key),
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$fieldPath」を削除",
                "文書 ${tally.files} ファイル・${tally.edits} 箇所のキーを削除（**うち値あり $valueCount 箇所**＝データが失われます）",
            ) + (if (reachable.size > 1) listOf("影響スキーマ: ${reachable.joinToString("、") { it.id }}") else emptyList()),
        )
    }

    // ================================================================ R25-03: 必須化バックフィル

    fun requireField(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        documents: List<RefactorDocument>,
        request: RefactoringRequest.RequireField,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (schemaId, fieldPath, defaultRaw, fromPhase) = request
        require(defaultRaw.isNotBlank()) { "--default（充填する既定値）を指定してください" }
        val entry = targetSchema(schemas, schemaId)
        val res = resolveField(schemas, entry, fieldPath)
        requireEditable(res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val target = res.site.def
        val key = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val (real, _) = derefChain(schemas, entry, res.site)
        val fillable = setOf(
            FieldType.STRING, FieldType.TEXT, FieldType.INT, FieldType.NUMBER,
            FieldType.BOOLEAN, FieldType.ENUM, FieldType.DATE, FieldType.REF,
        )
        require(real.def.type in fillable) { "型 ${real.def.type.id} は一括充填の対象外です（スカラー型のみ）" }
        fun defaultValue(): Any = when (real.def.type) {
            FieldType.INT -> defaultRaw.toLongOrNull() ?: error("--default は整数である必要があります: $defaultRaw")
            FieldType.NUMBER -> defaultRaw.toDoubleOrNull() ?: error("--default は数値である必要があります: $defaultRaw")
            FieldType.BOOLEAN -> defaultRaw.toBooleanStrictOrNull() ?: error("--default は true / false である必要があります: $defaultRaw")
            FieldType.ENUM -> {
                require(defaultRaw in real.def.values) { "--default は選択肢のいずれかである必要があります: ${real.def.values.joinToString(", ")}" }
                defaultRaw
            }
            else -> defaultRaw
        }
        defaultValue()  // 型検査を先に走らせる
        if (fromPhase != null) {
            val phases = entry.structural.phases ?: error("requiredFrom を使うにはスキーマ「$schemaId」に phases の宣言が必要です")
            require(fromPhase in phases.order) { "フェーズ「$fromPhase」は phases（${phases.order.joinToString(", ")}）にありません" }
        }
        val node = verifyFieldNode(res.site, key, tree)

        val builder = SemanticRewrite.PlanOps()
        val definitionSource = sourceOf(res.site.module)
        // スキーマ側: required / requiredFrom（併用不可のため他方を消す）
        if (fromPhase != null) {
            builder.add(definitionSource, YamlOperation.SetScalar(res.site.path + "requiredFrom", fromPhase))
            if ("required" in node.entries) builder.add(definitionSource, YamlOperation.Remove(res.site.path + "required"))
        } else {
            if (scalarText(node.entries["required"]) != "true") {
                builder.add(definitionSource, YamlOperation.SetScalar(res.site.path + "required", true))
            }
            if ("requiredFrom" in node.entries) builder.add(definitionSource, YamlOperation.Remove(res.site.path + "requiredFrom"))
        }
        // 文書側: 欠落・空値の充填。Query＝対象フィールドを実効フィールドに持つ親 object（値が無い場所も含む）／
        // Predicate＝requiredFrom ならフェーズ到達文書だけ・値が欠落または空であること／Edit＝SetScalar
        val reach = SemanticRewrite.reach(schemas) { f -> f === target }
        val tally = SemanticRewrite.rewriteDocuments(reach.documents(documents), builder) { doc, docSchema ->
            if (fromPhase != null) {
                val order = docSchema.structural.phases?.order ?: return@rewriteDocuments emptyList()
                val phaseIdx = docSchema.structural.currentPhaseIndex(doc.root) ?: return@rewriteDocuments emptyList()
                val fromIdx = order.indexOf(fromPhase)
                if (fromIdx < 0 || fromIdx > phaseIdx) return@rewriteDocuments emptyList()
            }
            SemanticRewrite.parentObjects(docSchema.structural, doc.root, target).mapNotNull { (path, m) ->
                val current = m.entries[key]
                val missing = current == null || (current is YNode.Scalar && scalarText(current).isNullOrBlank())
                if (missing) YamlOperation.SetScalar(path + key, defaultValue()) else null
            }
        }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = emptyList(),
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$fieldPath」を必須化" + (fromPhase?.let { "（フェーズ「$it」以降）" } ?: ""),
                "文書 ${tally.files} ファイル・${tally.edits} 箇所へ既定値「$defaultRaw」を充填",
            ),
        )
    }

    // ================================================================ R25-03: 型変換

    fun convertFieldType(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        documents: List<RefactorDocument>,
        request: RefactoringRequest.ConvertFieldType,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (schemaId, fieldPath, targetTypeId, enumValues) = request
        val entry = targetSchema(schemas, schemaId)
        val res = resolveField(schemas, entry, fieldPath)
        requireEditable(res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val target = res.site.def
        val key = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val node = verifyFieldNode(res.site, key, tree)
        require(node.entries["\$ref"] == null && node.entries["ref"] == null) {
            "参照（\$ref）フィールドは定義側を変換してください（使用側のパスで定義内フィールドを指定できます）"
        }
        require(node.entries["cases"] == null) { "cases を持つフィールドの型変換は未対応です" }
        val curType = target.type
        val scalarTypes = setOf(
            FieldType.STRING, FieldType.TEXT, FieldType.INT, FieldType.NUMBER,
            FieldType.BOOLEAN, FieldType.DATE, FieldType.ENUM,
        )
        val constraintKeys = listOf("values", "pattern", "minLength", "maxLength", "min", "max")
        val builder = SemanticRewrite.PlanOps()
        val schemaFile = sourceOf(res.site.module)
        val reach = SemanticRewrite.reach(schemas) { f -> f === target }
        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var converted = 0
        var docFileCount = 0

        // 文書側の変換: Query＝対象フィールドのデータキー／Edit＝transform（null は「操作なし」。変換できない値は
        // violations へ集め、全文書の評価後にまとめて中止する）
        fun convertDocs(transform: (YNode, YPath, DocumentId) -> YamlOperation?) {
            val tally = SemanticRewrite.rewriteDocuments(reach.documents(documents), builder) { doc, docSchema ->
                SemanticRewrite.fieldHits(docSchema.structural, doc.root) { it === target }.mapNotNull { hit ->
                    getAt(doc.root, hit)?.let { value -> transform(value, hit, doc.id) }
                }
            }
            converted += tally.edits
            docFileCount += tally.files
        }

        when {
            // スカラー → 配列（wrap）
            targetTypeId == FieldType.ARRAY.id && curType in scalarTypes -> {
                val item = YNode.Map()
                item.entries["type"] = YNode.Scalar(curType.id)
                constraintKeys.forEach { k -> node.entries[k]?.let { item.entries[k] = it.deepCopy() } }
                builder.add(schemaFile, YamlOperation.SetScalar(res.site.path + "type", FieldType.ARRAY.id))
                (constraintKeys + "default").forEach { k ->
                    if (k in node.entries) builder.add(schemaFile, YamlOperation.Remove(res.site.path + k))
                }
                if ("default" in node.entries) warnings += "default はスカラー用のため削除します（必要なら配列用に再設定してください）"
                builder.add(schemaFile, YamlOperation.Insert(res.site.path + "item", item))
                convertDocs { value, hit, _ ->
                    when {
                        value is YNode.Scalar && !scalarText(value).isNullOrBlank() ->
                            YamlOperation.Replace(hit, YNode.Seq(mutableListOf(YNode.Scalar(value.value))))
                        else -> null
                    }
                }
            }
            // 配列 → 要素の型（unwrap）
            curType == FieldType.ARRAY -> {
                val itemDef = target.item ?: error("配列の item 定義がありません")
                require(itemDef.ref == null) { "要素が定義参照（\$ref）の配列は解除できません" }
                require(targetTypeId == itemDef.type.id) { "配列の解除は要素の型（${itemDef.type.id}）を変換先に指定してください" }
                require(itemDef.type in scalarTypes) { "スカラー要素の配列のみ解除できます（要素: ${itemDef.type.id}）" }
                convertDocs { value, hit, docId ->
                    when {
                        value !is YNode.Seq -> null
                        value.items.isEmpty() -> YamlOperation.Remove(hit)
                        value.items.size == 1 && value.items[0] is YNode.Scalar ->
                            YamlOperation.Replace(hit, YNode.Scalar((value.items[0] as YNode.Scalar).value))
                        else -> {
                            violations += "${docId.value} の ${hit.pretty()}（要素 ${value.items.size} 件）"
                            null
                        }
                    }
                }
                builder.add(schemaFile, YamlOperation.SetScalar(res.site.path + "type", targetTypeId))
                val itemNode = node.entries["item"] as? YNode.Map
                constraintKeys.forEach { k ->
                    itemNode?.entries?.get(k)?.let { builder.add(schemaFile, YamlOperation.Replace(res.site.path + k, it.deepCopy())) }
                }
                builder.add(schemaFile, YamlOperation.Remove(res.site.path + "item"))
            }
            // string / text → enum（検証つき）
            targetTypeId == FieldType.ENUM.id && curType in setOf(FieldType.STRING, FieldType.TEXT) -> {
                require(enumValues.isNotEmpty()) { "--values で選択肢を指定してください（例 --values open,done）" }
                convertDocs { value, hit, docId ->
                    val text = scalarText(value)
                    if (!text.isNullOrBlank() && text !in enumValues) {
                        violations += "${docId.value} の ${hit.pretty()}（値「$text」）"
                    }
                    null
                }
                builder.add(schemaFile, YamlOperation.SetScalar(res.site.path + "type", FieldType.ENUM.id))
                builder.add(
                    schemaFile,
                    YamlOperation.Replace(res.site.path + "values", YNode.Seq(enumValues.mapTo(mutableListOf()) { YNode.Scalar(it) })),
                )
            }
            // enum → string / text
            curType == FieldType.ENUM && targetTypeId in setOf(FieldType.STRING.id, FieldType.TEXT.id) -> {
                builder.add(schemaFile, YamlOperation.SetScalar(res.site.path + "type", targetTypeId))
                if ("values" in node.entries) builder.add(schemaFile, YamlOperation.Remove(res.site.path + "values"))
            }
            // string / text → int / number（検証つきの型付け）
            targetTypeId in setOf(FieldType.INT.id, FieldType.NUMBER.id) && curType in setOf(FieldType.STRING, FieldType.TEXT) -> {
                convertDocs { value, hit, docId ->
                    val text = scalarText(value)?.trim()
                    if (text.isNullOrBlank()) return@convertDocs null
                    val typed: Any? = if (targetTypeId == FieldType.INT.id) text.toLongOrNull() else text.toDoubleOrNull()
                    if (typed == null) {
                        violations += "${docId.value} の ${hit.pretty()}（値「$text」）"
                        null
                    } else YamlOperation.SetScalar(hit, typed)
                }
                builder.add(schemaFile, YamlOperation.SetScalar(res.site.path + "type", targetTypeId))
            }
            // int / number / boolean / date → string / text（文字列化）
            targetTypeId in setOf(FieldType.STRING.id, FieldType.TEXT.id) &&
                curType in setOf(FieldType.INT, FieldType.NUMBER, FieldType.BOOLEAN, FieldType.DATE) -> {
                convertDocs { value, hit, _ ->
                    val text = scalarText(value) ?: return@convertDocs null
                    YamlOperation.SetScalar(hit, text)
                }
                builder.add(schemaFile, YamlOperation.SetScalar(res.site.path + "type", targetTypeId))
            }
            else -> error(
                "対応していない型変換です: ${curType.id} → $targetTypeId" +
                    "（対応: スカラー→array・array→要素型・string→enum/int/number・enum→string・int/number/boolean/date→string）"
            )
        }
        if (violations.isNotEmpty()) {
            error("変換できない値があるため中止します: " + SemanticRewrite.listTop(violations))
        }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = warnings,
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$fieldPath」を ${curType.id} → $targetTypeId へ変換",
                "文書 $docFileCount ファイル・$converted 箇所の値を変換",
            ),
        )
    }

    // ================================================================ R25-04: 定義・関係の改名

    /** named definition の改名: definitions のキーと全スキーマの `$ref`（非修飾・修飾）を書き換える。文書は変わらない。 */
    fun renameDefinition(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        request: RefactoringRequest.RenameDefinition,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (moduleId, defName, newName) = request
        requireValidName(newName, "改名先の定義名")
        require(newName != defName) { "改名先が現在の定義名と同じです: $defName" }
        val module = schemas[moduleId] ?: error("スキーマ「$moduleId」が見つかりません")
        requireEditable(module, "スキーマ「$moduleId」")
        val definitions = module.structural.definitions
        require(defName in definitions) {
            "定義「$defName」が見つかりません（定義: ${definitions.keys.sorted().joinToString(", ")}）"
        }
        require(newName !in definitions) { "定義「$newName」は既にあります" }

        // Query＝全スキーマ文書の `$ref` / `ref` エントリ／Predicate＝この定義を指すこと／Edit＝SetScalar
        val builder = SemanticRewrite.PlanOps()
        builder.add(sourceOf(module), YamlOperation.RenameKey(listOf("definitions", defName), newName))
        var refCount = 0
        SemanticRewrite.forEachSchemaEntry(schemas, tree) { s, source, path, entryKey, value ->
            val ref = definitionRefOf(s, entryKey, value) ?: return@forEachSchemaEntry
            if (ref.moduleId == moduleId && ref.name == defName) {
                builder.add(source, YamlOperation.SetScalar(path + entryKey, if (ref.qualified) "$moduleId#$newName" else newName))
                refCount++
            }
        }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = nonFollowupWarning(defName),
            summary = listOf(
                "定義「$moduleId#$defName」→「$moduleId#$newName」",
                "スキーマの参照 $refCount 箇所を書き換え（文書は変わりません）",
            ),
        )
    }

    /**
     * 定義の抽出: インラインのフィールド構造（type / fields / item / values など）を named definition へ
     * 移し、フィールドは `$ref` 参照に置き換える（key / label / required / description / role は使用側に残す）。
     * 文書は変わらない。
     */
    fun extractDefinition(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        request: RefactoringRequest.ExtractDefinition,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (schemaId, fieldPath, defName) = request
        requireValidName(defName, "定義名")
        val entry = targetSchema(schemas, schemaId)
        val res = resolveField(schemas, entry, fieldPath)
        requireEditable(res.site.module, "フィールドの定義元（${res.site.module.id}）")
        require(defName !in res.site.module.structural.definitions) { "定義「$defName」は既にあります" }
        val key = res.site.def.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val node = verifyFieldNode(res.site, key, tree)
        require(node.entries["\$ref"] == null && node.entries["ref"] == null) { "既に定義参照（\$ref）です: $fieldPath" }
        val keep = setOf("key", "label", "required", "requiredFrom", "description", "role")
        val moved = node.entries.keys.filter { it !in keep }
        require(moved.isNotEmpty()) { "抽出する構造がありません（type などの構造キーが必要です）" }

        val builder = SemanticRewrite.PlanOps()
        val file = sourceOf(res.site.module)
        val fileTree = tree(file) ?: error("スキーマ文書が読めません: ${file.value}")
        val defMap = YNode.Map()
        moved.forEach { k -> defMap.entries[k] = node.entries[k]!!.deepCopy() }
        if ((fileTree as? YNode.Map)?.entries?.get("definitions") == null) {
            builder.add(file, YamlOperation.Replace(listOf("definitions"), YNode.Map()))
        }
        builder.add(file, YamlOperation.Insert(listOf("definitions", defName), defMap))
        moved.forEach { k -> builder.add(file, YamlOperation.Remove(res.site.path + k)) }
        builder.add(file, YamlOperation.Insert(res.site.path + "\$ref", YNode.Scalar(defName)))
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = emptyList(),
            summary = listOf(
                "フィールド「$fieldPath」の構造を定義「${res.site.module.id}#$defName」へ抽出",
                "使用側は \$ref 参照に置き換え（${moved.size} キーを移動。文書は変わりません）",
            ),
        )
    }

    /**
     * 定義のインライン化: `$ref` 参照を定義の内容の複製へ展開する（使用側の上書きキーは維持）。
     * 他に参照が残らない場合は定義自体も削除する。文書は変わらない。
     */
    fun inlineDefinition(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        request: RefactoringRequest.InlineDefinition,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (schemaId, fieldPath) = request
        val entry = targetSchema(schemas, schemaId)
        val res = resolveField(schemas, entry, fieldPath)
        requireEditable(res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val key = res.site.def.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val node = verifyFieldNode(res.site, key, tree)
        val refKey = when {
            "\$ref" in node.entries -> "\$ref"
            "ref" in node.entries -> "ref"
            else -> error("定義参照（\$ref）ではありません: $fieldPath")
        }
        val located = entry.structural.definitionTarget(res.site.def)
            ?: error("参照先の定義が見つかりません: ${scalarText(node.entries[refKey])}")
        val (defKey, _) = located
        val defModule = schemas[defKey.moduleId] ?: error("定義モジュール「${defKey.moduleId}」が見つかりません")
        requireEditable(defModule, "定義モジュール（${defModule.id}）")
        val defSource = sourceOf(defModule)
        val defNode = tree(defSource)?.let { getAt(it, listOf("definitions", defKey.name)) } as? YNode.Map
            ?: error("定義「$defKey」の実体が見つかりません")

        val builder = SemanticRewrite.PlanOps()
        val useSource = sourceOf(res.site.module)
        builder.add(useSource, YamlOperation.Remove(res.site.path + refKey))
        var copied = 0
        defNode.entries.forEach { (k, v) ->
            if (k !in node.entries) {
                builder.add(useSource, YamlOperation.Insert(res.site.path + k, v.deepCopy()))
                copied++
            }
        }
        // 参照が残らなければ定義自体を削除する
        val remaining = countDefinitionRefs(schemas, tree, defKey.moduleId, defKey.name) - 1
        val notes = mutableListOf<String>()
        if (remaining <= 0) {
            builder.add(defSource, YamlOperation.Remove(listOf("definitions", defKey.name)))
            notes += "他に参照が無いため定義「$defKey」自体も削除します"
        } else {
            notes += "定義「$defKey」は他に $remaining 箇所から参照されているため残します"
        }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = emptyList(),
            summary = listOf(
                "フィールド「$fieldPath」の \$ref を定義「$defKey」の内容へインライン化（$copied キーを複製。文書は変わりません）",
            ) + notes,
        )
    }

    /**
     * 関係（relation）の改名: 関係レジストリの `name`・全スキーマの `relation:`（ref フィールド・coverage・
     * constraints のクエリ）を書き換える。文書は変わらない（関係は文書に書かれない）。
     */
    fun renameRelation(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        request: RefactoringRequest.RenameRelation,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (oldName, newName) = request
        requireValidName(newName, "改名先の関係名")
        require(newName != oldName) { "改名先が現在の関係名と同じです: $oldName" }
        require(newName !in declaredRelationNames(schemas, tree)) { "関係「$newName」は既にレジストリに宣言されています" }
        // Query＝全スキーマ文書のエントリ／Predicate＝値が旧関係名の `relation:`、またはレジストリの `name`／Edit＝SetScalar
        val builder = SemanticRewrite.PlanOps()
        var count = 0
        SemanticRewrite.forEachSchemaEntry(schemas, tree) { s, source, path, entryKey, value ->
            val hit = scalarText(value) == oldName && (
                entryKey == "relation" ||
                    (entryKey == "name" && s.kind == SchemaKind.RELATIONS && path.firstOrNull() == "relations")
                )
            if (hit) {
                builder.add(source, YamlOperation.SetScalar(path + entryKey, newName))
                count++
            }
        }
        require(count > 0) { "関係「$oldName」の出現が見つかりません" }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = nonFollowupWarning(oldName),
            summary = listOf(
                "関係「$oldName」→「$newName」",
                "レジストリ・スキーマの $count 箇所を書き換え（文書は変わりません）",
            ),
        )
    }

    /**
     * スキーマIDの改名: スキーマの `id:` と、全スキーマの修飾 `$ref`（`old#name`）・`definition`・
     * `schemaId(s)` 系・クエリの `schema:`・coverage の `detailIn.schemaIds` を書き換える。
     * 文書・match（glob）は変わらない。
     */
    fun renameSchemaId(
        sourceVersion: Long,
        schemas: Map<String, SchemaSemantics>,
        request: RefactoringRequest.RenameSchemaId,
        tree: (DocumentId) -> YNode?,
    ): RefactoringPlan {
        val (oldId, newId) = request
        requireValidName(newId, "改名先のスキーマID")
        require(newId != oldId) { "改名先が現在のスキーマIDと同じです: $oldId" }
        val schemaDef = schemas[oldId] ?: error("スキーマ「$oldId」が見つかりません")
        requireEditable(schemaDef, "スキーマ「$oldId」")
        require(newId !in schemas) { "スキーマID「$newId」は既にあります" }

        // Query＝全スキーマ文書のエントリ／Predicate＝旧IDを指す参照キー（修飾 ref・schemaId 系・schemaIds 系の配列項目）／
        // Edit＝SetScalar
        val builder = SemanticRewrite.PlanOps()
        builder.add(sourceOf(schemaDef), YamlOperation.SetScalar(listOf("id"), newId))
        var count = 0
        SemanticRewrite.forEachSchemaEntry(schemas, tree) { _, file, path, entryKey, value ->
            when {
                (entryKey == "\$ref" || entryKey == "ref" || entryKey == "definition") &&
                    scalarText(value)?.startsWith("$oldId#") == true -> {
                    builder.add(file, YamlOperation.SetScalar(path + entryKey, "$newId#${scalarText(value)!!.substringAfter('#')}"))
                    count++
                }
                (entryKey == "schemaId" || entryKey == "schema") && scalarText(value) == oldId -> {
                    builder.add(file, YamlOperation.SetScalar(path + entryKey, newId))
                    count++
                }
                entryKey in setOf("schemaIds", "detailSchemaIds") && value is YNode.Seq -> {
                    value.items.forEachIndexed { i, v ->
                        if (scalarText(v) == oldId) {
                            builder.add(file, YamlOperation.SetScalar(path + entryKey + i, newId))
                            count++
                        }
                    }
                }
                else -> Unit
            }
        }
        return RefactoringPlan(
            sourceVersion = sourceVersion,
            request = request,
            changes = builder.toChanges(),
            warnings = nonFollowupWarning(oldId),
            summary = listOf(
                "スキーマID「$oldId」→「$newId」",
                "スキーマの参照 $count 箇所を書き換え（文書と match は変わりません）",
            ),
        )
    }

    // ================================================================ スキーマ側の解決（Site 機構）

    /** スキーマ定義上の実体1件: 保持モジュール・そのスキーマ文書内のYAML座標・FieldDef インスタンス。 */
    private data class Site(val module: SchemaSemantics, val path: YPath, val def: FieldDef)

    /** 解決したフィールドと、同じ階層の兄弟キー（改名先の衝突検査用）。 */
    private class Target(val site: Site, val siblings: Set<String>)

    /** 配列・map の横断1件（要素コンテキストの署名）。prefixKeys はそこまでのドット区切りキー列。 */
    private class Crossing(val prefixKeys: List<String>, val container: FieldDef) {
        override fun equals(other: Any?): Boolean =
            other is Crossing && other.prefixKeys == prefixKeys && other.container === container

        override fun hashCode(): Int = prefixKeys.hashCode()
    }

    /** フィールド解決の結果: 実体・兄弟キー・要素コンテキスト署名・要素ルートからのキー列。 */
    private class Resolution(
        val site: Site,
        val siblings: Set<String>,
        val crossings: List<Crossing>,
        val tailKeys: List<String>,
    )

    /** 移動先コンテナ解決の結果: object 定義の実体・要素コンテキスト署名・要素ルートからのキー列。 */
    private class ContainerResolution(
        val objectSite: Site,
        val crossings: List<Crossing>,
        val tailKeys: List<String>,
    )

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

    /** スキーマの書き換え先の文書（requireEditable 済みなら必ずある。無ければ組込み・派生スキーマ）。 */
    private fun sourceOf(module: SchemaSemantics): DocumentId =
        module.source ?: error("スキーマ「${module.id}」に書き換え先の文書がありません")

    private fun requireValidName(name: String, label: String) {
        require(name.isNotBlank()) { "${label}を指定してください" }
        require(name.none { it in ".[]#" || it.isWhitespace() }) { "${label}に . [ ] # や空白は使えません: $name" }
    }

    /** スキーマYAMLの実体（フィールド定義の map）を確認して返す。解決結果と文書がずれていたら中止する安全確認。 */
    private fun verifyFieldNode(site: Site, expectedKey: String, tree: (DocumentId) -> YNode?): YNode.Map {
        val source = sourceOf(site.module)
        val node = tree(source)?.let { getAt(it, site.path) } as? YNode.Map
            ?: error("スキーマ定義の実体が見つかりません: ${source.value} の ${site.path.pretty()}")
        require(scalarText(node.entries["key"]) == expectedKey) {
            "スキーマ定義の実体（${site.path.pretty()}）のキーが「$expectedKey」と一致しません"
        }
        return node
    }

    /** ビュー定義・アクティビティ等への非追随の案内（6E 仮想文書の設計後に対応）。 */
    private fun nonFollowupWarning(name: String): List<String> = listOf(
        "ビュー定義・アクティビティ等の「$name」への言及は追随しません（6E 仮想文書の設計後に対応。該当があれば手動で更新してください）",
    )

    /** `$ref` の最終実体（定義インスタンス）。非 ref はそのまま返す。 */
    private fun finalDef(entry: SchemaSemantics, field: FieldDef): FieldDef {
        var current = field
        val seen = mutableSetOf<String>()
        while (current.ref != null) {
            val (key, targetDef) = entry.structural.definitionTarget(current) ?: break
            if (!seen.add(key.toString())) break
            current = targetDef
        }
        return current
    }

    /** `$ref` / `ref` エントリが指す定義（非修飾は書かれているスキーマ自身のモジュール）。参照キーでなければ null。 */
    private class DefinitionRef(val moduleId: String, val name: String, val qualified: Boolean)

    private fun definitionRefOf(holder: SchemaSemantics, entryKey: String, value: YNode): DefinitionRef? {
        if (entryKey != "\$ref" && entryKey != "ref") return null
        val v = scalarText(value) ?: return null
        val qualified = v.contains('#')
        return DefinitionRef(
            moduleId = if (qualified) v.substringBefore('#') else holder.id,
            name = if (qualified) v.substringAfter('#') else v,
            qualified = qualified,
        )
    }

    /** 全編集可能スキーマの `$ref` / `ref` のうち、指定定義を参照するものの件数。 */
    private fun countDefinitionRefs(
        schemas: Map<String, SchemaSemantics>,
        tree: (DocumentId) -> YNode?,
        moduleId: String,
        defName: String,
    ): Int {
        var count = 0
        SemanticRewrite.forEachSchemaEntry(schemas, tree) { s, _, _, entryKey, value ->
            val ref = definitionRefOf(s, entryKey, value) ?: return@forEachSchemaEntry
            if (ref.moduleId == moduleId && ref.name == defName) count++
        }
        return count
    }

    /** 関係レジストリ（kind: relations のスキーマ）に宣言された関係名の集合。 */
    private fun declaredRelationNames(
        schemas: Map<String, SchemaSemantics>,
        tree: (DocumentId) -> YNode?,
    ): Set<String> {
        val names = mutableSetOf<String>()
        SemanticRewrite.forEachSchemaEntry(schemas, tree, only = { it.kind == SchemaKind.RELATIONS }) { _, _, path, entryKey, value ->
            if (entryKey == "name" && path.firstOrNull() == "relations") scalarText(value)?.let(names::add)
        }
        return names
    }

    /** `entity.owns` のリスト項目のうち key に一致するもののYAML座標（削除の掃除用）。 */
    private fun ownsEntryPaths(tree: YNode, key: String): List<YPath> {
        val out = mutableListOf<YPath>()
        fun scanOwns(base: YPath, decl: YNode?) {
            val owns = (decl as? YNode.Map)?.entries?.get("owns") as? YNode.Seq ?: return
            owns.items.forEachIndexed { i, v -> if (scalarText(v) == key) out += base + "owns" + i }
        }
        when (val entity = (tree as? YNode.Map)?.entries?.get("entity")) {
            is YNode.Map -> scanOwns(listOf("entity"), entity)
            is YNode.Seq -> entity.items.forEachIndexed { i, d -> scanOwns(listOf("entity", i), d) }
            else -> Unit
        }
        return out
    }

    /** ドット区切りのフィールドパスを、スキーマ定義上の実体へ解決する（要素コンテキストの署名つき）。 */
    private fun resolveField(
        schemas: Map<String, SchemaSemantics>,
        entry: SchemaSemantics,
        fieldPath: String,
    ): Resolution {
        val segments = fieldPath.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "フィールドパスを指定してください（例 details.priority）" }
        var container = Site(entry, listOf("root"), entry.structural.root)
        val crossings = mutableListOf<Crossing>()
        val consumed = mutableListOf<String>()
        val tail = mutableListOf<String>()
        var result: Resolution? = null
        segments.forEachIndexed { i, name ->
            container = descendContainers(schemas, entry, container) { real ->
                crossings += Crossing(consumed.toList(), real)
                tail.clear()
            }
            val found = lookupChild(schemas, entry, container, name, fieldPath)
            consumed += name
            tail += name
            if (i == segments.lastIndex) result = Resolution(found.site, found.siblings, crossings.toList(), tail.toList())
            else container = found.site
        }
        return result!!
    }

    /** 移動先コンテナ（object）の解決。空・`.` はルート。配列・map を指した場合は要素の object へ降りる。 */
    private fun resolveContainer(
        schemas: Map<String, SchemaSemantics>,
        entry: SchemaSemantics,
        containerPath: String,
    ): ContainerResolution {
        val trimmed = containerPath.trim()
        if (trimmed.isEmpty() || trimmed == ".") {
            val (real, _) = derefChain(schemas, entry, Site(entry, listOf("root"), entry.structural.root))
            require(real.def.type == FieldType.OBJECT) { "スキーマの root が object ではありません" }
            return ContainerResolution(real, emptyList(), emptyList())
        }
        val res = resolveField(schemas, entry, trimmed)
        val crossings = res.crossings.toMutableList()
        val tail = res.tailKeys.toMutableList()
        val allKeys = trimmed.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        val landed = descendContainers(schemas, entry, res.site) { real ->
            crossings += Crossing(allKeys, real)
            tail.clear()
        }
        val (real, _) = derefChain(schemas, entry, landed)
        require(real.def.type == FieldType.OBJECT) { "移動先「$containerPath」は object ではありません（${real.def.type.id}）" }
        return ContainerResolution(real, crossings.toList(), tail.toList())
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
        onCross: ((FieldDef) -> Unit)? = null,
    ): Site {
        var current = site
        while (true) {
            val (real, _) = derefChain(schemas, entry, current)
            current = when (real.def.type) {
                FieldType.ARRAY -> {
                    val item = real.def.item ?: return real
                    onCross?.invoke(real.def)
                    Site(real.module, real.path + "item", item)
                }
                FieldType.MAP -> {
                    val value = real.def.value ?: return real
                    onCross?.invoke(real.def)
                    Site(real.module, real.path + "value", value)
                }
                else -> return current
            }
        }
    }

    /** object 実体サイトの子キー集合（fields＋cases 由来。移動先の衝突検査用）。 */
    private fun childKeysOf(
        schemas: Map<String, SchemaSemantics>,
        entry: SchemaSemantics,
        objectSite: Site,
    ): Set<String> {
        val keys = mutableSetOf<String>()
        objectSite.def.fields.forEach { f ->
            f.key?.let(keys::add)
            val (_, casesSite) = derefChain(schemas, entry, Site(objectSite.module, objectSite.path, f))
            casesSite?.def?.cases?.values?.flatten()?.forEach { cf -> cf.key?.let(keys::add) }
        }
        return keys
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

    /** target がスキーマのルート直下フィールドか（phases.key の追随判定）。 */
    private fun isRootField(s: StructuralSchema, target: FieldDef): Boolean =
        (s.resolve(s.root) ?: s.root).fields.any { it === target }

    // ================================================================ 文書側の操作列（戦略）

    /** 移動の文書側ops: 中間 object の生成 → Move → 空になった親の削除。衝突（移動先の既存値）を集める。 */
    private fun moveDataOps(
        s: StructuralSchema,
        root: YNode,
        target: FieldDef,
        tailSize: Int,
        destTail: List<String>,
        key: String,
        deleteEmptyParents: Boolean,
    ): Pair<List<YamlOperation>, List<String>> {
        val ops = mutableListOf<YamlOperation>()
        val conflicts = mutableListOf<String>()
        val hits = SemanticRewrite.fieldHits(s, root) { it === target }.sortedWith(SemanticRewrite.pathDescending)
        for (p in hits) {
            val elementRoot = p.dropLast(tailSize)
            val destParent = elementRoot + destTail
            var invalid = false
            val creates = mutableListOf<YPath>()
            var cur = elementRoot
            for (k in destTail) {
                cur = cur + k
                when (getAt(root, cur)) {
                    null -> creates += cur
                    is YNode.Map -> Unit
                    else -> {
                        conflicts += "${cur.pretty()} が object ではありません"
                        invalid = true
                    }
                }
            }
            if (invalid) continue
            val destKey = destParent + key
            if (getAt(root, destKey) != null) {
                conflicts += "${destKey.pretty()} に既に値があります"
                continue
            }
            creates.forEach { ops += YamlOperation.Insert(it, YNode.Map()) }
            ops += YamlOperation.Move(p, destKey)
            if (deleteEmptyParents) {
                var depth = p.size - 1
                while (depth > elementRoot.size) {
                    val parentPath = p.subList(0, depth)
                    // 移動先の経路上にある親は空にならない（中間生成・移動で値が入る）
                    if (destParent.size >= parentPath.size && destParent.subList(0, parentPath.size) == parentPath) break
                    val node = getAt(root, parentPath) as? YNode.Map ?: break
                    if (node.entries.size != 1) break
                    ops += YamlOperation.Remove(parentPath)
                    depth--
                }
            }
        }
        return ops to conflicts
    }

    /** 削除によるインデックスずれの調整: seqPath 直下の removedIdx より後ろを指すパスを 1 つ詰める。 */
    private fun adjustAfterDelete(path: YPath, seqPath: YPath, removedIdx: Int): YPath {
        if (path.size <= seqPath.size) return path
        if (path.subList(0, seqPath.size) != seqPath) return path
        val idx = path[seqPath.size] as? Int ?: return path
        if (idx <= removedIdx) return path
        return path.toMutableList().also { it[seqPath.size] = idx - 1 }
    }
}
