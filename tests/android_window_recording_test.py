"""Host-only recording regressions: fake subprocesses; no Android, Root or network."""
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from android_window_recording import SegmentedRecording, preflight_media_tools


class FakeClock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class FakeProcess:
    def __init__(self, host, kind, duration, code=0):
        self.host, self.kind, self.started = host, kind, host.clock()
        self.deadline, self.exit_code = self.started + duration, code
        self.returncode = None
        self.stopped = None

    def poll(self):
        if self.returncode is None and self.host.clock() >= self.deadline:
            self.returncode = self.exit_code
            self.stopped = self.deadline
        return self.returncode

    def wait(self, timeout):
        if self.poll() is None:
            raise subprocess.TimeoutExpired(self.kind, timeout)
        return self.returncode

    def terminate(self):
        self.returncode, self.stopped = -15, self.host.clock()

    def kill(self):
        self.returncode, self.stopped = -9, self.host.clock()


class FakeHost:
    def __init__(self, duration=560, test_code=0):
        self.clock = FakeClock()
        self.duration, self.test_code = duration, test_code
        self.recorders, self.commands = [], []
        self.test = None
        self.fail_launch = False
        self.fail_pull = set()
        self.corrupt = set()
        self.failed_recorder = None
        self.never_ready = False
        self.reject_signal = False
        self.fail_concat = False
        self.media_scale = 1.0

    def popen(self, command, **kwargs):
        self.commands.append(command)
        if command[0] == "instrumentation":
            self.test = FakeProcess(self, "instrumentation", self.duration, self.test_code)
            kwargs["stdout"].write(b"original instrumentation output\n")
            return self.test
        if self.fail_launch:
            raise OSError("screenrecord adb client failed to launch")
        index = len(self.recorders) + 1
        duration, code = (3, 17) if index == self.failed_recorder else (180, 0)
        process = FakeProcess(self, "recording", duration, code)
        self.recorders.append(process)
        kwargs["stdout"].write(b"screenrecord log retained\n")
        return process

    def media(self, index):
        process = self.recorders[index - 1]
        duration = max(0.25, (process.stopped or self.clock()) - process.started) * self.media_scale
        return duration, max(1, int(duration * 5))

    def run(self, command, **kwargs):
        command = list(map(str, command))
        self.commands.append(command)
        code, stdout, stderr = 0, b"", b""
        if command[0] == "adb" and "pull" in command:
            path = Path(command[-1])
            index = int(path.stem.split("-")[-1])
            if index in self.fail_pull:
                code, stderr = 1, b"remote file missing"
            else:
                path.write_bytes(b"retained fake MP4 input" * 100)
        elif command[0] == "adb" and "printf" in command[-1]:
            code, stdout = (4, b"") if self.never_ready else (0, b"42000\n")
        elif command[0] == "adb" and "kill -INT" in command[-1]:
            if self.reject_signal:
                code, stderr = 3, b"ownership mismatch"
            else:
                process = self.recorders[-1]
                process.returncode, process.stopped = 0, self.clock()
        elif command[0] == "ffprobe":
            path = Path(command[-1])
            if path.name == "interaction.mp4":
                duration, frames = map(sum, zip(*(self.media(i + 1) for i in range(len(self.recorders)))))
            else:
                index = int(path.stem.split("-")[-1])
                duration, frames = self.media(index)
                if index in self.corrupt:
                    stderr = b"Invalid NAL unit, corrupt video packet"
            stdout = json.dumps({"format": {"duration": str(duration)}, "streams": [{
                "codec_type": "video", "width": 720, "height": 1560, "nb_read_frames": str(frames)}]}).encode()
        elif command[0] == "ffmpeg":
            if self.fail_concat:
                raise OSError("ffmpeg execution failed")
            Path(command[-1]).write_bytes(b"retained fake concatenation" * 100)
        return subprocess.CompletedProcess(command, code, stdout, stderr)


class RecordingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.out = Path(self.temp.name)

    def recorder(self, host):
        return SegmentedRecording("adb", "emulator-5556", self.out, {},
                                  {"ffprobe": "ffprobe", "ffmpeg": "ffmpeg"},
                                  popen=host.popen, run=host.run, clock=host.clock,
                                  sleep=host.clock.sleep, run_id="hosttest")

    def execute(self, host, timeout=900):
        recorder = self.recorder(host)
        result = recorder.run(["instrumentation"], output=self.out / "instrumentation.log", timeout=timeout)
        return recorder, result

    def test_560_second_test_naturally_rotates_and_preserves_every_segment(self):
        host = FakeHost(duration=560)
        recorder, result = self.execute(host)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(recorder.segments), 4)
        self.assertEqual([item["stopReason"] for item in recorder.segments],
                         ["natural-limit"] * 3 + ["instrumentation-ended"])
        recorder.require_valid()
        self.assertEqual(recorder.coverage["instrumentationElapsedSeconds"], 560)
        self.assertEqual(recorder.coverage["mergedMediaDurationSeconds"], 560)
        self.assertFalse(recorder.coverage["fullInteractionCoverageVerified"])
        self.assertTrue(all(Path(item["file"]).is_file() and Path(item["log"]).is_file() for item in recorder.segments))
        stop_commands = [command[-1] for command in host.commands if "kill -INT" in command[-1]]
        self.assertEqual(len(stop_commands), 1)
        self.assertIn("/proc/$pid/cmdline", stop_commands[0])
        self.assertIn("segment-0004.mp4", stop_commands[0])
        self.assertNotIn("pkill", " ".join(" ".join(command) for command in host.commands))

    def test_test_end_interrupts_current_segment_without_waiting_for_180_seconds(self):
        recorder, _ = self.execute(FakeHost(duration=7.25))
        recorder.require_valid()
        self.assertEqual(len(recorder.segments), 1)
        self.assertEqual(recorder.segments[0]["ended"] - recorder.segments[0]["launched"], 7.25)

    def test_start_failure_never_launches_instrumentation_and_retains_log(self):
        host = FakeHost()
        host.fail_launch = True
        recorder = self.recorder(host)
        with self.assertRaisesRegex(OSError, "failed to launch"):
            recorder.run(["instrumentation"], output=self.out / "instrumentation.log")
        self.assertIsNone(host.test)
        self.assertEqual(recorder.segments[0]["stopReason"], "launch-failed")
        self.assertTrue((self.out / "recording-status.json").is_file())

    def test_not_ready_fails_before_instrumentation_with_bounded_wait(self):
        host = FakeHost()
        host.never_ready = True
        recorder = self.recorder(host)
        with self.assertRaisesRegex(RuntimeError, "startup bound"):
            recorder.run(["instrumentation"], output=self.out / "instrumentation.log")
        self.assertIsNone(host.test)
        self.assertLessEqual(host.clock(), 130.25)

    def test_mid_recording_failure_does_not_replace_test_failure(self):
        host = FakeHost(duration=220, test_code=42)
        host.failed_recorder = 2
        recorder, result = self.execute(host)
        self.assertEqual(result.returncode, 42)
        self.assertEqual(host.test.stopped, 320)
        self.assertTrue(any("segment 2 failed (17)" in error for error in recorder.errors))
        self.assertTrue((self.out / "instrumentation.log").read_text().startswith("original instrumentation"))

    def test_missing_segment_still_collects_later_segments_and_never_concatenates(self):
        host = FakeHost(duration=390)
        host.fail_pull = {2}
        recorder, _ = self.execute(host)
        self.assertEqual([item["pulled"] for item in recorder.segments], [True, False, True])
        self.assertFalse(any(command[0] == "ffmpeg" for command in host.commands))
        with self.assertRaisesRegex(RuntimeError, "not retrieved intact"):
            recorder.require_valid()

    def test_corrupt_media_with_zero_probe_exit_is_rejected_and_retained(self):
        host = FakeHost(duration=190)
        host.corrupt = {1}
        recorder, _ = self.execute(host)
        self.assertEqual([item["valid"] for item in recorder.segments], [False, True])
        self.assertTrue(Path(recorder.segments[0]["file"]).is_file())
        with self.assertRaisesRegex(RuntimeError, "decode .* cleanly"):
            recorder.require_valid()

    def test_timeout_survives_concatenation_failure(self):
        host = FakeHost(duration=100)
        host.fail_concat = True
        recorder = self.recorder(host)
        with self.assertRaises(subprocess.TimeoutExpired):
            recorder.run(["instrumentation"], output=self.out / "instrumentation.log", timeout=10)
        self.assertEqual(host.test.returncode, -9)
        self.assertTrue(any("ffmpeg execution failed" in error for error in recorder.errors))

    def test_nonzero_test_exit_survives_concatenation_failure(self):
        host = FakeHost(duration=10, test_code=5)
        host.fail_concat = True
        recorder, result = self.execute(host)
        self.assertEqual(result.returncode, 5)
        self.assertTrue(any("ffmpeg execution failed" in error for error in recorder.errors))

    def test_stale_pid_rejects_remote_signal_and_only_terminates_local_client(self):
        host = FakeHost(duration=10)
        host.reject_signal = True
        recorder, _ = self.execute(host)
        self.assertEqual(host.recorders[0].returncode, -15)
        self.assertTrue(any("verify/signal" in error for error in recorder.errors))
        self.assertFalse(any("pkill" in " ".join(command) for command in host.commands))
        with self.assertRaises(RuntimeError):
            recorder.require_valid()

    def test_positive_duration_alone_does_not_validate_a_truncated_capture(self):
        host = FakeHost(duration=560)
        host.media_scale = 0.3
        recorder, _ = self.execute(host)
        self.assertGreater(recorder.coverage["mergedMediaDurationSeconds"], 0)
        with self.assertRaisesRegex(RuntimeError, "materially shorter"):
            recorder.require_valid()

    def test_other_devices_and_oversized_segments_are_rejected(self):
        for serial, limit in (("emulator-5554", 180), ("physical-device", 180), ("emulator-5556", 181)):
            with self.subTest(serial=serial, limit=limit), self.assertRaises(ValueError):
                SegmentedRecording("adb", serial, self.out, {}, {}, segment_seconds=limit)


