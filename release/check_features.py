#!/usr/bin/env python3
"""Release gate: checks every feature in release/features.txt has tests that ran and passed.

Usage: python3 release/check_features.py RESULTS_DIR_OR_XML [...]
Reads JUnit XML results (Gradle and the Firebase rules tests), prints a table per feature, also writes it to
$GITHUB_STEP_SUMMARY when set, and exits 1 if any feature is not covered or any test failed.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))


def load_results(paths):
    """List of (classname, name, status) for every test case, status is passed, failed or skipped."""
    files = []
    for p in paths:
        files += sorted(glob.glob(os.path.join(p, "**", "*.xml"), recursive=True)) if os.path.isdir(p) else [p]
    cases = []
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except (ET.ParseError, OSError) as e:
            sys.exit(f"Can't read test results {f}: {e}")
        for c in root.iter("testcase"):
            if c.find("failure") is not None or c.find("error") is not None:
                status = "failed"
            elif c.find("skipped") is not None:
                status = "skipped"
            else:
                status = "passed"
            cases.append((c.get("classname", ""), c.get("name", ""), status))
    return cases


def load_features(path):
    features = []
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "|" not in line:
                sys.exit(f"{path}:{n}: expected 'Feature | tests', got: {line}")
            name, tests = (s.strip() for s in line.split("|", 1))
            refs = [t.strip() for t in tests.split(",") if t.strip()]
            if not refs:
                sys.exit(f"{path}:{n}: feature '{name}' lists no tests")
            features.append((name, refs))
    return features


def matches(ref, case):
    classname, name, _ = case
    if ref.startswith("rules:"):
        return name.startswith(ref[len("rules:"):].strip())
    return classname == ref


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    cases = load_results(argv[1:])
    features = load_features(os.path.join(HERE, "features.txt"))
    rows, problems = [], []
    for name, refs in features:
        passed = failed = skipped = 0
        for ref in refs:
            hit = [c for c in cases if matches(ref, c)]
            if not hit:
                problems.append(f"{name}: no results for {ref} (test missing, renamed or not run)")
            for c in hit:
                if c[2] == "passed":
                    passed += 1
                elif c[2] == "skipped":
                    skipped += 1
                    problems.append(f"{name}: skipped {c[0]} > {c[1]}")
                else:
                    failed += 1
                    problems.append(f"{name}: FAILED {c[0]} > {c[1]}")
        ok = failed == 0 and skipped == 0 and all(any(matches(r, c) for c in cases) for r in refs)
        rows.append((name, passed, failed + skipped, ok))
    # A failing test fails the release even when no feature lists it.
    for c in cases:
        if c[2] == "failed" and not any(matches(r, c) for _, refs in features for r in refs):
            problems.append(f"(not in features.txt): FAILED {c[0]} > {c[1]}")

    total = len(cases)
    good = sum(1 for c in cases if c[2] == "passed")
    lines = [
        "## Release tests",
        "",
        f"{good} of {total} tests passed across {len(features)} features.",
        "",
        "| Feature | Passed | Failed or skipped | OK |",
        "| --- | ---: | ---: | :---: |",
    ] + [f"| {n} | {p} | {f} | {'✅' if ok else '❌'} |" for n, p, f, ok in rows]
    if problems:
        lines += ["", "### Problems", ""] + [f"- {p}" for p in problems]
    report = "\n".join(lines) + "\n"
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(report)
    if problems:
        print(f"Release gate FAILED: {len(problems)} problem(s). Fix them before releasing.", file=sys.stderr)
        return 1
    print("Release gate passed: every feature is covered and every test passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
