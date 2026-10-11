package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import dev.toros.yde.yaml.model.YNode
import dev.toros.yde.workspace.layout.WorkspaceLayout
import dev.toros.yde.semantic.DocumentMeta
import dev.toros.yde.semantic.SemanticCheckSession
import dev.toros.yde.semantic.SemanticDeclarations
import dev.toros.yde.semantic.SemanticGraphIntegrity
import dev.toros.yde.workspace.validate.CheckDelivery
import dev.toros.yde.workspace.validate.DocumentValidation
import dev.toros.yde.workspace.validate.DocumentValidator
import dev.toros.yde.workspace.validate.WorkspaceChecks

/**
 * 読み取ったワークスペース全体について、検査を実行する（P27 段B-4・段D の D-b）。カーネル側の CLI（`workspace validate`）が使う。
 *
 * 派生状態の導出（WorkspaceReader.derive）には含めない——導出はセッションの編集のたびに走るため、そこでワークスペース全体の
 * 検査を評価すると、アプリで1文字打つたびに全体を評価することになる。全体の検査が要る場面（CLI・準備完了レポートなど）が、
 * 明示的に呼ぶ。評価の覚え書き（SemanticCheckSession）を1つ共有するため、時間は文書数に比例する。
 */
object WorkspaceCheckRunner {

    /**
     * 読み込んだ文書の1つ1つを、アプリと同じ組み立て（validate.DocumentValidator）で検証する。文書ID 順。
     * 指摘も保留も無い文書は含めない。中身が空の文書は、アプリと同じく、空のマップとして検証する（意図的差分 D-34）。
     */
    fun validateAll(loaded: LoadedWorkspace): Map<DocumentId, DocumentValidation> =
        validateAll(loaded.schemas, loaded.declarations, loaded.layout, loaded.snapshot.documents, loaded.derived,
            fileExists = loaded.fileExists)

    fun validateAll(
        schemas: List<WorkspaceSchema>,
        declarations: SemanticDeclarations,
        layout: WorkspaceLayout,
        documents: Map<DocumentId, WorkspaceDocument>,
        derived: WorkspaceDerivedState,
        /** 検証する文書（省略時は全部）。 */
        only: Set<DocumentId>? = null,
        /** 索引（台帳）に載る文書か。既定は、アプリの走査対象と同じ決め方（台帳の文書と、予約領域の文書）。 */
        indexedOf: (DocumentId) -> Boolean = { layout.inLedger(it.value) || layout.isReserved(it.value) },
        /** ファイルがあるか（file 型の値の存在の検査）。null なら存在は見ない。許可の決まりは layout.files。 */
        fileExists: ((String) -> Boolean)? = null,
    ): Map<DocumentId, DocumentValidation> {
        val snapshot = derived.semantic
        val schemaById = schemas.associateBy { it.id }
        val scans = snapshot.scans.associateBy { it.document }
        val integrity by lazy { SemanticGraphIntegrity.issues(snapshot.refs, snapshot.graph, declarations).groupBy { it.document } }
        val validator = DocumentValidator(
            schemas, declarations.relations, declarations.enforcement,
            snapshot.ids, snapshot.refs, snapshot.graph,
            SemanticCheckSession(snapshot.ids, snapshot.refs, snapshot.graph),
            integrity = { integrity },
            rel = { it.value },
            tree = { d -> documents[d]?.let(::rootOf) },
            files = layout.files, fileExists = fileExists,
        )
        val out = LinkedHashMap<DocumentId, DocumentValidation>()
        for ((id, document) in documents.entries.sortedBy { it.key.value }) {
            if (only != null && id !in only) continue
            val root = rootOf(document)
            val scan = scans[id]
            val result = validator.validate(
                document = id,
                schema = document.schemaId?.let(schemaById::get),
                root = root,
                parseError = null,
                documentId = scan?.documentId ?: DocumentMeta.idOf(root, id.value),
                indexed = indexedOf(id),
                // 走査結果が手元にある文書（中身が空でない、索引に載る文書）は、走査し直さない
                scan = scan,
            )
            if (result.issues.isNotEmpty() || result.withheld.isNotEmpty()) out[id] = result
        }
        return out
    }

    /** 文書のルート。中身が空の文書（0バイトのファイル）は、空のマップとして扱う（アプリの読み込みと同じ）。 */
    fun rootOf(document: WorkspaceDocument): YNode = document.document.documents.firstOrNull() ?: YNode.Map()

    /**
     * 索引に載っている文書（意味解析の走査結果を持つ文書）ごとの、文書へ配る指摘（定義体の検査と、コードで実装された検査のうち
     * ワークスペースの他の文書も見るもの）。文書ID 順。指摘も保留も無い文書は含めない。
     */
    fun deliverAll(loaded: LoadedWorkspace): Map<DocumentId, CheckDelivery> {
        val semantic = loaded.derived.semantic
        val schemaById = loaded.schemas.associateBy { it.id }
        val documents = loaded.snapshot.documents
        val checks = WorkspaceChecks(
            loaded.schemas, loaded.declarations.relations, loaded.declarations.enforcement,
            semantic.ids, semantic.refs, semantic.graph,
            SemanticCheckSession(semantic.ids, semantic.refs, semantic.graph),
            rel = { it.value },
            tree = { d -> documents[d]?.document?.documents?.firstOrNull() },
        )
        val out = LinkedHashMap<DocumentId, CheckDelivery>()
        for (scan in semantic.scans.sortedBy { it.document.value }) {
            val document = documents[scan.document] ?: continue
            val root = document.document.documents.firstOrNull() ?: continue
            val delivery = checks.deliver(scan.document, document.schemaId?.let(schemaById::get), root, scan.ids, scan.refs)
            if (delivery.issues.isNotEmpty() || delivery.withheld.isNotEmpty()) out[scan.document] = delivery
        }
        return out
    }
}
