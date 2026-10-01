#!/usr/bin/env python3
"""Run an isolated Android window test. No root, security changes, or license acceptance."""
from pathlib import Path
import json
import hashlib
import os
import re
import shutil
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


def capture_file(data, name):
    """exec-out may report a remote error with exit code zero; validate actual bytes."""
    if name.endswith(".png"):
        return (data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) > 45
                and data.endswith(b"\x00\x00\x00\x00IEND\xaeB`\x82"))
    if name == "report.json":
        try:
            value = json.loads(data)
            return isinstance(value, dict) and isinstance(value.get("passed"), bool)
        except (ValueError, UnicodeDecodeError):
            return False
    return False


def collect(args, *, timeout=30, output=None, check=False):
    """Best-effort diagnostics must not prevent collecting the remaining crash evidence."""
    try:
        return run(args, timeout=timeout, output=output, check=check)
    except (subprocess.TimeoutExpired, OSError) as error:
        if output:
            with Path(output).open("ab") as stream:
                stream.write(("\nDiagnostic collection failed: " + str(error) + "\n").encode())
        return subprocess.CompletedProcess(args, 124, stdout=b"", stderr=str(error).encode())


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
# Preserve the Pixel 5's approximately 393 x 852 dp viewport at fewer physical pixels.
# This changes only the disposable AVD display definition, not fonts or device security.
avd_config = Path(env["ANDROID_AVD_HOME"]) / "hetu_window_qa.avd/config.ini"
config = dict(line.split("=", 1) for line in avd_config.read_text().splitlines() if "=" in line)
config.update({"hw.lcd.width": "720", "hw.lcd.height": "1560", "hw.lcd.density": "293",
               "skin.name": "720x1560", "skin.path": "720x1560"})
avd_config.write_text("\n".join(f"{key}={value}" for key, value in config.items()) + "\n")
# Only use permissions already present. Never chmod/chown /dev/kvm or change groups.
acceleration = "on" if os.access("/dev/kvm", os.R_OK | os.W_OK) else "off"
(OUT / "environment.json").write_text(json.dumps({"avd": "hetu_window_qa", "api": 35,
    "image": "google_apis/x86_64", "vmAcceleration": acceleration, "graphics": "software",
    "physicalSize": [720, 1560], "densityDpi": 293, "installMode": "push-then-pm-install",
    "systemSecurityChanged": False, "fontsChanged": False, "newLicenseAccepted": False,
    "limits": "SwiftShader/software graphics in an Android hardware-accelerated window, not phone GPU or FPS validation."}, indent=2))
app_apk = ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"
test_apk = ROOT / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if not app_apk.is_file() or not test_apk.is_file():
    raise RuntimeError("Build both the reviewed app APK and its instrumentation test APK first")
app_digest = hashlib.sha256(app_apk.read_bytes()).hexdigest()
expected_digest = os.environ.get("HETU_WINDOW_EXPECTED_APK_SHA256", "")
if expected_digest and app_digest != expected_digest:
    raise RuntimeError("Diagnostic reproduction must use the identical previously tested production APK")
(OUT / "apk-identity.json").write_text(json.dumps({"sha256": app_digest, "expectedSha256": expected_digest or None}, indent=2))

emulator_log = (OUT / "emulator.log").open("wb")
process = subprocess.Popen([str(emulator), "-avd", "hetu_window_qa", "-port", "5556",
    "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", acceleration,
    "-gpu", "software", "-memory", "2048"], env=env, stdout=emulator_log, stderr=subprocess.STDOUT)
recording = None
record_log = None
app_log = None
app_log_process = None
record_path = "/data/local/tmp/hetu-window-qa.mp4"
ready = False
installed = False
stage = "boot"
stage_started = time.monotonic()


def record_stage(name):
    global stage, stage_started
    stage = name
    stage_started = time.monotonic()
    (OUT / "progress.json").write_text(json.dumps({"stage": name, "status": "running"}, indent=2))
    print(f"Android window QA: {name}", flush=True)


def install(apk, label):
    # Explicitly separate transfer from package verification/dex work in slow CPU emulation.
    # Never retry a timed-out installation in the same emulator (it may still be committing).
    remote = "/data/local/tmp/hetu-window-" + label + ".apk"
    record_stage("transfer-" + label)
    run([adb, "-s", SERIAL, "push", "-Z", apk, remote], timeout=300, output=OUT / ("transfer-" + label + ".log"))
    record_stage("install-" + label)
    run([adb, "-s", SERIAL, "shell", "pm", "install", "-r", "-t", remote], timeout=600,
        output=OUT / ("install-" + label + ".log"))
    if "Success" not in (OUT / ("install-" + label + ".log")).read_text(errors="replace"):
        raise RuntimeError("Package manager did not confirm installation: " + label)