class PreflightTests(unittest.TestCase):
    def test_missing_or_broken_tool_fails_closed(self):
        for missing in ("ffmpeg", "ffprobe"):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as directory:
                calls = []
                def run(args, **kwargs):
                    calls.append(args)
                    return subprocess.CompletedProcess(args, 0, b"official tool version\n", b"")
                with self.assertRaisesRegex(RuntimeError, missing):
                    preflight_media_tools(directory, {}, run=run, which=lambda name: None if name == missing else name)
                self.assertTrue(all(command[-1] == "-version" for command in calls))
                self.assertTrue(json.loads((Path(directory) / "media-preflight.json").read_text())["errors"])

    def test_executable_that_cannot_run_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            def run(args, **kwargs):
                raise OSError("executable loader is unavailable")
            with self.assertRaisesRegex(RuntimeError, "preflight failed"):
                preflight_media_tools(directory, {}, run=run, which=lambda name: name)

    def test_success_checks_both_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            calls = []
            def run(args, **kwargs):
                calls.append(args)
                return subprocess.CompletedProcess(args, 0, b"tool version\n", b"")
            tools = preflight_media_tools(directory, {}, run=run, which=lambda name: "/usr/bin/" + name)
            self.assertEqual(set(tools), {"ffprobe", "ffmpeg"})
            self.assertEqual(len(calls), 2)


if __name__ == "__main__":
    unittest.main()
