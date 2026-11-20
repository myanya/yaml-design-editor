#!/usr/bin/env python3
"""日次の通知（ワンストップ通知・飼養管理通知）の文面を、前日の分について作る。

要件定義書の「ワンストップ通知」「飼養管理通知」（日次バッチとメール）に当たる。
YDE には決まった時刻の自動実行もメールの送信も無いので、OS のスケジューラ（Windows のタスク スケジューラ・cron）から
このスクリプトを毎日 1 回動かし、できた文面を外のメールの仕組みで送る（devtrack の指摘 UX-366）。

使い方（リポジトリの根で。Windows は py -3）:
  python3 labs/microchip-registry/scripts/daily-batch.py [--date YYYY-MM-DD] [--workspace <場所>] [--out <フォルダ>]

- 日付を省くと前日。文面は <ワークスペース>/output/notices/ に書く。
- レポートは読むだけ（--print）で作るので、デスクトップで同じワークスペースを開いていても動く。
"""
import argparse, datetime, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bulk import DEFAULT_WS, cli_command  # CLI の起動の形は一括のスクリプトと同じ

import subprocess

REPORTS = [("onestop-notice", "onestop"), ("breeding-notice", "breeding")]


def main():
    p = argparse.ArgumentParser(description="日次の通知の文面を作る")
    p.add_argument("--date", help="対象の日（省くと前日）")
    p.add_argument("--workspace", default=str(DEFAULT_WS))
    p.add_argument("--out", help="文面を書くフォルダ（省くと <ワークスペース>/output/notices）")
    a = p.parse_args()
    date = a.date or (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
    ws = Path(a.workspace).resolve()
    out = Path(a.out) if a.out else ws / "output" / "notices"
    out.mkdir(parents=True, exist_ok=True)
    base = cli_command()
    failed = False
    for report, name in REPORTS:
        r = subprocess.run(base + ["report", str(ws), report, "--param", f"date={date}", "--print"],
                           capture_output=True, text=True, encoding="utf-8")
        if r.returncode != 0:
            print(f"{report}: 作れませんでした\n{r.stdout}{r.stderr}", file=sys.stderr)
            failed = True
            continue
        target = out / f"{name}-{date}.md"
        target.write_text(r.stdout, encoding="utf-8")
        print(f"{report}: {target}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
