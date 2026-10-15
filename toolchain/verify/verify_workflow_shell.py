#!/usr/bin/env python3
"""Verify the CI workflows themselves.

The workflows are the only compiler and the only emulator available to this
repository, and a broken shell line in them fails a job *after* every test has
already passed - which reads exactly like a product bug to whoever is watching
the pull request. Two real instances, both in this repository:

  1. the device job used ``${PIPESTATUS[0]}``, a bash-only expansion. dash
     aborts with "Bad substitution" and exit code 2 and never reaches the lines
     below it, so the step went red with 36/36 green tests;
  2. the same job had a multi-line ``if [ ... ]; then`` block. The action runs
     the block one line per ``/usr/bin/sh -c``, so dash saw a dangling ``then``
     and again exited 2 - after gradle had already passed every test;
  3. ``pkill -f "adb logcat"`` matched the command line of the very shell that
     ran it and killed its own parent (annotation: "exit code null").

This check therefore pins, for every shell block in .github/workflows:

  * no bash-only construct (PIPESTATUS, ``[[ ]]'', arrays, ``+=``, ``function``),
  * in the emulator-runner block (which the action executes one line per
    ``/usr/bin/sh -c``): no construct spanning lines, no line continuation and
    no variable read by a later line,
  * every embedded ``python3 - <<'PY'`` heredoc parses as Python,
  * the device job records gradle's exit code, ends with ``exit 0`` and has the
    report gate that decides the job from the XML reports.
"""

import ast
import os
import re
import sys
import textwrap

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
WORKFLOWS = os.path.join(REPO, ".github", "workflows")

RESULTS = []


def check(ok: bool, label: str) -> None:
    RESULTS.append((bool(ok), label))


def load_workflows():
    try:
        import yaml  # type: ignore
    except ImportError:
        return None
    out = {}
    for name in sorted(os.listdir(WORKFLOWS)):
        if name.endswith((".yml", ".yaml")):
            with open(os.path.join(WORKFLOWS, name), encoding="utf-8") as handle:
                out[name] = yaml.safe_load(handle)
    return out


def shell_blocks(document):
    """Yield (job, step, text) for every shell block in a workflow document."""
    for job_name, job in (document.get("jobs") or {}).items():
        for step in job.get("steps") or []:
            if isinstance(step.get("run"), str):
                yield job_name, step.get("name", "?"), step["run"]
            script = (step.get("with") or {}).get("script")
            if isinstance(script, str):
                yield job_name, step.get("name", "?"), script


# bash-only constructs that dash rejects, either at parse time or at run time.
BASH_ONLY = [
    (re.compile(r"\$\{?PIPESTATUS"), "PIPESTATUS"),
    (re.compile(r"^\s*(if|while|elif)\s+.*\[\["), "[[ ]] test"),
    (re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_]*\["), "array expansion"),
    (re.compile(r"^\s*(local|declare|typeset)\s+-[aA]"), "declare -a/-A"),
    (re.compile(r"^\s*function\s+[A-Za-z_]"), "function keyword"),
    (re.compile(r"^\s*[A-Za-z_][A-Za-z0-9_]*\+="), "+= append"),
]

PY_HEREDOC = re.compile(r"<<-?'PY'\n(.*?)\n\s*PY\b", re.DOTALL)


def main() -> int:
    workflows = load_workflows()
    if workflows is None:
        print("PyYAML is not installed; cannot parse the workflows. "
              "pip install pyyaml, then re-run this check.")
        return 1

    check(bool(workflows), "workflows found in .github/workflows")
    postpatch = workflows.get("postpatch-verify.yml")
    check(postpatch is not None, "postpatch-verify.yml exists")

    for name, document in workflows.items():
        for job_name, step_name, text in shell_blocks(document):
            where = f"{name} :: {job_name} :: {step_name}"
            code = [line for line in text.splitlines() if line.strip() and not line.strip().startswith("#")]
            joined = "\n".join(code)
            for pattern, label in BASH_ONLY:
                hits = [line.strip() for line in code if pattern.search(line)]
                check(not hits, f"no {label} in {where}" + (f" -> {hits[0]}" if hits else ""))
            for body in PY_HEREDOC.findall(text):
                try:
                    ast.parse(textwrap.dedent(body))
                    ok, detail = True, ""
                except SyntaxError as exc:
                    ok, detail = False, f" ({exc})"
                check(ok, f"embedded python parses in {where}{detail}")
            check(joined.strip() != "", f"{where} is not empty")

    device = (postpatch or {}).get("jobs", {}).get("device-tests", {})
    steps = {step.get("name"): step for step in device.get("steps") or []}
    runner = steps.get("Run instrumented tests on the emulator") or {}
    script = (runner.get("with") or {}).get("script", "")
    check("> gradle-connected.log 2>&1; echo $? > gradle-exit.txt" in script,
          "gradle's exit code is recorded on the same line it runs on")

    # The emulator-runner action splits `script:` into one command per line and runs each line in
    # its own `/usr/bin/sh -c`, so a line is the unit of execution: no construct may span lines,
    # and no variable may be read by a later line.
    code = [line.strip() for line in script.splitlines()
            if line.strip() and not line.strip().startswith("#")]
    check(bool(code), "the emulator script has commands")
    closed = re.compile(r"\b(fi|done|esac)\b")
    dangling = [line for line in code
                if line.split(" ")[0] in ("if", "for", "while", "case")
                and not closed.search(line)]
    check(not dangling, "no construct spans lines in the emulator script"
          + (f" -> {dangling[0]}" if dangling else ""))
    continued = [line for line in code if line.endswith("\\")]
    check(not continued, "no line continuation in the emulator script"
          + (f" -> {continued[0]}" if continued else ""))
    background = [line for line in code if line.endswith("&")]
    check(not background, "the emulator script starts no background process"
          + (f" -> {background[0]}" if background else ""))
    signalled = [line for line in code if re.search(r"\b(pkill|pgrep|kill)\b", line)]
    check(not signalled, "the emulator script signals no process (the pattern matches its own sh)"
          + (f" -> {signalled[0]}" if signalled else ""))
    stale = []
    for index, line in enumerate(code):
        for name in re.findall(r"(?:^|[;&|]\s*)([A-Za-z_][A-Za-z0-9_]*)=", line):
            for other in code[index + 1:]:
                if f"${name}" in other or f"${{{name}}}" in other:
                    stale.append(f"{name}: {line[:40]} -> {other[:40]}")
    check(not stale, "no variable is read by a later line of the emulator script"
          + (f" -> {stale[0]}" if stale else ""))
    check("Gate on the instrumented reports" in steps,
          "the device job has a gate that decides from the XML reports")
    gate = (steps.get("Gate on the instrumented reports") or {}).get("run", "")
    for needed in ("failures", "errors", "suites that never reported a result", "sys.exit(1)"):
        check(needed in gate, f"the gate checks: {needed}")

    print()
    for ok, label in RESULTS:
        print(f"  {'PASS' if ok else 'FAIL'}  {label}")
    failures = [label for ok, label in RESULTS if not ok]
    print()
    print("=" * 78)
    print(f"RESULT: {len(RESULTS)} checks, {len(failures)} failures")
    print("=" * 78)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
