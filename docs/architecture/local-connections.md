# ローカル接続

## 概要

ローカル接続は、通信ライブラリを local-server に閉じる組込み構成を取る。
現在は設定型と loopback 待受の基礎部品、SDK・HTTP・同梱ランタイムの互換性検査がある。
デスクトップの起動経路にはつないでおらず、業務 API・外部 MCP・Web の利用はまだできない。
全体の設計契約は [ローカル接続の決定](../decisions/0016-local-connections-and-shared-host.md)、実施順序は ROADMAP にある。
外部確定と Undo の扱いは [履歴の決定](../decisions/0017-external-commit-and-desktop-history.md) が補足する。

## 部品

| 部品 | 責務 |
|---|---|
| `server.LocalConnectionSettings` | API・MCP・Web の初期値 OFF と利用者指定ポートの範囲 |
| `service.WorkspaceHost` / `WorkspaceStatus` | 共通セッションの所有、状態の世代・版・未保存・競合・処理中の通知、所有スレッドへの受付 |
| `service.SourceDraftWorker` | 画面と独立した下書きの遅延解析・木との同期、古い入力の採用防止 |
| `desktop.DesktopRuntime` | アプリ全体とワークスペース単位の寿命、監視・自動実行・手動実行・終了の統括 |
| `server.transport.LoopbackEngine` | CIO の明示起動と終了、`127.0.0.1` の固定、確定したポートの取得 |
| `TransportCompatibilityTest` | Ktor testApplication と公式 MCP クライアントによる JSON・初期化・ツールの往復 |
| `LocalServerDistributionSmoke` | CIO の実待受・停止・占有時の失敗と、同梱 JAR だけでの MCP 往復 |

通信依存の版は `gradle/libs.versions.toml` にまとめ、local-server の解決結果を lockfile に固定する。
JSON の版は既存の agent・app-core・CLI の試験も含めて検査する。

## 流れ

LoopbackEngine の生成では待受しない。`start()` の成功後にだけ確定ポートを返す。
ポートが占有されているときは失敗し、別ポートや全 NIC に切り替えない。
`close()` で待受を止め、終了後のインスタンスは再起動させない。
利用者の設定ではポート 0 を許さず、試験では OS が空きポートを選ぶために内部の部品へ 0 を渡す。

## 規律

- Ktor と MCP SDK の import と実行時の直接依存は local-server だけに置く。
- local-server は app-core とカーネルまでに依存し、Compose・agent・desktop・cli・形式の拡張に依存しない。
- app-core・agent・カーネル・拡張から local-server へ依存しない。desktop が組み立て、CLI の通信アダプターもこの境界に置く。
- app.service は app-core の最上層として扱い、通信・画面・agent に依存しない。
- モジュールを依存に足すだけでは起動しない。設定の永続化と利用者による有効化は desktop の責務。
- テスト用の経路・ツールを main に置かない。待受の試験は自分が起動した loopback だけを使い、成功・失敗のどちらでも閉じる。
- 通信と接続管理は実行基盤で、Core Calculus の新しい業務評価や書込経路を追加しない。

## 共通状態と寿命

DesktopRuntime は Window の外で生成し、WorkspaceHost と AppState を一つずつ保持する。
AppState は Host の DocumentSession を受け取り、画面の覚えと Compose の写しを持つ。
保存済みを映す Workspace と編集用 WorkspaceSession、カーネルの単一履歴は分けたまま使う。
WorkspaceStatus は可変の本文を含めず、StateFlow で最新値を配る。セッション交換で世代を変え、旧世代の要求を拒否する。
版は文書の変更とソース入力で進め、処理中の開始・終了だけでは進めない。デスクトップでは操作途中の通知を所有スレッドの次の処理へまとめる。
文書とセッションは解除可能な複数購読を持ち、画面と共通状態が互いの購読を上書きしない。
OpenDocument の Compose の写しだけは最後に作った殻へ接続し、破棄した描画の殻を購読一覧へ残さない。

監視・AutomationEngine・RunController・SourceDraftWorker はワークスペース単位の子ジョブである。
AppRoot と SourcePane はこれらを起動・停止しない。画面がなくても監視・解析・質問待ち・実行を保持する。
切替は候補の読込成功後に旧ジョブの終了を待って採用する。読込失敗では旧状態・履歴・ジョブを残す。
監視の登録直後に本文も再確認し、候補の読込から登録までの変更を取り込む。
終了は新規要求を止め、進行中のファイル操作の反映を待ってから監視と子ジョブを終了する。
トレイ・非表示起動の利用者操作と接続の ON/OFF はまだ公開していない。

共有状態の通常操作は DesktopUiDispatcher に集める。ソース解析は文字列を渡して背景で計算し、文書・版・入力世代を確認して採用する。
ファイル配置とその履歴は WorkspaceSession の PendingFileOperation が独立した状態で処理し、所有スレッドの finish で採用する。
処理中の画面は確定済みのセッションを参照できるが、編集・接続からの受付は止め、監視と解析結果の反映を保留する。
書込が始まった作業は呼出し元の取消で途中放棄せず、結果の反映まで完了してから処理中を解除する。

試験は WorkspaceHostTest・DesktopRuntimeTest・BackgroundFileOperationTest と、既存の編集・履歴・外部変更の試験で行う。

## 拡張ポイント

共通状態・権限・接続の状態機械がそろってから、HTTP API・MCP・Web の経路をサービスへ接続する。
機能別停止は、最後の機能を止めるときだけ LoopbackEngine の寿命を終える。
Web の業務通信と外部向け API の有効化は分け、同じ app-core の操作窓口を使う。
SDK 内蔵の Origin 検査はホスト名の比較なので、機能を公開するときは scheme・port も照合する受付側の検査を追加する。
SDK の既定保護だけでブラウザの認証・CSRF 対策が完成したとは扱わない。
