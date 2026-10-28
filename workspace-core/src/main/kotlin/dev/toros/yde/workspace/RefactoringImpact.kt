package dev.toros.yde.workspace

import dev.toros.yde.yaml.edit.YamlOperation
import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YPath
import dev.toros.yde.yaml.model.parseYPath
import dev.toros.yde.yaml.model.pretty
import dev.toros.yde.semantic.schema.FieldDef
import dev.toros.yde.semantic.schema.FieldType
import dev.toros.yde.semantic.schema.SchemaKind
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.semantic.schema.globToRegex
import dev.toros.yde.semantic.PlannedDocumentChange
import dev.toros.yde.semantic.schema.AnyIndex
import dev.toros.yde.semantic.schema.PathPattern
import dev.toros.yde.semantic.schema.Projection
import dev.toros.yde.yaml.YamlIO

/**
 * リファクタリングの計画のうち、ワークスペースの定義文書を見て決まる分（P27 段D の D-c で、root の core/RefactorImpact.kt と
 * core/Views.kt から移した。意図的差分 D-36）。データ・スキーマ本体の計画は semantic.SemanticRefactor が作り、ここは、
 * - フィールドの改名へのビュー定義の追随（スカラーの書き換えだけ）と、宣言節・定義文書の言及の警告
 * - フィールドの削除での、ビュー・宣言節・定義文書の言及の警告
 * - 文書の移動・改名で、ビュー定義の対象から外れる警告
 * を作る。WorkspaceSession.planRefactoring が計画に含めるので、どの入口（アプリ・CLI）でも同じ計画になる。
 * ビュー定義・スキーマ文書・定義文書の内容は、保存されている内容（ストア）から読む。
 */
object RefactoringImpact {

    /** 文書のファイル名（警告の文面の先頭に使う）。 */
    private fun fileName(id: DocumentId): String = id.value.substringAfterLast('/')

    /** ディレクトリの直下にある YAML 文書（ファイル名順。下の階層は含めない）。 */
    private fun documentsDirectlyUnder(session: WorkspaceSession, dir: String): List<DocumentId> =
        session.storedDocuments()
            .filter { it.value.substringBeforeLast('/', "") == dir }
            .sortedBy { fileName(it) }

    /** views ゾーンの定義文書の一覧（ファイル名順）。 */
    internal fun viewDocuments(session: WorkspaceSession): List<DocumentId> =
        documentsDirectlyUnder(session, session.layout.viewsDir)

    /** 計画への補強: 追加の文書変更（ビュー追随。全てスカラー Set）と警告。 */
    data class Impact(val changes: List<PlannedDocumentChange>, val warnings: List<String>)

    /** フィールド一括改名（R25-01）のビュー追随＋言及警告。解決できない場合は追随なし（警告のみ）。 */
    fun fieldRename(session: WorkspaceSession, schemaId: String, fieldPath: String, newName: String): Impact {
        val entry = session.schemas.firstOrNull { it.id == schemaId } ?: return Impact(emptyList(), emptyList())
        val oldName = fieldPath.substringAfterLast('.').trim()
        val mentions = schemaSectionMentions(session, oldName) + definitionDocMentions(session, oldName)
        val target = resolveFieldDef(entry, fieldPath) ?: return Impact(emptyList(), mentions)
        val reachableIds = session.schemas
            .filter { it.kind == SchemaKind.DOCUMENT && reaches(it) { f -> f === target } }
            .map { it.id }.toSet()
        val impact = fieldRenameImpact(session, session.schemas, reachableIds, target, oldName, newName)
        // ビュー追随は型付き操作（内部の正準）で生成される＝変換なしで計画へ合成する（RP-02）
        val changes = impact.ops.entries.sortedBy { it.key.value }.mapNotNull { (file, ops) ->
            if (ops.isEmpty()) null else PlannedDocumentChange(file, ops)
        }
        return Impact(changes, impact.warnings + mentions)
    }

