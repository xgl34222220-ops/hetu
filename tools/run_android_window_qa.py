#!/usr/bin/env python3
"""Run an isolated Android window test. No app Root/proxy, guest security changes, or license acceptance."""
from pathlib import Path
import json
import hashlib
import os
import re
import subprocess
import time

from android_window_recording import SegmentedRecording, preflight_media_tools, require_reported_captures
from android_window_readiness import home_component, home_readiness, await_home_readiness

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
            try:
                with Path(output).open("ab") as stream:
                    stream.write(("\nDiagnostic collection failed: " + str(error) + "\n").encode())
            except OSError:
                print(f"Diagnostic collection failed: {error}", flush=True)
        return subprocess.CompletedProcess(args, 124, stdout=b"", stderr=str(error).encode())


# Discover and execute both tools before any SDK download or emulator work. A missing
# binary must not invalidate an otherwise expensive completed Android run afterwards.
media_tools = preflight_media_tools(OUT, env)
sdkmanager = sorted(SDK.glob("cmdline-tools/*/bin/sdkmanager"))[-1]
avdmanager = sdkmanager.with_name("avdmanager")
image_package = "system-images;android-35;default;x86_64"
# Empty stdin deliberately declines any new license prompt. Never use `yes --licenses` here.
run([sdkmanager, "emulator", image_package], timeout=600,
    output=OUT / "sdk-install.log", input=b"")
emulator = SDK / "emulator/emulator"
adb = SDK / "platform-tools/adb"
if not emulator.is_file() or not adb.is_file():
    raise RuntimeError("Official emulator/platform-tools are not installed")
run([emulator, "-version"], timeout=30, output=OUT / "emulator-version.log")
version_text = (OUT / "emulator-version.log").read_text(errors="replace")
version_match = re.search(r"Android emulator version ([\d.]+)", version_text)
emulator_version = version_match.group(1) if version_match else None
expected_emulator_version = os.environ.get("HETU_WINDOW_EXPECTED_EMULATOR_VERSION") or None
(OUT / "emulator-identity.json").write_text(json.dumps({"version": emulator_version,
    "expectedVersion": expected_emulator_version}, indent=2))
if expected_emulator_version and emulator_version != expected_emulator_version:
    raise RuntimeError("Diagnostic reproduction requires the identical emulator version; inspect emulator-version.log")
run([avdmanager, "create", "avd", "--force", "--name", "hetu_window_qa", "--package",
     image_package, "--device", "pixel_5"], timeout=60,
    output=OUT / "avd-create.log", input=b"no\n")
# Preserve the Pixel 5's approximately 393 x 852 dp viewport at fewer physical pixels.
# This changes only the disposable AVD display definition, not fonts or device security.
avd_config = Path(env["ANDROID_AVD_HOME"]) / "hetu_window_qa.avd/config.ini"
config = dict(line.split("=", 1) for line in avd_config.read_text().splitlines() if "=" in line)
config.update({"hw.lcd.width": "720", "hw.lcd.height": "1560", "hw.lcd.density": "293",
               "skin.name": "720x1560", "skin.path": "720x1560"})
avd_config.write_text("\n".join(f"{key}={value}" for key, value in config.items()) + "\n")
# Permissions are managed separately by the explicitly approved CI transaction.
# Never silently return to the known-unreliable CPU-only environment.
if not os.access("/dev/kvm", os.R_OK | os.W_OK):
    raise RuntimeError("KVM read/write access is unavailable; CPU fallback is disabled")
run([emulator, "-accel-check"], timeout=30, output=OUT / "acceleration-check.log")
acceleration = "on"
(OUT / "environment.json").write_text(json.dumps({"avd": "hetu_window_qa", "api": 35,
    "image": "default/x86_64", "imagePackage": image_package, "emulatorVersion": emulator_version,
    "vmAcceleration": acceleration, "graphics": "software",
    "physicalSize": [720, 1560], "densityDpi": 293, "installMode": "push-then-pm-install",
    "guestSecurityChanged": False, "hostKvmAclGrantedForThisRun": os.environ.get("HETU_KVM_ONCE") == "true",
    "fontsChanged": False, "newLicenseAccepted": False,
    "limits": "SwiftShader/software graphics in an Android hardware-accelerated window, not phone GPU or FPS validation."}, indent=2))
