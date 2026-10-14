# ヘッドレス CLI（エージェント向け基盤）


GUIを起動せずに、ワークスペースの探索・検索・部分変更・検証を実行できます。出力は機械可読なYAML（UTF-8）で、AIエージェントや自動化スクリプトからの利用を想定しています。

`kind: flow`のモデル・原本座標・診断を取得する例は [フロービュー](flow-views.md)を参照してください。

```bash
./gradlew runCli -q --args="catalog sample-workspace"                     # 文書カタログ（_document要約・スキーマ・ID数）
./gradlew runCli -q --args="outline sample-workspace path/to/doc.yaml"    # 骨子（キー構造・配列件数。長文は文字数表示）
./gradlew runCli -q --args="read sample-workspace path/to/doc.yaml items[0]"  # 指定パス配下の全文
./gradlew runCli -q --args="bundle sample-workspace path/to/doc.yaml --budget 4000"  # 対象＋参照・被参照の文脈バンドル
./gradlew runCli -q --args="search sample-workspace --text ログイン --schema requirement"  # 本文検索
./gradlew runCli -q --args="search sample-workspace --ref-to REQ-001"     # 参照検索（--unresolved で未解決のみ）
./gradlew runCli -q --args="traverse my-ws USC-011-108 --steps '[{emit: summary}, {follow: out, via: actor, emit: full}]'"  # グラフの経路トラバース（レシピ graph 項目の試作台）
./gradlew runCli -q --args="query my-ws --spec '{match: [{node: {entity: screen, bind: scr}}, {edge: {relation: uses}}, {node: {entity: usecase, bind: uc}}]}'"  # グラフパターン照合（束縛の表。ビューのクエリ行・foreach source: query・constraints の試作台。spec はファイル指定も可）
./gradlew runCli -q --args="view sample-workspace screen-catalog"         # ビューの解決（引数なしで一覧。セルに原本座標つき）
./gradlew runCli -q --args="view my-ws actor-document 00-requirements/01-actors.yaml"  # 文書ビューの解決（適用対象1件なら file 省略可）
./gradlew runCli -q --args="validate sample-workspace"                    # 全文書の検証（カバレッジ含む。判定を保留した検査があれば withheld に内訳と件数。--sources で指摘の出どころ＝どの検査の・どの要素についての指摘か。構造の検証・参照の解決などの指摘にも付く）
./gradlew runCli -q --args="transform test-fixtures/workspaces/rename-target test-fixtures/transforms/req-list.yaml --origins"   # YAML から YAML への変換（試作台）: 定義（from＝クエリで選ぶ／build＝出力のひな型／rules＝入れ子に当てる規則）を評価して、組み立てた YAML と出どころを返す。出力は保存しない
./gradlew runCli -q --args="readiness sample-workspace --pending"         # 準備完了レポート（エンティティ単位にエラーを帰属。--pending で未完了のみ。判定を保留した検査＝データの不備で確かめられなかった検査の件数と内訳も出る）
./gradlew runCli -q --args="bench --modules 2,4,8,16"                    # 規模の計測（合成ワークスペースを作り、開く・全体の検証・準備完了レポートの時間と、規模に対する伸び方を出す。--keep <dir> で作ったものを残す）
./gradlew runCli -q --args="checks sample-workspace --definitions"        # 展開後の検査の表示（語彙・定義の使用が、どんな基本の検査へ展開されたか。--definitions で定義と語彙の一覧も）
./gradlew runCli -q --args="hash sample-workspace path/to/doc.yaml"       # 変更セットの base に使う内容ハッシュ
./gradlew runCli -q --args="apply sample-workspace changeset.yaml --dry-run"  # 変更セットの適用（ドライラン。適用のときは保存時に自動で入る値＝更新日・版も入る）
./gradlew runCli -q --args="action labs/sysdev-template"                     # 操作の一覧（スキーマに定義したアクションと型の操作。manual/actions.md）
./gradlew runCli -q --args="action labs/sysdev-template add-screen --param id=SCR-X --param name=新画面 --param derived_from=[UA-003]"  # 操作の計画（変更セットと適用後の検証。--apply で書く。検証エラーが残れば書かず --allow-errors で書く。--on は受け手）
./gradlew runCli -q --args="entity my-ws show SCR-001"                    # エンティティ全体像（アスペクト合成・関係・被参照。エンティティページ・read_entity と同じ合成）

# 変更系リファクタリング（エンティティ改名・統合・削除／ファイル移動／スキーマ・リファクタリング）は
# 新CLI（cli モジュール）へ移動しました（--apply なしは計画のプレビュー出力）:
./gradlew :cli:run -q --args="workspace refactor my-ws rename-entity SCR-001 SCR-100 --apply"
./gradlew :cli:run -q --args="workspace refactor my-ws move-document specs/a.yaml archive/a.yaml --apply"
./gradlew :cli:run -q --args="workspace refactor my-ws rename-field task-schema details.priority urgency"
                                                                          # 他に merge-entity / delete-entity / move-field / align-order / rename-enum-value /
                                                                          # delete-field / require-field / convert-type / rename-definition / extract-definition /
                                                                          # inline-definition / rename-relation / rename-schema-id
./gradlew runCli -q --args="activities sample-workspace"                  # アクティビティ／エージェント定義の一覧
./gradlew runCli -q --args="agent sample-workspace review-concepts"       # アクティビティ実行（--apply で即適用、--foreach で対象列挙の一括実行）
./gradlew runCli -q --args="watch sample-workspace --once"                # 自動実行（トリガ判定→キュー消化を1回）
./gradlew runCli -q --args="revert sample-workspace _runs/xxx.yaml"       # 自動適用の巻き戻し
./gradlew runCli -q --args="metrics sample-workspace"                     # 実行記録の集計（提出率・採用率・コスト）
./gradlew runCli -q --args="eval sample-workspace review-eval"            # 評価セットの実行（引数なしで一覧）
./gradlew runCli -q --args="skills sample-workspace"                      # skill定義の一覧と検証
./gradlew runCli -q --args="import my-ws import-issues ledger.xlsx --apply"  # 表形式の取り込み（既定はプレビュー）
./gradlew runCli -q --args="export sample-workspace export-actors"        # 派生成果物の再生成
./gradlew runCli -q --args="run-skill my-ws normalize --yes"              # コード実行skill（--yes なしは内容表示のみ）
./gradlew runCli -q --args="init new-ws --template ledger"                # テンプレートで初期化
./gradlew runCli -q --args="infer-schema my-ws 'issues/**/*.yaml' --apply"  # 既存文書からスキーマ推測（--apply なしはプレビュー）
./gradlew runCli -q --args="infer-relations my-ws --apply"                # 参照と保有（入れ子で書かれた要素）の実績から関係レジストリのドラフト生成（[schema.md](schema.md)）
./gradlew :cli:run -q --args="workspace validate my-ws"                    # 新CLI の検証（スキーマの定義の点検・構造・意味・定義体の検査・エンティティ型の点検・テンプレートの健全性。アプリと同じ実装で評価する。検査のエラーは終了コード 3。--format yaml / json で機械可読）
./gradlew :cli:run -q --args="workspace apply my-ws changeset.yaml --dry-run --require-valid"  # 新CLI の変更セットの適用（apply と同じ部品。--dry-run は検証だけ。終了コード 0 / 2＝競合など / 3＝検証のエラーが残る）
./gradlew :cli:run -q --args="workspace bench my-ws --iterations 3"       # 意味解析の性能計測（新CLI。全再構築 vs 増分更新）
./gradlew runCli -q --args="migrate my-ws --ledger doc"                   # レガシー構成 → .yde 構成へ移行（本書 [README.md](README.md)「ワークスペースの構成」）
```

変更セット（changeset.yaml）は複数ファイルへの部分パッチの束です。`base` を指定すると、作成時点からディスクが変わっていた場合に適用を中止します（競合検出）。
事前の競合判定・操作に失敗すると全ファイルの書込を中止します。`--require-valid` は適用後の検証エラーでも中止します。保存途中の I/O 失敗では部分保存があり得るため、対象ファイルを確認してください。

```yaml
changes:
  - file: requirements/req-001.yaml
    base: 3f2a…                      # hash コマンドの値（省略可）
    ops:
      - { op: set,    path: title,        value: 新しいタイトル }
      - { op: insert, path: "tags[0]",    value: 重要 }        # 配列は挿入、マップは新規キー
      - { op: delete, path: obsolete }
      - { op: move,   from: "items[2]",   to: "items[0]" }
      - { op: rename, path: title,        to: name }           # キー改名（値・キー順・コメントを保持）
      - { op: reorder, path: details,     order: [id, name] }  # キー並べ替え（order を先頭に・残りは相対順。path 省略・空はルート）
```

パスは `items[2].title` 形式です。フロースタイル（`{ }`）内で `[` を含むパスは引用符で囲んでください。ルート（空パス）への操作はできません。

