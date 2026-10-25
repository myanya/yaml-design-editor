#!/usr/bin/env bash
# docs-lint: ドキュメントの形の規約（docs/CONVENTIONS.md「ドキュメント更新規約」）を機械的に確かめる。
#
# 使い方: scripts/docs-lint.sh
# 終了コード: 失敗の指摘（TICKET・LINK）があれば 1、なければ 0。目安の指摘（SIZE・LINE・CELL）は報告するだけで失敗にしない。
#
# 失敗にするもの:
#   TICKET 設計・マニュアル・規約・機能索引・CLAUDE.md にチケット番号（P／R／D／UX 等）がない
#   LINK   相対リンクの先が存在する（archive を含む全文書）
# 目安として報告するもの（長さの上限は良い方法ではないため、別の方法を考えるまでの目安）:
#   SIZE   1 ファイルの文字数（既定 40,000。機能索引と台帳は 80,000。manual/ は 60,000）
#   LINE   1 行の文字数（300。台帳 BACKLOG の表の行は除く）
#   CELL   同じ文書群で、表のセルに 2 文以上（「。」が 2 つ以上）
set -u
export LC_ALL=C.UTF-8 LANG=C.UTF-8
cd "$(dirname "$0")/.."

MAX_LINE=300
TICKET='\b(P[0-9]{1,2}|R[0-9]{1,2}-[0-9]{2}[a-z]?|D-[0-9]{2}|UX-[0-9]{1,3}|RB-[0-9]{2}|SM-[0-9]{3}|RP-[0-9]{2}|MP-[0-9]{2}|SC-[0-9]{2}|TF-[0-9]{2}|DL-[0-9]{2}|OQ-[0-9]{2})\b'

size_limit() {
  case "$1" in
    docs/FEATURES.md|docs/ROADMAP.md|docs/BACKLOG.md|docs/UX-SCENARIOS.md) echo 80000 ;;
    docs/manual/*) echo 60000 ;;
    *) echo 40000 ;;
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
      case "$f" in docs/BACKLOG.md) skip_rows=1 ;; *) skip_rows=0 ;; esac
      awk -v f="$f" -v max="$MAX_LINE" -v skip="$skip_rows" 'length($0) > max && !(skip && $0 ~ /^\|/) { printf "LINE   %s:%d: %d 字\n", f, NR, length($0) }' "$f"
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
errors=$(printf '%s\n' "$out" | grep -E '^(TICKET|LINK) ' || true)
notes=$(printf '%s\n' "$out" | grep -E '^(SIZE|LINE|CELL) ' || true)
if [ -n "$notes" ]; then
  printf '目安（失敗にしない）:\n%s\n\n' "$notes"
fi
if [ -n "$errors" ]; then
  printf '失敗:\n%s\n' "$errors"
  printf '\n失敗 %d 件・目安 %d 件\n' "$(printf '%s\n' "$errors" | grep -c .)" "$(printf '%s\n' "$notes" | grep -c .)"
  exit 1
fi
if [ -n "$notes" ]; then
  printf 'docs-lint: 失敗なし（目安の指摘 %d 件）\n' "$(printf '%s\n' "$notes" | grep -c .)"
else
  echo "docs-lint: 指摘なし"
fi
