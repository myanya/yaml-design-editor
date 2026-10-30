# 用語集

このプロジェクトで決めた語（造語と、一般の意味より狭く使う語）を 1 行で説明し、詳しい文書の見出しへ案内する索引。
意味の正は「詳しく」の文書で、ここには要点だけを書く。「近い一般の語」は既知の技術で近いもので、同じ意味ではない。
語を足した・意味が変わった・案内先の見出しが変わったときに直す（[文書の地図](README.md)）。

## 全体の形

| 語 | 意味 | 近い一般の語 | 詳しく |
|---|---|---|---|
| カーネル | yaml-core・semantic-core・workspace-core の 3 モジュールで、画面も AI も知らない中核 | コアライブラリ・ドメイン層 | [ADR-0013](decisions/0013-application-layer-and-extensions.md)・[architecture/README.md「モジュールと依存方向」](architecture/README.md) |
| 応用層 | app-core と agent で、ワークスペースを画面無しで開き・読み・検証し・変える | アプリケーション層・サービス層 | [ADR-0013](decisions/0013-application-layer-and-extensions.md) |
| 入口 | desktop（画面）や cli のように、応用層を呼んで人や機械に見せるモジュール | エントリポイント・プレゼンテーション層 | [ADR-0013](decisions/0013-application-layer-and-extensions.md) |
| 拡張 | app-core の外のモジュールで、差し込み口の実装を足すもの（今は Excel・Word の形式の format-office）。入口の一覧に書いて登録する。chart・flow と文字の形式は app-core の組込みで、登録簿が最初から持つ | プラグイン（JDBC のドライバの登録） | [architecture/views.md「ビューの種類の差し込み口」](architecture/views.md) |
| 原本 | ワークスペースの YAML 文書そのもので、索引・グラフ・ビューはすべてここから導き、別の保存先を持たない | 信頼できる唯一の情報源（source of truth） | [architecture/graph-query.md「融合の原則」](architecture/graph-query.md) |
| 計算体系 | YAML に対する読み取り・判定・書き換え・組み立ての演算の決まり（Core Calculus）で、新しい決定論的機能はまずこの言葉で設計する | クエリ言語と評価器の設計 | [CORE-CALCULUS.md](CORE-CALCULUS.md) |

## 文書・場所・索引

| 語 | 意味 | 近い一般の語 | 詳しく |
|---|---|---|---|
| 文書ID | ワークスペース相対のパスで表した文書の場所（例 `screens/index.yaml`）で、ファイルの `Path` とは別の型 | 相対 URI | [architecture/index-references.md「場所は文書ID」](architecture/index-references.md) |
| 表記 | 引用符・リテラル・フロー表記・コメントのような書き方の情報で、値の意味を決めない | 構文木の trivia・書式 | [architecture/yaml-model.md「コメントと表記の保持」](architecture/yaml-model.md) |
| 出現 | ある ID の要素が 1 つの文書に書かれた 1 か所で、同じ ID は複数の文書に出現してよい | — | [architecture/index-references.md「ID と参照の意味」](architecture/index-references.md) |
| 定義出現 | 同じ ID の出現のうち要素を定義する側（ほかは詳細化の出現）で、表示とジャンプはこれを優先する | 主キーを持つ行 | [architecture/index-references.md「ID と参照の意味」](architecture/index-references.md) |
| 詳細化 | 同じ ID を別の文書にもう一度書いて中身を足すこと（段階的詳細化）で、重複の誤りにしない | 部分クラス（partial class） | [architecture/index-references.md「段階的詳細化の規約」](architecture/index-references.md) |
| アスペクト | 出現が受け持つエンティティの側面の名前（`entity.aspect`）で、全体像は出現をアスペクトごとに並べて 1 枚にする | 部分ビュー・ファセット | [architecture/graph-query.md「エンティティページ」](architecture/graph-query.md) |
| 索引の二層 | 保存済みの内容から作る索引（ディスク索引）と、未保存の編集を重ねた索引を分けて持つこと | 確定済みのスナップショットと作業中の差分 | [architecture/index-references.md「索引の二層」](architecture/index-references.md) |
| オーバーレイ | 未保存の編集中の木を重ねた索引で、画面は見たままの状態で判定するためにこちらを読む | 未コミットの作業領域 | [architecture/index-references.md「オーバーレイ」](architecture/index-references.md) |
| 台帳（範囲） | 索引・参照・ビュー・エージェントが対象にするファイルの範囲（`inLedger`）で、範囲の外の YAML も 1 文書の編集と検証はできる | インデックスの対象範囲 | [architecture/workspace.md「エディタ層と台帳層」](architecture/workspace.md) |
| 下書き | ソースのタブで書いている途中の文字列で、解析できれば木へ反映し、解析できない間はフォームが閲覧専用になる | 未反映のバッファ | [architecture/editing-history.md「ソースとの同期」](architecture/editing-history.md) |
| 一般ファイル | YAML 文書でないファイルとフォルダで、改名・移動・削除・作成も文書と同じ履歴で戻せる | — | [architecture/editing-history.md「一般ファイルを伴う履歴」](architecture/editing-history.md) |

