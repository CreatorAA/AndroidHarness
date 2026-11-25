"""Count the text of an AndroidHarness Responses body, not HTTP headers or API billing.

Usage: python count_context.py first-turn-request.json  (requires tiktoken)
"""
import argparse
import json
import re
from pathlib import Path

import tiktoken


def compact(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def count_body(body, repo, snapshot=None):
    try:
        enc = tiktoken.encoding_for_model(body["model"])
        mapped = True
    except KeyError:
        enc = tiktoken.get_encoding("o200k_base")
        mapped = False
    count = lambda s: len(enc.encode_ordinary(s))
    instructions, tools, inputs = body["instructions"], body.get("tools", []), body["input"]
    rows = {
        "instructions_plain_text": count(instructions),
        "tools_compact_json": count(compact(tools)) if tools else 0,
        "input_compact_json": count(compact(inputs)),
    }
    result = {
        "model": body["model"], "encoding": enc.name, "tiktoken_model_mapped": mapped,
        "components": rows, "local_component_sum": sum(rows.values()), "tool_count": len(tools),
        "tool_tokens_individual": {
            tool.get("name", tool["type"]): count(compact(tool)) for tool in tools
        },
    }
    root = repo / "app/src/main/java/com/androidharness/app"
    if snapshot is not None:
        rich = (snapshot / "rich-rules.txt").read_text(encoding="utf8")
        guides = {key: (snapshot / filename).read_text(encoding="utf8") for key, filename in (
            ("basics+chart", "visual-guide-chart.txt"),
            ("basics+interactive", "visual-guide-interactive.txt"),
            ("basics+chart+interactive", "visual-guide-chart-interactive.txt"),
        )}
        guides["basics"] = guides["basics+chart"].split("\n\n## Chart", 1)[0]
    elif root.is_dir():
        rich_src = (root / "core/response/RichResponse.kt").read_text(encoding="utf8")
        rich = rich_src.split('const val INSTRUCTIONS = """', 1)[1].split('"""', 1)[0]
        guide = (root / "tools/VisualGuideTool.kt").read_text(encoding="utf8")
        mods = dict(re.findall(r'private const val (\w+) = """(.*?)"""', guide, re.S))
        groups = [("BASICS",), ("BASICS", "CHART"), ("BASICS", "INTERACTIVE"),
                  ("BASICS", "CHART", "INTERACTIVE")]
        guides = {"+".join(names).lower(): "\n\n".join(
            f"## {name.title()}\n{mods[name].strip()}" for name in names) for names in groups}
    else:
        return result

    if rich.strip() in instructions:
        parts = {"rich_response_rules_and_component_catalog": count(rich)}
        a, b, c = (rich.find(term) for term in (
            "Choose the simplest presentation", "Catalog examples", "CUSTOM VISUALS"))
        if 0 <= a < b < c:
            parts["presentation_selection_subset"] = count(rich[a:b])
            parts["native_component_catalog_and_usage_subset"] = count(rich[b:c])
        result["inside_instructions"] = parts
    result["on_demand_tool_result_text"] = {key: count(text) for key, text in guides.items()}
    return result


if __name__ == "__main__":
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("body", type=Path)
    args.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args.add_argument("--snapshot", action="store_true",
                      help="Measure the archived rich rules and visual guides next to this script")
    p = args.parse_args()
    print(json.dumps(count_body(json.loads(p.body.read_text(encoding="utf-8-sig")), p.repo,
                                Path(__file__).resolve().parent if p.snapshot else None),
                     ensure_ascii=False, indent=2))
