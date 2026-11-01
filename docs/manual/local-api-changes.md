# API による変更

接続先とトークンの発行は [接続と常駐](local-connections.md) を参照してください。API を ON にし、API 用の接続者へ必要な変更権限を保存した後にトークンを発行します。
変更対象は業務データです。定義・構成は変更できません。計算には保存済みの全入力文書への閲覧権限が必要です。
全ての POST は `Content-Type: application/json` を指定し、引数のない場合も `{}` を送ります。契約は `GET /api/v1/openapi.json` で取得できます。

## 作業方式

| 方式 | 権限 | 保存までの流れ |
|---|---|---|
| `proposal` | 変更提案 | 作業開始 → 編集 → 検証 → 提出 → デスクトップの受信箱で差分を再確認して承認保存 |
| `direct` | 直接確定 | 作業開始 → 編集 → 検証 → 保存内容の計画 → 計画 ID を指定して確定 |

方式は開始時に決め、後から変えられません。両方の権限を持つ接続者も、提案として始めた作業を外部から承認することはできません。
未保存なら破棄できます。API の編集は作業コピーに入り、提出や計画の取得だけでは原本を保存しません。

## 直接確定の例

次は、既存の `tasks/a.yaml` の `title` を更新する PowerShell 7 の例です。文書・項目・値は実際のワークスペースに合わせて変更してください。
接続先は設定画面の表示、トークンは発行した値を使います。トークンをスクリプトや履歴へ固定保存しないでください。

```powershell
$base = 'http://127.0.0.1:18765/api/v1'
$token = Read-Host 'API トークン' -MaskInput
$headers = @{ Authorization = "Bearer $token" }
function Send-Yde($path, $value) {
    $json = ConvertTo-Json -InputObject $value -Depth 30 -Compress
    Invoke-RestMethod -Method Post -Uri "$base/$path" -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json))
}
$work = (Send-Yde 'works' @{ title = 'タスク名を更新'; mode = 'direct' }).data
Send-Yde "works/$($work.id)/changes" @{
    changes = @(@{ file = 'tasks/a.yaml'; ops = @(@{ op = 'set'; path = 'title'; value = '更新後の名前' }) })
}
$check = (Send-Yde "works/$($work.id)/validate" @{}).data
$check.issues
if (-not $check.valid) { throw '検証結果を確認し、作業コピーを修正してください' }
$plan = (Send-Yde "works/$($work.id)/plan" @{}).data
$plan.documents | Format-List
if (-not $plan.canCommit) { throw '保存できない計画です' }
```

ここまでは未保存です。差分を確認した後、次を実行すると保存します。承認待ちは発生しません。

```powershell
$result = (Send-Yde "works/$($work.id)/commit" @{ planId = $plan.planId }).data
$result.state
$result.persistence
```

保存せずに終える場合は `Send-Yde "works/$($work.id)/discard" @{}` を実行します。
確定の成功は `state: applied` です。HTTP 200 だけを成功と判定せず、状態と文書別の `persistence` を確認してください。

## 提案の例

同じ開始要求の `mode` を `proposal` にし、同じ `changes` と `validate` を呼び出します。検証後に次を実行します。

```powershell
$submitted = (Send-Yde "works/$($work.id)/submit" @{}).data
$submitted.state  # awaiting_approval
```

デスクトップの実行スペースの受信箱に提案が届きます。「差分と検証を再確認」の後、「承認して保存」または却下を選びます。
提出後の追記はできません。修正したい場合は承認前に破棄し、新しい作業として提出します。
接続を OFF にしても提出済みの提案は残り、現在の登録権限が有効ならデスクトップで承認できます。

## 作業と結果の取得

