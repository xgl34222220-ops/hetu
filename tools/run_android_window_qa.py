#!/usr/bin/env python3
"""Run an isolated Android window test. No root, security changes, or license acceptance."""
from pathlib import Path
import json
import os
import shutil
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "out/android-window-qa"
OUT.mkdir(parents=True, exist_ok=True)
SDK = Path(os.environ["ANDROID_HOME"])
SERIAL = "emulator-5556"
APP = "io.github.xgl34222220.hetu"
TEST_PACKAGE = APP + ".test/androidx.test.runner.AndroidJUnitRunner"
env = dict(os.environ)
scratch = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "hetu-window-qa"
env["ANDROID_AVD_HOME"] = str(scratch / "avd")
env["ANDROID_USER_HOME"] = str(scratch / "android-user")
for key in ("ANDROID_AVD_HOME", "ANDROID_USER_HOME"):
    Path(env[key]).mkdir(parents=True, exist_ok=True)


def run(args, *, timeout=60, output=None, check=True, input=None):
    if output:
        with Path(output).open("wb") as stream:
            result = subprocess.run([str(a) for a in args], env=env, input=input, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    else:
        result = subprocess.run([str(a) for a in args], env=env, input=input, capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"{Path(str(args[0])).name} failed ({result.returncode}); see retained QA logs")
    return result


sdkmanager = sorted(SDK.glob("cmdline-tools/*/bin/sdkmanager"))[-1]
avdmanager = sdkmanager.with_name("avdmanager")
# Empty stdin deliberately declines any new license prompt. Never use `yes --licenses` here.
run([sdkmanager, "emulator", "system-images;android-35;google_apis;x86_64"], timeout=600,
    output=OUT / "sdk-install.log", input=b"")
emulator = SDK / "emulator/emulator"
adb = SDK / "platform-tools/adb"
if not emulator.is_file() or not adb.is_file():
    raise RuntimeError("Official emulator/platform-tools are not installed")
run([avdmanager, "create", "avd", "--force", "--name", "hetu_window_qa", "--package",
     "system-images;android-35;google_apis;x86_64", "--device", "pixel_5"], timeout=60,
    output=OUT / "avd-create.log", input=b"no\n")
# Only use permissions already present. Never chmod/chown /dev/kvm or change groups.
acceleration = "on" if os.access("/dev/kvm", os.R_OK | os.W_OK) else "off"
(OUT / "environment.json").write_text(json.dumps({"avd": "hetu_window_qa", "api": 35,
    "image": "google_apis/x86_64", "vmAcceleration": acceleration, "graphics": "software",
    "systemSecurityChanged": False, "fontsChanged": False, "newLicenseAccepted": False,
    "limits": "SwiftShader/software graphics in an Android hardware-accelerated window, not phone GPU or FPS validation."}, indent=2))
app_apk = ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"
test_apk = ROOT / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if not app_apk.is_file() or not test_apk.is_file():
    raise RuntimeError("Build both the reviewed app APK and its instrumentation test APK first")

emulator_log = (OUT / "emulator.log").open("wb")
process = subprocess.Popen([str(emulator), "-avd", "hetu_window_qa", "-port", "5556",
    "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", acceleration,
    "-gpu", "software", "-memory", "2048"], env=env, stdout=emulator_log, stderr=subprocess.STDOUT)
recording = None
record_log = None
ready = False
try:
    run([adb, "-s", SERIAL, "wait-for-device"], timeout=180, output=OUT / "adb-wait.log")
    started = time.monotonic()
    while time.monotonic() - started < 900:
        if process.poll() is not None:
            raise RuntimeError("Emulator exited before Android finished booting")
        boot = run([adb, "-s", SERIAL, "shell", "getprop", "sys.boot_completed"], timeout=15, check=False)
        if boot.stdout.strip() == b"1":
            ready = True
            break
        time.sleep(5)
    if not ready:
        raise RuntimeError("Android did not finish booting within the bounded 15-minute window")
    run([adb, "-s", SERIAL, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False)
    run([adb, "-s", SERIAL, "shell", "input", "keyevent", "KEYCODE_MENU"], check=False)
    run([adb, "-s", SERIAL, "install", "-r", "-t", app_apk], timeout=120, output=OUT / "install-app.log")
    run([adb, "-s", SERIAL, "install", "-r", "-t", test_apk], timeout=120, output=OUT / "install-test.log")
    record_log = (OUT / "screenrecord.log").open("wb")
    recording = subprocess.Popen([str(adb), "-s", SERIAL, "shell", "screenrecord", "--time-limit", "180",
        "/sdcard/hetu-window-qa.mp4"], env=env, stdout=record_log, stderr=subprocess.STDOUT)
    result = run([adb, "-s", SERIAL, "shell", "am", "instrument", "-w", "-r", "-e", "class",
        APP + ".HetuWindowUiTest", TEST_PACKAGE], timeout=240, output=OUT / "instrumentation.log", check=False)
    text = (OUT / "instrumentation.log").read_text(errors="replace")
    if result.returncode or "OK (1 test)" not in text:
        raise RuntimeError("Android window validation failed; inspect instrumentation.log")
finally:
    if ready:
        for name in ["report.json", "01-home-default-glass.png", "02-strategy-default-glass.png",
                     "03-inline-nodes-default-glass.png", "04-strategy-collapsed.png",
                     "05-overview-default-glass.png", "06-tools-default-glass.png"]:
            # Supported debug-app sandbox access, never adb root or a permission change.
            result = run([adb, "-s", SERIAL, "exec-out", "run-as", APP, "cat", "files/window-ui-qa/" + name], timeout=20, check=False)
            if result.returncode == 0 and result.stdout:
                (OUT / name).write_bytes(result.stdout)
        if recording is not None and recording.poll() is None:
            # Stop only the screenrecord process launched by this test, allowing MP4 finalization.
            run([adb, "-s", SERIAL, "shell", "pkill", "-INT", "screenrecord"], check=False)
            try:
                recording.wait(timeout=15)
            except subprocess.TimeoutExpired:
                recording.terminate()
        run([adb, "-s", SERIAL, "pull", "/sdcard/hetu-window-qa.mp4", OUT / "interaction.mp4"], timeout=30, check=False)
        pid = run([adb, "-s", SERIAL, "shell", "pidof", APP], timeout=10, check=False).stdout.decode().strip().split()
        if pid:
            run([adb, "-s", SERIAL, "logcat", "-d", "--pid=" + pid[0]], timeout=20, output=OUT / "app-logcat.txt", check=False)
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
    emulator_log.close()
    if record_log:
        record_log.close()
report = json.loads((OUT / "report.json").read_text())
if not report.get("passed") or not report.get("hardwareCanvasSeen"):
    raise RuntimeError("Missing successful Android hardware-window evidence")
video = OUT / "interaction.mp4"
if not video.is_file() or video.stat().st_size < 1024:
    raise RuntimeError("Window screenshots exist but the requested interaction recording is missing")
ffprobe = shutil.which("ffprobe")
if not ffprobe:
    raise RuntimeError("Recording retained; ffprobe is unavailable to validate it")
probe = run([ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "json", video], timeout=30)
video_info = json.loads(probe.stdout)
if float(video_info.get("format", {}).get("duration", 0)) <= 0:
    raise RuntimeError("Interaction recording has no usable video duration")
(OUT / "video-metadata.json").write_text(json.dumps(video_info, indent=2))
print("Android default-glass window validation passed; screenshots and recording retained")
