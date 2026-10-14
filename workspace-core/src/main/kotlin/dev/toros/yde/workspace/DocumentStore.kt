package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * 文書の永続化ポート（3層コア移行 Phase 7・WS-001。計画書§4.3）。
 * DocumentId の値は「ワークスペース相対パス（区切りは '/'）」とする。
 * coreの書込はこのポートへ集約する（ADR-0005）。楽観ロックは持たない（ADR-0007: 最終書込み優先。
 * 外部変更の検出・通知は上位の watch が担う）。
 * 一時ファイル経由の atomic 書込は Phase 8（保存の安全性）で加える。
 */

/** ストア内の文書1件。 */
data class StoredDocument(val id: DocumentId)

/** 読み出した内容。 */
data class StoredContent(val text: String)

interface DocumentStore {
    /** 全YAML文書（*.yaml / *.yml）を列挙する（相対パス順。台帳・ゾーンの絞り込みは呼び出し側の責務）。 */
    fun list(): List<StoredDocument>

    /** 文書を読む。存在しなければ null。 */
    fun read(id: DocumentId): StoredContent?

    /** 文書を書く（無ければ作成。親ディレクトリは自動作成）。 */
    fun write(id: DocumentId, text: String)

    /** 文書を消す。存在しなければエラー。 */
    fun delete(id: DocumentId)

    /** 文書を移動する。移動元がない・移動先が既に存在する場合はエラー。 */
    fun move(from: DocumentId, to: DocumentId)

    /**
     * 任意のファイル（YAML 以外も）があるか（file 型の値の存在の検査）。相対パスはルートから、絶対パスはそのまま。
     * 既定は「分からない」として false（メモリ実装などは必要なら上書きする）。
     */
    fun exists(path: String): Boolean = false
}

/** テスト・単文書利用向けのメモリ実装。 */
class InMemoryDocumentStore(
    initial: Map<String, String> = emptyMap(),
) : DocumentStore {

    private val texts = sortedMapOf<String, String>()

    /** 書込回数（apply が書かないことのテスト用。計画書 Phase 8 必須テスト）。 */
    var writeCount: Int = 0
        private set

    init {
        texts.putAll(initial)
    }

    override fun list(): List<StoredDocument> =
        texts.keys.filter { it.endsWith(".yaml") || it.endsWith(".yml") }.map { StoredDocument(DocumentId(it)) }

    override fun read(id: DocumentId): StoredContent? = texts[id.value]?.let(::StoredContent)

    override fun write(id: DocumentId, text: String) {
        texts[id.value] = text
        writeCount++
    }

    override fun delete(id: DocumentId) {
        require(texts.remove(id.value) != null) { "文書が存在しません: ${id.value}" }
        writeCount++
    }

    override fun move(from: DocumentId, to: DocumentId) {
        val text = requireNotNull(texts[from.value]) { "移動元が存在しません: ${from.value}" }
        require(to.value !in texts) { "移動先が既に存在します: ${to.value}" }
        texts.remove(from.value)
        texts[to.value] = text
        writeCount++
    }

    /** メモリ上のキー（YAML 以外も置ける）があれば存在する扱い（file 型の検査のテスト用）。 */
    override fun exists(path: String): Boolean = texts.containsKey(path.replace('\\', '/'))
}

/**
 * ファイルシステム実装。root（ワークスペースルート）配下だけを扱い、
 * ルート外へ出るID（絶対パス・`..` 上抜け）は拒否する（計画書 Phase 7 作業3・Phase 8 保存の安全性）。
 */
class FileSystemDocumentStore(private val root: Path) : DocumentStore {

    /** IDをルート配下の実パスへ解決する（正規化してルート外を拒否）。 */
    private fun resolve(id: DocumentId): Path {
        return WorkspacePaths.resolve(root, id.value)
    }

    override fun list(): List<StoredDocument> =
        Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() }
                .filter { val n = it.fileName.toString(); n.endsWith(".yaml") || n.endsWith(".yml") }
                .filter { runCatching { resolve(DocumentId(root.relativize(it).toString())) }.isSuccess }
                .map { StoredDocument(DocumentId(root.relativize(it).toString().replace('\\', '/'))) }
                .sortedBy { it.id.value }
                .toList()
        }

    override fun read(id: DocumentId): StoredContent? {
        val file = resolve(id)
        return if (Files.isRegularFile(file)) StoredContent(Files.readString(file)) else null
    }

    /** 相対パスはルートから、絶対パスはそのまま（file 型の値はワークスペースの外も指せるため、ここは拒否しない）。 */
    override fun exists(path: String): Boolean = runCatching {
        val p = Path.of(path.replace('\\', '/'))
        Files.isRegularFile(if (p.isAbsolute) p else root.resolve(p).normalize())
    }.getOrDefault(false)

    override fun write(id: DocumentId, text: String) {
        val file = resolve(id)
        Files.createDirectories(file.parent)
        // 一時ファイルへ書いてから move する（可能なら atomic。計画書 Phase 8 保存の安全性）
        val tmp = Files.createTempFile(file.parent, ".${file.fileName}.", ".tmp")
        try {
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun delete(id: DocumentId) {
        val file = resolve(id)
        require(Files.isRegularFile(file)) { "文書が存在しません: ${id.value}" }
        Files.delete(file)
    }

    override fun move(from: DocumentId, to: DocumentId) {
        val src = resolve(from)
        val dst = resolve(to)
        require(Files.isRegularFile(src)) { "移動元が存在しません: ${from.value}" }
        require(!Files.exists(dst)) { "移動先が既に存在します: ${to.value}" }
        Files.createDirectories(dst.parent)
        Files.move(src, dst)
    }
}
