#!/usr/bin/env bash
# 画面の確認用に、ワークスペースをリポジトリの scratchpad/ui-check/<名前>（git 管理外）へ写す。
# 原本（samples/・labs/）を開いて編集・保存で変えないため。写した先の絶対パスを 1 行で出す（hotRun の --args に渡す）。
# 使い方: bash .claude/skills/ui-check/scripts/prepare.sh <元のフォルダ> [名前]
set -euo pipefail
src="${1:?使い方: prepare.sh <元のフォルダ> [名前]}"
root="$(git rev-parse --show-toplevel)"
cd "$root"
[ -d "$src" ] || { echo "フォルダがありません: $src" >&2; exit 1; }
name="${2:-$(basename "$src")}"
case "$name" in
  ""|*/*|*\\*|.|..) echo "名前に / や \\ や . だけは使えません: $name" >&2; exit 1 ;;
esac
dest="scratchpad/ui-check/$name"
rm -rf "$dest"
mkdir -p "scratchpad/ui-check"
cp -r "$src" "$dest"
# Git Bash（Windows）では Windows 形式の絶対パス（C:/…）を出す
if command -v cygpath >/dev/null 2>&1; then cygpath -m "$root/$dest"; else echo "$root/$dest"; fi
