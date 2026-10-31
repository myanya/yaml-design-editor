# YAML Design Editor v@VERSION@

@MODE@。スキーマに沿った YAML のフォーム編集、検証、参照・ビュー、任意の AI 作成支援を提供します。

## v0.1.0 からの主な変更

- 業務ダッシュボードを追加しました。件数・一覧・詳細・業務操作をまとめ、基準日や共有入力で絞り込めます。
- 棒・折れ線・円・面・散布図などのチャート、月別など値の式を軸にしたクロス集計、Markdown などへのレポート出力を追加しました。
- 新規ワークスペースの作成ウィザードとテンプレートを追加しました。「課題管理」や「自分の項目で」から始められます。
- 文書ビューに同じ ID の別の側面と、値を受け取って絞り込む表を埋め込めるようにしました。クエリ行の並び順も指定できます。
- ソース編集に検索・補完・検証指摘・折り畳み・参照先への移動を追加し、フォームとの位置同期と下書きの保持を改善しました。
- フォームとスキーマ設計の構成ツリー、選択一覧、表の列幅、フォーム／ビュー／ソース／差分の切替を整えました。
- Undo/Redo、未保存の編集を保つ再読込、外部変更との競合処理を改善しました。ファイルの移動・改名・削除と参照の追随も同じ履歴で扱えます。
- CLI の全命令で `--format yaml|json|text` を選べるようにしました。注文管理とシステム設計のサンプルもソースに追加しています。

詳しくは[変更履歴](https://github.com/myanya/yaml-design-editor/blob/v@VERSION@/docs/archive/changelog.md)と
[v0.1.0 からの差分](https://github.com/myanya/yaml-design-editor/compare/v0.1.0...v@VERSION@)を参照してください。

## ダウンロードと起動

- Windows x64 用: `@ZIP@`
- Java のインストールは不要です。ZIP をすべて展開し、`yaml-design-editor.exe` を起動してください。
- `examples/todos` を作業用フォルダへコピーして開くと、編集・保存を試せます。
- 更新はアプリ終了後に新版を別フォルダへ展開します。ワークスペースはアプリフォルダ外に保管してください。
- ZIP は未署名です。Windows の設定や評判情報により警告が出る場合があります。

添付の `SHA256SUMS.txt` と、ダウンロード後に PowerShell で計算した値を照合できます。

```powershell
Get-FileHash -Algorithm SHA256 '.\@ZIP@'
```

## 利用上の前提

内容を確認したワークスペースを使ってください。既知の制約はアプリ内ヘルプに記載しています。
更新前にワークスペースをバックアップし、コピーで編集・保存を試してください。
AI 機能には利用者自身の接続設定が必要です。通常の編集に API キーは不要です。
macOS / Linux、Windows ARM64、MSI インストーラー、自動更新はこの配布の対象外です。

[利用者マニュアル](https://github.com/myanya/yaml-design-editor/blob/v@VERSION@/docs/manual/README.md)・
[既知の制約](https://github.com/myanya/yaml-design-editor/blob/v@VERSION@/docs/manual/limits.md)・
[対応するソース](https://github.com/myanya/yaml-design-editor/tree/v@VERSION@)も参照できます。

## ビルド情報

- 外部バージョン: `@VERSION@`
- 内部バージョン: `@BUILD@`
- コミット: `@COMMIT@`
- 本体は MIT License。依存ライブラリ・フォント・Java にはそれぞれのライセンスが適用されます。
- 同梱の `BUILD-INFO.txt`、`RUNTIME-RELEASE.txt`、`app/resources/licenses/`、`runtime/legal/` も参照してください。