## スキーマと型

| 語 | 意味 | 近い一般の語 | 詳しく |
|---|---|---|---|
| 実効フィールド | `$ref` や型の名前を解決し、enum の値ごとの追加項目（`cases`）を合成した、実際に効く項目の並び | 継承を解決した後のメンバの一覧 | [architecture/schema.md「$ref と実効フィールド」](architecture/schema.md) |
| 型の連鎖 | 項目の型が名前の定義を辿って基底の種類に着くまでの道すじ（`lineage`）で、判定は連鎖で行う | 継承の連鎖 | [architecture/schema.md「型の連鎖と狭める規則」](architecture/schema.md) |
| 狭める | 型を使う側に許すのは決まりを厳しくする書き方（項目を足す・範囲を狭める・選択肢を減らす）だけ、という規則 | サブタイプ・refinement type | [architecture/schema.md「型の連鎖と狭める規則」](architecture/schema.md) |
| オブジェクト型 | 名前を付けた入れ物（object）の定義で、`entity` を書けば要素のエンティティ型を決める | クラス・構造体 | [architecture/schema.md「型の連鎖と狭める規則」](architecture/schema.md) |
| エンティティ型 | ID を持つ要素の種類で、ID の形かオブジェクト型の `entity` で決まり、関係と検査の単位になる | ER 図のエンティティ | [architecture/graph-query.md「エンティティ型と関係レジストリ」](architecture/graph-query.md) |
| 関係レジストリ | 参照と保有の線の名前・表示名・両端の型・多重度を宣言する `_schemas` の `kind: relations` 文書 | ER 図の関連の定義・オントロジー | [architecture/graph-query.md「エンティティ型と関係レジストリ」](architecture/graph-query.md) |
| 保有 | ID を持つ要素の中に ID を持つ要素が書かれている関係で、外側が保有し内側が所属する（文書には何も書かず入れ子から導く） | コンポジション（UML）・包含 | [architecture/graph-query.md「線の意味」](architecture/graph-query.md) |
| フェーズ | 文書が申告する進み具合で、項目の必須（`requiredFrom`）と検査の開始（`activeFrom`）がそれに従う | ワークフローの段階・ステータス | [architecture/schema.md「フェーズ」](architecture/schema.md) |
| 構造スキーマ | 利用者が書いたスキーマから導いた、値の形と必須だけを持つ最小のスキーマで、構造の検証はこれで行う | JSON Schema への変換結果 | [architecture/schema.md「構造スキーマの導出」](architecture/schema.md) |
| メタスキーマ | スキーマやビューの定義文書そのものを検証し、フォームで編集させるための組込みのスキーマ | メタモデル・JSON Schema のメタスキーマ | [architecture/schema.md「推測・メタスキーマ・派生」](architecture/schema.md) |
| 派生スキーマ | 導出元の文書（`derive:`）から読み込みのときに組み立てる読み取り専用のスキーマで、ファイルとしては無い | 生成コード・マテリアライズドビュー | [architecture/schema.md「推測・メタスキーマ・派生」](architecture/schema.md) |
| 印 | 項目に付けて意味を決める宣言（`expression: true`・`auto: updated` など）で、値の書き方からは意味を決めない | アノテーション | [architecture/schema.md「値の式」](architecture/schema.md)・[architecture/auto-values.md「部品」](architecture/auto-values.md) |
| 値の式 | 印のある項目・ビューの列・検査に書く、読み出し・穴埋め・集計の決定的な式で、新しい式言語は作らない | スプレッドシートの数式（1 段だけ） | [architecture/schema.md「値の式」](architecture/schema.md) |
| 項 | 値の式の値（スカラー・配列・マップ）とその出どころの組で、集計は配列の項を受け取る関数 | 式の評価結果の値 | [architecture/schema.md「値の式」](architecture/schema.md) |
| ひな型 | 新しい文書の初期の中身（必須の項目と `scaffold`）と、変換の出力の形（`$` で始まるキーが指示） | テンプレート | [architecture/schema.md「ひな型」](architecture/schema.md)・[architecture/transform.md「変換」](architecture/transform.md) |
| 変換定義 | 選ぶ（クエリ）× 組み立てる（ひな型）で YAML から新しい YAML を作る定義で、入力を書き換えない | XSLT・jq | [architecture/transform.md「変換」](architecture/transform.md) |

