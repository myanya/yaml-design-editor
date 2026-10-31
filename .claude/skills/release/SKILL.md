---
name: release
description: YAML Design Editor のリリースを準備・検証し、依頼された範囲で GitHub Releases に公開する。版の更新、前回リリースとの差分整理、Windows ZIP・SHA-256・公開本文の作成に使う。通常のビルドやアプリ内の実行 skill の作成には使わない。
---

# リリース

このリポジトリの Windows x64 ZIP を、ソースのコミットと照合できる状態にする。
コマンドはリポジトリの根で実行する。配布工程の正は [docs/releasing.md](../../../docs/releasing.md)。
Claude Code では `/release`、Codex では `.agents/skills/release` の入口から `$release` で呼ぶ。
先に根の [AGENTS.md](../../../AGENTS.md) と配布手順を読み、版・比較元・公開先・依頼範囲を把握する。
「公開準備」の依頼はローカル成果物と確認結果まで。「下書き作成」「公開」まで依頼されていれば、その範囲を続ける。

## 対象と変更内容

- `git status --short`、現在のブランチ・HEAD・remote・タグ、公開済み Releases の本文・添付・prerelease を確認する。ローカルの最新タグと最後の公開版は別々に調べる。
- 最新版の依頼ではリモートの対象ブランチと HEAD を照合する。履歴が分岐している場合は勝手に混ぜず、取り込む対象を明らかにする。
- 無関係な未コミット差分、とくに `labs/` に触れず、作業前の状態を記録する。公開用ビルドのために他者の差分をコミット・退避・削除しない。
- 前回の実際の Release を参考に、対象 OS・添付ファイル・本文・公開区分をそろえる。初回の参考は [v0.1.0](https://github.com/myanya/yaml-design-editor/releases/tag/v0.1.0)（Windows x64 ZIP と SHA256SUMS.txt、プレリリース）。
- `git log` / `git diff` と FEATURES・マニュアルを照合し、利用者向けの変更と更新時の注意を抽出する。作業途中のタグがあっても、公開本文の比較元は前回の公開版にする。

## 準備と検証

1. `gradle.properties` の `appVersion` を依頼された版にする。内部版は Git から生成させる。
2. `docs/archive/changelog.md` に未記録の変更と範囲を追記する。準備日と公開日を混同しない。
   `distribution/windows/release-notes.md` の変更要約と比較元を今回の版に合わせる。`@VERSION@`・`@ZIP@`・`@BUILD@`・`@COMMIT@`・`@MODE@` は生成時の置換に残す。
   README の版の案内も照合する。生成済みの本文だけを直して済ませない。
3. [sync-docs](../sync-docs/SKILL.md) に従い文書を照合し、`scripts/docs-lint.sh` と全モジュールの `test` を通す。
   Windows では AGENTS.md の既存 Gradle キャッシュと文字コードの指定を使う。キャッシュ書込が制限された場合は、その Gradle コマンドだけ実行環境の権限昇格を使う。
4. 必要な検証を済ませ、自分の変更だけをコミットする。コミットを行わない指定や他者の差分がある場合は `-PdistributionPreview=true` で検証し、公開用を作るための残作業を明記する。
5. 作業ツリーが空であることを確かめ、配布手順の `windowsRelease --no-parallel --no-daemon --console=plain` を実行する。
   このタスクが全テスト、配布イメージ、ZIP 展開後のランタイム・内容・版を検査する。途中で失敗した場合、残った ZIP を合格成果物として扱わない。
6. 出力先の ZIP・`SHA256SUMS.txt`・`RELEASE-NOTES.md` を確認する。SHA-256 を再計算し、ZIP の `BUILD-INFO.txt` の版・コミット・`dirty=false`・`preview=false`、本文の置換漏れとリンク先の版を照合する。
   検証結果、対象コミット、ハッシュ、残る実機確認を `scratchpad/` に記録する。配布後の記録を履歴へ追加する場合は archive を使い、ビルド対象のコミットと区別する。

ビルド中はソースを変えない。準備内容を変更した場合は検証・コミット後に公開用を作り直す。
プレビューを改名して公開用にしない。失敗時に検査や dirty 判定を外して通さない。

## 公開の境界

- `docs/releasing.md`「公開前の実機確認」を配布と同じ ZIP で行う。別 PC / VM の未実施項目は未確認のまま記録する。開発機のテストや Hot Reload はその代わりにならない。
- 公開の依頼がある場合は、検証結果を確認して対象コミットに `v<版>` タグを付け、指定の公開先へ push し、Release の下書きに生成本文と ZIP・チェックサムを登録する。
- 同じタグや Release が既にあれば内容とコミットを確認する。公開済みの資産・タグを差し替えず、再試行で下書きを重複作成しない。
- 添付をダウンロードして SHA-256 を照合し、ソースへの案内・公開区分を確認してから、依頼された範囲で公開する。準備だけの依頼から push・タグ公開・Release 公開へ進めない。
- 最後に、版・コミット・成果物の場所・検査結果・未確認項目・次の操作を報告する。試し方には ZIP の展開、サンプルのコピー、編集・保存・再起動、テンプレート作成を含める。
