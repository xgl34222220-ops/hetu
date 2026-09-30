#!/usr/bin/env python3
from pathlib import Path
import hashlib, sys, zipfile
source = Path("android-app/app/src/main/assets/hetu-root.sh").read_bytes()
with zipfile.ZipFile(sys.argv[1]) as apk:
    actual = apk.read("assets/hetu-root.sh")
assert actual == source, "APK runtime script differs from reviewed source"
print("Verified runtime SHA256", hashlib.sha256(actual).hexdigest())
