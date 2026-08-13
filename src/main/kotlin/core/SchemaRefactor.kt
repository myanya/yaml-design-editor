package core

import model.YNode
import model.YPath
import model.getAt
import model.pretty
import model.scalarText
import schema.FieldDef
import schema.FieldType
import schema.SchemaDef
import schema.SchemaKind
import schema.WorkspaceLayout
import schema.currentPhaseIndex
import schema.effectiveFields
import yaml.YamlIO
import java.nio.file.Files
import java.nio.file.Path

/**
 * スキーマ・リファクタリング（P25。ARCHITECTURE §4.35）: スキーマ定義の変更に既存文書・ビューを
 * **1つの変更セット**で追随させる生成器。EntityOps（R21-02）・DocMove（R21-03）と同型で、
 * 新しい書込経路は作らず、適用は Workspace.apply（オール・オア・ナッシング・オーバーレイ検証）に委ねる。
 * `_schemas` はエージェント書込不可のため、入口は人間専用（スキーマエディタ・CLI `refactor`）。
 *
 * 操作の一覧: renameField / moveField / alignOrder（R25-02）、renameEnumValue / deleteField /
 * requireField / convertFieldType（R25-03）、renameDefinition / extractDefinition / inlineDefinition /
 * renameRelation / renameSchemaId（R25-04）。
 *
 * フィールドの同定は FieldDef の**同一インスタンス**（`===`）。パーサが定義を1回だけ生成し、
 * `$ref` 解決（`resolve` は浅いコピー）が子リストのインスタンスを保つことが前提（§4.35）。
 */
object SchemaRefactor {

    // ================================================================ R25-01: フィールド改名

    /**
     * フィールド改名: スキーマ定義のキー（`fields[i].key`）と全該当文書のデータキー（順序・コメント保持の
     * RenameKey）、ビューのパスを1つの変更セットへまとめる。
     * - fieldPath はデータと同じドット区切り（例 `details.priority`。配列・map は暗黙に降りる。cases 由来の
     *   フィールドも兄弟キーとして指定できる）
     * - `$ref` 先（named definition）のフィールドは定義側を書き換え、その定義を使う全スキーマの文書が対象になる
     * - 改名先キーが既存文書に存在する衝突は生成段階で拒否する（黙って上書きしない）
     * - 派生スキーマ（derive の導出フィールド）・組込みスキーマは対象外（導出元文書・アプリ側の定義のため）
     */
    fun renameField(ws: Workspace, schemaId: String, fieldPath: String, newName: String): EntityOps.Plan {
        requireValidName(newName, "改名先のフィールド名")
        val entry = targetSchema(ws, schemaId)
        val res = resolveField(ws, entry, fieldPath)
        requireEditableSchema(ws, res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val target = res.site.def
        val oldName = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        require(newName != oldName) { "改名先が現在のフィールド名と同じです: $oldName" }
        require(newName !in res.siblings) { "同じ階層に「$newName」が既に定義されています（スキーマ側の衝突）" }
        verifyFieldNode(ws, res.site, oldName)

        val reachable = reachableSchemas(ws) { it === target }
        val reachableIds = reachable.map { it.id }.toSet()
        val builder = PlanBuilder(ws)
        val conflicts = mutableListOf<String>()
        var occurrenceCount = 0
        var docFileCount = 0
        forEachDocument(ws, reachableIds) { file, docSchema, root ->
            val found = collectFieldHits(docSchema, root) { it === target }
            val fileConflicts = found.filter { hit ->
                (getAt(root, hit.dropLast(1)) as? YNode.Map)?.entries?.containsKey(newName) == true
            }
            if (fileConflicts.isNotEmpty()) {
                conflicts += "${ws.relPath(file)}（${fileConflicts.first().pretty()} など ${fileConflicts.size} 箇所）"
                return@forEachDocument
            }
            if (found.isEmpty()) return@forEachDocument
            occurrenceCount += found.size
            docFileCount++
            // 深い方から改名する（祖先キーの改名で子のパスが無効にならないように）
            builder.addAll(file, found.sortedWith(pathDescending).map { PatchOp.RenameKey(it, newName) })
        }
        if (conflicts.isNotEmpty()) {
            error(
                "改名先のキー「$newName」が既存文書に存在するため中止します（先に該当キーを整理してください）: " +
                    conflicts.take(5).joinToString("、") + (if (conflicts.size > 5) " 他${conflicts.size - 5}件" else "")
            )
        }

        // スキーマ側: フィールドキーの書換（＋ルート直下フィールドなら phases.key の追随）
        builder.add(res.site.module.source, PatchOp.Set(res.site.path + "key", YNode.Scalar(newName)))
        var schemaFollowups = 1
        for (s in reachable) {
            if (s.phases?.key != oldName || !isRootField(s, target)) continue
            if (!isEditableSchema(ws, s)) continue
            val phasesNode = getAt(YamlIO.parse(Files.readString(s.source)), listOf("phases")) as? YNode.Map ?: continue
            if (scalarText(phasesNode.entries["key"]) != oldName) continue  // リスト省略形（key 暗黙）は対象外
            builder.add(s.source, PatchOp.Set(listOf("phases", "key"), YNode.Scalar(newName)))
            schemaFollowups++
        }

        // ビュー定義の追随（静的解決が一意な箇所のみ自動書換・その他の参照箇所は警告）
        val impact = Views.fieldRenameImpact(ws.root, ws.schemas, reachableIds, target, oldName, newName)
        impact.ops.forEach { (file, ops) -> builder.addAll(file, ops) }

        return builder.build(
            warnings = impact.warnings + schemaSectionMentions(ws, oldName) + definitionDocMentions(ws, oldName),
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$oldName」→「$newName」",
                "文書 $docFileCount ファイル・$occurrenceCount 箇所のキーを改名（順序・コメント保持）",
                "スキーマ定義 $schemaFollowups 箇所・ビュー ${impact.ops.values.sumOf { it.size }} 箇所を追随",
            ) + (if (reachable.size > 1) listOf("影響スキーマ: ${reachable.joinToString("、") { it.id }}") else emptyList()),
        )
    }

    // ================================================================ R25-02: 親子の移動・順序

