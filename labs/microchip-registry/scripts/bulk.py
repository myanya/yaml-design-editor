#!/usr/bin/env python3
"""一括の手続（CSV）を、業務の操作を 1 行ずつ呼んで実行し、行ごとの成否を結果 CSV に書く。

要件定義書の「マイクロチップ情報登録（一括）」「所有者変更登録（一括）」「情報変更（一括）」に当たる。
YDE の取り込み（skill の import）は全件か無しかで、操作の前提（二重登録・変更キーの確認）も通らないので、
YDE の外のスクリプトから CLI の action --apply を行ごとに呼ぶ（devtrack の指摘 UX-365）。

使い方（リポジトリの根で。Windows は py -3）:
  python3 labs/microchip-registry/scripts/bulk.py register <入力.csv> [--out <結果.csv>] [--workspace <場所>] [--dry-run]
  python3 labs/microchip-registry/scripts/bulk.py transfer <入力.csv> ...
  python3 labs/microchip-registry/scripts/bulk.py change   <入力.csv> ...

- 入力は見出し行つきの UTF-8 の CSV。列は scripts/samples/ の見本と同じにする。
- 一括の手数料はクレジットカード決済だけ（要件）。決済はシステムの外なので、ここでは支払を記録する。
- --dry-run は計画だけで、原本を変えない（前提の誤りは分かるが、受付のあとの手順は確かめない）。
- デスクトップで同じワークスペースを開いている間は書けない（CLI が「使用中」で止まる）。閉じてから実行する。
"""
import argparse, csv, json, os, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent.parent          # リポジトリの根
DEFAULT_WS = HERE.parent

SPECIES = {"犬": "dog", "猫": "cat"}
SEX = {"オス": "male", "メス": "female"}


