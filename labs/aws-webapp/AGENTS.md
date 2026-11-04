# AWS WEBアプリ設計ラボの編集方針

- 原本は`model/`。1 AWS資源1文書の`resourceType`と`properties`を正にする。図用のcomponent・接続一覧・派生データを原本として増やさない。
- 参照は`{ref: 設計ID, attribute: 展開属性}`。attribute省略はRef。文字列をIDやARNらしさから参照扱いにしない。契約は[GENERATION.md](GENERATION.md)。
- サービスを足すときはスキーマcases、参照先、記法、設定表、生成契約、AWS仕様の検証用抜粋も更新する。任意mapで型を回避しない。
- `model/apps/`はTaskへ渡す実行設定。SG・IAMの許可だけからアプリの接続先を推測しない。
- 図の対象範囲はビューで選ぶ。flowのsourcesはクエリ変換、projectionは表示への変換。設定値を二重に書かない。
- Account ID、image digest等の実値は`model/inputs.yaml`。exampleを実値へ黙って採用しない。秘密本文をYAML・レポートへ書かない。
- 設計目標と実測結果を分ける。AWS上で試していない性能・復旧目標を達成済みにしない。
- `node labs/aws-webapp/scripts/verify.cjs`で正例・反例・射影の一致を検査する。JDK 21、`:cli:installDist`、Webの依存取得済み環境が必要。原本はコピーして検査する。
- 製品の単体テストからlabsを参照しない。画面確認もルートのui-check手順でコピー上に行う。
- 生成入力は完成したCFnではない。Stack依存・属性の束縛・生成器を変更したら生成契約と検証範囲を更新する。
- AWSアイコンと仕様抜粋の出典を保持する。第三者素材へリポジトリのMITライセンスを適用した扱いにしない。
