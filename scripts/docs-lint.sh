#!/usr/bin/env bash
# docs-lint: ドキュメントの形の規約（docs/CONVENTIONS.md「ドキュメント更新規約」）を機械的に確かめる。
#
# 使い方: scripts/docs-lint.sh
# 終了コード: 指摘があれば 1、なければ 0。
#
# 確かめること:
#   SIZE   1 ファイルの文字数の上限（既定 20,000。機能索引と台帳は 40,000。manual/ は 30,000）
#   LINE   1 行の文字数の上限（300）
#   TICKET 設計・マニュアル・規約・機能索引・CLAUDE.md にチケット番号（P／R／D／UX 等）がない
#   CELL   同じ文書群で、表のセルに 2 文以上（「。」が 2 つ以上）書いていない
#   LINK   相対リンクの先が存在する（archive を含む全文書）
set -u
export LC_ALL=C.UTF-8 LANG=C.UTF-8
cd "$(dirname "$0")/.."

MAX_LINE=300
TICKET='\b(P[0-9]{1,2}|R[0-9]{1,2}-[0-9]{2}[a-z]?|D-[0-9]{2}|UX-[0-9]{1,3}|RB-[0-9]{2}|SM-[0-9]{3}|CALC-[0-9]{2}|RP-[0-9]{2}|MP-[0-9]{2}|SC-[0-9]{2}|TF-[0-9]{2}|DL-[0-9]{2}|OQ-[0-9]{2})\b'

size_limit() {
  case "$1" in
    docs/FEATURES.md|docs/ROADMAP.md|docs/BACKLOG.md|docs/UX-SCENARIOS.md) echo 40000 ;;
    docs/manual/*) echo 30000 ;;
    *) echo 20000 ;;
  esac
}

is_state_doc() {
  case "$1" in
    CLAUDE.md|docs/README.md|docs/CONVENTIONS.md|docs/FEATURES.md|docs/architecture/*|docs/manual/*) return 0 ;;
    *) return 1 ;;
  esac
}

run_checks() {
  local f limit chars
  while IFS= read -r f; do
    case "$f" in docs/archive/*) ;; *)
      limit=$(size_limit "$f")
      chars=$(wc -m < "$f")
      if [ "$chars" -gt "$limit" ]; then printf 'SIZE   %s: %s 字（上限 %s）\n' "$f" "$chars" "$limit"; fi
      awk -v f="$f" -v max="$MAX_LINE" 'length($0) > max { printf "LINE   %s:%d: %d 字\n", f, NR, length($0) }' "$f"
      if is_state_doc "$f"; then
        grep -nE "$TICKET" "$f" | sed -E "s|^([0-9]+):.*|TICKET $f:\1|" | sort -u
        awk -v f="$f" '
          /^\|/ {
            n = split($0, cells, "|")
            for (i = 1; i <= n; i++) {
              c = cells[i]
              if (gsub(/。/, "。", c) >= 2) { printf "CELL   %s:%d: セルに 2 文以上\n", f, NR; break }
            }
          }' "$f"
      fi
    ;; esac
    # LINK: archive を含む全文書
    grep -noE '\]\([^)]+\)' "$f" | while IFS=: read -r ln m; do
      t=${m#](}; t=${t%)}
      case "$t" in http://*|https://*|mailto:*|\#*) continue ;; esac
      t=${t%%#*}
      [ -z "$t" ] && continue
      if [ ! -e "$(dirname "$f")/$t" ]; then printf 'LINK   %s:%s: %s が無い\n' "$f" "$ln" "$t"; fi
    done
  done < <(find CLAUDE.md README.md docs -name '*.md' | sort)
}

out=$(run_checks)
if [ -n "$out" ]; then
  printf '%s\n' "$out"
  printf '\n%d 件\n' "$(printf '%s\n' "$out" | wc -l)"
  exit 1
fi
echo "docs-lint: 指摘なし"
