#!/usr/bin/env python3
# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Every tool's parametersJson must be a JSON Schema object with type=object,
# otherwise OpenAI/Anthropic/Gemini reject the whole request ("Invalid schema
# for function ... got 'type: null'"). That exact bug shipped once; this guard
# keeps it from shipping again.
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PATTERN = re.compile(r'parametersJson\s*=\s*(?:"""(.*?)"""|"((?:[^"\\]|\\.)*)")', re.S)

def validate_doc_counts(tool_count, failures):
    """Keep README/docs/site tool counts in sync with the registry.

    The 48-vs-52 drift shipped once because nothing checked the prose. These
    patterns cover every place the count is stated.
    """
    checks = [
        ("README.md", r"(\d+)\s*个内置工具"),
        ("docs/README.en.md", r"(\d+) built-in tools"),
        ("docs/HOW_IT_WORKS.md", r"(\d+) built-in device tools"),
        ("docs/HOW_IT_WORKS.md", r"\| Built-in tools \| (\d+) \|"),
        ("docs/HOW_IT_WORKS.md", r"The (\d+) tools at a glance"),
        ("site/index.html", r"(\d+) built-in device tools"),
        ("site/index.html", r"(\d+) real device tools"),
        ("site/index.html", r"(\d+) device tools"),
        ("site/index.html", r"<b>(\d+)</b><span>built-in device tools</span>"),
    ]
    for relative, pattern in checks:
        path = ROOT / relative
        if not path.exists():
            continue
        for match in re.finditer(pattern, path.read_text()):
            if int(match.group(1)) != tool_count:
                failures.append(
                    (path, f"states {match.group(1)} tools, registry has {tool_count}"),
                )


def count_tool_providers():
    """Real registry size: one @IntoSet provider per statically advertised tool.

    Counting schema literals drifted (a composite skill template counts as a
    schema but registers dynamically), so the provider set is the source of
    truth for the prose checks.
    """
    roots = [ROOT / "tools", ROOT / "device", ROOT / "app"]
    count = 0
    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob("*.kt"):
            if "build" in path.parts:
                continue
            count += path.read_text().count("@IntoSet")
    return count


def main() -> int:
    checked = 0
    failures = []
    for path in sorted((ROOT / "tools/src/main/java").rglob("*.kt")):
        for match in PATTERN.finditer(path.read_text()):
            raw = match.group(1) if match.group(1) is not None else match.group(2)
            checked += 1
            try:
                schema = json.loads(raw)
            except Exception as error:  # noqa: BLE001
                failures.append((path, f"invalid JSON: {error}"))
                continue
            if not isinstance(schema, dict) or schema.get("type") != "object":
                failures.append((path, "missing \"type\": \"object\""))
    tool_count = count_tool_providers()
    if not failures:
        validate_doc_counts(tool_count, failures)
    if failures:
        for path, why in failures:
            print(f"{path.relative_to(ROOT)}: {why}")
        print(f"{len(failures)} failure(s); registry has {tool_count} tools, {checked} schemas.")
        return 1
    print(
        f"All {checked} tool schemas are valid object schemas; "
        f"registry has {tool_count} tools and docs match.",
    )
    return 0

if __name__ == "__main__":
    sys.exit(main())
