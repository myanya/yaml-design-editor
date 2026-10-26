# ADR-0013: 画面を持たない応用層（app-core）と入口・拡張のモジュール構成

- 日付: 2026-10-27
- 状態: 承認済み
- 上書き対象: なし。ADR-0001（in-repo マルチモジュール）と ADR-0002（依存方向）を、応用層・入口・拡張の段を足して拡張する

## 文脈

root のアプリモジュールは 3 万 7 千行で、3 分の 2 が Compose の画面、残りが画面を持たない応用層（ワークスペースの窓口・索引・スキーマ・検証・エージェント・AI・形式変換）である。
応用層は Compose 非依存で層の向きも守られているが、`AppState` がワークスペースの状態（セッション・文書キャッシュ・保存・再読込・履歴）と画面の状態（スペース・タブ・選択・通知）を 1 つに束ねているため、画面無しでは応用層を使えない。
今後の構想（常駐プロセス・ダッシュボードの Web 化・MCP からの操作・ワークフロー・エージェントの作り直し・外部エージェントによる機械的リファクタリング）はどれも「画面を持たない応用層を、別の入口から使う」ことを必要とする。
また取り込み・書き出しの形式とビューの種類は今後も増え、そのたびに応用層が膨らむ。

## 決定

### 全体の形

```
入口      desktop          cli            mcp          （web・daemon は後で足す）
            │                │              │
拡張      format-*  view-*（画面なし）  view-*-ui（Compose。desktop だけが使う）
            │                │
応用      agent ──────► app-core
                          │
カーネル  workspace-core ◄─ semantic-core ◄─ yaml-core
```

依存は下向きだけ。

- 入口（desktop・cli・mcp）は app-core・agent・拡張を使ってよい。
- 拡張は app-core とカーネルだけを使う。拡張どうし、拡張から agent への依存は作らない。
- agent は app-core とカーネルだけを使う。
- app-core はカーネルだけを使う。Compose・`java.util.prefs`・AI の呼び出し・特定の形式のライブラリ（POI など）を持たない。
- 同じ 1 つのプロセスで動かす。別プロセス（常駐＋接続）にするときは、ツールの一式（下記）の「JSON にできる値」の境界を使う。

### モジュールの責務と窓口

| モジュール | パッケージの根 | 責務 | 公開する窓口 |
|---|---|---|---|
| yaml-core・semantic-core・workspace-core | 今のまま | 今のまま（ADR-0001〜0012） | 今のまま |
| app-core | `app` | ワークスペースを画面無しで開き、読み、検証し、変える。開いている文書の集合（セッション側の状態）。ビュー・ダッシュボード・レポート・検索の読み出し。ツールの一式。差し込み口と登録簿 | `Workspace`・`DocumentSession`・`Toolset`・`Views`／`Dashboards`／`Reports`／`FileCatalog`／`Search`・`FormatCodec`／`ViewKind`／`ExtensionRegistry` |
| agent | `agent` | LLM との会話、アクティビティの実行、ステージングと承認待ち、自動実行、評価と指標、文書の生成支援 | `AgentRuns`（開始・状態・受信箱・承認と却下）・`Generation` |
| format-xlsx・format-docx・format-text | `format.<名前>` | ファイルの形式を読み書きする部品。対応づけは skill 定義（YAML）のまま | `FormatCodec` の実装 1 つ |
| view-chart・view-lens・view-flow・view-matrix | `view.<名前>` | ビューの種類の定義の形・読み取り・解決（画面無し） | `ViewKind` の実装 1 つ |
| view-*-ui（必要になったら） | `view.<名前>.ui` | その種類の Compose の描画 | `ViewRenderer` の実装 1 つ |
| desktop | `desktop` | Compose の画面、画面の状態、セッションの Compose への写し、描画の登録簿 | 無し（入口） |
| cli | `cli` | 1 つの CLI。引数をツールかワークスペースの呼び出しに変え、text／yaml／json で出す | 無し（入口） |
| mcp | `mcp` | ツールの一式を MCP のツールとして出す。agent 無しで動く | 無し（入口） |

中核に残すビューの種類は grid・document・dashboard（ダッシュボードは業務の入口で、他の種類を区画として束ねる）。chart・lens・flow・matrix は拡張へ出す。

### 窓口の形

- **`Workspace`**（既存）: 開く・文書を読む・保存・検証・変更セットの適用・リファクタリングの計画。
- **`DocumentSession`**（`AppState` から移す）: 開いている文書の集合・dirty・保存と競合・再読込・Undo／Redo の段・一括変更・文書の作成とファイルの配置。変更の通知は Compose でなく「版の番号と聞き手（listener）」で出し、desktop がそれを Compose の状態に写す。
- **`Toolset`**: `Tool` は名前・説明・引数の形・`run(workspace, args)`。引数と結果は JSON にできる値（連想配列・配列・文字列・数・真偽・null）だけで、カーネルの型を外へ出さない。中核のツールは read_outline・read_node・read_entity・read_view・list_documents・search・validate・apply_patch・create_document・action。拡張はツールを足せる。`ask_human` のような会話の相手が要るものは agent に置く。
  agent は `Tool` を LLM のツール宣言に包み、mcp は MCP のツールに包み、cli は命令に対応づける。同じ `Tool` を 3 つの入口が使う。
