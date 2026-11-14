# AGENTS.md

本ワークスペースは、YDE(Yaml Design Editor)用のサンプルプロジェクトです。
`.tooldocs`がある場合は、そのマニュアルに沿ってYAMLファイルを作成・編集してください。
リポジトリ内で`.tooldocs`がない場合は、ルートの`docs/manual/`を参照してください。

単独配布時はツール本体を含みません。リポジトリ内での作業はルートのAGENTS.mdに従い、同梱CLIで検証できます。
サンプル専用のテストコードや実行環境は追加しません。YDEの検証とREADMEの試用手順で確認してください。

TODO管理のモデルは`.yde/`の型・制約・操作・ビューと状態の流れ（`todo-behavior.yaml`）で定義し、具体的なデータは`todos/`に置きます。
業務上の意味は定義の`description`とREADMEで説明します。変更時は`todos-sysdev-tablemodel`の設計への影響も確認してください。
参照には関係の名前（`relation`）を付け、`relations.yaml`に宣言します。
TODOの状態は状態の流れ（Lifecycle）を正にします。状態・移り方は`todo-behavior.yaml`にだけ書き、操作には`lifecycle: todo-lifecycle`を書きます（状態の条件・状態の値・履歴の行を操作に書かない）。ダッシュボードの業務の流れも`flow: {lifecycle}`で作るので、段や線を書きません。
状態を変える操作を足す・直すときは、`todo-behavior.yaml`の移り方、状態の履歴の操作の名前（`common.yaml`の「TODOの操作」）、`scenarios/lifecycle.yaml`を合わせ、`behavior-audit`と`behavior-scenarios`で確かめてください。

GitHub上で公開されるため、個人情報などのセンシティブな情報は含めないでください。

本ワークスペースの内容の詳細は、`README.md`を参照してください。