app_apk = Path(os.environ.get("HETU_WINDOW_APP_APK") or ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk")
test_apk = ROOT / "android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if not app_apk.is_file() or not test_apk.is_file():
    raise RuntimeError("Build both the reviewed app APK and its instrumentation test APK first")
app_digest = hashlib.sha256(app_apk.read_bytes()).hexdigest()
expected_digest = os.environ.get("HETU_WINDOW_EXPECTED_APK_SHA256", "")
if expected_digest and app_digest != expected_digest:
    raise RuntimeError("Diagnostic reproduction must use the identical previously tested production APK")
(OUT / "apk-identity.json").write_text(json.dumps({"path": str(app_apk), "sha256": app_digest,
    "expectedSha256": expected_digest or None}, indent=2))

emulator_log = (OUT / "emulator.log").open("wb")
process = subprocess.Popen([str(emulator), "-avd", "hetu_window_qa", "-port", "5556",
    "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-accel", acceleration,
    "-gpu", "software", "-memory", "2048"], env=env, stdout=emulator_log, stderr=subprocess.STDOUT)
recording = None
app_log = None
app_log_process = None
ready = False
installed = False
captured_names = set()
required_captures = ["report.json", "01-home-default-glass.png", "02-strategy-default-glass.png",
                     "03-inline-nodes-default-glass.png", "04-strategy-collapsed.png",
                     "05-overview-default-glass.png", "06-tools-default-glass.png", "07-settings-default-glass.png",
                     "motion-open-panel-middle.png", "motion-expand-nodes-middle.png",
                     "motion-collapse-nodes-middle.png", "motion-open-overview-middle.png",
                     "motion-open-tools-middle.png", "motion-open-settings-middle.png"]
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
    # Read-only startup check: do not dismiss system dialogs or relax ANR policy.
    record_stage("startup-health")
    startup_events = run([adb, "-s", SERIAL, "logcat", "-b", "events", "-d", "-v", "brief"], timeout=30)
    (OUT / "startup-events.log").write_bytes(startup_events.stdout + startup_events.stderr)
    event_text = startup_events.stdout.decode(errors="replace")
    if re.search(r"\bam_anr\b", event_text) or len(re.findall(r"\bam_crash\b", event_text)) >= 3:
        collect([adb, "-s", SERIAL, "shell", "dumpsys", "activity", "activities"], timeout=20,
            output=OUT / "startup-activity.txt")
        collect([adb, "-s", SERIAL, "shell", "dumpsys", "window", "windows"], timeout=20,
            output=OUT / "startup-window.txt")
        collect([adb, "-s", SERIAL, "logcat", "-b", "crash", "-d", "-v", "threadtime"], timeout=20,
            output=OUT / "startup-crash-logcat.txt")
        screenshot = collect([adb, "-s", SERIAL, "exec-out", "screencap", "-p"], timeout=20)
        if screenshot.returncode == 0 and capture_file(screenshot.stdout, "blocked-startup.png"):
            try:
                (OUT / "blocked-startup.png").write_bytes(screenshot.stdout)
            except OSError as error:
                print(f"Could not retain startup screenshot: {error}", flush=True)
        raise RuntimeError("Android startup has an ANR or repeated crashes; system state retained without dismissing dialogs")
    # A boot property may precede the launcher's first frame and first-boot work.
    # Observe actual readiness instead of racing Quickstep or waiting an arbitrary delay.
    record_stage("home-readiness")
    def read_home_state():
        resolution = run([adb, "-s", SERIAL, "shell", "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"], timeout=20)
        (OUT / "home-resolution.txt").write_bytes(resolution.stdout + resolution.stderr)
        component = home_component(resolution.stdout.decode(errors="replace"))
        home_text = {}
        for key, args in {
            "events": ["logcat", "-b", "events", "-d", "-v", "brief"],
            "activity": ["shell", "dumpsys", "activity", "activities"],
            "windows": ["shell", "dumpsys", "window"],
        }.items():
            result = run([adb, "-s", SERIAL] + args, timeout=20)
            home_text[key] = result.stdout.decode(errors="replace")
            (OUT / ("home-" + key + ".txt")).write_bytes(result.stdout + result.stderr)
        home_state = home_readiness(component, **home_text)
        (OUT / "home-readiness.json").write_text(json.dumps(home_state, indent=2))
        return home_state
    # Do not inject MENU into a launcher that has not finished starting.
    # The fresh SDK image completes its own initialization and home selection.
    await_home_readiness(read_home_state, time.monotonic, time.sleep, timeout_seconds=180)
    screenshot = run([adb, "-s", SERIAL, "exec-out", "screencap", "-p"], timeout=20)
    if not capture_file(screenshot.stdout, "home-ready.png"):
        raise RuntimeError("Could not retain the home readiness screenshot")
    (OUT / "home-ready.png").write_bytes(screenshot.stdout)
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
    recording = SegmentedRecording(adb, SERIAL, OUT, env, media_tools)
    result = recording.run([adb, "-s", SERIAL, "shell", "am", "instrument", "-w", "-r", "-e", "class",
        APP + ".HetuWindowUiTest", TEST_PACKAGE], timeout=900, output=OUT / "instrumentation.log")
    text = (OUT / "instrumentation.log").read_text(errors="replace")
    if result.returncode or "OK (1 test)" not in text:
        raise RuntimeError("Android window validation failed; inspect instrumentation.log")
    recording.require_valid()
except Exception as error:
    (OUT / "progress.json").write_text(json.dumps({"stage": stage, "status": "failed",
        "elapsedSeconds": round(time.monotonic() - stage_started, 1), "error": str(error)}, indent=2))
    raise
finally:
    if ready:
        # Retain late system ANRs as well as application errors. These are fresh test devices.
        collect([adb, "-s", SERIAL, "logcat", "-b", "events", "-d", "-v", "threadtime"], timeout=20,
            output=OUT / "final-events.log")
        collect([adb, "-s", SERIAL, "logcat", "-b", "system", "-b", "main", "-d", "-t", "4000", "-v", "threadtime"], timeout=20,
            output=OUT / "final-system.log")
        collect([adb, "-s", SERIAL, "shell", "dumpsys", "activity", "lastanr"], timeout=20,
            output=OUT / "last-anr.txt")
        if not installed:
            screenshot = collect([adb, "-s", SERIAL, "exec-out", "screencap", "-p"], timeout=20)
            if screenshot.returncode == 0 and capture_file(screenshot.stdout, "blocked-startup.png"):
                (OUT / "blocked-startup.png").write_bytes(screenshot.stdout)
    if ready and installed:
        collect([adb, "-s", SERIAL, "logcat", "-b", "crash", "-d", "-v", "threadtime"], timeout=30,
            output=OUT / "crash-logcat.txt")
        collect([adb, "-s", SERIAL, "shell", "dumpsys", "activity", "exit-info", APP], timeout=30,
            output=OUT / "app-exit-info.txt")
        rejected = []
        for name in required_captures + ["blocked-foreground.png"]:
            # Supported debug-app sandbox access, never adb root or a permission change.
            result = collect([adb, "-s", SERIAL, "exec-out", "run-as", APP, "cat", "files/window-ui-qa/" + name], timeout=20)
            if result.returncode == 0 and capture_file(result.stdout, name):
                (OUT / name).write_bytes(result.stdout)
                captured_names.add(name)
            else:
                rejected.append({"name": name, "reason": "missing or invalid file bytes", "exitCode": result.returncode})
        (OUT / "capture-status.json").write_text(json.dumps(rejected, indent=2))
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
try:
    missing = set(required_captures) - captured_names
    if missing:
        raise RuntimeError("Missing current-run Android evidence: " + ", ".join(sorted(missing)))
    report = json.loads((OUT / "report.json").read_text())
    require_reported_captures(report, captured_names)
    foreground_checks = report.get("foregroundChecks")
    if (report.get("passed") is not True or report.get("hardwareCanvasSeen") is not True
            or report.get("defaultGlassEnabled") is not True
            or report.get("runtimeShaderSupported") is not True
            or report.get("nativeBackupVerified") is not True
            or not isinstance(foreground_checks, list) or not foreground_checks
            or any(not isinstance(check, dict) or check.get("windowFocused") is not True
                   or check.get("activePackage") != APP for check in foreground_checks)):
        raise RuntimeError("Missing successful Android foreground/default-glass/hardware-window/RuntimeShader evidence")
except Exception as error:
    (OUT / "progress.json").write_text(json.dumps({"stage": "evidence", "status": "failed", "error": str(error)}, indent=2))
    raise
(OUT / "progress.json").write_text(json.dumps({"stage": "evidence", "status": "passed"}, indent=2))
print("Android default-glass window validation passed; screenshots, video segments and recording coverage retained")
