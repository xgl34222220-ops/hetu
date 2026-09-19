#!/usr/bin/env python3
"""Re-sign a Hetu preview APK with the existing, pinned preview key.

This tool never creates a key. The private PKCS12 file must be supplied from
private persistent storage; the repository contains only its public certificate.
"""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


PREVIEW_PACKAGE = "io.github.xgl34222220.hetu.preview"
PREVIEW_ALIAS = "hetu-preview"
PREVIEW_CERT_SHA256 = "6cbba419309b93f031417601e19feaed3dd056a0cb06c2aaaa7694946338085d"
REPO_ROOT = Path(__file__).resolve().parent.parent


def run(command, env):
    result = subprocess.run(command, capture_output=True, env=env, timeout=180)
    if result.returncode:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise RuntimeError(f"{Path(command[0]).name} failed: {detail[-2000:]}")
    return result.stdout


def sdk_tool(name, build_tools):
    if build_tools:
        candidate = build_tools / name
        if not candidate.is_file():
            raise RuntimeError(f"Android build tool is missing: {candidate}")
        return str(candidate)
    found = shutil.which(name)
    if found:
        return found
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(variable)
        if not sdk:
            continue
        candidates = list((Path(sdk) / "build-tools").glob(f"*/{name}"))
        # Prefer the latest installed stable/preview directory without using
        # lexical ordering, which would sort 9.x after 37.x.
        candidates.sort(key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name)), reverse=True)
        for candidate in candidates:
            if candidate.is_file():
                return str(candidate)
    raise RuntimeError(f"Cannot find {name}; pass --build-tools /path/to/Android/build-tools/37.0.0")


def check_package(apk, aapt, env):
    badging = run([aapt, "dump", "badging", str(apk)], env).decode("utf-8", "replace")
    match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not match or match.group(1) != PREVIEW_PACKAGE:
        raise RuntimeError(f"Only {PREVIEW_PACKAGE} may use this preview signing key")
    return badging.splitlines()[0]


def apk_payload(apk):
    """Compare decompressed file content, excluding APK/JAR signing metadata."""
    result = {}
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            name = entry.filename
            upper = name.upper()
            if upper == "META-INF/MANIFEST.MF" or (
                upper.startswith("META-INF/")
                and "/" not in upper[len("META-INF/"):]
                and (upper.endswith((".SF", ".RSA", ".DSA", ".EC")) or upper.startswith("META-INF/SIG-"))
            ):
                continue
            if name in result:
                raise RuntimeError(f"APK contains a duplicate file: {name}")
            digest = hashlib.sha256()
            with archive.open(entry) as content:
                for block in iter(lambda: content.read(1024 * 1024), b""):
                    digest.update(block)
            result[name] = digest.digest()
    return result


def sign(args):
    keystore = args.keystore.expanduser().resolve()
    apk = args.input.expanduser().resolve()
    output = args.output.expanduser().resolve()
    if not keystore.is_file():
        raise RuntimeError("Existing preview keystore is missing. Restore hetu-preview.p12 from private storage; never generate a replacement.")
    if keystore.is_relative_to(REPO_ROOT):
        raise RuntimeError("Keep the private preview keystore outside the repository")
    if os.name == "posix" and keystore.stat().st_mode & 0o077:
        raise RuntimeError("Private keystore permissions must be 600; run chmod 600 on the keystore")
    if not apk.is_file():
        raise RuntimeError(f"Input APK is missing: {apk}")
    if output.exists() or output == apk or output == keystore:
        raise RuntimeError("Output must be a new file distinct from the input APK and keystore")

    pin_file = REPO_ROOT / "docs/signing/hetu-preview-cert.sha256"
    if pin_file.read_text().strip() != PREVIEW_CERT_SHA256:
        raise RuntimeError("Repository certificate pin does not match the fixed preview identity")
    env = dict(os.environ, LC_ALL="C", HETU_PREVIEW_STORE_PASSWORD="android")
    keytool = shutil.which("keytool")
    if not keytool:
        raise RuntimeError("keytool is required; install a Java JDK")
    cert = run([
        keytool, "-exportcert", "-storetype", "PKCS12", "-keystore", str(keystore),
        "-storepass:env", "HETU_PREVIEW_STORE_PASSWORD", "-alias", PREVIEW_ALIAS,
    ], env)
    if hashlib.sha256(cert).hexdigest() != PREVIEW_CERT_SHA256:
        raise RuntimeError("Keystore certificate does not match the pinned Hetu preview key; refusing to sign")

    build_tools = args.build_tools.expanduser().resolve() if args.build_tools else None
    apksigner = sdk_tool("apksigner", build_tools)
    zipalign = sdk_tool("zipalign", build_tools)
    aapt = sdk_tool("aapt", build_tools)
    package = check_package(apk, aapt, env)
    original_payload = apk_payload(apk)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".hetu-preview-sign-", dir=output.parent) as temp:
        aligned = Path(temp) / "aligned.apk"
        signed = Path(temp) / "signed.apk"
        run([zipalign, "-P", "16", "-f", "4", str(apk), str(aligned)], env)
        run([
            apksigner, "sign", "--ks", str(keystore), "--ks-type", "PKCS12",
            "--ks-key-alias", PREVIEW_ALIAS,
            "--ks-pass", "env:HETU_PREVIEW_STORE_PASSWORD",
            "--key-pass", "env:HETU_PREVIEW_STORE_PASSWORD",
            "--v4-signing-enabled", "false", "--out", str(signed), str(aligned),
        ], env)
        verification = run([apksigner, "verify", "--verbose", "--print-certs", str(signed)], env).decode("utf-8", "replace")
        digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", verification, re.MULTILINE)
        if [value.lower() for value in digests] != [PREVIEW_CERT_SHA256]:
            raise RuntimeError("Signed APK does not have exactly the pinned preview signer")
        run([zipalign, "-c", "-P", "16", "4", str(signed)], env)
        if check_package(signed, aapt, env) != package or apk_payload(signed) != original_payload:
            raise RuntimeError("APK identity or payload changed while re-signing")
        # Both files are on the output filesystem. Publish only after every check;
        # link fails if a concurrent caller has already created the destination.
        os.link(signed, output)
    print(f"Verified preview APK: {output}")
    print(package)
    print(f"Certificate SHA-256: {PREVIEW_CERT_SHA256}")
    print("APK signature, unchanged payload, and 16 KiB ZIP alignment verified")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="CI-built Hetu preview APK")
    parser.add_argument("--output", required=True, type=Path, help="New signed APK destination; existing files are never overwritten")
    parser.add_argument("--keystore", required=True, type=Path, help="Existing private hetu-preview.p12 outside the repository")
    parser.add_argument("--build-tools", type=Path, help="Android build-tools directory containing aapt, zipalign and apksigner")
    args = parser.parse_args()
    try:
        sign(args)
    except (OSError, RuntimeError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        print(f"Preview signing failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
