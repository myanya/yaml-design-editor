# 図の記法のサンプル

YAMLの原本を、人物カード・クラスの区画・UMLの辺で表示します。
フォルダをコピーしてワークスペースとして開いてください。

1. 台帳のグラフレンズで「記法: 標準」を押し、「人物相関図」を選びます。型をpersonに絞ると、画像と下段の名前、画像未設定の代替アイコンを比べられます。
2. 「クラス図」を選び、型をclassに切り替えます。Orderからの白三角、破線の白三角、塗りつぶし菱形と多重度を確認できます。属性・操作の文字を押すと原本の項目へ移動します。
3. レンズの「型」の段で「スキーマの属性」を選ぶと、データを読むことなくスキーマの直下の属性を表示します。これは設計したプログラムのクラスとは別の図です。
4. 台帳の「フロー」で「人物カードのフロー」または「クラス図のフロー」を選びます。人物のstatusをawayにした蓮だけ、枠と「留守」が条件付きで変わります。
5. 図の「PNG に保存」で、画像・区画・端点記号を含む出力を確認します。枠を押した詳細欄の編集や、原本の変更は通常の保存・Undoの対象です。

CLIでも記法と射影を読めます。リポジトリのルートで実行します。

```text
./gradlew runCli -q --args="view samples/diagrams"
./gradlew runCli -q --args="view samples/diagrams uml-class"
./gradlew runCli -q --args="view samples/diagrams class-flow"
./gradlew runCli -q --args="schema-graph samples/diagrams"
```

`.yde/views/` が適用指定と独自記法、`.yde/schemas/` が業務の構造です。
`data/` が原本で、`assets/` のSVGはこのサンプル用の単純な図形です。
多重度は表示用の参照属性です。検証上の多重度が必要なら関係レジストリで別に制約を宣言します。
詳しい構文は[図の記法](../../docs/manual/diagram-notations.md)を参照してください。
