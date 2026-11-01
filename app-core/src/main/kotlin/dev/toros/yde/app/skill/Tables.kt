package dev.toros.yde.app.skill

import dev.toros.yde.app.ext.Extensions
import dev.toros.yde.app.format.FormatCodec
import dev.toros.yde.app.format.ReadOptions
import dev.toros.yde.app.format.Table
import dev.toros.yde.app.format.WriteOptions
import dev.toros.yde.yaml.model.YNode
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 表の読み書きの窓口（R5-02 / R5-03）。形式の実装は `FormatCodec`（文字の形式は app.format の組込み・Excel と Word は拡張 format-office）にあり、
 * ここは skill 定義（`ImportDef` / 出力ファイル名）から形式を選び、ファイルと bytes の間を受け持つ。
 * 中核（app-core）は特定の形式のライブラリ（POI など）を持たない。
 */
object Tables {

    // ---------------------------------------------------------------- 形式を選ぶ

    /** skill 定義の `format` の値から読む形式を選ぶ。登録されていない値は誤り（使える形式を添える）。 */
    fun codecFor(id: String): FormatCodec =
        Extensions.format(id) ?: error(
            "format「$id」は使えません（使える形式: ${Extensions.registry.formatIds.joinToString(" / ").ifEmpty { "なし" }}）"
        )

    /** 出力ファイルの拡張子から書く形式を選ぶ。 */
    fun writerFor(fileName: String): FormatCodec =
        Extensions.formatForFile(fileName) ?: error(
            "出力形式を判定できません（${Extensions.registry.writableExtensions.joinToString(" / ") { ".$it" }.ifEmpty { "書ける形式が登録されていません" }}）: $fileName"
        )

    // ---------------------------------------------------------------- 読み込み

    fun read(file: Path, def: ImportDef): Table {
        val codec = codecFor(def.format)
        require(codec.canRead) { "${codec.id} はエクスポート専用の形式です（インポートには使えません）" }
        val options = ReadOptions(
            sheet = def.sheet, headerRow = def.headerRow, jsonPath = def.jsonPath, tableIndex = def.tableIndex,
            encoding = def.encoding, columns = def.columns,
        )
        return try {
            codec.read(Files.readAllBytes(file), options)
        } catch (e: CharacterCodingException) {
            // 合わない文字コードは、化けた表にせず誤りとして知らせる
            error("文字コード ${def.encoding.name()} として読めません: ${file.fileName}（銀行の CSV などは input.encoding: windows-31j を試してください）")
        }
    }

    /** 木のまま読む（input.tree）。`input.path` があればその場所の部分木。 */
    fun readTree(file: Path, def: ImportDef): YNode {
        val codec = codecFor(def.format)
        require(codec.canReadTree) { "${codec.id} は木のままの取り込みに対応していません" }
        return try {
            codec.readTree(Files.readAllBytes(file), ReadOptions(jsonPath = def.jsonPath, encoding = def.encoding))
        } catch (e: CharacterCodingException) {
            error("文字コード ${def.encoding.name()} として読めません: ${file.fileName}")
        }
    }

    // ---------------------------------------------------------------- 書き出し

    /** 出力ファイルの拡張子で形式を選んで bytes にする。sheet は xlsx のシート名（html では題名）。 */
    fun encode(table: Table, fileName: String, sheet: String): ByteArray =
        writerFor(fileName).write(table, WriteOptions(sheet = sheet, title = sheet))

    /** 出力ファイルの拡張子で形式を選んで書く。 */
    fun write(table: Table, file: Path, sheet: String) {
        Files.createDirectories(file.parent ?: file.toAbsolutePath().parent)
        Files.write(file, encode(table, file.fileName.toString(), sheet))
    }

    /** 出力ファイルの拡張子で形式を選んで、木を bytes にする。 */
    fun encodeTree(node: YNode, fileName: String, options: WriteOptions): ByteArray {
        val codec = writerFor(fileName)
        require(codec.canWriteTree) { "${codec.id} は木を書けません: $fileName" }
        return codec.writeTree(node, options)
    }

    /** 書き出し先の既存の木（mode: merge）。 */
    fun decodeTree(bytes: ByteArray, fileName: String): YNode = writerFor(fileName).readTree(bytes, ReadOptions())
}
