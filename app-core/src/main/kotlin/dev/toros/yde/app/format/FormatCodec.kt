package dev.toros.yde.app.format

import dev.toros.yde.yaml.model.YNode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 入出力の形式 1 つの差し込み口（ADR-0013「窓口の形」）。skill 定義の `format` の値が `id` に対応し、出力ファイルの拡張子で書く形式を選ぶ。
 * 読むときは bytes から表（`Table`）か木（`YNode`）へ、書くときは表か木から bytes へ。ファイルの場所は中核（`skill/Tables.kt`）が扱い、実装はファイルに触れない。
 * 木の読み書きは入れ子を持つ形式（json）だけが持ち、表の形式は持たない（`canReadTree` / `canWriteTree` が false）。
 * 文字の形式（csv・json・markdown・html。TextFormats.kt）は app-core の組込みで登録簿が最初から持つ。Excel・Word は POI を使うので
 * 拡張 format-office にあり、入口（desktop・cli）が `Extensions.install` で登録する（ADR-0015）。
 */
interface FormatCodec {
    /** skill 定義の `format` の値（xlsx / csv / json / markdown / docx / html …）。 */
    val id: String
    /** この形式で書くファイルの拡張子（小文字・ドット無し。先頭が代表）。読むだけの形式は空でよい。 */
    val extensions: List<String>
    val canRead: Boolean get() = true
    val canWrite: Boolean get() = true
    fun read(bytes: ByteArray, options: ReadOptions): Table
    fun write(table: Table, options: WriteOptions): ByteArray

    /** 木のまま読めるか（入れ子・数の型・null・キーの順を保つ）。 */
    val canReadTree: Boolean get() = false
    /** 木を書けるか。 */
    val canWriteTree: Boolean get() = false
    /** 木のまま読む（`options.jsonPath` があれば、その場所の部分木）。 */
    fun readTree(bytes: ByteArray, options: ReadOptions): YNode = error("$id は木のままの取り込みに対応していません")
    /** 木を書く（書式は `options.style` と `options.finalNewline`）。 */
    fun writeTree(node: YNode, options: WriteOptions): ByteArray = error("$id は木を組み立てる書き出しに対応していません")
}

/** 読むときの指定（skill の import 定義から。形式が使うものだけを見る）。 */
class ReadOptions(
    val sheet: String? = null,               // xlsx: シート名（省略時は先頭）
    val headerRow: Int = 1,                  // xlsx: ヘッダ行（1 始まり）
    val jsonPath: String? = null,            // json: 行配列へのドット区切りパス（省略時はルート配列）
    val tableIndex: Int = 1,                 // docx: 文書内の何番目の表か（1 始まり）
    val encoding: Charset = Charsets.UTF_8,  // 文字の形式の文字コード（例 windows-31j）
    val columns: List<String> = emptyList(), // csv: 見出しの行が無いとき、列の名前の並び（1 行目からデータ）
)

/** 書くときの指定（skill の export 定義から）。 */
class WriteOptions(
    val sheet: String = "Sheet1",   // xlsx: シート名
    val title: String = sheet,      // html: 題名
    val style: TreeStyle = TreeStyle.PRETTY,  // 木の書き出しの書式
    val finalNewline: Boolean = true,         // 木の書き出しで、末尾に改行を付けるか
)

/** 木の書き出しの書式。 */
enum class TreeStyle(val id: String) {
    /** 字下げ 2 つで入れ子を 1 行ずつ（JavaScript の `JSON.stringify(v, null, 2)` と同じ）。 */
    PRETTY("pretty"),
    /** 空白を入れず 1 行（`JSON.stringify(v)` と同じ）。 */
    COMPACT("compact"),
    /** 一番外の配列の要素（object なら項目）を 1 行に 1 つ。要素の中は 1 行（要素ごとに行の差分を見たいデータの書き方）。 */
    LINES("lines");

    companion object {
        fun parse(raw: String): TreeStyle =
            entries.firstOrNull { it.id == raw } ?: error("style は ${entries.joinToString(" / ") { it.id }} のいずれかです: $raw")
    }
}

/**
 * 文字の形式が bytes を文字へ解読する決まり: 文字コードに合わない bytes は、化けた表にせず `CharacterCodingException` で知らせる
 * （中核がファイル名を添えて利用者向けの文に直す）。
 */
@Throws(CharacterCodingException::class)
fun ByteArray.decodeStrict(charset: Charset): String =
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