| メソッドと経路（`/api/v1` 以下） | 内容 |
|---|---|
| `POST /works` | `title` と `mode` を指定して開始し、作業 `id` を取得 |
| `POST /works/{id}/changes` | 共通の変更セット `changes` を作業コピーへ適用 |
| `POST /works/{id}/validate` | `valid`、変更前後の本文 `documents`、`issues`、`failedDocuments` を取得 |
| `POST /works/{id}/submit` | 提案を提出して承認待ちにする |
| `POST /works/{id}/plan` | 直接確定の `planId`、期限、差分、検証結果を取得 |
| `POST /works/{id}/commit` | 本文の `planId` で直接確定 |
| `POST /works/{id}/discard` | 未保存の作業・未承認の提案を破棄 |
| `GET /works/{id}` | 自分の作業の現在の状態と結果を取得 |

変更セットのパッチは `set / insert / delete / move / rename / reorder`、新規文書は `create: true` と `content` を指定します。
`delete` は文書内の項目・要素の削除です。ファイル削除の API ではありません。
既存文書への変更には、必要なら元の本文の SHA-256 を `base` に指定できます。作業中のディスク変更は `base` の省略時も検出します。
作業コピーは最大 64 件、変更セットは 2 MiB、要求と応答は 4 MiB までです。開始した作業は接続が切れても保持しますが、提出・確定前の作業はアプリの再起動で消えます。

計画は 10 分で失効します。計画取得後に編集・再検証した場合も再計画してください。保存版・権限・ワークスペース・デスクトップの編集の版が変わった場合は保存を拒否します。
対象に未保存の編集があるときも拒否します。変更対象と影響先に検証エラーが残る場合は確定も提出もできません。警告だけなら進めます。
直接確定は外部用の Undo/Redo を作りません。デスクトップの履歴は、変更文書に触れる古い一手を適用不可とし、無関係な履歴を保持します。

通信が途切れた場合は作業 ID で照会してください。同じ作業 ID と計画 ID の確定再送は既存の結果を返し、再保存しません。別の計画 ID への差替えは拒否します。
`failed` は確定失敗、`partial` は一部保存、`unknown` は結果不明です。再送で処理をやり直すことはありません。原本と文書別結果を照合し、必要な修正は新しい作業で行ってください。
結果不明の対象は照合・復旧まで別の確定も拒否します。復旧の画面・API は現在未公開です。
記録は自動削除しません。保管上限や記録の破損では、新しい保存を開始しません。

## 定義済みの業務操作

`GET /actions` で保存済みの操作カタログを取得します。一覧の閲覧と実行の許可は別です。対象範囲と操作名の許可がある場合だけ呼び出せます。
例えば、スキーマに定義した `rename` を `tasks/a.yaml` に対して実行する場合は次のように計画と確定を分けます。

```powershell
$operation = (Send-Yde 'operations/plan' @{ name = 'rename'; target = 'tasks/a.yaml'; params = @{} }).data
$operation.documents | Format-List
if (-not $operation.canCommit) { throw '検証結果を確認してください' }
$requestId = [guid]::NewGuid().ToString()
$result = (Send-Yde 'operations/commit' @{ planId = $operation.planId; requestId = $requestId }).data
$result.state  # 成功は committed
Invoke-RestMethod -Uri "$base/operations/$requestId" -Headers $headers
```

`name` は定義した操作 ID または `型名.操作名`、`target` は受け手の要素 ID または文書パス、`params` は定義に応じた入力値です。
`requestId` は英数字・ハイフン・下線の 1〜180 文字で、接続者の中で一意にします。同じ要求と計画の再送は同じ結果を返し、別計画への使い回しは拒否します。
業務操作の権限だけでは、任意のパッチを直接確定できません。

## 変更要求の失敗

認証・機能 OFF の扱いは [API の読み取り](local-connections.md#api-の読み取り) と同じです。415 は JSON の Content-Type 不足、400 は要求の形式や未知項目です。
403 は必要な能力・操作名の不足です。409 の `work_conflict` は所有者・作業方式・対象範囲・閲覧不足・検証・競合・期限などで作業を進められない場合です。
作業の状態、検証結果、登録権限、デスクトップの未保存編集を確認してください。実装や OS の例外本文をそのまま返さないため、必要ならデスクトップでも確認します。