    /** フィールド一括削除（R25-03）の言及警告（ビュー参照が壊れる旨＋宣言節・定義文書）。 */
    fun fieldDeleteWarnings(session: WorkspaceSession, fieldPath: String): List<String> {
        val key = fieldPath.substringAfterLast('.').trim()
        if (key.isEmpty()) return emptyList()
        return fieldMentionWarnings(session, key, "削除によりビューの参照が壊れます・要修正") +
            schemaSectionMentions(session, key) + definitionDocMentions(session, key)
    }

    /** 文書移動・改名（R21-03）でビュー定義の対象から外れる警告（旧 DocMove.plan から移設）。 */
    fun moveWarnings(session: WorkspaceSession, oldRel: String, newRel: String): List<String> {
        fun globMatches(glob: String?, rel: String): Boolean =
            glob != null && runCatching { globToRegex(glob).matches(rel) }.getOrDefault(false)
        fun leaves(glob: String?): Boolean = globMatches(glob, oldRel) && !globMatches(glob, newRel)
        val warnings = mutableListOf<String>()
        val viewDefs = ViewDefinitions.load(viewDocuments(session).mapNotNull { id -> session.storedText(id)?.let { id to it } }, session.schemas, session.viewKinds)
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
        // 拡張の種類（flow など）の対象から外れる警告は種類が作る
        for (def in viewDefs.extensionViews) {
            warnings += session.viewKinds.firstOrNull { it.id == def.viewKind }?.moveWarnings(def, ::leaves).orEmpty()
        }
        return warnings
    }

    /**
     * 投影内の固定ID・関係名等は参照型の裏付けがないため、自動置換せず場所付きで開示する。
     * これは意味の評価ではなく保守的な言及の列挙。規則・条件・辞書キー・未知の指示も漏らさない。
     * レンズの見え方（kind: lens）の関係名も同じ扱い（関係の改名で言及を開示する）。
     */
    internal fun viewMentionWarnings(session: WorkspaceSession, token: String): List<String> =
        viewDocuments(session).flatMap { file ->
            val raw = runCatching { YamlIO.parseToRaw(session.storedText(file) ?: "") }.getOrNull() as? Map<*, *>
                ?: return@flatMap emptyList()
            if (raw["kind"] == "lens") {
                return@flatMap (raw["relations"] as? List<*>).orEmpty().mapIndexedNotNull { i, e ->
                    if ((e as? Map<*, *>)?.get("name") == token) "${file.value}: relations[${i + 1}].name に「$token」の言及があります（自動書換の対象外・要確認）" else null
                }
            }
            if (raw["kind"] == "dashboard") {
                val sites = (raw["sections"] as? List<*>).orEmpty().flatMapIndexed { i, section ->
                    DefinitionMentions.literal((section as? Map<*, *>)?.get("filter"), token, "sections[${i + 1}].filter")
                } + DefinitionMentions.literal(raw["actions"], token, "actions")
                return@flatMap sites.map { "${file.value}: $it に「$token」の言及があります（自動書換の対象外・要確認）" }
            }
            // 拡張の種類（flow の projection など）の言及の場所は種類が知る
            val kind = session.viewKinds.firstOrNull { it.id == raw["kind"] } ?: return@flatMap emptyList()
            kind.mentionSites(raw, token).map { "${file.value}: $it に「$token」の言及があります（自動書換の対象外・要確認）" }
        }

    // ================================================================ スキーマ側の解決（読み取り専用の簡約版）

    /**
     * ドット区切りのフィールドパスを WorkspaceSchema 上の FieldDef 実体へ解決する（旧 SchemaRefactor.resolveField の
     * 読み取り簡約版: 書換座標・兄弟検査は持たない）。多義・不在は null（追随なし・警告のみに落とす）。
     * カーネル側の計画生成が同じ規則で検査済みのため、ここで失敗しても計画自体は成立している。
     */
    private fun resolveFieldDef(entry: WorkspaceSchema, fieldPath: String): FieldDef? {
        val segments = fieldPath.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var container = entry.tree.root
        segments.forEachIndexed { i, name ->
            val found = childNamed(entry, descend(entry, container), name) ?: return null
            if (i == segments.lastIndex) return found
            container = found
        }
        return null
    }

