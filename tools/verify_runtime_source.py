#!/usr/bin/env python3
from pathlib import Path
import hashlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in ("hetu-root.sh", "hetu-core-inventory.sh"):
        source = (Path("android-app/app/src/main/assets") / name).read_bytes()
        actual = apk.read("assets/" + name)
        assert actual == source, f"APK {name} differs from reviewed source"
        print("Verified runtime SHA256", name, hashlib.sha256(actual).hexdigest())
