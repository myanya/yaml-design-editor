package dev.toros.yde.app.format

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 入出力の形式 1 つの差し込み口（ADR-0013「窓口の形」）。skill 定義の `format` の値が `id` に対応し、出力ファイルの拡張子で書く形式を選ぶ。
 * 読むときは bytes から表（`Table`）へ、書くときは表から bytes へ。ファイルの場所は中核（`skill/Tables.kt`）が扱い、実装はファイルに触れない。
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
)

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