    /**
     * フィールドの親子移動: スキーマ定義の移動（コメント保持の Move）と、全該当文書での値の移動を
     * 1つの変更セットへまとめる。移動先（destPath）は object フィールドのドット区切りパス（`.` = ルート）。
     * - 移動元と移動先は**同じ要素コンテキスト**（配列・map の横断が一致）にあること。配列・map の境界を
     *   またぐ移動（1↔N の対応になり決定的に解決できない）は拒否する
     * - 共有定義（definitions）をまたぐ移動は拒否（他の使用箇所の文書が壊れるため。定義内どうしは可）
     * - 移動先の中間 object が文書に無ければ生成し、移動で空になった親 object は削除する（deleteEmptyParents）
     */
    fun moveField(
        ws: Workspace,
        schemaId: String,
        fieldPath: String,
        destPath: String,
        deleteEmptyParents: Boolean = true,
    ): EntityOps.Plan {
        val entry = targetSchema(ws, schemaId)
        val src = resolveField(ws, entry, fieldPath)
        require("cases" !in src.site.path) { "cases 由来のフィールドの移動は未対応です（スキーマのソースで編集してください）" }
        requireEditableSchema(ws, src.site.module, "フィールドの定義元（${src.site.module.id}）")
        val target = src.site.def
        val key = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        verifyFieldNode(ws, src.site, key)
        val dst = resolveContainer(ws, entry, destPath)

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
        require(key !in childKeysOf(ws, entry, dst.objectSite)) { "移動先に同名のフィールド「$key」が既にあります" }

        val reachable = reachableSchemas(ws) { it === target }
        for (s in reachable) {
            require(!(s.phases?.key == key && isRootField(s, target) && dst.objectSite.path != listOf<Any>("root"))) {
                "フェーズ申告キー「$key」はルート直下に必要なため移動できません（スキーマ「${s.id}」の phases）"
            }
        }

        // 文書側: 値の移動（中間 object の生成・空親の削除・衝突検査）
        val reachableIds = reachable.map { it.id }.toSet()
        val builder = PlanBuilder(ws)
        val conflicts = mutableListOf<String>()
        var moved = 0
        var docFileCount = 0
        forEachDocument(ws, reachableIds) { file, docSchema, root ->
            val (ops, fileConflicts) = moveDataOps(docSchema, root, target, src.tailKeys.size, dst.tailKeys, key, deleteEmptyParents)
            if (fileConflicts.isNotEmpty()) {
                conflicts += "${ws.relPath(file)}（${fileConflicts.first()} など ${fileConflicts.size} 件）"
                return@forEachDocument
            }
            if (ops.isEmpty()) return@forEachDocument
            moved += ops.count { it is PatchOp.Move }
            docFileCount++
            builder.addAll(file, ops)
        }
        if (conflicts.isNotEmpty()) {
            error(
                "移動先に既存の値があるため中止します（先に該当キーを整理してください）: " +
                    conflicts.take(5).joinToString("、") + (if (conflicts.size > 5) " 他${conflicts.size - 5}件" else "")
            )
        }

        // スキーマ側: 定義の移動（移動先の fields が無ければ生成。同一ファイル内の削除によるインデックスずれを調整）
        val moduleTree = YamlIO.parse(Files.readString(src.site.module.source))
        val dstObjNode = getAt(moduleTree, dst.objectSite.path) as? YNode.Map
            ?: error("移動先の実体が見つかりません: ${dst.objectSite.path.pretty()}")
        val dstFields = dstObjNode.entries["fields"] as? YNode.Seq
        if (dstFields == null) builder.add(src.site.module.source, PatchOp.Set(dst.objectSite.path + "fields", YNode.Seq()))
        val srcIdx = src.site.path.last() as Int
        val adjustedDst = adjustAfterDelete(dst.objectSite.path, srcSeqPath, srcIdx)
        builder.add(src.site.module.source, PatchOp.Move(src.site.path, adjustedDst + "fields" + (dstFields?.items?.size ?: 0)))

        return builder.build(
            warnings = Views.fieldMentionWarnings(ws.root, key, "移動の自動追随は対象外・要修正") +
                schemaSectionMentions(ws, key) + definitionDocMentions(ws, key),
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$fieldPath」→「${if (destPath.isBlank() || destPath == ".") "（ルート）" else destPath}」配下へ移動",
                "文書 $docFileCount ファイル・$moved 箇所の値を移動" +
                    (if (deleteEmptyParents) "（空になった親 object は削除）" else ""),
            ) + (if (reachable.size > 1) listOf("影響スキーマ: ${reachable.joinToString("、") { it.id }}") else emptyList()),
        )
    }

    /**
     * 文書のキー順をスキーマのフィールド順へ整列する（R25-02。任意の正規化＝スキーマは変更しない）。
     * 各 object 要素のキーを「`_document`（ルートのみ）→ スキーマ順の既知キー → 未知キー（元の相対順）」に
     * 並べ替える（値・コメントは保持）。
     */
    fun alignOrder(ws: Workspace, schemaId: String): EntityOps.Plan {
        val entry = ws.schemas.firstOrNull { it.id == schemaId } ?: error("スキーマ「$schemaId」が見つかりません")
        require(entry.kind == SchemaKind.DOCUMENT) { "文書スキーマを指定してください: $schemaId" }
        require(entry.source.startsWith(ws.root)) { "組込みスキーマは対象外です: $schemaId" }
        val builder = PlanBuilder(ws)
        var elements = 0
        var docFileCount = 0
        for (file in ws.files) {
            val s = ws.schemaFor(file) ?: continue
            if (s.id != schemaId) continue
            val root = runCatching { YamlIO.parse(Files.readString(file)) }.getOrNull() ?: continue
            val ops = mutableListOf<PatchOp>()
            fun walk(def: FieldDef, node: YNode, path: YPath) {
                val eff = s.resolve(def) ?: def
                when (eff.type) {
                    FieldType.OBJECT -> {
                        val m = node as? YNode.Map ?: return
                        val fs = s.effectiveFields(eff.fields, m).fields
                        val current = m.entries.keys.toList()
                        val head = if (path.isEmpty() && "_document" in m.entries) listOf("_document") else emptyList()
                        val known = fs.mapNotNull { it.key }.filter { it in m.entries && it !in head }.distinct()
                        val desired = head + known + current.filter { it !in head && it !in known }
                        if (desired != current) {
                            ops += PatchOp.ReorderKeys(path, desired)
                            elements++
                        }
                        fs.forEach { f -> f.key?.let { k -> m.entries[k]?.let { child -> walk(f, child, path + k) } } }
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
            if (ops.isNotEmpty()) {
                docFileCount++
                builder.addAll(file, ops)
            }
        }
        return builder.build(
            warnings = emptyList(),
            summary = listOf(
                "スキーマ「$schemaId」の文書をスキーマ順へ整列",
                "文書 $docFileCount ファイル・$elements 要素のキー順を並べ替え（値・コメント保持。未知キーは末尾に相対順で残す）",
            ),
        )
    }

    // ================================================================ R25-03: 語彙拡大

    /**
     * enum 値の改名: スキーマの選択肢（`values`。リッチ選択肢は `value`）・`cases` のキー・`default`・
     * フェーズ申告フィールドなら `phases.order` と全 `requiredFrom` / `activeFrom`、そして全該当文書の
     * 現在値を1つの変更セットへまとめる。
     */
    fun renameEnumValue(ws: Workspace, schemaId: String, fieldPath: String, oldValue: String, newValue: String): EntityOps.Plan {
        require(newValue.isNotBlank()) { "改名先の値を指定してください" }
        require(newValue != oldValue) { "改名先が現在の値と同じです: $oldValue" }
        val entry = targetSchema(ws, schemaId)
        val res = resolveField(ws, entry, fieldPath)
        val (owner, casesSite) = derefChain(ws, entry, res.site)
        requireEditableSchema(ws, owner.module, "選択肢の定義元（${owner.module.id}）")
        require(owner.def.type == FieldType.ENUM) { "enum 型ではありません（${owner.def.type.id}）: $fieldPath" }
        require(oldValue in owner.def.values) { "選択肢に「$oldValue」がありません（選択肢: ${owner.def.values.joinToString(", ")}）" }
        require(newValue !in owner.def.values) { "選択肢「$newValue」は既にあります" }

        val builder = PlanBuilder(ws)
        // 選択肢の実体（文字列 or リッチ選択肢）
        val ownerTree = YamlIO.parse(Files.readString(owner.module.source))
        val valuesSeq = getAt(ownerTree, owner.path + "values") as? YNode.Seq
            ?: error("values の実体が見つかりません: ${owner.path.pretty()}（スキーマの未保存編集の可能性）")
        val idx = valuesSeq.items.indexOfFirst { item ->
            scalarText(item) == oldValue || scalarText((item as? YNode.Map)?.entries?.get("value")) == oldValue
        }
        require(idx >= 0) { "values の実体に「$oldValue」が見つかりません（スキーマの未保存編集の可能性）" }
        val valuePath = if (valuesSeq.items[idx] is YNode.Map) owner.path + "values" + idx + "value" else owner.path + "values" + idx
        builder.add(owner.module.source, PatchOp.Set(valuePath, YNode.Scalar(newValue)))
        // cases のキー（実効 cases を持つサイト）
        if (casesSite != null && oldValue in casesSite.def.cases) {
            builder.add(casesSite.module.source, PatchOp.RenameKey(casesSite.path + "cases" + oldValue, newValue))
        }
        // default（使用サイト・定義サイトの両方を確認）
        val sites = listOf(res.site, owner).distinctBy { it.module.source.toString() + it.path.pretty() }
        for (site in sites) {
            val node = getAt(YamlIO.parse(Files.readString(site.module.source)), site.path) as? YNode.Map ?: continue
            if (scalarText(node.entries["default"]) == oldValue) {
                builder.add(site.module.source, PatchOp.Set(site.path + "default", YNode.Scalar(newValue)))
            }
        }
        // フェーズ申告フィールドなら order / requiredFrom / activeFrom を追随（フェーズ名の改名にあたる）
        val target = res.site.def
        val fieldKey = target.key
        val reachable = reachableSchemas(ws) { finalDef(entry, it) === owner.def || it === target }
        for (s in reachable) {
            if (s.phases?.key != fieldKey || !isRootField(s, target) || !isEditableSchema(ws, s)) continue
            val sTree = YamlIO.parse(Files.readString(s.source))
            when (val ph = (sTree as? YNode.Map)?.entries?.get("phases")) {
                is YNode.Map -> (ph.entries["order"] as? YNode.Seq)?.items?.forEachIndexed { i, v ->
                    if (scalarText(v) == oldValue) builder.add(s.source, PatchOp.Set(listOf("phases", "order", i), YNode.Scalar(newValue)))
                }
                is YNode.Seq -> ph.items.forEachIndexed { i, v ->
                    if (scalarText(v) == oldValue) builder.add(s.source, PatchOp.Set(listOf("phases", i), YNode.Scalar(newValue)))
                }
                else -> Unit
            }
            walkYamlEntries(sTree) { path, entryKey, value ->
                if (entryKey in setOf("requiredFrom", "activeFrom") && scalarText(value) == oldValue) {
                    builder.add(s.source, PatchOp.Set(path + entryKey, YNode.Scalar(newValue)))
                }
            }
        }
        // 文書の現在値
        var valueCount = 0
        var docFileCount = 0
        forEachDocument(ws, reachable.map { it.id }.toSet()) { file, docSchema, root ->
            val hits = collectFieldHits(docSchema, root) { finalDef(docSchema, it) === owner.def || it === target }
                .filter { scalarText(getAt(root, it)) == oldValue }
            if (hits.isEmpty()) return@forEachDocument
            valueCount += hits.size
            docFileCount++
            builder.addAll(file, hits.map { PatchOp.Set(it, YNode.Scalar(newValue)) })
        }
        return builder.build(
            warnings = definitionDocMentions(ws, oldValue),
            summary = listOf(
                "スキーマ「$schemaId」の「$fieldPath」の選択肢「$oldValue」→「$newValue」",
                "文書 $docFileCount ファイル・$valueCount 箇所の値を書き換え",
            ),
        )
    }

    /**
     * フィールド削除: スキーマ定義・全該当文書のデータキー・`entity.owns` の項目を1つの変更セットで削除する。
     * 値が入っている件数をサマリで明示する（データ損失の可視化）。ID型・フェーズ申告キーは拒否する。
     */
    fun deleteField(ws: Workspace, schemaId: String, fieldPath: String): EntityOps.Plan {
        val entry = targetSchema(ws, schemaId)
        val res = resolveField(ws, entry, fieldPath)
        requireEditableSchema(ws, res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val target = res.site.def
        val key = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val (real, _) = derefChain(ws, entry, res.site)
        require(real.def.type != FieldType.ID) { "ID型フィールドは削除できません（参照・エンティティが壊れます。要素の削除は entity delete を使ってください）" }
        verifyFieldNode(ws, res.site, key)
        val reachable = reachableSchemas(ws) { it === target }
        for (s in reachable) {
            require(!(s.phases?.key == key && isRootField(s, target))) {
                "フェーズ申告キー「$key」は削除できません（スキーマ「${s.id}」の phases が参照しています）"
            }
        }

        val builder = PlanBuilder(ws)
        var hitCount = 0
        var valueCount = 0
        var docFileCount = 0
        forEachDocument(ws, reachable.map { it.id }.toSet()) { file, docSchema, root ->
            val hits = collectFieldHits(docSchema, root) { it === target }
            if (hits.isEmpty()) return@forEachDocument
            hitCount += hits.size
            valueCount += hits.count { hit ->
                when (val node = getAt(root, hit)) {
                    is YNode.Scalar -> !scalarText(node).isNullOrBlank()
                    is YNode.Map -> node.entries.isNotEmpty()
                    is YNode.Seq -> node.items.isNotEmpty()
                    else -> false
                }
            }
            docFileCount++
            builder.addAll(file, hits.sortedWith(pathDescending).map { PatchOp.Delete(it) })
        }
        // スキーマ側: 定義の削除と entity.owns の掃除
        builder.add(res.site.module.source, PatchOp.Delete(res.site.path))
        for (s in reachable) {
            if (!isEditableSchema(ws, s)) continue
            val sTree = YamlIO.parse(Files.readString(s.source))
            ownsEntryPaths(sTree, key).sortedWith(pathDescending).forEach { p ->
                builder.add(s.source, PatchOp.Delete(p))
            }
        }
        return builder.build(
            warnings = Views.fieldMentionWarnings(ws.root, key, "削除によりビューの参照が壊れます・要修正") +
                schemaSectionMentions(ws, key) + definitionDocMentions(ws, key),
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$fieldPath」を削除",
                "文書 $docFileCount ファイル・$hitCount 箇所のキーを削除（**うち値あり $valueCount 箇所**＝データが失われます）",
            ) + (if (reachable.size > 1) listOf("影響スキーマ: ${reachable.joinToString("、") { it.id }}") else emptyList()),
        )
    }

    /**
     * 必須化バックフィル: フィールドを必須化（`required` または `requiredFrom`）し、値が無い（キー欠落・空）
     * 既存文書へ既定値を一括充填する。`requiredFrom` 指定時は、そのフェーズに達している文書だけを充填する。
     */
    fun requireField(ws: Workspace, schemaId: String, fieldPath: String, defaultRaw: String, fromPhase: String? = null): EntityOps.Plan {
        require(defaultRaw.isNotBlank()) { "--default（充填する既定値）を指定してください" }
        val entry = targetSchema(ws, schemaId)
        val res = resolveField(ws, entry, fieldPath)
        requireEditableSchema(ws, res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val target = res.site.def
        val key = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val (real, _) = derefChain(ws, entry, res.site)
        val fillable = setOf(
            FieldType.STRING, FieldType.TEXT, FieldType.INT, FieldType.NUMBER,
            FieldType.BOOLEAN, FieldType.ENUM, FieldType.DATE, FieldType.REF,
        )
        require(real.def.type in fillable) { "型 ${real.def.type.id} は一括充填の対象外です（スカラー型のみ）" }
        fun newDefault(): YNode.Scalar = when (real.def.type) {
            FieldType.INT -> YNode.Scalar(defaultRaw.toLongOrNull() ?: error("--default は整数である必要があります: $defaultRaw"))
            FieldType.NUMBER -> YNode.Scalar(defaultRaw.toDoubleOrNull() ?: error("--default は数値である必要があります: $defaultRaw"))
            FieldType.BOOLEAN -> YNode.Scalar(defaultRaw.toBooleanStrictOrNull() ?: error("--default は true / false である必要があります: $defaultRaw"))
            FieldType.ENUM -> {
                require(defaultRaw in real.def.values) { "--default は選択肢のいずれかである必要があります: ${real.def.values.joinToString(", ")}" }
                YNode.Scalar(defaultRaw)
            }
            else -> YNode.Scalar(defaultRaw)
        }
        newDefault()  // 型検査を先に走らせる
        if (fromPhase != null) {
            val phases = entry.phases ?: error("requiredFrom を使うにはスキーマ「$schemaId」に phases の宣言が必要です")
            require(fromPhase in phases.order) { "フェーズ「$fromPhase」は phases（${phases.order.joinToString(", ")}）にありません" }
        }
        val node = verifyFieldNode(ws, res.site, key)

        val builder = PlanBuilder(ws)
        // スキーマ側: required / requiredFrom（併用不可のため他方を消す）
        if (fromPhase != null) {
            builder.add(res.site.module.source, PatchOp.Set(res.site.path + "requiredFrom", YNode.Scalar(fromPhase)))
            if ("required" in node.entries) builder.add(res.site.module.source, PatchOp.Delete(res.site.path + "required"))
        } else {
            if (scalarText(node.entries["required"]) != "true") {
                builder.add(res.site.module.source, PatchOp.Set(res.site.path + "required", YNode.Scalar(true)))
            }
            if ("requiredFrom" in node.entries) builder.add(res.site.module.source, PatchOp.Delete(res.site.path + "requiredFrom"))
        }
        // 文書側: 欠落・空値の充填（親 object が存在する要素だけ）
        val reachable = reachableSchemas(ws) { it === target }
        var filled = 0
        var docFileCount = 0
        forEachDocument(ws, reachable.map { it.id }.toSet()) { file, docSchema, root ->
            if (fromPhase != null) {
                val order = docSchema.phases?.order ?: return@forEachDocument
                val phaseIdx = docSchema.currentPhaseIndex(root) ?: return@forEachDocument
                val fromIdx = order.indexOf(fromPhase)
                if (fromIdx < 0 || fromIdx > phaseIdx) return@forEachDocument
            }
            val parents = collectParentObjects(docSchema, root, target)
            val ops = parents.mapNotNull { (path, m) ->
                val current = m.entries[key]
                val missing = current == null || (current is YNode.Scalar && scalarText(current).isNullOrBlank())
                if (missing) PatchOp.Set(path + key, newDefault()) else null
            }
            if (ops.isEmpty()) return@forEachDocument
            filled += ops.size
            docFileCount++
            builder.addAll(file, ops)
        }
        return builder.build(
            warnings = emptyList(),
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$fieldPath」を必須化" + (fromPhase?.let { "（フェーズ「$it」以降）" } ?: ""),
                "文書 $docFileCount ファイル・$filled 箇所へ既定値「$defaultRaw」を充填",
            ),
        )
    }

    /**
     * 型変換の一括版（UX-70 の修復UIの一般化）: 決定的に解決できる変換だけを提供する。
     * - スカラー → array: 現在値を1要素の配列に包む（制約は item へ移す）
     * - array → 要素の型: 1要素なら値を引き継ぐ・空なら削除・複数要素は拒否
     * - string / text → enum: `--values` の選択肢へ変換（逸脱値があれば一覧つきで拒否）・→ int / number: 全値の解析を検証して型付け
     * - enum → string、int / number / boolean / date → string: 値を文字列化
     */
    fun convertFieldType(
        ws: Workspace,
        schemaId: String,
        fieldPath: String,
        targetTypeId: String,
        enumValues: List<String> = emptyList(),
    ): EntityOps.Plan {
        val entry = targetSchema(ws, schemaId)
        val res = resolveField(ws, entry, fieldPath)
        requireEditableSchema(ws, res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val target = res.site.def
        val key = target.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val node = verifyFieldNode(ws, res.site, key)
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
        val builder = PlanBuilder(ws)
        val schemaFile = res.site.module.source
        val reachable = reachableSchemas(ws) { it === target }
        val reachableIds = reachable.map { it.id }.toSet()
        val violations = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var converted = 0
        var docFileCount = 0

        /** 文書側の変換を全ファイルへ適用する（transform が null を返した箇所は違反として集める）。 */
        fun convertDocs(transform: (YNode, YPath, Path) -> PatchOp?) {
            forEachDocument(ws, reachableIds) { file, docSchema, root ->
                val hits = collectFieldHits(docSchema, root) { it === target }
                val ops = mutableListOf<PatchOp>()
                for (hit in hits) {
                    val value = getAt(root, hit) ?: continue
                    transform(value, hit, file)?.let { ops += it }
                }
                if (ops.isEmpty()) return@forEachDocument
                converted += ops.size
                docFileCount++
                builder.addAll(file, ops)
            }
        }

        when {
            // スカラー → 配列（wrap）
            targetTypeId == FieldType.ARRAY.id && curType in scalarTypes -> {
                val item = YNode.Map()
                item.entries["type"] = YNode.Scalar(curType.id)
                constraintKeys.forEach { k -> node.entries[k]?.let { item.entries[k] = deepCopy(it) } }
                builder.add(schemaFile, PatchOp.Set(res.site.path + "type", YNode.Scalar(FieldType.ARRAY.id)))
                (constraintKeys + "default").forEach { k ->
                    if (k in node.entries) builder.add(schemaFile, PatchOp.Delete(res.site.path + k))
                }
                if ("default" in node.entries) warnings += "default はスカラー用のため削除します（必要なら配列用に再設定してください）"
                builder.add(schemaFile, PatchOp.Insert(res.site.path + "item", item))
                convertDocs { value, hit, _ ->
                    when {
                        value is YNode.Scalar && !scalarText(value).isNullOrBlank() ->
                            PatchOp.Set(hit, YNode.Seq(mutableListOf(YNode.Scalar(value.value))))
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
                convertDocs { value, hit, file ->
                    when {
                        value !is YNode.Seq -> null
                        value.items.isEmpty() -> PatchOp.Delete(hit)
                        value.items.size == 1 && value.items[0] is YNode.Scalar ->
                            PatchOp.Set(hit, YNode.Scalar((value.items[0] as YNode.Scalar).value))
                        else -> {
                            violations += "${ws.relPath(file)} の ${hit.pretty()}（要素 ${value.items.size} 件）"
                            null
                        }
                    }
                }
                builder.add(schemaFile, PatchOp.Set(res.site.path + "type", YNode.Scalar(targetTypeId)))
                val itemNode = node.entries["item"] as? YNode.Map
                constraintKeys.forEach { k -> itemNode?.entries?.get(k)?.let { builder.add(schemaFile, PatchOp.Set(res.site.path + k, deepCopy(it))) } }
                builder.add(schemaFile, PatchOp.Delete(res.site.path + "item"))
            }
            // string / text → enum（検証つき）
            targetTypeId == FieldType.ENUM.id && curType in setOf(FieldType.STRING, FieldType.TEXT) -> {
                require(enumValues.isNotEmpty()) { "--values で選択肢を指定してください（例 --values open,done）" }
                convertDocs { value, hit, file ->
                    val text = scalarText(value)
                    if (!text.isNullOrBlank() && text !in enumValues) {
                        violations += "${ws.relPath(file)} の ${hit.pretty()}（値「$text」）"
                    }
                    null
                }
                builder.add(schemaFile, PatchOp.Set(res.site.path + "type", YNode.Scalar(FieldType.ENUM.id)))
                builder.add(schemaFile, PatchOp.Set(res.site.path + "values", YNode.Seq(enumValues.map { YNode.Scalar(it) }.toMutableList())))
            }
            // enum → string / text
            curType == FieldType.ENUM && targetTypeId in setOf(FieldType.STRING.id, FieldType.TEXT.id) -> {
                builder.add(schemaFile, PatchOp.Set(res.site.path + "type", YNode.Scalar(targetTypeId)))
                if ("values" in node.entries) builder.add(schemaFile, PatchOp.Delete(res.site.path + "values"))
            }
            // string / text → int / number（検証つきの型付け）
            targetTypeId in setOf(FieldType.INT.id, FieldType.NUMBER.id) && curType in setOf(FieldType.STRING, FieldType.TEXT) -> {
                convertDocs { value, hit, file ->
                    val text = scalarText(value)?.trim()
                    if (text.isNullOrBlank()) return@convertDocs null
                    val typed: Any? = if (targetTypeId == FieldType.INT.id) text.toLongOrNull() else text.toDoubleOrNull()
                    if (typed == null) {
                        violations += "${ws.relPath(file)} の ${hit.pretty()}（値「$text」）"
                        null
                    } else PatchOp.Set(hit, YNode.Scalar(typed))
                }
                builder.add(schemaFile, PatchOp.Set(res.site.path + "type", YNode.Scalar(targetTypeId)))
            }
            // int / number / boolean / date → string / text（文字列化）
            targetTypeId in setOf(FieldType.STRING.id, FieldType.TEXT.id) &&
                curType in setOf(FieldType.INT, FieldType.NUMBER, FieldType.BOOLEAN, FieldType.DATE) -> {
                convertDocs { value, hit, _ ->
                    val text = scalarText(value) ?: return@convertDocs null
                    PatchOp.Set(hit, YNode.Scalar(text))
                }
                builder.add(schemaFile, PatchOp.Set(res.site.path + "type", YNode.Scalar(targetTypeId)))
            }
            else -> error(
                "対応していない型変換です: ${curType.id} → $targetTypeId" +
                    "（対応: スカラー→array・array→要素型・string→enum/int/number・enum→string・int/number/boolean/date→string）"
            )
        }
        if (violations.isNotEmpty()) {
            error(
                "変換できない値があるため中止します: " +
                    violations.take(5).joinToString("、") + (if (violations.size > 5) " 他${violations.size - 5}件" else "")
            )
        }
        return builder.build(
            warnings = warnings,
            summary = listOf(
                "スキーマ「$schemaId」のフィールド「$fieldPath」を ${curType.id} → $targetTypeId へ変換",
                "文書 $docFileCount ファイル・$converted 箇所の値を変換",
            ),
        )
    }

    // ================================================================ R25-04: 定義・関係の改名

    /**
     * named definition の改名: `definitions` のキーと、全スキーマの `$ref`（非修飾はモジュール内・
     * `module#name` は横断）、ビューの `definition` 参照を1つの変更セットで書き換える。文書は変わらない。
     */
    fun renameDefinition(ws: Workspace, moduleId: String, defName: String, newName: String): EntityOps.Plan {
        requireValidName(newName, "改名先の定義名")
        require(newName != defName) { "改名先が現在の定義名と同じです: $defName" }
        val module = ws.schemas.firstOrNull { it.id == moduleId } ?: error("スキーマ「$moduleId」が見つかりません")
        requireEditableSchema(ws, module, "スキーマ「$moduleId」")
        require(defName in module.definitions) {
            "定義「$defName」が見つかりません（定義: ${module.definitions.keys.sorted().joinToString(", ")}）"
        }
        require(newName !in module.definitions) { "定義「$newName」は既にあります" }

        val builder = PlanBuilder(ws)
        builder.add(module.source, PatchOp.RenameKey(listOf("definitions", defName), newName))
        var refCount = 0
        for (s in ws.schemas) {
            if (!isEditableSchema(ws, s)) continue
            val tree = YamlIO.parse(Files.readString(s.source))
            walkYamlEntries(tree) { path, entryKey, value ->
                if (entryKey != "\$ref" && entryKey != "ref") return@walkYamlEntries
                val v = scalarText(value) ?: return@walkYamlEntries
                val qualified = v.contains('#')
                val mod = if (qualified) v.substringBefore('#') else s.id
                val name = if (qualified) v.substringAfter('#') else v
                if (mod == moduleId && name == defName) {
                    builder.add(s.source, PatchOp.Set(path + entryKey, YNode.Scalar(if (qualified) "$moduleId#$newName" else newName)))
                    refCount++
                }
            }
        }
        val warnings = mutableListOf<String>()
        var viewCount = 0
        for (vf in Views.viewFiles(ws.root)) {
            val tree = runCatching { YamlIO.parse(Files.readString(vf)) }.getOrNull() as? YNode.Map ?: continue
            walkYamlEntries(tree) { path, entryKey, value ->
                if (entryKey != "definition") return@walkYamlEntries
                val v = scalarText(value) ?: return@walkYamlEntries
                when {
                    v == "$moduleId#$defName" -> {
                        builder.add(vf, PatchOp.Set(path + entryKey, YNode.Scalar("$moduleId#$newName")))
                        viewCount++
                    }
                    !v.contains('#') && v == defName ->
                        if (scalarText(tree.entries["schemaId"]) == moduleId) {
                            builder.add(vf, PatchOp.Set(path + entryKey, YNode.Scalar(newName)))
                            viewCount++
                        } else {
                            warnings += "${vf.fileName}: definition「$v」が同名です（モジュールが特定できないため書き換えません・要確認）"
                        }
                    else -> Unit
                }
            }
        }
        return builder.build(
            warnings = warnings,
            summary = listOf(
                "定義「$moduleId#$defName」→「$moduleId#$newName」",
                "スキーマの参照 $refCount 箇所・ビューの参照 $viewCount 箇所を書き換え（文書は変わりません）",
            ),
        )
    }

    /**
     * 定義の抽出: インラインのフィールド構造（type / fields / item / values など）を named definition へ
     * 移し、フィールドは `$ref` 参照に置き換える（key / label / required / description / role は使用側に残す）。
     * 文書は変わらない。
     */
    fun extractDefinition(ws: Workspace, schemaId: String, fieldPath: String, defName: String): EntityOps.Plan {
        requireValidName(defName, "定義名")
        val entry = targetSchema(ws, schemaId)
        val res = resolveField(ws, entry, fieldPath)
        requireEditableSchema(ws, res.site.module, "フィールドの定義元（${res.site.module.id}）")
        require(defName !in res.site.module.definitions) { "定義「$defName」は既にあります" }
        val key = res.site.def.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val node = verifyFieldNode(ws, res.site, key)
        require(node.entries["\$ref"] == null && node.entries["ref"] == null) { "既に定義参照（\$ref）です: $fieldPath" }
        val keep = setOf("key", "label", "required", "requiredFrom", "description", "role")
        val moved = node.entries.keys.filter { it !in keep }
        require(moved.isNotEmpty()) { "抽出する構造がありません（type などの構造キーが必要です）" }

        val builder = PlanBuilder(ws)
        val file = res.site.module.source
        val tree = YamlIO.parse(Files.readString(file))
        val defMap = YNode.Map()
        moved.forEach { k -> defMap.entries[k] = deepCopy(node.entries[k]!!) }
        if ((tree as? YNode.Map)?.entries?.get("definitions") == null) {
            builder.add(file, PatchOp.Set(listOf("definitions"), YNode.Map()))
        }
        builder.add(file, PatchOp.Insert(listOf("definitions", defName), defMap))
        moved.forEach { k -> builder.add(file, PatchOp.Delete(res.site.path + k)) }
        builder.add(file, PatchOp.Insert(res.site.path + "\$ref", YNode.Scalar(defName)))
        return builder.build(
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
    fun inlineDefinition(ws: Workspace, schemaId: String, fieldPath: String): EntityOps.Plan {
        val entry = targetSchema(ws, schemaId)
        val res = resolveField(ws, entry, fieldPath)
        requireEditableSchema(ws, res.site.module, "フィールドの定義元（${res.site.module.id}）")
        val key = res.site.def.key ?: error("フィールドのキーを特定できません: $fieldPath")
        val node = verifyFieldNode(ws, res.site, key)
        val refKey = when {
            "\$ref" in node.entries -> "\$ref"
            "ref" in node.entries -> "ref"
            else -> error("定義参照（\$ref）ではありません: $fieldPath")
        }
        val located = entry.definitionTarget(res.site.def) ?: error("参照先の定義が見つかりません: ${scalarText(node.entries[refKey])}")
        val (defKey, _) = located
        val defModule = ws.schemas.firstOrNull { it.id == defKey.moduleId } ?: error("定義モジュール「${defKey.moduleId}」が見つかりません")
        requireEditableSchema(ws, defModule, "定義モジュール（${defModule.id}）")
        val defNode = getAt(YamlIO.parse(Files.readString(defModule.source)), listOf("definitions", defKey.name)) as? YNode.Map
            ?: error("定義「$defKey」の実体が見つかりません")

        val builder = PlanBuilder(ws)
        builder.add(res.site.module.source, PatchOp.Delete(res.site.path + refKey))
        var copied = 0
        defNode.entries.forEach { (k, v) ->
            if (k !in node.entries) {
                builder.add(res.site.module.source, PatchOp.Insert(res.site.path + k, deepCopy(v)))
                copied++
            }
        }
        // 参照が残らなければ定義自体を削除する
        val remaining = countDefinitionRefs(ws, defKey.moduleId, defKey.name) - 1
        val notes = mutableListOf<String>()
        if (remaining <= 0) {
            builder.add(defModule.source, PatchOp.Delete(listOf("definitions", defKey.name)))
            notes += "他に参照が無いため定義「$defKey」自体も削除します"
        } else {
            notes += "定義「$defKey」は他に $remaining 箇所から参照されているため残します"
        }
        return builder.build(
            warnings = emptyList(),
            summary = listOf(
                "フィールド「$fieldPath」の \$ref を定義「$defKey」の内容へインライン化（$copied キーを複製。文書は変わりません）",
            ) + notes,
        )
    }

    /**
     * 関係（relation）の改名: 関係レジストリの `name`・全スキーマの `relation:`（ref フィールド・coverage・
     * constraints のクエリ）・ビューの `relation:` を1つの変更セットで書き換える。文書は変わらない
     * （関係は参照を持つ側のスキーマに宣言され、文書には書かれない）。
     */
    fun renameRelation(ws: Workspace, oldName: String, newName: String): EntityOps.Plan {
        requireValidName(newName, "改名先の関係名")
        require(newName != oldName) { "改名先が現在の関係名と同じです: $oldName" }
        require(newName !in ws.relations) { "関係「$newName」は既にレジストリに宣言されています" }
        val builder = PlanBuilder(ws)
        var count = 0
        for (s in ws.schemas) {
            if (!isEditableSchema(ws, s)) continue
            val tree = YamlIO.parse(Files.readString(s.source))
            walkYamlEntries(tree) { path, entryKey, value ->
                val hit = scalarText(value) == oldName && (
                    entryKey == "relation" ||
                        (entryKey == "name" && s.kind == SchemaKind.RELATIONS && path.firstOrNull() == "relations")
                    )
                if (hit) {
                    builder.add(s.source, PatchOp.Set(path + entryKey, YNode.Scalar(newName)))
                    count++
                }
            }
        }
        for (vf in Views.viewFiles(ws.root)) {
            val tree = runCatching { YamlIO.parse(Files.readString(vf)) }.getOrNull() ?: continue
            walkYamlEntries(tree) { path, entryKey, value ->
                if (entryKey == "relation" && scalarText(value) == oldName) {
                    builder.add(vf, PatchOp.Set(path + entryKey, YNode.Scalar(newName)))
                    count++
                }
            }
        }
        require(count > 0) { "関係「$oldName」の出現が見つかりません" }
        return builder.build(
            warnings = definitionDocMentions(ws, oldName),
            summary = listOf(
                "関係「$oldName」→「$newName」",
                "レジストリ・スキーマ・ビューの $count 箇所を書き換え（文書は変わりません）",
            ),
        )
    }

    /**
     * スキーマIDの改名: スキーマの `id:` と、全スキーマの修飾 `$ref`（`old#name`）・`schemaId(s)` 系の参照・
     * coverage の `fromSchemaIds` / `detailSchemaIds`・クエリの `schema:`、ビューの同種の参照を書き換える。
     * 文書・match（glob）は変わらない。
     */
    fun renameSchemaId(ws: Workspace, oldId: String, newId: String): EntityOps.Plan {
        requireValidName(newId, "改名先のスキーマID")
        require(newId != oldId) { "改名先が現在のスキーマIDと同じです: $oldId" }
        val schemaDef = ws.schemas.firstOrNull { it.id == oldId } ?: error("スキーマ「$oldId」が見つかりません")
        requireEditableSchema(ws, schemaDef, "スキーマ「$oldId」")
        require(ws.schemas.none { it.id == newId }) { "スキーマID「$newId」は既にあります" }

        val builder = PlanBuilder(ws)
        builder.add(schemaDef.source, PatchOp.Set(listOf("id"), YNode.Scalar(newId)))
        var count = 0
        fun rewriteIn(file: Path, tree: YNode) {
            walkYamlEntries(tree) { path, entryKey, value ->
                when {
                    (entryKey == "\$ref" || entryKey == "ref") && scalarText(value)?.startsWith("$oldId#") == true -> {
                        builder.add(file, PatchOp.Set(path + entryKey, YNode.Scalar("$newId#${scalarText(value)!!.substringAfter('#')}")))
                        count++
                    }
                    entryKey == "definition" && scalarText(value)?.startsWith("$oldId#") == true -> {
                        builder.add(file, PatchOp.Set(path + entryKey, YNode.Scalar("$newId#${scalarText(value)!!.substringAfter('#')}")))
                        count++
                    }
                    (entryKey == "schemaId" || entryKey == "schema") && scalarText(value) == oldId -> {
                        builder.add(file, PatchOp.Set(path + entryKey, YNode.Scalar(newId)))
                        count++
                    }
                    entryKey in setOf("schemaIds", "fromSchemaIds", "detailSchemaIds") && value is YNode.Seq -> {
                        value.items.forEachIndexed { i, v ->
                            if (scalarText(v) == oldId) {
                                builder.add(file, PatchOp.Set(path + entryKey + i, YNode.Scalar(newId)))
                                count++
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
        for (s in ws.schemas) {
            if (!isEditableSchema(ws, s)) continue
            rewriteIn(s.source, YamlIO.parse(Files.readString(s.source)))
        }
        for (vf in Views.viewFiles(ws.root)) {
            runCatching { YamlIO.parse(Files.readString(vf)) }.getOrNull()?.let { rewriteIn(vf, it) }
        }
        return builder.build(
            warnings = definitionDocMentions(ws, oldId),
            summary = listOf(
                "スキーマID「$oldId」→「$newId」",
                "スキーマ・ビューの参照 $count 箇所を書き換え（文書と match は変わりません）",
            ),
        )
    }

    // ================================================================ スキーマ側の解決

    /** スキーマ定義上の実体1件: 保持モジュール（YAMLを持つスキーマ）・そのファイル内のYAML座標・FieldDef インスタンス。 */
    private data class Site(val module: SchemaDef, val path: YPath, val def: FieldDef)

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

    /** ワークスペース内の実ファイルに定義されたスキーマか（組込み・derive 派生の除外）。 */
    private fun isEditableSchema(ws: Workspace, s: SchemaDef): Boolean =
        s.source.startsWith(ws.root) &&
            runCatching { ws.layout.zoneOf(ws.relPath(s.source)) }.getOrNull() == WorkspaceLayout.Zone.SCHEMAS

    private fun requireEditableSchema(ws: Workspace, s: SchemaDef, label: String) {
        require(s.source.startsWith(ws.root)) { "$label は組込みスキーマのため変更できません" }
        require(isEditableSchema(ws, s)) {
            "$label は派生スキーマ（derive）のため対象外です。導出元文書（${ws.relPath(s.source)}）を修正してください"
        }
    }

    /** 操作対象のスキーマの共通検査（存在・kind・編集可能）。 */
    private fun targetSchema(ws: Workspace, schemaId: String): SchemaDef {
        val entry = ws.schemas.firstOrNull { it.id == schemaId } ?: error("スキーマ「$schemaId」が見つかりません")
        require(entry.kind != SchemaKind.RELATIONS) { "関係レジストリにフィールド定義はありません: $schemaId" }
        require(entry.kind != SchemaKind.LIBRARY) {
            "library の定義は、その定義を使う文書スキーマのフィールドパスで指定してください（定義側が書き換わり、全使用側の文書が対象になります）"
        }
        requireEditableSchema(ws, entry, "スキーマ「$schemaId」")
        return entry
    }

    private fun requireValidName(name: String, label: String) {
        require(name.isNotBlank()) { "${label}を指定してください" }
        require(name.none { it in ".[]#" || it.isWhitespace() }) { "${label}に . [ ] # や空白は使えません: $name" }
    }

    /** スキーマYAMLの実体（フィールド定義の map）を確認する。解決結果とディスクがずれていたら中止する安全確認。 */
    private fun verifyFieldNode(ws: Workspace, site: Site, expectedKey: String): YNode.Map {
        val node = getAt(YamlIO.parse(Files.readString(site.module.source)), site.path) as? YNode.Map
            ?: error("スキーマ定義の実体が見つかりません: ${ws.relPath(site.module.source)} の ${site.path.pretty()}")
        require(scalarText(node.entries["key"]) == expectedKey) {
            "スキーマ定義の実体（${site.path.pretty()}）のキーが「$expectedKey」と一致しません（スキーマの未保存編集の可能性）"
        }
        return node
    }

    /** ドット区切りのフィールドパスを、スキーマ定義上の実体へ解決する（要素コンテキストの署名つき）。 */
    private fun resolveField(ws: Workspace, entry: SchemaDef, fieldPath: String): Resolution {
        val segments = fieldPath.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "フィールドパスを指定してください（例 details.priority）" }
        var container = Site(entry, listOf("root"), entry.root)
        val crossings = mutableListOf<Crossing>()
        val consumed = mutableListOf<String>()
        val tail = mutableListOf<String>()
        var result: Resolution? = null
        segments.forEachIndexed { i, name ->
            container = descendContainers(ws, entry, container) { real ->
                crossings += Crossing(consumed.toList(), real)
                tail.clear()
            }
            val found = lookupChild(ws, entry, container, name, fieldPath)
            consumed += name
            tail += name
            if (i == segments.lastIndex) result = Resolution(found.site, found.siblings, crossings.toList(), tail.toList())
            else container = found.site
        }
        return result!!
    }

    /** 移動先コンテナ（object）の解決。空・`.` はルート。配列・map を指した場合は要素の object へ降りる。 */
    private fun resolveContainer(ws: Workspace, entry: SchemaDef, containerPath: String): ContainerResolution {
        val trimmed = containerPath.trim()
        if (trimmed.isEmpty() || trimmed == ".") {
            val (real, _) = derefChain(ws, entry, Site(entry, listOf("root"), entry.root))
            require(real.def.type == FieldType.OBJECT) { "スキーマの root が object ではありません" }
            return ContainerResolution(real, emptyList(), emptyList())
        }
        val res = resolveField(ws, entry, trimmed)
        val crossings = res.crossings.toMutableList()
        val tail = res.tailKeys.toMutableList()
        val allKeys = trimmed.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        val landed = descendContainers(ws, entry, res.site) { real ->
            crossings += Crossing(allKeys, real)
            tail.clear()
        }
        val (real, _) = derefChain(ws, entry, landed)
        require(real.def.type == FieldType.OBJECT) { "移動先「$containerPath」は object ではありません（${real.def.type.id}）" }
        return ContainerResolution(real, crossings.toList(), tail.toList())
    }

    /**
     * `$ref` の連鎖を辿り、実体定義のサイトと「実効 cases を持つサイト」（連鎖上の最初の非空 cases。
     * `SchemaDef.resolve` の合成規則と同じ）を返す。
     */
    private fun derefChain(ws: Workspace, entry: SchemaDef, site: Site): Pair<Site, Site?> {
        var current = site
        var casesSite: Site? = null
        val seen = mutableSetOf<String>()
        while (true) {
            if (casesSite == null && current.def.cases.isNotEmpty()) casesSite = current
            if (current.def.ref == null) break
            val (key, targetDef) = entry.definitionTarget(current.def) ?: break
            if (!seen.add(key.toString())) break
            val module = ws.schemas.firstOrNull { it.id == key.moduleId }
                ?: error("定義モジュール「${key.moduleId}」が見つかりません")
            current = Site(module, listOf("definitions", key.name), targetDef)
        }
        return current to casesSite
    }

    /** `$ref` の最終実体（定義インスタンス）。非 ref はそのまま返す。 */
    private fun finalDef(entry: SchemaDef, field: FieldDef): FieldDef {
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
    private fun descendContainers(ws: Workspace, entry: SchemaDef, site: Site, onCross: ((FieldDef) -> Unit)? = null): Site {
        var current = site
        while (true) {
            val (real, _) = derefChain(ws, entry, current)
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

    /** object コンテナから名前で子フィールドを引く（fields と、enum 子フィールドの cases 由来キーの両方。多義は拒否）。 */
    private fun lookupChild(ws: Workspace, entry: SchemaDef, container: Site, name: String, fieldPath: String): Target {
        val (real, _) = derefChain(ws, entry, container)
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
            val (_, casesSite) = derefChain(ws, entry, Site(real.module, real.path + "fields" + i, f))
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

    /** object 実体サイトの子キー集合（fields＋cases 由来。移動先の衝突検査用）。 */
    private fun childKeysOf(ws: Workspace, entry: SchemaDef, objectSite: Site): Set<String> {
        val keys = mutableSetOf<String>()
        objectSite.def.fields.forEach { f ->
            f.key?.let(keys::add)
            val (_, casesSite) = derefChain(ws, entry, Site(objectSite.module, objectSite.path, f))
            casesSite?.def?.cases?.values?.flatten()?.forEach { cf -> cf.key?.let(keys::add) }
        }
        return keys
    }

    /** スキーマの走査で条件に合うフィールドへ到達できるスキーマ（$ref 共有・derive 派生の影響判定）。 */
    private fun reachableSchemas(ws: Workspace, predicate: (FieldDef) -> Boolean): List<SchemaDef> =
        ws.schemas.filter { it.kind == SchemaKind.DOCUMENT && reaches(it, predicate) }

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

    /** target がスキーマのルート直下フィールドか（phases.key の追随判定）。 */
    private fun isRootField(s: SchemaDef, target: FieldDef): Boolean =
        (s.resolve(s.root) ?: s.root).fields.any { it === target }

    // ================================================================ 文書側の列挙

    /** 対象スキーマ集合に該当する全文書を列挙する（走査対象・パース可能なもの）。 */
    private fun forEachDocument(ws: Workspace, schemaIds: Set<String>, visit: (Path, SchemaDef, YNode) -> Unit) {
        for (file in ws.files) {
            val s = ws.schemaFor(file) ?: continue
            if (s.id !in schemaIds) continue
            val root = runCatching { YamlIO.parse(Files.readString(file)) }.getOrNull() ?: continue
            visit(file, s, root)
        }
    }

    /**
     * 文書ツリーをスキーマ誘導で走査し、条件に合うフィールドに対応するデータキーの座標を集める。
     * cases は effectiveFields（現在の enum 値で実効なフィールドだけ）を通すため、
     * 同名キーを持つ別 case の値を誤って対象にしない。
     */
    private fun collectFieldHits(s: SchemaDef, root: YNode, predicate: (FieldDef) -> Boolean): List<YPath> {
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

    /** target を実効フィールドに含む親 object の（パス, マップ）一覧（必須化バックフィルの充填先）。 */
    private fun collectParentObjects(s: SchemaDef, root: YNode, target: FieldDef): List<Pair<YPath, YNode.Map>> {
        val out = mutableListOf<Pair<YPath, YNode.Map>>()
        fun walk(def: FieldDef, node: YNode, path: YPath) {
            val eff = s.resolve(def) ?: def
            when (eff.type) {
                FieldType.OBJECT -> {
                    val m = node as? YNode.Map ?: return
                    val fs = s.effectiveFields(eff.fields, m).fields
                    if (fs.any { it === target }) out += path to m
                    fs.forEach { f -> f.key?.let { k -> m.entries[k]?.let { child -> walk(f, child, path + k) } } }
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
        return out
    }

    /** 移動の文書側ops: 中間 object の生成 → Move → 空になった親の削除。衝突（移動先の既存値）を集める。 */
    private fun moveDataOps(
        s: SchemaDef,
        root: YNode,
        target: FieldDef,
        tailSize: Int,
        destTail: List<String>,
        key: String,
        deleteEmptyParents: Boolean,
    ): Pair<List<PatchOp>, List<String>> {
        val ops = mutableListOf<PatchOp>()
        val conflicts = mutableListOf<String>()
        val hits = collectFieldHits(s, root) { it === target }.sortedWith(pathDescending)
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
            creates.forEach { ops += PatchOp.Insert(it, YNode.Map()) }
            ops += PatchOp.Move(p, destKey)
            if (deleteEmptyParents) {
                var depth = p.size - 1
                while (depth > elementRoot.size) {
                    val parentPath = p.subList(0, depth)
                    // 移動先の経路上にある親は空にならない（中間生成・移動で値が入る）
                    if (destParent.size >= parentPath.size && destParent.subList(0, parentPath.size) == parentPath) break
                    val node = getAt(root, parentPath) as? YNode.Map ?: break
                    if (node.entries.size != 1) break
                    ops += PatchOp.Delete(parentPath)
                    depth--
                }
            }
        }
        return ops to conflicts
    }

    /** 同一ファイル内の削除・改名を深い方から行うためのパス降順（数値セグメントは数値比較）。 */
    private val pathDescending = Comparator<YPath> { x, y ->
        for (i in 0 until minOf(x.size, y.size)) {
            val sx = x[i]
            val sy = y[i]
            val c = if (sx is Int && sy is Int) sx.compareTo(sy) else sx.toString().compareTo(sy.toString())
            if (c != 0) return@Comparator -c
        }
        -(x.size.compareTo(y.size))
    }

    // ================================================================ YAML走査・共通部品

    /** YNode ツリーの全マップエントリを訪問する（親パス・キー・値）。宣言の横断書換に使う。 */
    private fun walkYamlEntries(root: YNode, visit: (parentPath: YPath, key: String, value: YNode) -> Unit) {
        fun walk(node: YNode, path: YPath) {
            when (node) {
                is YNode.Map -> node.entries.forEach { (k, v) ->
                    visit(path, k, v)
                    walk(v, path + k)
                }
                is YNode.Seq -> node.items.forEachIndexed { i, v -> walk(v, path + i) }
                else -> Unit
            }
        }
        walk(root, emptyList())
    }

    /** ノードの複製（別の場所へ埋め込むときにインスタンス共有によるアンカー化を避ける）。 */
    private fun deepCopy(node: YNode): YNode = YamlIO.parse(YamlIO.dump(node))

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

    /** 全編集可能スキーマの `$ref` / `ref` のうち、指定定義を参照するものの件数。 */
    private fun countDefinitionRefs(ws: Workspace, moduleId: String, defName: String): Int {
        var count = 0
        for (s in ws.schemas) {
            if (!isEditableSchema(ws, s)) continue
            val tree = runCatching { YamlIO.parse(Files.readString(s.source)) }.getOrNull() ?: continue
            walkYamlEntries(tree) { _, entryKey, value ->
                if (entryKey != "\$ref" && entryKey != "ref") return@walkYamlEntries
                val v = scalarText(value) ?: return@walkYamlEntries
                val qualified = v.contains('#')
                val mod = if (qualified) v.substringBefore('#') else s.id
                val name = if (qualified) v.substringAfter('#') else v
                if (mod == moduleId && name == defName) count++
            }
        }
        return count
    }

    /** 削除によるインデックスずれの調整: seqPath 直下の removedIdx より後ろを指すパスを 1 つ詰める。 */
    private fun adjustAfterDelete(path: YPath, seqPath: YPath, removedIdx: Int): YPath {
        if (path.size <= seqPath.size) return path
        if (path.subList(0, seqPath.size) != seqPath) return path
        val idx = path[seqPath.size] as? Int ?: return path
        if (idx <= removedIdx) return path
        return path.toMutableList().also { it[seqPath.size] = idx - 1 }
    }

    // ================================================================ 警告スキャン

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

    // ================================================================ 変更セットの組み立て

    /** ファイル単位の ops を集め、baseハッシュ付きの変更セット（EntityOps.Plan）へ組む。 */
    private class PlanBuilder(private val ws: Workspace) {
        private val byFile = LinkedHashMap<Path, MutableList<PatchOp>>()

        fun add(file: Path, op: PatchOp) {
            byFile.getOrPut(file) { mutableListOf() } += op
        }

        fun addAll(file: Path, ops: List<PatchOp>) {
            if (ops.isNotEmpty()) byFile.getOrPut(file) { mutableListOf() } += ops
        }

        fun build(warnings: List<String>, summary: List<String>): EntityOps.Plan = EntityOps.Plan(
            changeSet = ChangeSet(
                byFile.entries.sortedBy { it.key.toString() }.map { (file, ops) ->
                    FileChange(file = ws.relPath(file), baseHash = ChangeSets.contentHash(Files.readString(file)), ops = ops)
                },
            ),
            warnings = warnings,
            summary = summary,
        )
    }
}
