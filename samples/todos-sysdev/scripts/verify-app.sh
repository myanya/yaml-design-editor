#!/usr/bin/env bash
# verify-app.ps1 と同じ検証を、PowerShell の無い環境（Linux・macOS・Git Bash）で行う。
# 生成したアプリ（app/）を作業用フォルダへ写し、verification/ の検証テストを足して、テストと bootJar を回す。
# 使い方: samples/todos-sysdev/scripts/verify-app.sh [アプリの場所] [検証テストの場所]
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
sample="$(dirname "$here")"
application="${1:-$sample/app}"
verification="${2:-$sample/verification}"
repository="$(cd "$sample/../.." && pwd)"
copy="$repository/scratchpad/application-verification/$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$copy"
# build・.gradle・data・.git は写さない（verify-app.ps1 と同じ）
(cd "$application" && tar cf - --exclude=./build --exclude=./.gradle --exclude=./data --exclude=./.git .) | (cd "$copy" && tar xf -)
mkdir -p "$copy/src/test/java/verification"
cp "$verification"/*.java "$copy/src/test/java/verification/"
echo "検証用コピー: $copy"
if (cd "$copy" && sh ./gradlew test bootJar projects --no-daemon --no-parallel --console=plain --max-workers=2); then
  echo "独立アプリのビルドと、verification/ の検証テストが通りました。結果: $copy/build/reports/tests/test/index.html"
else
  echo "アプリの検証に失敗しました。結果: $copy/build/reports/tests/test/index.html" >&2
  exit 1
fi
