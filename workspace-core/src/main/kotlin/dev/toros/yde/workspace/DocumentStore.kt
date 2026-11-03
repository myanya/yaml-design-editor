package dev.toros.yde.workspace

import dev.toros.yde.yaml.model.DocumentId
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 文書の永続化ポート（3層コア移行 Phase 7・WS-001。計画書§4.3）。
 * DocumentId の値は「ワークスペース相対パス（区切りは '/'）」とする。
 * coreの書込はこのポートへ集約する。セッションは書込直前に生テキストを比較する。
 * 比較と書込の間を排他する OS レベルのロックは持たない。
 * 一時ファイル経由の atomic 書込は Phase 8（保存の安全性）で加える。
 */

/** ストア内の文書1件。 */
data class StoredDocument(val id: DocumentId)

/** 読み出した内容。 */
data class StoredContent(val text: String)

/** 一回のディスク走査の結果。時刻は本文の一致判定の代わりには使わない。 */
class FileSystemListing(val yamlTimes: Map<DocumentId, Long>, val markdown: List<DocumentId>)

/** 読んだ本文と、その読込に用いた一覧。保存・外部変更の基準もこの本文から作る。 */
class FileSystemRead(val texts: Map<DocumentId, String>, val listing: FileSystemListing)

/** 保存基準と現在のストア内容が異なる。読み取り失敗とは区別して画面へ知らせる。 */
class StoreConflictException(val documentId: DocumentId) : IllegalStateException(
    "外部で変更されています: ${documentId.value}（ディスク版を読み込むか、編集内容での上書きを許可してください）",
)

fun DocumentStore.checkUnchanged(id: DocumentId, expectedText: String?) {
    if (read(id)?.text != expectedText) throw StoreConflictException(id)
}

interface DocumentStore {
    /** 可視のYAML文書（*.yaml / *.yml、隠し領域は .yde のみ）を列挙する（相対パス順。台帳・ゾーンは呼び出し側で絞る）。 */
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

    override fun list(): List<StoredDocument> {
        return scan(WorkspacePaths.ReadBoundary(root), includeMarkdown = false).yamlTimes.keys.map(::StoredDocument)
    }

    /** 全読込の候補。本文の直前にも対象の実パスを確認し、一覧の検査結果を使い回さない。 */
    fun capture(): FileSystemRead {
        val boundary = WorkspacePaths.ReadBoundary(root)
        val listing = scan(boundary, includeMarkdown = true)
        val texts = linkedMapOf<DocumentId, String>()
        for (id in listing.yamlTimes.keys) {
            readFile(boundary.resolve(id.value))?.let { texts[id] = it.text }
        }
        return FileSystemRead(texts, FileSystemListing(listing.yamlTimes.filterKeys { it in texts }, listing.markdown))
    }

    private fun scan(boundary: WorkspacePaths.ReadBoundary, includeMarkdown: Boolean): FileSystemListing {
        val yaml = linkedMapOf<DocumentId, Long>()
        val markdown = mutableListOf<DocumentId>()
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                if (dir != root) {
                    val relative = root.relativize(dir).toString().replace('\\', '/')
                    if (dir.fileName.toString().startsWith(".") && relative != ".yde") return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    if (runCatching { boundary.resolve(relative) }.isFailure) return java.nio.file.FileVisitResult.SKIP_SUBTREE
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                val name = file.fileName.toString()
                val isYaml = name.endsWith(".yaml") || name.endsWith(".yml")
                val isMarkdown = includeMarkdown && name.endsWith(".md", ignoreCase = true)
                if (!name.startsWith(".") && (isYaml || isMarkdown)) {
                    val id = DocumentId(root.relativize(file).toString().replace('\\', '/'))
                    if (runCatching { boundary.resolve(id.value) }.isSuccess) {
                        if (isYaml) yaml[id] = if (attrs.isSymbolicLink) {
                            runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
                        } else attrs.lastModifiedTime().toMillis()
                        // 構成領域の Markdown はエディタの一覧には出さない。
                        else if (id.value.split('/').none { it.startsWith(".") } &&
                            (attrs.isRegularFile || Files.isRegularFile(file))) markdown += id
                    }
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException) = java.nio.file.FileVisitResult.CONTINUE
        })
        return FileSystemListing(yaml.toSortedMap(compareBy { it.value }), markdown.sortedBy { it.value })
    }

    override fun read(id: DocumentId): StoredContent? {
        return readFile(resolve(id))
    }

    private fun readFile(file: Path): StoredContent? {
        return try { StoredContent(Files.readString(file)) }
        catch (_: java.nio.file.NoSuchFileException) { null }
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