## 検証と検査

| 語 | 意味 | 近い一般の語 | 詳しく |
|---|---|---|---|
| 定義体 | 検査・語彙・型を YAML で書いた定義で、組込み（同梱の YAML）も利用者の定義も同じ仕組み・同じ能力を持つ | 言語自身で書いた標準ライブラリ（prelude） | [architecture/checks.md「定義層の 3 段」](architecture/checks.md)・[CORE-CALCULUS「定義層モデル」](CORE-CALCULUS.md) |
| 文法プリミティブ | yaml-core が持つ、YAML の構造と最小の構造チェックの部品で、ID や参照の意味を知らない | 構文レベルの検証 | [CORE-CALCULUS「定義層モデル」](CORE-CALCULUS.md) |
| 意味プリミティブ | semantic-core が持つ、ID・参照・グラフ・クエリ・判定のような解釈の部品で、定義体の中で組み合わせる | 意味解析の組込み関数 | [CORE-CALCULUS「定義層モデル」](CORE-CALCULUS.md) |
| 語彙 | スキーマに短く書いた宣言（`relations[]` など）を、どの定義をどの値で使うかへ展開する定義（`vocabulary`） | マクロ・DSL のキーワード | [architecture/checks.md「定義層の 3 段」](architecture/checks.md) |
| 専用チェッカー | 部品の組合せで書くと不自然になる複雑な検査をコードで書いたもので、文面と配り方は定義体が宣言する | ネイティブ関数 | [architecture/checks.md「専用チェッカー」](architecture/checks.md) |
| 4 つの結果 | 検査を要素 1 つについて評価した結果の違反・充足・保留・対象外で、指摘が出ない＝充足とはしない | 三値論理に「対象外」を足したもの | [architecture/checks.md「4 つの結果」](architecture/checks.md) |
| 出どころ | 指摘がどの文書のどの書き方の検査から、どの要素について、どう配られたかの記録で、同じ指摘かを見分ける | 診断のルール ID と位置 | [architecture/checks.md「指摘の出どころ」](architecture/checks.md) |
| 提出ゲート | エージェントの変更を出す前に通す検証の条件（`error-free`・`no-new-errors`・`none`） | CI の必須チェック | [architecture/agents.md「エージェント実行と Staging」](architecture/agents.md) |

## 変更と操作

