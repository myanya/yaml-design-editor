# 設定を原本とする再設計の結果

旧試作では`application.yaml`のcomponent・接続を全体図の原本とし、別の資源一覧と`implements`で結んでいました。
この構造では、ALBの転送先やECSの登録先を変えても図が連動しませんでした。

現在は以下の形へ置き換えています。

1. **1 AWS資源＝1 YAML文書**。CloudFormationのResourceTypeとPropertiesに合わせ、Listener・Rule・TG・Scaling・Policy等も具体化する。
2. **設定内の参照を接続の正にする**。Behavior→Origin、Rule→TG、Service→TG、Task→実行設定等を本ツールのref型で索引化する。
3. **全体図と詳細図は同じ設定の射影**。複数文書flowのsourcesを本体に加え、別の接続モデルと生成済み図データを廃止する。
4. **パラメータ表を原本に直結**。サービス別の表に採用Propertiesの全項目を表示し、全設定フォーム・設計書へ進める。
5. **CFn生成の入力を具体化**。抽象的なkind→型名対応から、native Propertiesと属性付き参照を保持するcontractVersion 2へ移行する。
6. **変更追従を受入条件にする**。転送先を別Serviceへ変え、図・表・生成入力・設計書の全てが変わることを作業コピーで検査する。

運用要件・設計判断・処理シナリオ・AWSアイコンは用途を確認して引き継ぎました。旧`application.yaml`・`communications.yaml`は現在の原本には残していません。
シナリオは業務や運用の順序を表すもので、構成図の接続には使いません。

現在の設計は[DESIGN.md](DESIGN.md)、試し方は[README.md](README.md)、未実装の機能は[CAPABILITIES.md](CAPABILITIES.md)が正です。
AWS上で動作確認済みの配備テンプレートという扱いにはしていません。