try:
    record_stage("boot")
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
    install(app_apk, "app")
    installed = True
    install(test_apk, "test")
    # CPU-only emulation can spend Android's startup budget verifying these large debug
    # APKs. Precompile only our two installed packages through the public shell command;
    # never relax the system's ANR policy or change emulator permissions.
    for package, label, limit in [(APP, "app", 900), (APP + ".test", "test", 600)]:
        record_stage("compile-" + label)
        path = OUT / ("compile-" + label + ".log")
        run([adb, "-s", SERIAL, "shell", "cmd", "package", "compile", "-f", "-m", "speed", package],
            timeout=limit, output=path)
        if "Success" not in path.read_text(errors="replace"):
            raise RuntimeError("Package manager did not confirm test-package compilation: " + label)
    # Capture from before process launch; pidof after a fatal exit loses the useful stack.
    packages = run([adb, "-s", SERIAL, "shell", "cmd", "package", "list", "packages", "-U", APP], timeout=30).stdout.decode()
    uids = [uid for package, uid in re.findall(r"package:(\S+)\s+uid:(\d+)", packages) if package in (APP, APP + ".test")]
    if not uids:
        raise RuntimeError("Installed test application UID was not found")
    app_log = (OUT / "app-logcat.txt").open("wb")
    app_log_process = subprocess.Popen([str(adb), "-s", SERIAL, "logcat", "-v", "threadtime", "--uid=" + ",".join(uids)],
        env=env, stdout=app_log, stderr=subprocess.STDOUT)
    run([adb, "-s", SERIAL, "shell", "test", "-w", "/data/local/tmp"], timeout=15)
    record_stage("instrumentation")
    record_log = (OUT / "screenrecord.log").open("wb")
    recording = subprocess.Popen([str(adb), "-s", SERIAL, "shell", "screenrecord", "--time-limit", "180",
        record_path], env=env, stdout=record_log, stderr=subprocess.STDOUT)
    result = run([adb, "-s", SERIAL, "shell", "am", "instrument", "-w", "-r", "-e", "class",
        APP + ".HetuWindowUiTest", TEST_PACKAGE], timeout=900, output=OUT / "instrumentation.log", check=False)
    text = (OUT / "instrumentation.log").read_text(errors="replace")
    if result.returncode or "OK (1 test)" not in text:
        raise RuntimeError("Android window validation failed; inspect instrumentation.log")
    (OUT / "progress.json").write_text(json.dumps({"stage": stage, "status": "passed"}, indent=2))
except Exception as error:
    (OUT / "progress.json").write_text(json.dumps({"stage": stage, "status": "failed",
        "elapsedSeconds": round(time.monotonic() - stage_started, 1), "error": str(error)}, indent=2))
    raise
finally:
    if ready and installed:
        collect([adb, "-s", SERIAL, "logcat", "-b", "crash", "-d", "-v", "threadtime"], timeout=30,
            output=OUT / "crash-logcat.txt")
        collect([adb, "-s", SERIAL, "shell", "dumpsys", "activity", "exit-info", APP], timeout=30,
            output=OUT / "app-exit-info.txt")
        rejected = []
        for name in ["report.json", "01-home-default-glass.png", "02-strategy-default-glass.png",
                     "03-inline-nodes-default-glass.png", "04-strategy-collapsed.png",
                     "05-overview-default-glass.png", "06-tools-default-glass.png"]:
            # Supported debug-app sandbox access, never adb root or a permission change.
            result = collect([adb, "-s", SERIAL, "exec-out", "run-as", APP, "cat", "files/window-ui-qa/" + name], timeout=20)
            if result.returncode == 0 and capture_file(result.stdout, name):
                (OUT / name).write_bytes(result.stdout)
            else:
                rejected.append({"name": name, "reason": "missing or invalid file bytes", "exitCode": result.returncode})
        (OUT / "capture-status.json").write_text(json.dumps(rejected, indent=2))
        if recording is not None and recording.poll() is None:
            # Stop only the screenrecord process launched by this test, allowing MP4 finalization.
            collect([adb, "-s", SERIAL, "shell", "pkill", "-INT", "screenrecord"])
            try:
                recording.wait(timeout=15)
            except subprocess.TimeoutExpired:
                recording.terminate()
        collect([adb, "-s", SERIAL, "pull", record_path, OUT / "interaction.mp4"], timeout=30)
    if app_log_process is not None and app_log_process.poll() is None:
        app_log_process.terminate()
        try:
            app_log_process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            app_log_process.kill()
    if app_log:
        app_log.close()
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