| 語 | 意味 | 近い一般の語 | 詳しく |
|---|---|---|---|
| 変更セット | 複数の文書への変更（文書ごとのパッチか新しい文書の作成と、元の内容のハッシュ）をまとめたもので、競合と検証を確かめて全部を書くか何も書かず、Undo 1 回で戻る | パッチの束・トランザクション | [architecture/workspace.md「変更セットの適用」](architecture/workspace.md) |
| 操作 | スキーマに書いた、受け取った値と前提から変更セットを作る定義で、型の操作（`methods`）と横断の操作（`actions`）がある | ストアドプロシージャ・メソッド | [architecture/actions.md「流れ」](architecture/actions.md) |
| 受け手 | 操作が働きかける相手（要素か文書）で、定義のキーは `target` | メソッドのレシーバ（`this`） | [architecture/actions.md「流れ」](architecture/actions.md) |
| 前提 | 操作を使える条件（`require`）で、合わなければ理由を見せて計画しない | 事前条件 | [architecture/actions.md「流れ」](architecture/actions.md) |
| 手 | 操作の中身の 1 段（`do` の set・insert・delete・create・call・each）で、順に当てて後の手は前の手の結果を読める | スクリプトの 1 文・ステップ | [architecture/actions.md「流れ」](architecture/actions.md) |
| 一手 | Undo・Redo の 1 回分で、ワークスペースをまたぐ単一の履歴から戻す | Undo の 1 段 | [architecture/editing-history.md「履歴と dirty」](architecture/editing-history.md) |
| ツール | エージェントなどの入口に出す読み書きの一式（`Toolset`）で、スキーマの「操作」とは別 | 関数呼び出し（function calling）のツール | [architecture/agents.md「部品」](architecture/agents.md) |
| アクティビティ | エージェントに任せる仕事の定義（`_activities`）で、使えるツール・書いてよい範囲・提出ゲート・自動実行を決める | ジョブの定義・CI のワークフロー | [architecture/agents.md「エージェント実行と Staging」](architecture/agents.md) |
| 作業コピー | エージェントの書き込みを原本でなく記録しておく場所（`WorkingCopy`・`Staging`）で、提出したものが変更セットになる | Git の作業ツリーとステージ | [architecture/agents.md「エージェント実行と Staging」](architecture/agents.md) |
| 保存時に自動で入る値 | 項目の印 `auto`（作成日・更新日・版）で、保存の直前か変更セットの適用で入る値 | DB の `updated_at` のトリガ | [architecture/auto-values.md「流れ」](architecture/auto-values.md) |

## ビューと画面

