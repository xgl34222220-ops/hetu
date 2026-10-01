#!/usr/bin/env python3
"""Check the real Root start dispatcher without executing Root or network work.

The exact final dispatch block is executed with only fail/root/start recording
stubs. Arguments are passed as subprocess argv, never interpolated into shell
code. An optional APK verifies the actual packaged asset and its source identity.
All shell executions use a temporary working directory.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ASSET = Path("android-app/app/src/main/assets/hetu-root.sh")
MANAGER = Path("android-app/app/src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java")
DISPATCH = 'case "${1:-status}" in'
STUBS = r'''set -eu
fail(){ printf '%s\000' fail "$1"; exit 64; }
root(){ printf '%s\000' root; }
start(){ printf '%s\000' start "$#"; for value in "$@"; do printf '%s\000' "$value"; done; }
'''


def java_call_arguments(text):
    """Read the actual start call's top-level arguments, preserving Java strings."""
    match = re.search(r'runJsonWithTimeout\(\s*125000L\s*,\s*"start"\s*,', text)
    if not match:
        raise AssertionError("RootProxyManager's start invocation was not found")
    begin = text.index("(", match.start()) + 1
    depth, quote, escape = 0, None, False
    args, previous = [], begin
    for index in range(begin, len(text)):
        char = text[index]
        if quote:
            if escape:
                escape = False
            elif char == "\\":
                escape = True
            elif char == quote:
                quote = None
        elif char in ('"', "'"):
            quote = char
        elif char in "([{":
            depth += 1
        elif char == ")" and depth == 0:
            args.append(text[previous:index].strip())
            return args
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            args.append(text[previous:index].strip())
            previous = index + 1
    raise AssertionError("Unterminated RootProxyManager start invocation")


def check_dispatch(label, asset, caller_payload_count, directory):
    text = asset.decode("utf-8")
    if text.count(DISPATCH) != 1:
        raise AssertionError(f"{label}: expected exactly one final dispatch block")
    dispatch = text[text.index(DISPATCH):]
    program = STUBS + dispatch
    checks, failures = 0, []

    def check(payload, expected, description):
        nonlocal checks
        result = subprocess.run(
            ["sh", "-c", program, "hetu-start-contract", "start", *payload],
            cwd=directory, capture_output=True, timeout=5,
        )
        fields = result.stdout.decode("utf-8").split("\0")
        if fields and fields[-1] == "":
            fields.pop()
        wanted = (["fail", "参数错误"] if expected is None
                  else ["root", "start", str(len(expected)), *expected])
        wanted_rc = 64 if expected is None else 0
        checks += 1
        if fields != wanted or result.returncode != wanted_rc or result.stderr:
            failures.append(f"{description}: exit={result.returncode}, records={fields!r}, stderr={result.stderr!r}")

    # Total shell argc includes the action: legacy 23/24 and current 31.
    for count in (22, 23, caller_payload_count):
        payload = [f"argument-{number}" for number in range(1, count + 1)]
        expected = payload + [""] if count == 22 else payload
        check(payload, expected, f"accepted total argc={count + 1}")

    special = ["", "two words", "'single'", '"double"', "back\\slash",
               "$(printf expanded)", "`printf expanded`", "$HOME", "*?[abc]",
               "; printf injected", "line one\nline two", "--", "中文参数"]
    # Every slot, including the seven new settings, must retain empty strings,
    # whitespace, quotes and shell metacharacters exactly as one argument.
    for count in (22, 23, caller_payload_count):
        for offset in range(len(special)):
            payload = [special[(offset + position) % len(special)] for position in range(count)]
            expected = payload + [""] if count == 22 else payload
            check(payload, expected, f"literal argv total={count + 1} rotation={offset}")

    for count in range(34):
        if count not in (22, 23, caller_payload_count):
            check(["value"] * count, None, f"rejected total argc={count + 1}")

    digest = hashlib.sha256(asset).hexdigest()
    print(f"{label}: {checks - len(failures)}/{checks} passed; SHA256 {digest}")
    for failure in failures:
        print(f"FAIL {label}: {failure}")
    return checks, failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT, help="Source checkout (defaults to this script's parent checkout)")
    parser.add_argument("--apk", type=Path, help="Also test assets/hetu-root.sh from this APK")
    args = parser.parse_args()
    source = (args.repo / ASSET).read_bytes()
    java_args = java_call_arguments((args.repo / MANAGER).read_text())
    assert java_args[0] == "125000L" and java_args[1] == '"start"'
    caller_payload_count = len(java_args) - 2
    assert caller_payload_count == 30, f"Unexpected Java start payload: {caller_payload_count}"
    assets = [("source", source)]
    failures, total = [], 0
    if args.apk:
        with zipfile.ZipFile(args.apk) as archive:
            packaged = archive.read("assets/hetu-root.sh")
        assets.append(("APK", packaged))
        if packaged != source:
            failures.append("APK asset differs from reviewed source")
            print("FAIL APK: asset differs from reviewed source")
    with tempfile.TemporaryDirectory(prefix="hetu-start-contract-", dir="/tmp") as directory:
        for label, asset in assets:
            checks, observed = check_dispatch(label, asset, caller_payload_count, directory)
            total += checks
            failures.extend(observed)
    if failures:
        print(f"Start contract FAILED: {len(failures)} failure(s), {total} dispatch checks")
        raise SystemExit(1)
    print(f"Start contract passed: {total} dispatch checks; Java start payload=30; no Root/network commands executed")


if __name__ == "__main__":
    main()
