// root はビルド全体の設定だけを持ち、ソースを置かない（Gradle の推奨「Do Not Put Source Files in the Root Project」）。
// モジュールの一覧は settings.gradle.kts。アプリは desktop/、リポジトリ全体のテストは repo-tests/、共通の Gradle 設定は buildSrc/
