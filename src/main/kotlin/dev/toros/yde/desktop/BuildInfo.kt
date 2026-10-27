package dev.toros.yde.desktop

import java.util.Properties

/**
 * ビルド時に生成されるバージョン情報（`build-info.properties`。root の build.gradle.kts の `generateBuildInfo`）。
 * - appVersion: 外部バージョン（利用者に見せる。メジャー.マイナー.パッチ。gradle.properties の appVersion）
 * - buildVersion: 内部バージョン（開発管理用。yyyymmdd.ビルド番号。未コミットの変更を含むビルドは -dirty）
 * - commit: ビルド元のコミット（短縮ハッシュ）
 * 資源が無いとき（IDE から直接実行するなど）は "dev" になる。
 */
object BuildInfo {
    private val props: Properties by lazy {
        Properties().also { p ->
            BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use { p.load(it) }
        }
    }
    val appVersion: String get() = props.getProperty("appVersion", "dev")
    val buildVersion: String get() = props.getProperty("buildVersion", "dev")
    val commit: String get() = props.getProperty("commit", "unknown")

    /** 表示用の1行（例: v0.1.0（build 20260923.1）） */
    val display: String get() = "v$appVersion（build $buildVersion）"
}
