---
name: ui-check
description: 実際の画面（Compose for Desktop）で変更を確かめる手順。hotRun でアプリを起動し、Hot Reload の MCP（.mcp.json の compose-hot-reload）の status・list_windows・get_semantic_tree・click・type_text で操作して見る。画面の部品やビュー・フォームの見え方を変えたとき、オーナーに「試し方」を渡す前の確認に使う。自動テスト（UI テストを含む）で足りるときは使わない。
---

# 画面の確認（Hot Reload MCP）

アプリを起動し、画面の部品が持つ文字と操作の木（semantic tree）を読んで確かめる。見た目（色・配置）は木に出ないので、報告では「木で確かめた」と書く。
この手順はどのエージェントも読める。Claude Code では skill `ui-check` として呼べる。

## 先に決めること

- **テストで足りるなら使わない。** 項目が出る・閲覧専用の分岐・入力が木に届く、は Compose の UI テストで確かめられる（docs/CONVENTIONS.md の UI テストの項・例 `FormViewUiTest`）。画面の確認は、テストに書きにくい流れ（画面の行き来・ダッシュボード・図）を見るときに使う。
- **オーナーが同じ PC で作業しているかもしれない。** OS のキー送信（SendKeys）・座標のクリック・スクリーンショット（`take_screenshot`）は使わない。前面のウィンドウが入れ替わり、キーが別のアプリに届いたことがある。使うのは MCP の `click`・`type_text`・`get_semantic_tree` だけ。
- **samples/・labs/ をそのまま開かない。** 編集や保存で原本が変わる。写してから開く（手順 2）。labs/ はオーナーが整備中のことがあるので、読むだけにする。

## 手順

1. **つながりを見る。** MCP の `status` を呼ぶ。
   - `connected: true` なら、オーナーが起動したアプリにつながっていることがある。勝手に閉じない。使うなら自分の変更を `./gradlew reload -q`（手順 6）で差し込む。使わないなら触らずに報告する。
   - `connected: false` なら手順 2 へ。MCP サーバはセッションの開始時に `.mcp.json` から起動している（サーバより先にアプリを起動すると、つながらないことがある）。
2. **ワークスペースを写す。** 確かめたい中身に合うものを選び、`bash .claude/skills/ui-check/scripts/prepare.sh <元のフォルダ> [名前]` を実行する。リポジトリの `scratchpad/ui-check/<名前>`（git 管理外）に写し、写した先の絶対パスを出す。
   - samples/orders: ダッシュボード・チャート・クロス集計・表
   - samples/todos: フォーム・表の配列・参照・選択肢
   - labs/sysdev-template: フロー・グラフレンズ・文書ビュー・多くのスキーマ
   - 試したい定義がサンプルに無いとき（例: 文書ビューにチャートを埋め込む）は、写した先にだけ足す。
3. **起動する。** シェルの背景実行で次を走らせる（終わらないコマンドなので、前面で待たない）。

   ```bash
   ./gradlew hotRun --args="<写した先の絶対パス>" --console=plain > scratchpad/ui-check/hotrun.log 2>&1
   ```

   続けて `bash .claude/skills/ui-check/scripts/wait-ready.sh` を前面で実行する。アプリの起動の案内がログに出るか、ビルドが失敗するまで待ち、どちらかを 1 行で出す。コンパイルが要るときは数分かかる。
   案内が出てから窓が開くまで、さらに 30〜60 秒かかる。MCP の `status` が `connected: true` になってから `list_windows` を呼ぶ。まだなら 20 秒ほど待って呼び直す。
4. **読む。** `get_semantic_tree` に `list_windows` の `id` を `window_id` として渡す。
   - 浮いた一覧（選択肢のメニューなど）が開いている間は、`window_id` を渡さないと「No semantic owners available」になる。
   - 木が大きいとファイルに保存されたと返る。そのときは `py -3 -X utf8 -I .claude/skills/ui-check/scripts/tree_find.py <そのファイル> <語> [<語> …]` で、語を含む部品の id・文字・位置・操作を出す。
   - 確かめるのは `text`・`editableText`・`selected`・`toggleableState`・`stateDescription`。位置が 0 の部品は画面の外（スクロールで隠れている）だが、木にはある。
5. **操作する。** `click` は `actions` に `onClick` がある部品の id、`type_text` は `editableText` を持つ部品の id に使う。
   - 操作の後は id が変わることがある（タブの切り替えで画面が作り直される）。次の操作の前に木を読み直し、文字で部品を探してから id を使う。
   - 背景で計算する部品（フローの配置など）は数秒かかる。「…しています」の文が消えるまで待ってから読む。
   - 閲覧専用モードが前回から残っていると、編集の部品が出ない。表示メニューで外してから確かめる。
6. **変更を差し込む（起動したまま直すとき）。** 既存のファイルの変更は、シェルで `./gradlew reload -q` を実行する（MCP の `reload` は `gradlew.bat` を見つけられずに失敗することがある）。`status` の `successfulReloads` が増えれば入っている。新しいファイルやクラスを足したときは MCP の `restart` で起動し直す。
7. **片付ける。**
   - 触った状態（畳み・チェック・閲覧専用モード）を元に戻す。
   - 背景の hotRun を止め、`powershell -NoProfile -ExecutionPolicy Bypass -File .claude/skills/ui-check/scripts/stop.ps1` で残った Java のプロセスを止める（止めたプロセスの番号を出す）。背景の実行を止めただけでは、Gradle の呼び出し側とアプリが残る。
     止めるのはアプリと hotRun の Gradle だけで、MCP サーバと Gradle デーモンは残す（次の確認でもそのまま `status` が使える）。mac / Linux では `pkill -f 'dev.toros.yde.desktop.MainKt'` で代える。
   - 写したワークスペースは消してよい（`scratchpad/ui-check/`）。
8. **書き残す。** 報告と確認待ちの記録に、操作と、木で見えたもの（部品の文字）を書く。押して確かめていない操作は「未確認・オーナー確認」と書く。

## MCP でできないこと

- ホバー・ドラッグ・ホイール・右クリック、キー操作（Tab・Enter・Ctrl+… の組み合わせ）は出せない。報告の試し方に「オーナー確認」として書く。
- 1 万行のような大きな文書を開くと、木の取得が時間切れになることがある。小さな文書で確かめる。
