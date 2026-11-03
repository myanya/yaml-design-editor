# YAML Design Editor v@VERSION@

@MODE@。スキーマに沿った YAML のフォーム編集、検証、参照・ビュー、任意の AI 作成支援を提供します。

## v0.2.0 からの主な変更

- フローとグラフレンズの図の記法を YAML で定義できるようにしました。人物カード、クラスの属性・操作、辺の端点記号・多重度を使い、PNG に書き出せます。人物相関図・クラス図のサンプルも追加しました。
- 図のノードをドラッグして配置を調整できるようにしました。現在位置と手動固定をワークスペースへ保存し、「再配置」で未固定ノードだけを並べ直します。移動後の配線とラベルの追従も改善しました。
- グリッド吸着を追加しました。間隔は 8・16・32 dip から選べ、設定も図ごとに保存します。ON/OFF や間隔の変更だけでノードが動くことはありません。
- 同じ PC から API・MCP・業務 Web で接続できるようにしました。接続者ごとに閲覧範囲と操作権限を設定でき、CLI の `connect` も使えます。接続機能の初期値はすべて OFF です。
- タスクトレイへの常駐と、画面を表示しない起動を追加しました。同じワークスペースの二重起動を防ぎ、読込処理も高速化しました。
- JSON の木の取り込み・書き出し、並んだ配列を同じ番号で組み立てる `$zip`、文書内の配列を描くチャートを追加しました。
- 畳んだ見出しや要約で選択肢・参照の表示名を出すようにし、小さな画像の表示領域も整えました。

詳しくは[変更履歴](https://github.com/myanya/yaml-design-editor/blob/v@VERSION@/docs/archive/changelog.md)と
[v0.2.0 からの差分](https://github.com/myanya/yaml-design-editor/compare/v0.2.0...v@VERSION@)を参照してください。

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
