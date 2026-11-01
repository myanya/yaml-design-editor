# CLI の接続モード

`connect` は、この PC で起動中のデスクトップの API を利用します。
デスクトップが画面非表示で常駐中でも利用できます。対象は、トークンを発行したワークスペースです。
ローカルのワークスペースパスは指定しません。

通常の CLI はディスクを直接扱い、`connect` は API に要求を送ります。
API が OFF・無権限・通信断でも通常の CLI へ切り替えず、待受の起動や設定の変更も行いません。
一般の編集画面はデスクトップ専用です。

## 接続の準備

デスクトップで対象を開き、「設定 → 接続と常駐」で API を ON にします。
API 用の接続者を登録し、必要な権限を選んでトークンを発行してください。
MCP 用トークンでは利用できません。登録方法は [接続の設定](local-connections.md) を参照してください。

PowerShell では、トークンをコマンドの引数に書かず環境変数へ設定します。

```powershell
$apiSecret = Read-Host 'API トークン' -AsSecureString
$env:YDE_API_TOKEN = [System.Net.NetworkCredential]::new('', $apiSecret).Password
.\gradlew.bat runCli -q --args="connect workspace"
.\gradlew.bat runCli -q --args="connect documents --limit 20 --format json"
```

接続先は `http://127.0.0.1:18765/api/v1` です。
設定画面でポートを変更した場合は `connect --port 18766 workspace` のように合わせます。
環境変数名を変える場合は `--token-env MY_TOKEN` を指定します。
トークンの値を渡すオプションや、別ホストの URL を指定するオプションはありません。
利用後は `Remove-Item Env:YDE_API_TOKEN` で、この PowerShell の環境変数を外せます。

## 参照

次の命令に `connect` を付けます。共通の `--format yaml|json|text` は既定が YAML で、text も YAML と同じです。
API の応答全体を表示するため、読取結果の `workspaceId`・`epoch`・`revision` も残ります。

| 命令 | 内容・固有オプション |
|---|---|
| `workspace` | 接続中のワークスペース |
| `documents` | 文書の一覧 |
| `document <文書>` | ワークスペース相対パスで文書を取得 |
| `search <語>` | 保存済みの内容を検索 |
| `entity <ID>` | エンティティの情報 |
| `views` / `view <ID>` | ビューの一覧・解決結果（view は `--date yyyy-MM-dd`） |
| `validation` | 保存済みの検証結果（`--document 文書` で限定） |
| `actions` | 許可されている定義済み操作 |

すべての読取で `--revision 版` を使えます。
一覧系（documents / search / views / view / validation / actions）は `--offset N --limit N` を使えます。
上限と内容は [読取 API](local-connections.md#api-の読み取り) と同じです。
`nextOffset` がある場合は、返された `revision` とその offset を次の呼び出しへ渡してください。
自動の全ページ取得はしません。デスクトップの未保存編集は読取結果に含みません。

## 定義済み操作

まず計画して差分・検証結果を確認し、返された `planId` を確定へ渡します。
計画と確定は別命令です。CLI が古い計画を自動で作り直すことはありません。

```text
connect actions
connect operation plan <操作名> --target <IDまたは文書> --param 数量=2
connect operation commit <planId> --request-id my-operation-001
connect operation result my-operation-001
```

`--target` と `--param` は定義に応じて指定します。
`--param 名前=YAML値` は繰り返せますが、同じ名前は重複指定できません。
数値・真偽値・配列などは YAML の型で送ります。文字列にしたい値は YAML の引用符を付け、必要に応じてシェルの引用符で引数全体を囲みます。
`request-id` は英数字・`_`・`-` の 180 文字以内で、1 回の処理ごとに一意にしてください。
同じ処理の再送には、同じ計画 ID と要求 ID を使います。

## 任意の変更

開始時に `direct`（直接確定）か `proposal`（提案）を選びます。開始後の方式変更はできません。

```text
connect work begin --mode direct --title タイトル変更
connect work edit <作業ID> change.yaml
connect work validate <作業ID>
connect work plan <作業ID>
connect work commit <作業ID> --plan-id <planId>
connect work result <作業ID>
```

`change.yaml` は UTF-8 の変更セットです。標準入力を使う場合はファイル名の代わりに `-` を指定します。
CLI が読むローカルファイルはこの入力だけで、原本の変更・検証・保存はサーバが行います。

```yaml
changes:
  - file: a.yaml
    ops:
      - {op: set, path: title, value: 変更後のタイトル}
```

提案では `--mode proposal` で開始し、edit・validate の後に `connect work submit <作業ID>` で提出します。
`awaiting_approval` になった内容はデスクトップの受信箱で確認・承認します。
直接確定では受信箱での承認は不要です。どちらも `connect work discard <作業ID>` で破棄できます（既に保存済みの変更の取り消しには使えません）。
厳格な検証、未保存編集との競合、権限・計画の再確認、Undo 履歴の扱いは [API による変更](local-api-changes.md) と共通です。

## 結果と終了コード

成功した HTTP 応答は標準出力に、エラーの説明は標準エラー出力に出します。
検証・保存が不成功の場合も、取得できた結果の本文は標準出力に残します。

| 終了コード | 意味 |
|---|---|
| 0 | 要求の受付成功（提案の提出・編集中・破棄も含む。保存済みかは state を確認） |
| 1 | 引数・入力・資格情報・接続・HTTP 拒否・応答形式のエラー |
| 2 | valid / canCommit が false、却下、または取得した検証ページに error の指摘あり |
| 5 | 保存失敗・部分保存・結果不明・処理中・復旧済みで、保存成功を確認できない |

`validation` の終了コードは取得したページだけの判定です。ワークスペース全体の判断には残りのページも確認してください。
通信断はコード 1 で、保存されていない保証にはなりません。
確定要求を送った後で通信が切れたときは、同じ作業 ID / 要求 ID の result を確認してください。
未提出の作業と計画はメモリ内のため、再起動やワークスペース切替で利用できなくなることがあります。

Gradle 経由の `runCli` は、CLI が非ゼロで終わると Gradle 自体の失敗コードへ変換します。
スクリプトで CLI の終了コードを直接判定する場合は `./gradlew :cli:installDist` の後、
Windows は `cli/build/install/cli/bin/cli.bat`（ほかの環境は同じ場所の `cli`）を使ってください。

通信は最長 30 秒、入力と JSON 要求は 2 MiB、応答は 4 MiB までです。
別の URL への転送・自動再送はしません。HTTP のエラー本文や通信例外は表示せず、状態コードに応じた説明を返します。
