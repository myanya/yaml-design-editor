# Windows ZIP の配布

配布対象は Windows x64。Java ランタイムを含む ZIP とチェックサムを GitHub Releases に添付する。
利用者は ZIP を展開して exe を起動し、更新時は新版を別フォルダへ展開する。
MSI・署名・自動更新・他 OS の配布はこの手順の対象外。

## 作成

リポジトリのルートで、Windows x64 の JDK を使って実行する。
Gradle の起動は JDK 17 以上、アプリのビルドと同梱ランタイムは toolchain の JDK 21 を使う。

```powershell
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
.\gradlew.bat windowsRelease --no-parallel --no-daemon --console=plain
```

公開用は `gradle.properties` の `appVersion` を確認して変更をコミットし、作業ツリーを空にしてから作る。
未コミットの変更や未追跡ファイルがある場合は停止する。コミット情報を取得できない場合も停止する。
設定キャッシュは配布タスクでは使用しない。`--no-parallel` を指定してライセンス生成の共有出力を扱う。

開発中の検証は `-PdistributionPreview=true` を加える。ファイル名に `-preview` が付き、公開用とは別の場所に出力する。
プレビューは公開用の代わりにアップロードしない。コミット後に上記コマンドで作り直す。

出力先は `desktop/build/release/yaml-design-editor-<版>-windows-x64/`。

| ファイル | 用途 |
|---|---|
| `yaml-design-editor-<版>-windows-x64.zip` | Releases に添付するアプリ一式 |
| `SHA256SUMS.txt` | ZIP の SHA-256（Releases に添付） |
| `RELEASE-NOTES.md` | Releases の本文へ転記する説明 |

## 同梱物と検査

- `packageWindowsZip` が exe・app・runtime・起動案内・ビルド情報・TODO サンプルをまとめる。
- サンプルは `samples/todos` の定義とデータを対象パターンで選び、実行ログや個人設定を含めない。
- `BUILD-INFO.txt` に外部版・内部版・コミット・未コミット状態、`RUNTIME-RELEASE.txt` に同梱 JDK の対象版と供給元を記録する。
- `verifyWindowsZip` が ZIP を新しい場所へ展開し、元の配布イメージとの全ファイルの一致、追加資源、必須ファイル、版を確認する。
- 展開後のランタイムでも HTTP クライアント生成・Office 往復・グラフ配置を検査する。実 API に接続する試験ではない。
- `windowsRelease` は全モジュールのテストと ZIP 検査が成功してから、チェックサムと説明を出力する。
- 検証用の写しは `build/zv/` と `build/distribution-smoke/` に残る（Windows のパス長を考慮して展開先を短くする）。

通常の配布には `createDistributable` 系の成果物を使う。ProGuard を使う `createReleaseDistributable` 系は、この検査経路の対象外。
ZIP のエントリ順は固定し、更新日時は元ファイルの値を保持する（すべてがビルド日時になるわけではない）。
JDK・ネイティブ依存・ビルド環境を含むバイト単位の再現性は保証しない。

## 公開前の実機確認

Java 未導入の Windows x64 の別 PC または VM で、配布するものと同じ ZIP を使う。
検証機の Windows の版、ZIP の SHA-256、結果をリリース担当者の記録に残す。

1. 空白・日本語を含むパスへ「すべて展開」し、exe を起動する。
2. `examples/todos` をアプリの外へコピーし、「ワークスペースを開く…」でそのコピーを選ぶ。
3. `todos/todo-list.yaml` のタイトルを編集・保存し、アプリを再起動して内容が残ることを確認する。
   あわせて「新しいワークスペースを作る…」でテンプレート「課題管理」を別フォルダへ展開し、課題のフォームが開くことを確認する（同梱のテンプレートが資源に入っている確認）。
4. フォルダ選択、台帳の TODO 管理ビュー、日本語表示、内蔵ヘルプを確認する。
5. 利用する入出力機能や AI 接続も確認する（AI は利用者自身の接続設定を使う）。
6. 別フォルダへ展開したアプリでも同じワークスペースを開き、手動更新でデータが失われないことを確認する。

同梱の第三者表示とソース取得案内を確認し、同梱 JDK・ネイティブ依存の対象版に必要な再配布・ソース提供の条件も確認する。
自動検査の成功だけを、別 PC・全 Windows 版での動作確認やライセンス条件の最終確認の代わりにしない。

## GitHub Releases

1. 実機確認した ZIP の `BUILD-INFO.txt` と公開対象のコミットを照合する。
2. そのコミットに `v<版>` タグを付け、公開先へ push する。
3. Releases の下書きを作成し、`RELEASE-NOTES.md` の本文と ZIP・`SHA256SUMS.txt` を登録する。
4. ダウンロードした ZIP のチェックサムを照合し、対応するソース・起動手順・既知の制約への案内を確認して公開する。

リポジトリ本体を private から public に変える操作は別の判断になる。公開範囲と履歴の確認を済ませた公開先を使う。
公開済みの同じ版の ZIP を差し替えて内容を変えず、修正は新しい版として配布する。

参考: [Compose の配布タスク](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)、[GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases)。
