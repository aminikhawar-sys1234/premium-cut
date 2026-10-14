#!/usr/bin/env python3
"""Surface failing JUnit test cases as GitHub Actions annotations.

Usage:
    report_test_failures.py <test-results-dir> [<test-results-dir> ...]

Gradle writes one ``TEST-*.xml`` file per test class under ``app/build/test-results``.
Without this step a red "Run Unit Tests" only says "Process completed with exit code 1" and
the actual assertion is buried in a multi-megabyte log or a zipped HTML report. This script:

* emits an ``::error`` annotation per failed / errored test case, attached to the test
  source file and line when it can be found in the stack trace (shown inline on the PR);
* writes a Markdown table of every failure to ``$GITHUB_STEP_SUMMARY``;
* prints the full failure messages and the top of each stack trace to the log.

GitHub keeps at most 10 error annotations per step, so the first ``MAX_INDIVIDUAL``
failures get their own annotation and the remainder are folded into one final annotation.

The script never fails the build itself (exit code 0): the test step already did that.
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

MAX_INDIVIDUAL = 9  # + 1 combined annotation = GitHub's per-step limit of 10
MAX_MESSAGE_CHARS = 1500
MAX_STACK_LINES = 12


@dataclass
class Failure:
    classname: str
    name: str
    kind: str  # "failure" or "error"
    message: str
    details: str
    file: str | None
    line: int | None

    @property
    def title(self) -> str:
        simple = self.classname.rsplit(".", 1)[-1]
        return f"{simple}.{self.name}"


def _escape_data(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def _escape_property(value: str) -> str:
    return _escape_data(value).replace(":", "%3A").replace(",", "%2C")


def _find_source(classname: str, repo_root: Path) -> Path | None:
    """Map ``com.example.FooTest`` (or ``com.example.FooTest$Inner``) to its source file."""
    outer = classname.split("$", 1)[0]
    relative = outer.replace(".", "/")
    for source_set in ("test", "androidTest", "testDebug", "testRelease"):
        for ext in (".kt", ".java"):
            for module in repo_root.glob(f"*/src/{source_set}/*/{relative}{ext}"):
                return module
    return None


def _line_from_stack(details: str, classname: str) -> int | None:
    """First stack frame that points into the test class itself."""
    outer = classname.split("$", 1)[0]
    simple = outer.rsplit(".", 1)[-1]
    pattern = re.compile(
        r"at " + re.escape(outer) + r"[.$][^(]*\(" + re.escape(simple) + r"\.(?:kt|java):(\d+)\)"
    )
    match = pattern.search(details)
    return int(match.group(1)) if match else None


def _trim_stack(details: str, message: str = "") -> str:
    lines = [ln for ln in details.strip().splitlines() if ln.strip()]
    # The first line of a JUnit stack trace repeats the message; do not print it twice.
    if lines and message and lines[0].strip() == message.strip().splitlines()[0].strip():
        lines = lines[1:]
    kept = []
    for ln in lines:
        stripped = ln.strip()
        # Drop JUnit / Gradle / reflection plumbing: it never explains a failure.
        if stripped.startswith("at ") and re.match(
            r"at (org\.junit|org\.gradle|java\.base/|jdk\.internal|sun\.reflect|"
            r"java\.lang\.reflect|worker\.org\.gradle|org\.robolectric\.internal|"
            r"kotlin\.coroutines|kotlinx\.coroutines)",
            stripped,
        ):
            continue
        kept.append(ln.rstrip())
        if len(kept) >= MAX_STACK_LINES:
            break
    return "\n".join(kept)


def collect(results_dirs: list[Path], repo_root: Path) -> tuple[list[Failure], int, int]:
    failures: list[Failure] = []
    total = 0
    files = 0
    seen: set[tuple[str, str, str]] = set()
    for results_dir in results_dirs:
        for xml_file in sorted(results_dir.rglob("TEST-*.xml")):
            files += 1
            try:
                root = ET.parse(xml_file).getroot()
            except ET.ParseError as exc:
                print(f"warning: cannot parse {xml_file}: {exc}")
                continue
            suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
            for suite in suites:
                # Variant name (testDebugUnitTest / testReleaseUnitTest) keeps duplicates apart
                variant = xml_file.parent.name
                for case in suite.iter("testcase"):
                    total += 1
                    for kind in ("failure", "error"):
                        node = case.find(kind)
                        if node is None:
                            continue
                        classname = case.get("classname") or suite.get("name") or "?"
                        name = case.get("name") or "?"
                        key = (classname, name, (node.get("message") or "")[:200])
                        if key in seen:
                            continue  # identical failure in debug and release variants
                        seen.add(key)
                        details = (node.text or "").strip()
                        message = (node.get("message") or "").strip()
                        if not message:
                            message = details.splitlines()[0] if details else kind
                        source = _find_source(classname, repo_root)
                        failures.append(
                            Failure(
                                classname=classname,
                                name=f"{name} [{variant}]" if variant.startswith("test") else name,
                                kind=kind,
                                message=message,
                                details=details,
                                file=str(source.relative_to(repo_root)) if source else None,
                                line=_line_from_stack(details, classname),
                            )
                        )
    return failures, total, files


def annotate(failure: Failure) -> None:
    props = [f"title={_escape_property(failure.title)}"]
    if failure.file:
        props.insert(0, f"file={_escape_property(failure.file)}")
        if failure.line:
            props.insert(1, f"line={failure.line}")
    body = failure.message[:MAX_MESSAGE_CHARS]
    stack = _trim_stack(failure.details, failure.message)
    if stack:
        body += "\n\n" + stack
    print(f"::error {','.join(props)}::{_escape_data(body)}")


def write_summary(failures: list[Failure], total: int) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    with open(summary_path, "a", encoding="utf-8") as out:
        out.write(f"### Unit test failures: {len(failures)} of {total} test cases\n\n")
        out.write("| Test | Location | Message |\n|---|---|---|\n")
        for f in failures:
            location = f"`{f.file}:{f.line}`" if f.file and f.line else (f"`{f.file}`" if f.file else "")
            message = f.message.replace("|", "\\|").replace("\n", "<br>")[:500]
            out.write(f"| `{f.classname}.{f.name}` | {location} | {message} |\n")
        out.write("\n")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 0
    repo_root = Path(os.environ.get("GITHUB_WORKSPACE", ".")).resolve()
    dirs = [Path(a) for a in argv[1:] if Path(a).is_dir()]
    if not dirs:
        print("No test results directory found - the tests did not run (compile or setup failure?).")
        return 0

    failures, total, files = collect(dirs, repo_root)
    print(f"Parsed {files} result file(s), {total} test case(s), {len(failures)} failing.")
    if not failures:
        return 0

    for f in failures:
        print("=" * 100)
        print(f"{f.kind.upper()}: {f.classname}.{f.name}")
        if f.file:
            print(f"  at {f.file}:{f.line or '?'}")
        print(f.message)
        stack = _trim_stack(f.details, f.message)
        if stack:
            print(stack)

    for f in failures[:MAX_INDIVIDUAL]:
        annotate(f)
    rest = failures[MAX_INDIVIDUAL:]
    if rest:
        lines = [f"{len(rest)} more failing test(s):"]
        lines += [f"- {f.classname}.{f.name}: {f.message.splitlines()[0][:200] if f.message else ''}" for f in rest]
        print(f"::error title=More unit test failures::{_escape_data(chr(10).join(lines)[:8000])}")

    write_summary(failures, total)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
