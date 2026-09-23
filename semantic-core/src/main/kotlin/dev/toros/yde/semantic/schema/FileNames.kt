package dev.toros.yde.semantic.schema

/**
 * ファイル名の1区切りに使える形への直し方（P31・判断点 E-05）。新しい文書のファイル名の穴に入った値（DocumentScaffolds）と、
 * skill の取り込み先のファイル名（Importer）が共有する1実装。区切り文字（`/` `\`）・Windows で使えない記号・制御文字を `-` に直す。
 * ひな型に直接書いた `/` は、ディレクトリの区切りとしてそのまま残る（直すのは、穴に入った値だけ）。
 */
object FileNames {

    private const val FORBIDDEN = "\\/:*?\"<>|"

    /** 値をファイル名の1区切りに使える形に直す（前後の空白は落とす）。 */
    fun safeToken(value: String): String =
        value.trim().map { c -> if (c in FORBIDDEN || c.isISOControl()) '-' else c }.joinToString("")
}
