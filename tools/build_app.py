#!/usr/bin/env python3
"""Build the native Java Bichen app without Gradle or third-party runtime libraries.

Requirements: Python 3, JDK 17, Android SDK platform 35 + build-tools 35.0.0.
Set ANDROID_SDK_ROOT (or ANDROID_HOME). The local ../tooling SDK/ECJ bundle
is also supported for environments that only have a Java runtime.

Signing: set BICHEN_KEYSTORE, BICHEN_KEY_ALIAS, BICHEN_STOREPASS and
BICHEN_KEYPASS to reuse your own key. Without these, a development key is
created outside source directories. Keep that key to sign future updates;
do not publish it in a source archive.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android-app" / "app" / "src" / "main"
LOCAL_TOOLS = ROOT.parent / "tooling"
BUILD = ROOT / "android-app" / "build"
VERSION = "0.3.0-beta.1"
VERSION_CODE = 301


def run(args: list[str | Path], *, env: dict[str, str] | None = None) -> None:
    subprocess.run([str(arg) for arg in args], check=True, env=env)


def sdk_paths() -> tuple[Path, Path]:
    roots: list[Path] = []
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(key):
            roots.append(Path(os.environ[key]).expanduser())
    roots.extend((Path.home() / "Android" / "Sdk", Path("/usr/local/lib/android/sdk")))
    for sdk in roots:
        android_jar = sdk / "platforms" / "android-35" / "android.jar"
        tools = sdk / "build-tools" / "35.0.0"
        if android_jar.is_file() and all((tools / name).is_file() for name in ("aapt", "d8", "zipalign", "apksigner")):
            return tools, android_jar
    tools = LOCAL_TOOLS / "android-15"
    android_jar = LOCAL_TOOLS / "android-35" / "android.jar"
    if android_jar.is_file() and all((tools / name).is_file() for name in ("aapt", "d8", "zipalign", "apksigner")):
        return tools, android_jar
    raise RuntimeError(
        'Android SDK missing. Install sdkmanager "platforms;android-35" '
        '"build-tools;35.0.0" and set ANDROID_SDK_ROOT.'
    )


def java_tool(name: str) -> str | None:
    java_home = os.environ.get("JAVA_HOME")
    if java_home and (Path(java_home) / "bin" / name).is_file():
        return str(Path(java_home) / "bin" / name)
    return shutil.which(name)


def sign_key() -> tuple[Path, str, dict[str, str]]:
    env = os.environ.copy()
    explicit = bool(env.get("BICHEN_KEYSTORE"))
    if explicit:
        keystore = Path(env["BICHEN_KEYSTORE"]).expanduser().resolve()
    elif (LOCAL_TOOLS / "bichen-dev.keystore").is_file():
        keystore = LOCAL_TOOLS / "bichen-dev.keystore"
    else:
        keystore = BUILD / "signing" / "bichen-dev.keystore"
    alias = env.get("BICHEN_KEY_ALIAS", "androiddebugkey")
    env.setdefault("BICHEN_STOREPASS", "android")
    env.setdefault("BICHEN_KEYPASS", env["BICHEN_STOREPASS"])
    if not keystore.is_file():
        if explicit:
            raise RuntimeError(f"BICHEN_KEYSTORE does not exist: {keystore}")
        keytool = java_tool("keytool")
        if not keytool:
            raise RuntimeError("keytool is required to create a development signing key.")
        keystore.parent.mkdir(parents=True, exist_ok=True)
        run([
            keytool, "-genkeypair", "-keystore", keystore,
            "-storepass:env", "BICHEN_STOREPASS", "-keypass:env", "BICHEN_KEYPASS",
            "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Bichen Development,O=Bichen,C=CN",
        ], env=env)
        keystore.chmod(0o600)
        print("Generated a development signing key; retain it for compatible app updates.", flush=True)
    return keystore, alias, env


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "out" / f"Bichen-{VERSION}.apk")
    args = parser.parse_args()
    output = args.out.expanduser().resolve()
    tools, android_jar = sdk_paths()
    java = java_tool("java")
    javac = java_tool("javac")
    ecj = LOCAL_TOOLS / "ecj.jar"
    if not java or (not javac and not ecj.is_file()):
        raise RuntimeError("JDK 17 is required (or the local Java runtime + tooling/ecj.jar).")
    if not (MAIN / "AndroidManifest.xml").is_file():
        raise RuntimeError(f"Missing manifest: {MAIN / 'AndroidManifest.xml'}")

    BUILD.mkdir(parents=True, exist_ok=True)
    # Clean only generated compilation outputs; keep the signing key between builds.
    for dirname in ("generated", "classes", "dex"):
        path = BUILD / dirname
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    output.parent.mkdir(parents=True, exist_ok=True)
    aapt = [
        tools / "aapt", "package", "-f", "-M", MAIN / "AndroidManifest.xml",
        "-I", android_jar, "-J", BUILD / "generated", "-F", BUILD / "resources.apk",
        "--min-sdk-version", "26", "--target-sdk-version", "35",
        "--version-code", str(VERSION_CODE), "--version-name", VERSION,
    ]
    for dirname, flag in (("res", "-S"), ("assets", "-A")):
        if (MAIN / dirname).is_dir():
            aapt.extend((flag, MAIN / dirname))
    run(aapt)

    sources = sorted((MAIN / "java").rglob("*.java")) + sorted((BUILD / "generated").rglob("*.java"))
    if not sources:
        raise RuntimeError("No Java source files found.")
    source_list = BUILD / "sources.txt"
    source_list.write_text("\n".join('"' + str(p).replace("\\", "\\\\").replace('"', '\\"') + '"' for p in sources) + "\n", encoding="utf-8")
    compiler: list[str | Path] = [javac] if javac else [java, "-jar", ecj]
    # android.jar intentionally omits LambdaMetafactory; the SDK's compiler-only
    # stubs let javac/ECJ emit lambda bytecode for D8 to desugar into Android code.
    lambda_stubs = tools / "core-lambda-stubs.jar"
    if not lambda_stubs.is_file():
        raise RuntimeError(f"Missing SDK lambda compiler stubs: {lambda_stubs}")
    bootclasspath = os.pathsep.join((str(android_jar), str(lambda_stubs)))
    run(compiler + [
        "-source", "8", "-target", "8", "-proc:none", "-encoding", "UTF-8",
        "-bootclasspath", bootclasspath, "-d", BUILD / "classes", "@" + str(source_list),
    ])
    with zipfile.ZipFile(BUILD / "classes.jar", "w", zipfile.ZIP_DEFLATED) as jar:
        for file in sorted((BUILD / "classes").rglob("*.class")):
            jar.write(file, file.relative_to(BUILD / "classes").as_posix())
    run([
        tools / "d8", "--release", "--min-api", "26", "--lib", android_jar,
        "--output", BUILD / "dex", BUILD / "classes.jar",
    ])
    shutil.copyfile(BUILD / "resources.apk", BUILD / "unsigned.apk")
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
        for file in sorted((BUILD / "dex").glob("*.dex")):
            apk.write(file, file.name)
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
        for file in sorted((MAIN / "jniLibs").rglob("*.so")):
            apk.write(file, "lib/" + file.relative_to(MAIN / "jniLibs").as_posix())
    run([tools / "zipalign", "-f", "-p", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk"])
    keystore, alias, env = sign_key()
    run([
        tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", alias,
        "--ks-pass", "env:BICHEN_STOREPASS", "--key-pass", "env:BICHEN_KEYPASS",
        "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
        "--out", output, BUILD / "aligned.apk",
    ], env=env)
    run([tools / "apksigner", "verify", "--verbose", "--print-certs", output])
    run([tools / "zipalign", "-c", "-p", "4", output])
    print(f"Built: {output}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        sys.exit(1)