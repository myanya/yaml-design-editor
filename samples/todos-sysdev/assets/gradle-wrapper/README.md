# Gradle Wrapper の固定資源

Gradle 9.3.0 の `wrapper` タスクを、YDE から独立した空のプロジェクトで実行して作った。
`files.json` に生成対象と SHA-256 を記録し、アプリ生成時に毎回照合する。
JAR 内の `META-INF/LICENSE` と、スクリプトのライセンス表記を保持する。

```text
gradle wrapper --gradle-version 9.3.0 --distribution-type bin --gradle-distribution-sha256-sum 0d585f69da091fc5b2beced877feab55a3064d43b8a1d46aeb07996b0915e0e0
```

[公式の Wrapper 手順](https://docs.gradle.org/9.3.0/userguide/gradle_wrapper.html)に従う。
配布 ZIP の SHA-256 は [公式公開値](https://services.gradle.org/distributions/gradle-9.3.0-bin.zip.sha256)、
JAR の SHA-256 は [公式公開値](https://services.gradle.org/distributions/gradle-9.3.0-wrapper.jar.sha256)と照合した。
JAR は `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`。

版を変える場合は空の作業用プロジェクトで作り直し、設定の形式・レポートの properties・この資源・ハッシュを一緒に更新する。
アプリの通常のビルドはここを読まない。生成時に `app/` へ配置した Wrapper だけで動く。
