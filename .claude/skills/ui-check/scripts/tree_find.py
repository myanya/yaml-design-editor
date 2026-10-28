"""画面の木（MCP の get_semantic_tree の結果を保存したファイル）から、語を含む部品を探して 1 行ずつ出す。

木が大きいと、結果は画面に返らずファイルに保存される。そのファイルを渡す。
出すのは id・役割・文字（text / editableText / contentDescription）・状態・位置・操作。位置が 0 の部品は画面の外（スクロールで隠れている）。
語を 1 つも渡さなければ、文字を持つ部品をすべて出す。

使い方: py -3 -X utf8 -I .claude/skills/ui-check/scripts/tree_find.py <保存したファイル> [<語> ...]
"""
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as f:
        data = json.loads(f.read())
    # MCP の結果の形（[{"type": "text", "text": "<木の JSON>"}]）で保存されていることもある
    if isinstance(data, list) and data and isinstance(data[0], dict) and data[0].get("type") == "text":
        return json.loads(data[0]["text"])
    return data


def nodes(tree):
    stack = list(tree) if isinstance(tree, list) else [tree]
    while stack:
        node = stack.pop(0)
        if not isinstance(node, dict):
            continue
        yield node
        stack[0:0] = node.get("children", [])


def describe(node):
    texts = [node.get(k) for k in ("text", "editableText", "contentDescription") if node.get(k)]
    states = [f"{k}={node[k]}" for k in ("selected", "toggleableState", "stateDescription", "enabled") if k in node]
    b = node.get("bounds", {})
    where = "画面の外" if b.get("width", 0) == 0 and b.get("height", 0) == 0 else f"({b.get('x')},{b.get('y')} {b.get('width')}x{b.get('height')})"
    role = f"[{node['role']}] " if node.get("role") else ""
    actions = ",".join(node.get("actions", []))
    return f"id={node.get('id')} {role}{' / '.join(texts)} {' '.join(states)} {where} {actions}".rstrip()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    words = sys.argv[2:]
    hits = 0
    for node in nodes(load(sys.argv[1])):
        text = " ".join(str(node.get(k, "")) for k in ("text", "editableText", "contentDescription"))
        if not text.strip():
            continue
        if words and not any(w in text for w in words):
            continue
        print(describe(node))
        hits += 1
    if hits == 0:
        print("見つかりません: " + " / ".join(words))


main()