def cli_command():
    """CLI の起動の形。組んである一式（cli/build/yde-cli）を Java で直接起動する。無ければ ./yde で組む。"""
    dist = ROOT / "cli" / "build" / "yde-cli"
    if not (dist / "java-home.txt").exists():
        launcher = ROOT / ("yde.cmd" if os.name == "nt" else "yde")
        subprocess.run([str(launcher), "version"], check=True, stdout=subprocess.DEVNULL)
    java = Path((dist / "java-home.txt").read_text(encoding="utf-8").strip()) / "bin" / ("java.exe" if os.name == "nt" else "java")
    return [str(java), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-cp", str(dist / "lib" / "*"), "dev.toros.yde.cli.MainKt"]


class Yde:
    def __init__(self, workspace, dry_run):
        self.base = cli_command()
        self.ws = str(workspace)
        self.dry_run = dry_run

    def run(self, *args):
        r = subprocess.run(self.base + list(args) + ["--format", "json"], capture_output=True, text=True, encoding="utf-8")
        return r.returncode, r.stdout, r.stderr

    def action(self, op, target=None, **params):
        """操作を 1 つ適用する。戻り値は (成否, 作った文書の ID, 理由)。"""
        args = ["action", self.ws, op]
        if target:
            args += ["--on", target]
        for key, value in params.items():
            if value not in (None, ""):
                args += ["--param", f"{key}={value}"]
        if not self.dry_run:
            args.append("--apply")
        code, out, err = self.run(*args)
        if code != 0:
            return False, None, reason_of(out, err)
        result = json.loads(out)
        created = [c["file"] for c in result.get("changes", []) if c.get("create")]
        made = Path(created[0]).stem if created else None
        if self.dry_run:
            return True, made, "計画だけ（原本は変えていません）"
        if not result.get("applied"):
            issues = [i["message"] for f in result.get("results", []) for i in f.get("issues", [])]
            return False, None, "検証エラーで適用しませんでした: " + " / ".join(issues)
        return True, made, ""

    def fee(self, fee_id):
        code, out, err = self.run("view", self.ws, "fee-grid")
        if code != 0:
            sys.exit("料金一覧を読めません: " + reason_of(out, err))
        for row in json.loads(out).get("rows", []):
            if row.get("id") == fee_id:
                for cell in row.get("cells", []):
                    if cell.get("column") == "手数料（円）":
                        return cell.get("value")
        sys.exit(f"料金 {fee_id} が料金一覧にありません")

    def change_key(self, mc):
        code, out, err = self.run("read", self.ws, f"registry/microchips/{mc}.yaml", "changeKey")
        return out.strip().strip('"') if code == 0 else ""


def reason_of(out, err):
    text = (out + "\n" + err).strip()
    lines = [l.strip() for l in text.splitlines() if l.strip() and "Picked up" not in l]
    for line in lines:
        if line.startswith("エラー:"):
            return line[len("エラー:"):].strip()
    return lines[-1] if lines else "理由が分かりません"


def register(yde, row, fee):
    ok, app, why = yde.action(
        "apply-registration",
        mcNumber=row["マイクロチップ番号"], applicantId=row["申請者"], channel="online", receivedOn=row["受付日"],
        species=SPECIES.get(row["種別"], row["種別"]), name=row["名前"], breed=row.get("品種"), color=row.get("毛色"),
        sex=SEX.get(row["性別"], row["性別"]), birthDate=row["生年月日"], implantedOn=row["装着日"],
        veterinarian=row.get("装着した獣医師"), motherId=row.get("母親"),
        locationMunicipalityId=row["所在地の自治体"], locationAddress=row.get("所在地"))
    if not ok or yde.dry_run:
        return ok, app, "", why
    return settle(yde, app, row["受付日"], fee, row["マイクロチップ番号"])


def transfer(yde, row, fee):
    ok, app, why = yde.action(
        "apply-transfer", target=row["マイクロチップ番号"],
        applicantId=row["新しい所有者"], changeKey=row["変更キー"], channel="online", receivedOn=row["受付日"],
        locationMunicipalityId=row["新しい所在地の自治体"], locationAddress=row.get("新しい所在地"))
    if not ok or yde.dry_run:
        return ok, app, "", why
    return settle(yde, app, row["受付日"], fee, row["マイクロチップ番号"])


def settle(yde, app, date, fee, mc):
    """受付のあと: 支払の記録（クレジットカード）→ 手続の完了。途中で止まったら、申請は残る。"""
    ok, _, why = yde.action("record-payment", target=app, date=date, method="credit", amount=fee, receiptNo="BULK-" + app)
    if not ok:
        return False, app, "", f"受付まで（{app} は支払待ちで残っています）: {why}"
    ok, _, why = yde.action("complete-application", target=app, date=date)
    if not ok:
        return False, app, "", f"支払まで（{app} は支払済みで残っています）: {why}"
    return True, app, yde.change_key(mc), ""


def change(yde, row, fee):
    if row.get("所有者のメールアドレス"):
        return False, None, "", "一括の情報変更では、メールアドレスを変更できません（なりすましの防止）"
    ok, app, why = yde.action(
        "report-change", target=row["マイクロチップ番号"], changeKey=row["変更キー"], channel="online", receivedOn=row["届出日"],
        animalName=row.get("犬猫の名前"), animalColor=row.get("犬猫の毛色"),
        locationMunicipalityId=row.get("所在地の自治体"), locationAddress=row.get("所在地"),
        ownerAddress=row.get("所有者の住所"), ownerMunicipalityId=row.get("所有者の住所の自治体"), ownerPhone=row.get("所有者の電話番号"))
    return ok, app, "", why


KINDS = {
    "register": (register, "FEE-REG-ONLINE", "マイクロチップ情報登録（一括）"),
    "transfer": (transfer, "FEE-TRF-ONLINE", "所有者変更登録（一括）"),
    "change": (change, None, "情報変更（一括）"),
}


def main():
    p = argparse.ArgumentParser(description="一括の手続（CSV）を 1 行ずつ実行し、結果 CSV を書く")
    p.add_argument("kind", choices=KINDS.keys())
    p.add_argument("input")
    p.add_argument("--out")
    p.add_argument("--workspace", default=str(DEFAULT_WS))
    p.add_argument("--dry-run", action="store_true")
    a = p.parse_args()
    handler, fee_id, label = KINDS[a.kind]
    yde = Yde(Path(a.workspace).resolve(), a.dry_run)
    fee = yde.fee(fee_id) if fee_id else None
    source = Path(a.input)
    out = Path(a.out) if a.out else source.with_name(source.stem + "-result.csv")
    with source.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        sys.exit("入力に行がありません")
    done = 0
    with out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(list(rows[0].keys()) + ["結果", "申請番号", "新しい変更キー", "理由"])
        for n, row in enumerate(rows, start=2):
            row = {k: (v or "").strip() for k, v in row.items()}
            try:
                ok, app, key, why = handler(yde, row, fee)
            except KeyError as e:
                ok, app, key, why = False, None, "", f"列 {e} がありません"
            done += ok
            w.writerow(list(row.values()) + ["成功" if ok else "失敗", app or "", key, why])
            print(f"行{n}: {'成功' if ok else '失敗'} {app or ''} {why}")
    print(f"{label}: {len(rows)} 行のうち {done} 行が成功。結果は {out}")
    sys.exit(0 if done == len(rows) else 1)


if __name__ == "__main__":
    main()
