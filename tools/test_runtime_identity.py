#!/usr/bin/env python3
"""Compile the production runtime-identity resolver with real org.json, no Root/network."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/hetu"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json-jar", type=Path, required=True)
    args = parser.parse_args()
    assert args.json_jar.is_file(), "Existing org.json jar is required"
    java_home = Path(os.environ.get("JAVA_HOME", ""))
    javac = str(java_home / "bin/javac") if str(java_home) != "." else "javac"
    java = str(java_home / "bin/java") if str(java_home) != "." else "java"
    with tempfile.TemporaryDirectory(prefix="hetu-runtime-identity-") as work:
        stub = Path(work) / "SharedPreferences.java"
        stub.write_text("package android.content; public interface SharedPreferences { String getString(String key,String fallback); boolean getBoolean(String key,boolean fallback); }")
        subprocess.run([javac,"--release","17","-encoding","UTF-8","-cp",str(args.json_jar),"-d",work,
                        str(stub),str(PACKAGE / "ProxyRuntimeProfile.java"),str(PACKAGE / "RuntimeIdentity.java"),
                        str(ROOT / "tests/RuntimeIdentityTest.java")],check=True)
        subprocess.run([java,"-cp",work+os.pathsep+str(args.json_jar),"io.github.xgl34222220.hetu.RuntimeIdentityTest"],check=True)

if __name__ == "__main__": main()
