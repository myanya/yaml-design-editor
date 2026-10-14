# YAML Design Editor v@VERSION@

@MODE@。スキーマに沿った YAML のフォーム編集、検証、参照・ビュー、任意の AI 作成支援を提供します。

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
AI 機能には利用者自身の接続設定が必要です。通常の編集に API キーは不要です。
macOS / Linux、Windows ARM64、MSI インストーラー、自動更新はこの配布の対象外です。

## ビルド情報

- 外部バージョン: `@VERSION@`
- 内部バージョン: `@BUILD@`
- コミット: `@COMMIT@`
- 本体は MIT License。依存ライブラリ・フォント・Java にはそれぞれのライセンスが適用されます。
- 同梱の `BUILD-INFO.txt`、`RUNTIME-RELEASE.txt`、`app/resources/licenses/`、`runtime/legal/` も参照してください。
