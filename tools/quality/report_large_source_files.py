#!/usr/bin/env python3
"""Report authored source files that exceed a line-count threshold."""

from __future__ import annotations

import argparse
from pathlib import Path

SOURCE_SUFFIXES = {".kt", ".java", ".php", ".py", ".sh", ".js", ".ts"}
EXCLUDED_PARTS = {
    ".git",
    ".gradle",
    ".idea",
    "build",
    "dist",
    "generated",
    "node_modules",
    "vendor",
}


def source_files(root: Path):
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in SOURCE_SUFFIXES:
            continue
        if any(part in EXCLUDED_PARTS for part in path.parts):
            continue
        yield path


def line_count(path: Path) -> int:
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        return sum(1 for _ in handle)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--threshold", type=int, default=350)
    parser.add_argument("--fail", action="store_true")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    oversized = sorted(
        (
            (line_count(path), path.relative_to(root).as_posix())
            for path in source_files(root)
        ),
        reverse=True,
    )
    oversized = [item for item in oversized if item[0] > args.threshold]

    if not oversized:
        print(f"No authored source files exceed {args.threshold} lines.")
        return 0

    print(f"Authored source files over {args.threshold} lines:")
    for count, path in oversized:
        print(f"{count:5d}  {path}")
    return 1 if args.fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