- **`FormatCodec`**: 形式の ID・拡張子・`read(bytes)` で表か木へ・`write(表か木)` で bytes へ。skill 定義の `format` の値が ID に対応する。
- **`ViewKind`**: 種類の ID・定義の形の断片（資源の YAML。`kind` ごとにメタスキーマへ合成する）・`parse(raw)` で定義へ・`resolve(定義, workspace)` で行か図へ。
- **`ViewRenderer`**（desktop 側）: 種類の ID と、解決した結果を描く Composable。
- **`ExtensionRegistry`**: 形式・ビューの種類・ツールの登録簿。入口が `listOf(…)` で組み立てて app-core に渡す。自動発見（ServiceLoader）はしない。

### モジュールの中の形

- **app-core**: `app.session`（DocumentSession）・`app.core`（Workspace と読み出し）・`app.index`／`app.schema`／`app.validate`（カーネルへの薄い窓口。落ち着いたら `app.core` へ畳む）・`app.tools`（ツールの一式）・`app.ext`（差し込み口と登録簿）。層の順は index／schema／validate、core、session、tools、ext。「今」は引数で渡し、状態の変更は `Workspace` と `DocumentSession` を通す。
- **agent**: `agent.llm`（接続・HTTP・ツール呼び出しの往復）・`agent.run`（実行器・ステージング・実行記録）・`agent.auto`（自動実行・自動適用・繰り返し）・`agent.eval`（評価・指標）。書き込みはステージング → 変更セット → app-core の適用だけ。
- **desktop**: 根（`Main`・`AppState`＝画面側の状態と写し・`ScreenMemory`）・`desktop.ui`（画面。今の規律のまま）・`desktop.views`（描画の登録簿と中核の描画）・`desktop.ext`（登録の一覧 1 ファイル）。
- **拡張**: 形を固定する。`README.md`（足すもの・定義の例）、`build.gradle.kts`（共通の設定を使い 3 行）、`src/main/kotlin/dev/toros/yde/<名前>/`（差し込み口の実装 1 つが公開の全て）、`src/main/resources/<名前>/schema.yaml`（定義の形の断片。ビューだけ）、`src/test`。
- **cli・mcp**: 引数の解釈、呼び出し、出力の形だけ。判断や組み立てを書かない。

### 機械で守るもの

- モジュール間の依存は Gradle が止める（app-core に Compose・AI・POI が入らない、拡張どうしが依存しない）。
- モジュールの中の層の順と禁止 API は規則テストが守る。拡張の全モジュールが入口の一覧に登録されていることも規則テストで見る。
- 共通の Gradle 設定（convention plugin）で、新しいモジュールの設定を 3 行にする。

### 移行の順

1. app-core を切り出す。`AppState` をセッション側と画面側に分け、セッション側と core・index・schema・validate・skill を app-core へ移す。
2. agent を切り出す（今の ai と agent）。
3. CLI を 1 つにする。cli が app-core と agent に依存し、root の CLI の命令を移し、全命令で json を出せるようにする。
4. 形式の差し込み口。skill の形式 enum を `FormatCodec` の登録簿に置き換え、POI を format-xlsx と format-docx へ出す。
5. ビューの種類の差し込み口。`ViewDefinitions` の `when(kind)` を `ViewKind` の登録簿に置き換え、chart・lens・flow・matrix を出す。メタスキーマの断片の合成をここで作る。
6. mcp の入口。

各段で `./gradlew test` を緑に保ち、規則テストと設計文書を更新する。

## 影響

- 構想（常駐・Web・MCP・ワークフロー・エージェントの作り直し・外部からのリファクタリング）は、app-core の上に入口か応用を足す形で進められる。
- 新しい形式とビューの種類は拡張モジュールに閉じ、app-core は増えない。1 拡張が同じ形の閉じた箱になるので、見る範囲が 1 モジュールに絞れる。
- モジュール境界をまたぐ `internal` は公開に直す。agent のテストはモジュールと一緒に移る。
- 別プロセスの常駐は本決定の範囲外。ツールの一式の境界を使って後で決める。

## 却下した案

- 実行時に jar を読み込むプラグイン: Compose の版の一致とクラスローダの分離が重く、第三者が再ビルドなしで足す需要が出るまで不要。
- ServiceLoader による自動発見: どこで何が足されたかを追いにくい。明示的な一覧のほうが人にもエージェントにも分かる。
- AI を app-core に含める: 「AI は定義を作る、計算体系が実行する」の境界を Gradle で守れなくなり、エージェントの作り直しを隣に作って差し替える形が取れない。
