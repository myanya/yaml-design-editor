# todos-generated

詳細設計の確定設定から生成した、独立した Spring Boot プロジェクトです。
現在は物理テーブルの Java 行モデル、Mapper と XML、DB の初期作成、状態型と業務例外を生成します。業務処理の操作本体・画面は後続の生成で追加します。

## ビルドと起動

Java 21 を用意し、このフォルダで実行します。依存関係の初回取得にはネットワークが必要です。

```powershell
.\gradlew.bat test bootJar
java -jar build/libs/todos-generated.jar
```

macOS・Linux では `sh ./gradlew test bootJar` を使います。
待受ポートは 8085、DB は作業ディレクトリ内の `data/todos` に保存します。
終了は起動した端末で Ctrl+C。別のポート・DB は Spring Boot の起動引数で指定できます。
空の DB には `schema.sql` を適用します。既存 DB に必要なテーブルがあれば、そのまま使います。
必要なテーブルが欠けていれば起動を止めます。既存テーブルの列や制約の変更・移行は未対応です。設計変更の検証には別の DB 名を指定してください。

このフォルダだけを別の場所へコピーしても同じ手順でビルドできます。YDE や親の Gradle 設定は不要です。
生成物を直す場合は設計の入力か生成規則へ変更を戻して再生成します。