| 語 | 意味 | 近い一般の語 | 詳しく |
|---|---|---|---|
| 射影 | 原本から導いて見せるだけで自分のデータを持たないもの（ビュー・計算列・受信箱）で、編集は原本の座標へ戻す | データベースのビュー・CQRS の読み取りモデル | [architecture/views.md「ビュー機構」](architecture/views.md)・[architecture/transform.md「計算列と決定論的射影」](architecture/transform.md) |
| ビュー | 原本の要素を表・クロス集計・図・文書の形に並べる定義（`_views`）で、射影の 1 つ | スプレッドシートのピボット・DB のビュー | [architecture/views.md「ビュー機構」](architecture/views.md) |
| ビューの種類 | ビューの定義の `kind`（grid・document・matrix・lens・dashboard は中核、chart・flow は差し込み口を通る組込み） | プラグインの種別 | [architecture/views.md「ビューの種類の差し込み口」](architecture/views.md) |
| 複合文書ビュー | 文書ビュー（`kind: document`）の区画に、同じ ID の別の側面のフォーム（`fields.in`）や、その文書の値を受け取る値にした表（`grid`）を置き、複数の文書を 1 枚で読み書きする形 | マスター詳細画面・合成ビュー | [architecture/views.md「文書ビュー」](architecture/views.md)・[manual/views.md「複合文書ビュー」](manual/views.md) |
| 射影ビュー | （構想）変換のひな型で組んだ任意の木を、見せ方の契約（スキーマと同じ書き方）と出どころで編集面にするビュー。複合文書ビューはその部分集合 | テンプレートで組んだ画面・XSLT の出力 | [ROADMAP「複合文書ビューと射影ビュー」](ROADMAP.md) |
| 区画 | ダッシュボードの 1 枚のタイルで、表の行を条件で絞った件数と一覧を出し、詳細と操作へ進む | ダッシュボードのウィジェット | [architecture/dashboards.md「流れ」](architecture/dashboards.md) |
| 共有の入力 | ダッシュボードの全区画に渡す入力（`inputs`）で、空のときは絞らない | ダッシュボードのフィルタ・パラメータ | [architecture/dashboards.md「規律」](architecture/dashboards.md) |
| レンズ | エンティティと線を箱と矢印で描く図（グラフレンズ）で、起点の近くや型・関係で絞って見る | グラフの可視化 | [architecture/graph-query.md「スキーマレンズ」](architecture/graph-query.md)・[manual/views.md「レンズの見え方」](manual/views.md) |
| 実体の段・型の段 | レンズの切り替えで、実体の段は要素と参照、型の段はエンティティ型と関係を描く | インスタンス図とクラス図 | [architecture/graph-query.md「スキーマレンズ」](architecture/graph-query.md) |
| エンティティページ | 1 つの ID の全ての出現をアスペクトごとに合成した 1 枚の画面で、人と AI が同じ全体像を見る | 詳細ページ・レコードの詳細 | [architecture/graph-query.md「エンティティページ」](architecture/graph-query.md) |
| スペース | 左のレールで切り替える画面の大きな区分（ダッシュボード・編集・台帳・実行・設計） | アクティビティバーの切り替え（VS Code） | [architecture/ui.md「UI シェル」](architecture/ui.md) |
| 台帳（スペース） | ワークスペース全体を一覧・ビュー・レンズ・エンティティのタブで見渡すスペース | — | [manual/README.md「画面構成」](manual/README.md) |
| 常設画面・浮いたパネル | 長く続く作業はスペースの中の常設画面、値を集めて 1 回で適用する短い作業と確認だけを浮いたパネルにする線引き | 画面とモーダルダイアログ | [architecture/ui.md「UI シェル」](architecture/ui.md) |
| 場所 | 「戻る・進む」が戻す、どのスペースのどの文書・どの要素かの位置（`NavLocation`） | ブラウザの履歴の URL | [architecture/ui.md「UI シェル」](architecture/ui.md) |
| 覚え | 画面を離れても残す見え方（畳み・タブ・絞り込み・チェック・展開。`ScreenMemory`）で、あれば使い無ければ既定にする | ビューの状態の保存（スクロール位置の復元） | [architecture/ui.md「UI シェル」](architecture/ui.md) |
| 閲覧専用 | 編集の部品を隠して木の変更を封じる表示の切り替えで、スキーマの操作は確認して書くので通す | 読み取り専用モード | [architecture/ui.md「閲覧専用」](architecture/ui.md) |

## 文書の運用

| 語 | 意味 | 近い一般の語 | 詳しく |
|---|---|---|---|
| 今の姿 | 何があるか・どう動くか・何を守るかを書き直して小さく保つ文書で、チケット番号と日付を書かない | リファレンス・設計書 | [CONVENTIONS「ドキュメント更新規約」](CONVENTIONS.md) |
| 記録 | 完了した計画・台帳・経緯を追記だけで残す文書（`docs/archive/`）で、経緯を調べるときだけ読む | 変更履歴・議事録 | [CONVENTIONS「ドキュメント更新規約」](CONVENTIONS.md) |
| 台帳（文書） | 番号を持って項目を管理する文書（ROADMAP・BACKLOG・UX-SCENARIOS） | 課題管理・チケット | [文書の地図](README.md) |
| 規則テスト | 守るべき決まり（依存の向き・置き場・名前）をソースを読んで確かめ、破ると落ちるテスト | ArchUnit・Konsist | [architecture/README.md「ソース規則テスト」](architecture/README.md) |
