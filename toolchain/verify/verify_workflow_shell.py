#!/usr/bin/env python3
"""Verify the CI workflows themselves.

The workflows are the only compiler and the only emulator available to this
repository, and a broken shell line in them fails a job *after* every test has
already passed - which reads exactly like a product bug to whoever is watching
the pull request. One real instance: the device job used ``${PIPESTATUS[0]}``,
a bash-only expansion, while GitHub Actions runs ``script:`` blocks with
``/usr/bin/sh`` (dash). dash aborts with "Bad substitution" and exit code 2 and
never reaches the lines below it, so the step went red with 36/36 green tests.

This check therefore pins, for every shell block in .github/workflows:

  * no bash-only construct (PIPESTATUS, ``[[ ]]'', arrays, ``+=``, ``function``),
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
    check('echo "$GRADLE_EXIT" > gradle-exit.txt' in script,
          "the device job records gradle's exit code for the summary")
    check(script.rstrip().endswith("exit 0"),
          "the device job no longer uses gradle's exit code as the step result")
    check("> gradle-connected.log 2>&1" in script,
          "gradle output is captured so a non-zero exit can be quoted")
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
