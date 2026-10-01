"""Process-bounded screenrecord segments for the disposable window-QA emulator.

No Android execution occurs on import. Subprocess and clock dependencies are injectable
so the lifecycle, including failed evidence collection, can be tested on the host.
"""
from pathlib import Path
import json
import math
import shlex
import shutil
import subprocess
import sys
import time
import uuid


def preflight_media_tools(out, env, *, run=subprocess.run, which=shutil.which):
    """Fail before SDK downloads or emulator launch, retaining the exact tool failure."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    tools, errors = {}, []
    for name in ("ffprobe", "ffmpeg"):
        executable = which(name)
        if not executable:
            errors.append(f"Required media tool is unavailable: {name}")
            continue
        try:
            result = run([executable, "-version"], env=env, capture_output=True, timeout=15)
            (out / f"{name}-version.log").write_bytes(result.stdout + result.stderr)
            if result.returncode:
                errors.append(f"{name} preflight failed ({result.returncode})")
            else:
                tools[name] = executable
        except (OSError, subprocess.TimeoutExpired) as error:
            errors.append(f"{name} preflight failed: {error}")
    (out / "media-preflight.json").write_text(json.dumps({"tools": tools, "errors": errors}, indent=2))
    if errors:
        raise RuntimeError("; ".join(errors))
    return tools


class SegmentedRecording:
    """Keep recording until the instrumentation process exits, then collect every segment.

    A PID is read from our unique remote directory and its complete command line is
    checked again in the same shell command that sends SIGINT. Never use pkill/pidof.
    Media health and timing are evidence checks, not proof that each UI action worked.
    """

    def __init__(self, adb, serial, out, env, media_tools, *, segment_seconds=180,
                 popen=subprocess.Popen, run=subprocess.run, clock=time.monotonic,
                 sleep=time.sleep, run_id=None):
        if serial != "emulator-5556":
            raise ValueError("Window recording is restricted to the disposable emulator-5556")
        if not 1 <= segment_seconds <= 180:
            raise ValueError("Android screenrecord segments must be between 1 and 180 seconds")
        self.adb, self.serial, self.env = str(adb), serial, env
        self.out, self.tools = Path(out), media_tools
        self.limit = segment_seconds
        self.popen, self.run_process, self.clock, self.sleep = popen, run, clock, sleep
        token = run_id or uuid.uuid4().hex
        if not token.isalnum():
            raise ValueError("Recording identity must be alphanumeric")
        self.remote_dir = "/data/local/tmp/hetu-window-qa-" + token
        self.directory = self.out / ("recording-" + token)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.segments, self.errors = [], []
        self.current = None
        self.instrumentation = {"started": None, "ended": None, "returnCode": None}
        self.merged = None
        self.coverage = {"status": "not-started", "fullInteractionCoverageVerified": False}

    def _adb(self, *args):
        return [self.adb, "-s", self.serial, *map(str, args)]

    def _shell(self, script):
        return self._adb("shell", "sh -c " + shlex.quote(script))

    def _call(self, args, *, timeout=30, log=None):
        result = self.run_process(list(map(str, args)), env=self.env, capture_output=True, timeout=timeout)
        if log:
            Path(log).write_bytes(result.stdout + result.stderr)
        return result

    def _error(self, message):
        self.errors.append(str(message))

    def _save(self):
        document = {"serial": self.serial, "segmentLimitSeconds": self.limit,
                    "remoteDirectory": self.remote_dir, "instrumentation": self.instrumentation,
                    "segments": self.segments, "coverage": self.coverage, "errors": self.errors,
                    "mergedFile": str(self.merged) if self.merged else None,
                    "limits": "Screenrecord timing and decoded frames do not independently prove all UI interactions or phone GPU/FPS behavior."}
        (self.out / "recording-status.json").write_text(json.dumps(document, indent=2))

    def _start_segment(self):
        index = len(self.segments) + 1
        name = f"segment-{index:04d}"
        remote = f"{self.remote_dir}/{name}.mp4"
        segment = {"index": index, "remote": remote, "file": str(self.directory / (name + ".mp4")),
                   "log": str(self.directory / (name + ".log")), "launched": self.clock(),
                   "ready": None, "ended": None, "pid": None, "returnCode": None,
                   "stopReason": None, "pulled": False, "valid": False}
        self.segments.append(segment)
        stream = Path(segment["log"]).open("wb")
        script = (f"echo $$ > {shlex.quote(remote + '.pid')}; "
                  f"exec screenrecord --verbose --time-limit {self.limit} {shlex.quote(remote)}")
        try:
            process = self.popen(self._shell(script), env=self.env, stdout=stream, stderr=subprocess.STDOUT)
        except Exception:
            stream.close()
            segment["ended"] = self.clock()
            segment["stopReason"] = "launch-failed"
            raise
        self.current = (segment, process, stream)
        self._save()

    def _owned_process_script(self, segment):
        remote = segment["remote"]
        expected = f"screenrecord|--verbose|--time-limit|{self.limit}|{remote}|"
        return (f"pid=$(cat {shlex.quote(remote + '.pid')} 2>/dev/null) || exit 3; "
                "case \"$pid\" in ''|*[!0-9]*) exit 3;; esac; "
                "[ \"$pid\" -gt 1 ] || exit 3; "
                "actual=$(tr '\\000' '|' < /proc/$pid/cmdline) || exit 3; "
                f"[ \"$actual\" = {shlex.quote(expected)} ] || exit 3; ")

    def _check_ready(self):
        segment, _, _ = self.current
        result = self._call(self._shell(self._owned_process_script(segment) +
                            f"test -s {shlex.quote(segment['remote'])} || exit 4; printf '%s\\n' \"$pid\""), timeout=5)
        if result.returncode == 0 and result.stdout.strip().isdigit():
            segment["pid"] = int(result.stdout.strip())
            segment["ready"] = self.clock()
            self._save()
            return True
        return False

    def _finish_segment(self, reason):
        segment, process, stream = self.current
        segment.update(ended=self.clock(), returnCode=process.poll(), stopReason=reason)
        stream.close()
        self.current = None
        self._save()
        return segment

    def _await_first_segment(self):
        while self.current:
            segment, process, _ = self.current
            if process.poll() is not None:
                self._finish_segment("exited-before-instrumentation")
                raise RuntimeError("Screenrecord exited before instrumentation started")
            if self._check_ready():
                return
            if self.clock() - segment["launched"] >= 30:
                raise RuntimeError("Screenrecord did not produce a file within its 30-second startup bound")
            self.sleep(0.25)

    def _advance(self):
        if self.current is None:
            return
        segment, process, _ = self.current
        if process.poll() is not None:
            segment = self._finish_segment("natural-limit")
            if segment["returnCode"] != 0:
                raise RuntimeError(f"Screenrecord segment {segment['index']} failed ({segment['returnCode']})")
            # A failed recorder must not spin up hundreds of tiny segments.
            if segment["ended"] - segment["launched"] < max(1, self.limit - 5):
                raise RuntimeError(f"Screenrecord segment {segment['index']} ended unexpectedly early")
            self._start_segment()
            segment, _, _ = self.current
        if segment["ready"] is None:
            if not self._check_ready() and self.clock() - segment["launched"] >= 30:
                raise RuntimeError(f"Screenrecord segment {segment['index']} did not become ready")

    def _stop(self):
        if self.current is None:
            return
        segment, process, _ = self.current
        reason = "instrumentation-ended"
        if process.poll() is None:
            try:
                result = self._call(self._shell(self._owned_process_script(segment) + 'kill -INT "$pid"'),
                                    timeout=10, log=self.directory / f"segment-{segment['index']:04d}-stop.log")
                if result.returncode:
                    self._error(f"Could not verify/signal owned recorder for segment {segment['index']} ({result.returncode})")
                process.wait(timeout=20)
            except (OSError, subprocess.TimeoutExpired) as error:
                self._error(f"Recorder did not finalize normally: {error}")
                # This only terminates our local adb client. The disposable emulator is
                # torn down by the caller; no unrelated Android process is signalled.
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                reason = "local-adb-terminated"
        segment = self._finish_segment(reason)
        if segment["returnCode"] not in (0, 130, -2):
            self._error(f"Final screenrecord segment failed ({segment['returnCode']})")

    def _probe(self, path):
        result = self._call([self.tools["ffprobe"], "-v", "error", "-count_frames", "-select_streams", "v:0",
                             "-show_entries", "stream=codec_type,width,height,duration,nb_read_frames:format=duration",
                             "-of", "json", path], timeout=120, log=Path(str(path) + ".ffprobe.log"))
        if result.returncode or result.stderr.strip():
            raise RuntimeError(f"ffprobe could not decode {Path(path).name} cleanly ({result.returncode})")
        info = json.loads(result.stdout)
        streams = info.get("streams", [])
        duration = float(info.get("format", {}).get("duration", 0))
        if not streams or not math.isfinite(duration) or duration <= 0:
            raise RuntimeError(f"No usable video stream in {Path(path).name}")
        stream = streams[0]
        frames = int(stream.get("nb_read_frames", 0))
        if stream.get("codec_type") != "video" or frames < 1 or min(int(stream.get("width", 0)), int(stream.get("height", 0))) < 1:
            raise RuntimeError(f"No decoded video frames in {Path(path).name}")
        Path(str(path) + ".metadata.json").write_text(json.dumps(info, indent=2))
        return {"durationSeconds": duration, "decodedFrames": frames,
                "width": int(stream["width"]), "height": int(stream["height"])}

    def _collect_media(self):
        for segment in self.segments:
            try:
                result = self._call(self._adb("pull", segment["remote"], segment["file"]), timeout=60,
                                    log=self.directory / f"segment-{segment['index']:04d}-pull.log")
                path = Path(segment["file"])
                if result.returncode or not path.is_file() or path.stat().st_size == 0:
                    raise RuntimeError(f"Recording segment {segment['index']} was not retrieved intact")
                segment["pulled"] = True
                segment["media"] = self._probe(path)
                segment["valid"] = True
            except Exception as error:
                segment["error"] = str(error)
                self._error(error)
        if not self.segments or not all(segment["valid"] for segment in self.segments):
            self._error("Cannot assemble interaction recording: one or more segments are missing or invalid")
            return
        # Paths are relative generated filenames; ffmpeg concat needs no unsafe path mode.
        listing = self.directory / "concat.txt"
        listing.write_text("".join(f"file '{Path(segment['file']).name}'\n" for segment in self.segments))
        target = self.out / "interaction.mp4"
        result = self._call([self.tools["ffmpeg"], "-nostdin", "-v", "error", "-y", "-f", "concat", "-safe", "1",
                             "-i", listing, "-map", "0:v:0", "-c", "copy", "-movflags", "+faststart", target],
                            timeout=180, log=self.out / "screenrecord-concat.log")
        if result.returncode:
            raise RuntimeError(f"Recording concatenation failed ({result.returncode}); individual segments retained")
        merged_info = self._probe(target)
        self.merged = target
        (self.out / "video-metadata.json").write_text(json.dumps(merged_info, indent=2))
        self._check_coverage(merged_info)

    def _check_coverage(self, merged_info):
        start, end = self.instrumentation["started"], self.instrumentation["ended"]
        gaps = [{"afterSegment": previous["index"],
                 "seconds": round(max(0, (following["ready"] or following["ended"]) - previous["ended"]), 3)}
                for previous, following in zip(self.segments, self.segments[1:])]
        duration = sum(segment["media"]["durationSeconds"] for segment in self.segments)
        frames = sum(segment["media"]["decodedFrames"] for segment in self.segments)
        bounded = (start is not None and end is not None and self.segments[0]["ready"] is not None
                   and self.segments[0]["ready"] <= start and self.segments[-1]["ended"] >= end)
        elapsed = end - start if start is not None and end is not None else 0
        tolerance = max(5.0, elapsed * 0.05)
        self.coverage = {"status": "bounded-segments-with-reported-gaps" if bounded else "incomplete",
                         "instrumentationElapsedSeconds": round(elapsed, 3), "processBoundsCoverInstrumentation": bounded,
                         "segmentMediaDurationSeconds": duration, "mergedMediaDurationSeconds": merged_info["durationSeconds"],
                         "decodedFrames": frames, "rolloverGaps": gaps,
                         "durationToleranceSeconds": tolerance, "fullInteractionCoverageVerified": False}
        if not bounded or any(segment["ready"] is None for segment in self.segments):
            self._error("Recording process boundaries do not cover the instrumentation lifetime")
        if any(gap["seconds"] > 5 for gap in gaps):
            self._error("Recording has a rollover gap longer than five seconds; see recording-status.json")
        if duration + tolerance < elapsed or merged_info["durationSeconds"] + tolerance < elapsed:
            self._error("Decoded recording is materially shorter than the instrumentation lifetime")
        if abs(duration - merged_info["durationSeconds"]) > max(1.0, len(self.segments) * 0.25) or merged_info["decodedFrames"] != frames:
            self._error("Concatenated recording does not preserve all segment durations/frames")

    def run(self, command, *, output, timeout=900):
        """Return the test's status unchanged; cleanup failures remain separate evidence errors."""
        test_process = None
        monitoring = True
        try:
            result = self._call(self._adb("shell", "mkdir", "-p", self.remote_dir), timeout=15,
                                log=self.directory / "setup.log")
            if result.returncode:
                raise RuntimeError("Could not create isolated screenrecord evidence directory")
            self._start_segment()
            self._await_first_segment()
            with Path(output).open("wb") as stream:
                self.instrumentation["started"] = self.clock()
                test_process = self.popen(list(map(str, command)), env=self.env, stdout=stream, stderr=subprocess.STDOUT)
                self._save()
                while test_process.poll() is None:
                    if self.clock() - self.instrumentation["started"] >= timeout:
                        test_process.kill()
                        test_process.wait(timeout=10)
                        raise subprocess.TimeoutExpired(command, timeout)
                    if monitoring:
                        try:
                            self._advance()
                        except Exception as error:
                            self._error(error)
                            monitoring = False
                    self.sleep(0.25)
                self.instrumentation["returnCode"] = test_process.returncode
                return subprocess.CompletedProcess(command, test_process.returncode)
        finally:
            if test_process is not None:
                if test_process.poll() is None:
                    try:
                        test_process.terminate()
                        try:
                            test_process.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            test_process.kill()
                            test_process.wait(timeout=10)
                    except Exception as error:
                        self._error(f"Could not stop local instrumentation client: {error}")
                self.instrumentation["ended"] = self.clock()
                self.instrumentation["returnCode"] = test_process.poll()
            # Neither missing files nor media tooling can replace a test failure/timeout.
            for cleanup in (self._stop, self._collect_media):
                try:
                    cleanup()
                except Exception as error:
                    self._error(error)
            if self.errors:
                self.coverage["status"] = "incomplete"
            try:
                self._save()
            except Exception as error:
                self._error(f"Could not save final recording status: {error}")
                print(self.errors[-1], file=sys.stderr)

    def require_valid(self):
        if self.errors or self.merged is None or not self.coverage.get("processBoundsCoverInstrumentation"):
            raise RuntimeError("Interaction recording evidence failed; inspect recording-status.json: " + "; ".join(self.errors))
