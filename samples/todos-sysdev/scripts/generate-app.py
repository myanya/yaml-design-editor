#!/usr/bin/env python3
"""generate-app.ps1 と同じ再生成を、PowerShell の無い環境（Linux・macOS）で行う。

確定した設計（.yde/ と design/）から、アプリ（app/）と生成記録（design/detail/reports/application-files.json）を作り直す。
Java・SQL・HTML の組み立ては YDE の CLI に任せ、このスクリプトはファイルの差し替えと記録の更新だけを行う。

使い方（リポジトリのルートで）:
  python3 samples/todos-sysdev/scripts/generate-app.py            # 作成・更新・削除の予定を表示する（書かない）
  python3 samples/todos-sysdev/scripts/generate-app.py --apply    # 予定を適用する
  python3 samples/todos-sysdev/scripts/generate-app.py --check    # 差分があれば失敗する（終了コード 1）

手順と安全確認は generate-app.ps1 に合わせている（どちらで作り直しても同じ結果になる）。
GitHub Actions の todos-application が、両方の --check / -Check を push ごとに実行する。
設計を変更したときは、先に `generate samples/todos-sysdev --apply` で確定項目を更新してから実行する。
標準ライブラリだけで動く。
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import uuid

SCRIPTS = os.path.dirname(os.path.abspath(__file__))
REPOSITORY = os.path.abspath(os.path.join(SCRIPTS, "..", "..", ".."))
MANIFEST = "design/detail/reports/application-files.json"
ZONES = ("schemas", "views", "transforms", "reports", "stages")
ASSET_GROUPS = ("gradle-wrapper", "web")


class Stop(Exception):
    """利用者に伝えて止める誤り。"""


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_bytes(path):
    with open(path, "rb") as stream:
        return stream.read()


def design_inputs(root):
    """設計の入力（.yde/ 直下のファイルと定義のフォルダ、design/ 以下）を、ワークスペース相対のパスで返す。"""
    found = []
    definitions = os.path.join(root, ".yde")
    for name in sorted(os.listdir(definitions)):
        if os.path.isfile(os.path.join(definitions, name)):
            found.append(".yde/" + name)
    for top in [os.path.join(definitions, zone) for zone in ZONES] + [os.path.join(root, "design")]:
        for folder, _, names in os.walk(top):
            for name in names:
                found.append(os.path.relpath(os.path.join(folder, name), root).replace(os.sep, "/"))
    return sorted(found)


def application_path(root, relative):
    """生成先を app/ の中に限る。リンクを経由して別の場所を書き換えない。"""
    if not re.fullmatch(r"app/[^\\:]+", relative) or ".." in relative.split("/"):
        raise Stop(f"アプリの外を生成先にはできません: {relative}")
    target = os.path.abspath(os.path.join(root, relative))
    if not target.startswith(os.path.abspath(os.path.join(root, "app")) + os.sep):
        raise Stop(f"アプリの外を生成先にはできません: {relative}")
    entry = target
    while entry and entry != os.path.abspath(root):
        if os.path.islink(entry):
            raise Stop(f"リンクを経由する生成先は使えません: {relative}")
        entry = os.path.dirname(entry)
    return target


class Yde:
    """YDE の CLI を呼び、結果（JSON）を読む。"""

    def __init__(self, stage):
        self.stage = stage
        self.number = 0
        launcher = os.path.join(REPOSITORY, "yde.cmd" if os.name == "nt" else "yde")
        self.command = ["cmd", "/c", launcher] if os.name == "nt" else ["bash", launcher]

    def result(self, *arguments):
        self.number += 1
        output = os.path.join(self.stage, f"result-{self.number}.json")
        done = subprocess.run(self.command + list(arguments) + ["--format", "json", "--output-file", output],
                              cwd=REPOSITORY, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if done.returncode != 0 and not os.path.exists(output):
            sys.stderr.write(done.stdout.decode("utf-8", "replace"))
            raise Stop(f"YDE の処理に失敗しました: {arguments[0]}")
        with open(output, encoding="utf-8") as stream:
            return json.load(stream)


def render(workspace, stage):
    """設計だけを一時領域へ写し、検証・確定項目の最新性を確かめてから、アプリの出力を全部作る。"""
    hashes = {}
    for relative in design_inputs(workspace):
        data = read_bytes(os.path.join(workspace, relative))
        hashes[relative] = sha256(data)
        destination = os.path.join(stage, relative)
        os.makedirs(os.path.dirname(destination), exist_ok=True)
        with open(destination, "wb") as stream:
            stream.write(data)
    yde = Yde(stage)
    validation = yde.result("validate", stage)
    if validation.get("errors") != 0 or validation.get("schemaErrors"):
        raise Stop("設計の検証で誤りが見つかりました。先に設計を修正してください")
    if not yde.result("generate", stage, "--check").get("upToDate"):
        raise Stop("設計の確定項目を再生成してから実行してください（generate samples/todos-sysdev --apply）")
    reports = yde.result("report", stage)
    if reports.get("errors"):
        raise Stop("レポート定義に誤りがあります: " + " / ".join(reports["errors"]))
    application_reports = [report for report in reports["reports"] if report["id"].startswith("app-")]
    if not application_reports:
        raise Stop("アプリの出力定義がありません")
    plan = yde.result("report", stage, "application-outputs")
    if plan.get("missing"):
        raise Stop("アプリの出力単位を読み取れません")
    with open(os.path.join(stage, plan["written"]), encoding="utf-8") as stream:
        rows = json.load(stream)["outputs"]
    if not rows:
        raise Stop("確定したアプリの出力単位がありません")
    planned = {row["report"] for row in rows}
    known = {report["id"] for report in reports["reports"]}
    for report in application_reports:
        # 可変個数のレポートは、対象がゼロでも定義を残せる
        if not report["params"] and report["id"] not in planned:
            raise Stop(f"出力計画に登録されていないレポートです: {report['id']}")
    for row in rows:
        if row["report"] not in known:
            raise Stop(f"出力計画のレポートが見つかりません: {row['report']}")
        if "destination" in row:
            application_path(stage, row["destination"])
    batch = yde.result("report", stage, "--batch", os.path.join(stage, plan["written"]))
    if len(batch["outputs"]) != len(rows):
        raise Stop("出力計画と実行結果の件数が一致しません")
    files = {}
    for output in batch["outputs"]:
        relative = output["written"]
        target = application_path(stage, relative)
        if relative in files:
            raise Stop(f"生成先が重複しています: {relative}")
        files[relative] = read_bytes(target)
    # 固定資源（Gradle Wrapper・画面の JavaScript）は、ワークスペースの assets/ から写す
    for group in ASSET_GROUPS:
        directory = os.path.join(workspace, "assets", group)
        with open(os.path.join(directory, "files.json"), encoding="utf-8") as stream:
            assets = json.load(stream)["files"]
        for asset in assets:
            relative = "app/" + asset["path"]
            application_path(stage, relative)
            data = read_bytes(os.path.join(directory, asset["path"]))
            if sha256(data) != asset["sha256"]:
                raise Stop(f"固定資源が変わっています: {group}/{asset['path']}")
            if relative in files:
                if sha256(files[relative]) != asset["sha256"]:
                    raise Stop(f"設定の Gradle 版と Wrapper 資源が一致しません: {asset['path']}")
            else:
                files[relative] = data
    return files, hashes


def file_plan(workspace, files):
    """いまの app/ と生成記録を、作り直した内容と比べる。手で直したファイルや、記録の無いファイルとの競合は止める。"""
    manifest_path = os.path.join(workspace, MANIFEST)
    before_manifest = read_bytes(manifest_path) if os.path.isfile(manifest_path) else None
    previous = {}
    if before_manifest is not None:
        manifest = json.loads(before_manifest.decode("utf-8"))
        if manifest.get("format") != 1:
            raise Stop("アプリ生成記録の形式が未対応です")
        for entry in manifest["files"]:
            application_path(workspace, entry["path"])
            if entry["path"] in previous or not re.fullmatch(r"[a-f0-9]{64}", entry["sha256"]):
                raise Stop(f"アプリ生成記録が不正です: {entry['path']}")
            previous[entry["path"]] = entry["sha256"]
    operations = []
    record = []
    # 記録の並びは、パスの大文字と小文字を区別しない順（generate-app.ps1 の Sort-Object と同じ）
    for relative in sorted(set(files) | set(previous), key=lambda path: (path.lower(), path)):
        target = application_path(workspace, relative)
        if os.path.isdir(target):
            raise Stop(f"生成先にフォルダがあります: {relative}")
        before = read_bytes(target) if os.path.isfile(target) else None
        before_hash = sha256(before) if before is not None else None
        after = files.get(relative)
        after_hash = sha256(after) if after is not None else None
        if relative in previous:
            if before_hash != previous[relative]:
                raise Stop(f"生成後に変更または削除されています: {relative}。変更を設計の入力か生成規則へ戻してください")
        elif before is not None and before_hash != after_hash:
            raise Stop(f"生成記録のないファイルと競合しています: {relative}")
        if after is not None:
            record.append({"path": relative, "sha256": after_hash})
        if before_hash != after_hash:
            kind = "create" if before is None else "delete" if after is None else "update"
            operations.append({"kind": kind, "path": relative, "target": target, "after": after})
    # 記録の形は generate-app.ps1 と同じ（2 字下げ・LF・末尾に改行）
    manifest_bytes = (json.dumps({"format": 1, "files": record}, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if before_manifest is None or sha256(manifest_bytes) != sha256(before_manifest):
        operations.append({"kind": "record", "path": MANIFEST, "target": manifest_path, "after": manifest_bytes})
    return operations


def write_bytes(target, data):
    if data is None:
        os.remove(target)
        return
    os.makedirs(os.path.dirname(target), exist_ok=True)
    # 同じ場所の一時ファイルから置き換え、書込失敗で元のファイルを途中まで切らない
    temporary = f"{target}.yde-{uuid.uuid4().hex}.tmp"
    try:
        with open(temporary, "wb") as stream:
            stream.write(data)
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.remove(temporary)


def main():
    parser = argparse.ArgumentParser(description="確定した設計から、アプリ（app/）と生成記録を作り直す")
    parser.add_argument("--workspace", default=os.path.dirname(SCRIPTS), help="設計ワークスペース（既定は samples/todos-sysdev）")
    parser.add_argument("--apply", action="store_true", help="予定を適用する")
    parser.add_argument("--check", action="store_true", help="差分があれば失敗する")
    options = parser.parse_args()
    workspace = os.path.abspath(options.workspace)
    stage = os.path.join(REPOSITORY, "scratchpad", "application-generation", uuid.uuid4().hex)
    try:
        if options.apply and options.check:
            raise Stop("--apply と --check は同時に指定できません")
        if not os.path.isfile(os.path.join(workspace, ".yde", "workspace.yaml")):
            raise Stop("設計ワークスペースが見つかりません")
        os.makedirs(stage)
        files, hashes = render(workspace, stage)
        operations = file_plan(workspace, files)
        print(json.dumps({"files": len(files),
                          "operations": [{"kind": operation["kind"], "path": operation["path"]} for operation in operations],
                          "apply": options.apply}, indent=2, ensure_ascii=False))
        if options.check and operations:
            raise Stop("アプリの生成物が最新ではありません。生成予定を確認して --apply で作り直してください")
        if options.apply:
            current = design_inputs(workspace)
            if len(current) != len(hashes):
                raise Stop("生成中に設計のファイル一覧が変わりました。再実行してください")
            for relative in current:
                if sha256(read_bytes(os.path.join(workspace, relative))) != hashes.get(relative):
                    raise Stop(f"生成中に設計が変わりました: {relative}")
            for operation in operations:
                write_bytes(operation["target"], operation["after"])
    except Stop as stop:
        print(str(stop), file=sys.stderr)
        return 1
    finally:
        shutil.rmtree(stage, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
