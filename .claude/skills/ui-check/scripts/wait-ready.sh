#!/usr/bin/env bash
# 背景で走らせた hotRun のログを見て、アプリの起動（Hot Reload の案内）が出るか、ビルド・起動が失敗するまで待つ。結果を 1 行で出す。
# 案内はアプリの JVM を起こした時点で出る。窓が開くまでさらに 30〜60 秒かかるので、その後は MCP の status と list_windows で待つ。
# 使い方: bash .claude/skills/ui-check/scripts/wait-ready.sh [ログ（既定 scratchpad/ui-check/hotrun.log）] [待つ秒数（既定 600）]
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"
log="${1:-scratchpad/ui-check/hotrun.log}"
limit="${2:-600}"
for ((i = 0; i < limit; i++)); do
  if [ -f "$log" ]; then
    if grep -qE "BUILD FAILED|FAILURE:|Exception in thread" "$log"; then
      echo "失敗: ビルドか起動が失敗しました（$log の末尾）"
      tail -n 20 "$log"
      exit 1
    fi
    if grep -q "Compose Hot Reload" "$log" && grep -q "Running '" "$log"; then
      echo "アプリを起動しました（${i} 秒）。窓が開くまで 30〜60 秒かかります。MCP の status が connected になってから list_windows を呼んでください"
      exit 0
    fi
  fi
  sleep 1
done
echo "時間切れ（${limit} 秒）: $log の末尾を読んでください"
tail -n 20 "$log" 2>/dev/null
exit 2
