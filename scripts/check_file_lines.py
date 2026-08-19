#!/usr/bin/env python3
"""检查暂存源码文件的行数，并对历史超限文件采用渐进式门禁。"""

from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Iterable


WARNING_LINES = 1500
MAX_LINES = 2000

SOURCE_SUFFIXES = {
    ".gradle",
    ".groovy",
    ".java",
    ".js",
    ".json",
    ".jsx",
    ".kt",
    ".kts",
    ".properties",
    ".py",
    ".sh",
    ".ts",
    ".tsx",
    ".xml",
    ".yaml",
    ".yml",
}

EXCLUDED_PARTS = {
    ".git",
    ".gradle",
    "build",
    "generated",
    "node_modules",
    "vendor",
}


@dataclass(frozen=True)
class LimitDecision:
    warn: bool
    block: bool
    reason: str | None = None


def evaluate_limit(current_lines: int, baseline_lines: int | None) -> LimitDecision:
    """根据当前暂存行数和 HEAD 基线决定是否告警或阻断。"""
    warn = current_lines >= WARNING_LINES
    if current_lines <= MAX_LINES:
        return LimitDecision(warn=warn, block=False)

    if baseline_lines is None:
        return LimitDecision(warn=warn, block=True, reason="新增文件超过 2000 行")

    if baseline_lines <= MAX_LINES:
        return LimitDecision(warn=warn, block=True, reason="本次修改首次超过 2000 行")

    if current_lines > baseline_lines:
        return LimitDecision(
            warn=warn,
            block=True,
            reason=f"历史超限文件继续增长（HEAD {baseline_lines} 行）",
        )

    return LimitDecision(warn=warn, block=False)


def normalize_path(raw_path: str) -> str:
    return raw_path.replace("\\", "/").removeprefix("./")


def is_maintainable_source(path: str) -> bool:
    normalized = normalize_path(path)
    parts = PurePosixPath(normalized).parts
    if any(part in EXCLUDED_PARTS for part in parts):
        return False
    if len(parts) >= 2 and parts[0] == "app" and parts[1] == "schemas":
        return False
    return PurePosixPath(normalized).suffix.lower() in SOURCE_SUFFIXES


def run_git(*args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def read_git_blobs(specs: Iterable[str]) -> dict[str, bytes | None]:
    """用单个 cat-file 进程批量读取 blob，避免 Windows 下反复启动 Git。"""
    ordered_specs = list(dict.fromkeys(specs))
    if not ordered_specs:
        return {}

    request = "".join(f"{spec}\n" for spec in ordered_specs).encode("utf-8")
    result = subprocess.run(
        ["git", "cat-file", "--batch"],
        input=request,
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"无法批量读取 Git blob: {detail}")

    output = result.stdout
    offset = 0
    blobs: dict[str, bytes | None] = {}
    for spec in ordered_specs:
        header_end = output.find(b"\n", offset)
        if header_end < 0:
            raise RuntimeError(f"Git cat-file 返回不完整: {spec}")
        header = output[offset:header_end]
        offset = header_end + 1

        if header.endswith(b" missing"):
            blobs[spec] = None
            continue

        fields = header.rsplit(b" ", 2)
        if len(fields) != 3 or fields[1] != b"blob":
            raise RuntimeError(f"Git cat-file 返回了非 blob 对象: {spec}")
        try:
            size = int(fields[2])
        except ValueError as error:
            raise RuntimeError(f"Git cat-file 返回了无效长度: {spec}") from error

        payload_end = offset + size
        if payload_end >= len(output) or output[payload_end : payload_end + 1] != b"\n":
            raise RuntimeError(f"Git cat-file 返回不完整: {spec}")
        blobs[spec] = output[offset:payload_end]
        offset = payload_end + 1

    return blobs


def count_utf8_lines(content: bytes, path: str) -> int:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError(f"{path} 不是有效的 UTF-8 文件") from error
    return len(text.splitlines())


def staged_rename_sources() -> dict[str, str]:
    result = run_git("diff", "--cached", "--name-status", "-z", "-M", "--diff-filter=AMR")
    if result.returncode != 0:
        return {}

    fields = result.stdout.decode("utf-8").split("\0")
    rename_sources: dict[str, str] = {}
    index = 0
    while index < len(fields) and fields[index]:
        status = fields[index]
        index += 1
        if status.startswith("R"):
            if index + 1 >= len(fields):
                break
            old_path = normalize_path(fields[index])
            new_path = normalize_path(fields[index + 1])
            rename_sources[new_path] = old_path
            index += 2
        else:
            index += 1
    return rename_sources


def unique_source_paths(paths: Iterable[str]) -> list[str]:
    normalized = {normalize_path(path) for path in paths}
    return sorted(path for path in normalized if is_maintainable_source(path))


def configure_utf8_console() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="replace")


def main(argv: list[str]) -> int:
    configure_utf8_console()
    rename_sources = staged_rename_sources()
    warnings: list[str] = []
    errors: list[str] = []

    paths = unique_source_paths(argv)
    try:
        staged_blobs = read_git_blobs(f":{path}" for path in paths)
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    line_counts: dict[str, int] = {}
    for path in paths:
        staged_blob = staged_blobs.get(f":{path}")
        if staged_blob is None:
            continue
        try:
            line_counts[path] = count_utf8_lines(staged_blob, path)
        except ValueError as error:
            errors.append(str(error))

    # 2000 行以内不涉及历史豁免，无需额外读取 HEAD，可显著缩短全量检查耗时。
    baseline_paths = {
        path: rename_sources.get(path, path)
        for path, current_lines in line_counts.items()
        if current_lines > MAX_LINES
    }
    try:
        baseline_blobs = read_git_blobs(
            f"HEAD:{baseline_path}" for baseline_path in baseline_paths.values()
        )
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    for path, current_lines in line_counts.items():
        baseline_path = baseline_paths.get(path)
        baseline_blob = (
            baseline_blobs.get(f"HEAD:{baseline_path}")
            if baseline_path is not None
            else None
        )
        try:
            baseline_lines = (
                count_utf8_lines(baseline_blob, baseline_path)
                if baseline_blob is not None and baseline_path is not None
                else None
            )
        except ValueError as error:
            errors.append(str(error))
            continue

        decision = evaluate_limit(current_lines, baseline_lines)
        if decision.warn:
            warnings.append(f"{path}: {current_lines} 行")
        if decision.block:
            errors.append(f"{path}: {current_lines} 行，{decision.reason}")

    if warnings:
        print("WARNING: 以下文件达到 1500 行，请考虑按职责拆分：")
        for warning in warnings:
            print(f"  - {warning}")

    if errors:
        print("ERROR: 源文件行数门禁未通过：", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        print("规则：新文件或首次跨线不得超过 2000 行；历史超限文件不得继续增长。", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