    /** `$ref` の最終実体（定義インスタンス）。子リストのインスタンス保持が前提（architecture/schema.md「リファクタリング」）。 */
    private fun deref(entry: WorkspaceSchema, field: FieldDef): FieldDef {
        var current = field
        val seen = mutableSetOf<String>()
        while (current.ref != null) {
            val (key, targetDef) = entry.tree.definitionTarget(current) ?: break
            if (!seen.add(key.toString())) break
            current = targetDef
        }
        return current
    }

    /** `$ref` を辿りながら array の item / map の value へ暗黙に降りる（object・スカラーに到達するまで）。 */
    private fun descend(entry: WorkspaceSchema, field: FieldDef): FieldDef {
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

    /** `$ref` 連鎖上の最初の非空 cases（WorkspaceSchema.resolve の合成規則と同じ）。 */
    private fun casesOf(entry: WorkspaceSchema, field: FieldDef): Map<String, List<FieldDef>> {
        var current = field
        val seen = mutableSetOf<String>()
        while (true) {
            if (current.cases.isNotEmpty()) return current.cases
            val next = current.ref?.let { entry.tree.definitionTarget(current) }
                ?.takeIf { seen.add(it.first.toString()) }?.second ?: return emptyMap()
            current = next
        }
    }

    /** object コンテナから名前で子フィールドを引く（fields と cases 由来キーの両方。多義は null）。 */
    private fun childNamed(entry: WorkspaceSchema, container: FieldDef, name: String): FieldDef? {
        val real = deref(entry, container)
        if (real.type != FieldType.OBJECT) return null
        val direct = real.fields.filter { it.key == name }
        val fromCases = real.fields.flatMap { f -> casesOf(entry, f).values.flatten().filter { it.key == name } }
        return (direct + fromCases).singleOrNull()
    }

    /** target へ到達できるか（fields / item / value / cases を `$ref` 解決しつつ走査）。 */
    private fun reaches(s: WorkspaceSchema, predicate: (FieldDef) -> Boolean): Boolean {
        var found = false
        val seen = mutableSetOf<String>()
        fun walk(f: FieldDef) {
            if (found) return
            if (predicate(f)) {
                found = true
                return
            }
            val located = s.tree.definitionTarget(f)
            if (located != null && !seen.add(located.first.toString())) return
            val r = s.tree.resolve(f) ?: f
            r.fields.forEach(::walk)
            r.item?.let(::walk)
            r.value?.let(::walk)
            r.cases.values.flatten().forEach(::walk)
        }
        walk(s.tree.root)
        return found
    }

    // ================================================================ 言及スキャン（旧 SchemaRefactor から移設）

    /** ワークスペース内の実ファイルに定義されたスキーマか（組込み・derive 派生の除外）。 */
    private fun isEditableSchema(session: WorkspaceSession, s: WorkspaceSchema): Boolean =
        session.layout.zoneOf(s.source.value) == WorkspaceLayout.Zone.SCHEMAS

    /**
     * スキーマ内の宣言節（consistency / constraints / coverage / derive / filePattern / scaffold）と entity.owns の言及スキャン。
     * 自動書換の対象外のため、語境界つきの近似で言及箇所を列挙し対応判断は人間に委ねる。
     */
    private fun schemaSectionMentions(session: WorkspaceSession, token: String): List<String> {
        val out = mutableListOf<String>()
        val re = tokenRegex(token)
        for (s in session.schemas) {
            if (!isEditableSchema(session, s)) continue
            val raw = runCatching { YamlIO.parseToRaw(session.storedText(s.source) ?: "") }.getOrNull() as? Map<*, *> ?: continue
            for (section in listOf("consistency", "constraints", "coverage", "derive", "filePattern", "scaffold")) {
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
    private fun definitionDocMentions(session: WorkspaceSession, token: String): List<String> {
        val re = tokenRegex(token)
        val mentioned = mutableListOf<String>()
        val layout = session.layout
        for (dir in listOf(layout.activitiesDir, layout.agentsDir, layout.skillsDir, layout.evalsDir)) {
            for (id in documentsDirectlyUnder(session, dir)) {
                val text = session.storedText(id) ?: continue
                if (re.containsMatchIn(text)) mentioned += id.value
            }
        }
        if (mentioned.isEmpty()) return emptyList()
        return listOf(
            "定義文書に「$token」の言及があります（自動書換の対象外・要確認）: " +
                mentioned.take(5).joinToString("、") + (if (mentioned.size > 5) " 他${mentioned.size - 5}件" else "")
        )
    }

    private fun tokenRegex(token: String): Regex = DefinitionMentions.tokenRegex(token)

    // ================================================================ ビュー定義の追随（旧 core/Views.kt から移設）

    /** フィールド改名のビュー追随の結果。ops はビュー定義ファイルごとの書換、warnings は自動書換できない参照箇所。 */
    data class FieldRenameImpact(val ops: Map<DocumentId, List<YamlOperation>>, val warnings: List<String>)

    /**
     * スキーマのフィールド改名（R25-01 / R25-02。architecture/schema.md「リファクタリング」）にビュー定義を追随させる。
     * 自動書換は「パスの静的解決が**全て改名対象を指す**場合」に限る——通常列 `path`・値の式の列 `value` の中の
     * 読み出しの `path` / 参照を辿る `via` / 短い書き方の穴・`newRow.path`・文書ビューの `sections[].fields.path` と
     * include / exclude・被参照表の列 `path` が対象。多義（同名の別フィールドにも解決しうる）は
     * 書き換えず警告する（黙って壊さない）。アタッチ（`at`）等その他の参照箇所は旧名の言及として警告に列挙する。
     */
    internal fun fieldRenameImpact(
        session: WorkspaceSession,
        schemas: List<WorkspaceSchema>,
        reachableIds: Set<String>,
        target: FieldDef,
        oldName: String,
        newName: String,
    ): FieldRenameImpact {
        // 計画（内部の正準＝型付き操作）へそのまま合成できる形で生成する（P29 Phase 4・RP-02: 交換形式の PatchOp を
        // 内部の計画生成では作らない）
        val ops = LinkedHashMap<DocumentId, MutableList<YamlOperation>>()
        val warnings = mutableListOf<String>()
        val docSchemas = schemas.filter { it.kind == SchemaKind.DOCUMENT }
        val byId = docSchemas.associateBy { it.id }
        fun representative(glob: String): String = glob
            .replace(Regex("\\{([^}]*)\\}")) { it.groupValues[1].substringBefore(',') }
            .replace("**", "X").replace("*", "X").replace("?", "X")
        fun contexts(schemaId: Any?, fileGlob: Any?): List<WorkspaceSchema> = when {
            schemaId != null -> listOfNotNull(byId[schemaId.toString()])
            fileGlob != null -> representative(fileGlob.toString()).let { rel -> docSchemas.filter { it.matches(rel) } }
            else -> docSchemas.filter { it.id in reachableIds }
        }
        for (file in viewDocuments(session)) {
            val raw = runCatching { YamlIO.parseToRaw(session.storedText(file) ?: "") }.getOrNull() as? Map<*, *> ?: continue
            val handled = mutableSetOf<String>()  // 書換済み・多義警告済みの座標（collectMentions と同じ crumb 形式）

            /**
             * パス（`[*]` を含んでよい）の静的解決: 全ての解決が改名対象を指せば書き換え後のパス、同名の別フィールドにも
             * 解決しうれば多義（null と true）、改名対象に届かなければ null。
             */
            fun resolveRename(pattern: List<Any>, ctx: List<WorkspaceSchema>, starts: (WorkspaceSchema) -> List<FieldDef>): Pair<List<Any>?, Boolean>? {
                if (pattern.none { it == oldName }) return null
                val results = ctx.flatMap { s -> starts(s).mapNotNull { start -> staticRewrite(s, start, pattern, target, newName) } }
                val rewritten = results.filter { it.second }.map { it.first }.distinct()
                return when {
                    rewritten.size == 1 && results.all { it.second } -> rewritten.single() to false
                    rewritten.isNotEmpty() -> null to true
                    else -> null
                }
            }

            /** パスを contexts×starts の静的解決で書き換える。全解決が対象を指すときだけ書換、多義は警告。 */
            fun tryRewrite(pathValue: Any?, crumb: String, yamlPath: YPath, ctx: List<WorkspaceSchema>, starts: (WorkspaceSchema) -> List<FieldDef>) {
                val text = pathValue?.toString() ?: return
                val pattern = runCatching { PathPattern.parse(text) }.getOrNull() ?: return
                val (rewritten, ambiguous) = resolveRename(pattern, ctx, starts) ?: return
                when {
                    rewritten != null -> {
                        ops.getOrPut(file) { mutableListOf() } += YamlOperation.SetScalar(yamlPath, PathPattern.pretty(rewritten))
                        handled += crumb
                    }
                    ambiguous -> {
                        warnings += "${fileName(file)}: $crumb の path「$text」は同名の別フィールドにも解決しうるため書き換えません（要確認）"
                        handled += crumb
                    }
                }
            }

            /** 短い書き方（`{パス}` を含む文字列）の穴を 1 つずつ静的解決で書き換える。どれかが多義なら書き換えず警告。 */
            fun rewriteTemplate(text: String, crumb: String, yamlPath: YPath, ctx: List<WorkspaceSchema>) {
                var out = text
                var changed = false
                var ambiguous = false
                for (hole in Projection.placeholders(text)) {
                    val pattern = runCatching { PathPattern.parse(hole) }.getOrNull() ?: continue
                    val (rewritten, isAmbiguous) = resolveRename(pattern, ctx, ViewDefinitions::idElements) ?: continue
                    if (isAmbiguous) ambiguous = true
                    else if (rewritten != null) {
                        out = out.replace("{$hole}", "{${PathPattern.pretty(rewritten)}}")
                        changed = true
                    }
                }
                when {
                    ambiguous -> {
                        warnings += "${fileName(file)}: $crumb の「$text」の穴は同名の別フィールドにも解決しうるため書き換えません（要確認）"
                        handled += crumb
                    }
                    changed -> {
                        ops.getOrPut(file) { mutableListOf() } += YamlOperation.SetScalar(yamlPath, out)
                        handled += crumb
                    }
                }
            }

            /**
             * 値の式（列の `value`）の中のパスの書き換え。いまの要素から読む場所（読み出しの `path`・参照を辿る `via`・集計のパス・
             * `format` の短い書き方の穴）は列の出現のスキーマから、`in` で相手を指定した読み出しの `path` は相手のスキーマから解決する。
             * 束縛（`of`）から読む場所や、`via` だけで相手を指定した `path` は文脈が決まらないので言及の警告に任せる。
             * 辞書（`using`）・文書の指定（`in`）・順序の種類（`type`）・決まった値（`value`・配列の中の文字列）はパスではない。
             */
            fun rewriteValue(node: Any?, crumb: String, yamlPath: YPath, rowCtx: List<WorkspaceSchema>) {
                when (node) {
                    is Map<*, *> -> {
                        val hasOf = node["of"] != null
                        val inSel = node["in"] as? Map<*, *>
                        val hasVia = node["via"] != null
                        node.forEach { (k, v) ->
                            val key = k.toString()
                            val here = "$crumb.$key"
                            val yp = yamlPath + key
                            when {
                                key == "via" && !hasOf -> tryRewrite(v, here, yp, rowCtx, ViewDefinitions::idElements)
                                key == "path" && !hasOf -> {
                                    val ctx = when {
                                        inSel != null -> contexts(inSel["schemaId"], inSel["fileGlob"])
                                        hasVia -> emptyList()
                                        else -> rowCtx
                                    }
                                    tryRewrite(v, here, yp, ctx, ViewDefinitions::idElements)
                                }
                                key == "format" && v is String -> rewriteTemplate(v, here, yp, rowCtx)
                                key in setOf("using", "in", "type", "value", "of") -> Unit
                                else -> rewriteValue(v, here, yp, rowCtx)
                            }
                        }
                    }
                    is List<*> -> node.forEachIndexed { j, v -> rewriteValue(v, "$crumb[${j + 1}]", yamlPath + j, rowCtx) }
                    else -> Unit
                }
            }

            /** include / exclude の選別キーを、セクションパス先の子フィールド解決で書き換える。 */
            fun tryRewriteSelection(fm: Map<*, *>, listKey: String, crumb: String, yamlBase: YPath, ctx: List<WorkspaceSchema>) {
                val list = fm[listKey] as? List<*> ?: return
                val sectionPath = runCatching { parseYPath(fm["path"]?.toString() ?: "") }.getOrNull() ?: return
                list.forEachIndexed { j, v ->
                    if (v?.toString() != oldName) return@forEachIndexed
                    val hits = ctx.mapNotNull { s ->
                        val sectionDef = ViewDefinitions.staticFieldAt(s, s.tree.root, sectionPath) ?: return@mapNotNull null
                        val resolved = s.tree.resolve(sectionDef) ?: sectionDef
                        val child = ViewDefinitions.staticChildren(s, resolved).firstOrNull { it.key == oldName } ?: return@mapNotNull null
                        child === target
                    }
                    when {
                        hits.isNotEmpty() && hits.all { it } -> {
                            ops.getOrPut(file) { mutableListOf() } += YamlOperation.SetScalar(yamlBase + listKey + j, newName)
                            handled += crumb
                        }
                        hits.any { it } -> {
                            warnings += "${fileName(file)}: $crumb の「$oldName」は同名の別フィールドにも解決しうるため書き換えません（要確認）"
                            handled += crumb
                        }
                        else -> Unit
                    }
                }
            }

            if (raw["kind"]?.toString() != "document") {
                (raw["columns"] as? List<*>)?.forEachIndexed { i, entry ->
                    val m = entry as? Map<*, *> ?: return@forEachIndexed
                    when {
                        m.containsKey("definition") || m.containsKey("backlinks") -> Unit
                        m.containsKey("value") -> {
                            // 値の式の列: 短い書き方は穴を、完全な書き方は式の中のパスを、それぞれの文脈で書き換える
                            val rowCtx = contexts(m["schemaId"], m["fileGlob"])
                            val crumb = "columns[${i + 1}].value"
                            val yp = listOf("columns", i, "value")
                            when (val v = m["value"]) {
                                is String -> rewriteTemplate(v, crumb, yp, rowCtx)
                                else -> rewriteValue(v, crumb, yp, rowCtx)
                            }
                        }
                        else -> tryRewrite(
                            m["path"], "columns[${i + 1}].path", listOf("columns", i, "path"),
                            contexts(m["schemaId"], m["fileGlob"]), ViewDefinitions::idElements,
                        )
                    }
                }
                (raw["newRow"] as? Map<*, *>)?.let { nr ->
                    val nrFile = nr["file"]?.toString()
                    if (nr["path"] != null && nrFile != null) {
                        // 配列モードの追加先パスは一覧文書のルートから解決する
                        val ctx = docSchemas.filter { it.matches(nrFile.replace('\\', '/')) }
                        tryRewrite(nr["path"], "newRow.path", listOf("newRow", "path"), ctx) { s -> listOf(s.tree.root) }
                    }
                }
            } else {
                val docCtx = when {
                    raw["schemaId"] != null -> listOfNotNull(byId[raw["schemaId"].toString()])
                    raw["match"] != null -> representative(raw["match"].toString()).let { rel -> docSchemas.filter { it.matches(rel) } }
                    else -> emptyList()
                }
                (raw["sections"] as? List<*>)?.forEachIndexed { i, entry ->
                    val m = entry as? Map<*, *> ?: return@forEachIndexed
                    (m["fields"] as? Map<*, *>)?.let { fm ->
                        tryRewrite(
                            fm["path"], "sections[${i + 1}].fields.path", listOf("sections", i, "fields", "path"),
                            docCtx,
                        ) { s -> listOf(s.tree.root) }
                        tryRewriteSelection(fm, "include", "sections[${i + 1}].fields.include", listOf("sections", i, "fields"), docCtx)
                        tryRewriteSelection(fm, "exclude", "sections[${i + 1}].fields.exclude", listOf("sections", i, "fields"), docCtx)
                    }
                    (m["backlinks"] as? Map<*, *>)?.let { bm ->
                        val ctx = stringListOf(bm["schemaIds"]).mapNotNull { byId[it] }
                        (bm["columns"] as? List<*>)?.forEachIndexed { j, c ->
                            val cm = c as? Map<*, *> ?: return@forEachIndexed
                            tryRewrite(
                                cm["path"], "sections[${i + 1}].backlinks.columns[${j + 1}].path",
                                listOf("sections", i, "backlinks", "columns", j, "path"), ctx, ViewDefinitions::idElements,
                            )
                        }
                    }
                }
            }
            collectMentions(raw, oldName).filterNot { it in handled }.forEach {
                warnings += "${fileName(file)}: $it に「$oldName」の言及があります（自動書換の対象外・要確認）"
            }
        }
        return FieldRenameImpact(ops, warnings)
    }

    private fun stringListOf(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.map { it.toString() }
        null -> emptyList()
        else -> listOf(raw.toString())
    }

    /**
     * フィールド名の言及があるビュー定義の警告を列挙する（移動・削除など、パス書換を伴わない
     * リファクタリング操作の影響提示用。R25-02）。
     */
    internal fun fieldMentionWarnings(session: WorkspaceSession, fieldName: String, note: String): List<String> =
        viewDocuments(session).flatMap { file ->
            val raw = runCatching { YamlIO.parseToRaw(session.storedText(file) ?: "") }.getOrNull() as? Map<*, *>
                ?: return@flatMap emptyList()
            collectMentions(raw, fieldName).map { "${fileName(file)}: $it に「$fieldName」の言及があります（$note）" }
        }

    /** 列パスをID要素起点で静的に辿り、target を指すセグメントを newName へ置換したパスと到達有無を返す。 */
    private fun staticRewrite(
        schema: WorkspaceSchema,
        start: FieldDef,
        path: List<Any>,
        target: FieldDef,
        newName: String,
    ): Pair<YPath, Boolean>? {
        var current = start
        val out = mutableListOf<Any>()
        var hit = false
        for (seg in path) {
            val def = schema.tree.resolve(current) ?: return null
            val next: FieldDef? = when {
                def.type == FieldType.OBJECT && seg is String -> ViewDefinitions.staticChildren(schema, def).firstOrNull { it.key == seg }
                def.type == FieldType.MAP && seg is String -> def.value
                def.type == FieldType.ARRAY && (seg is Int || seg === AnyIndex) -> def.item
                else -> null
            }
            if (next == null) return null
            if (next === target && seg is String) { out += newName; hit = true } else out += seg
            current = next
        }
        return out to hit
    }

    /**
     * ビュー定義の生YAMLから、パス系のキー（path / via / at / include / exclude）と、値の式の短い書き方（value / format の
     * 文字列の穴）に含まれる旧名の言及を集める。戻り値は座標（例 `columns[2].path`）。fieldRenameImpact の書換済み座標フィルタと同じ形式。
     */
    private fun collectMentions(raw: Any?, oldName: String): List<String> {
        val out = mutableListOf<String>()
        fun containsOld(text: String): Boolean =
            runCatching { PathPattern.parse(text) }.getOrNull()?.any { it == oldName } ?: false
        fun walk(node: Any?, crumb: String) {
            when (node) {
                is Map<*, *> -> node.forEach { (k, v) ->
                    val key = k.toString()
                    val here = if (crumb.isEmpty()) key else "$crumb.$key"
                    when {
                        // Transformの相対パス・規則・条件を静的な列パスとして扱わない。
                        key == "projection" -> out += DefinitionMentions.literal(v, oldName, here)
                        key in setOf("path", "via", "at") && v != null && v !is Map<*, *> && v !is List<*> ->
                            if (containsOld(v.toString())) out += here
                        key in setOf("value", "format") && v is String ->
                            if (Projection.placeholders(v).any { containsOld(it) }) out += here
                        key in setOf("include", "exclude") && v is List<*> ->
                            if (v.any { it?.toString() == oldName }) out += here
                        else -> walk(v, here)
                    }
                }
                is List<*> -> node.forEachIndexed { i, v -> walk(v, "$crumb[${i + 1}]") }
                else -> Unit
            }
        }
        walk(raw, "")
        return out
    }
}
