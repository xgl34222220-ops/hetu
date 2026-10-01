#!/usr/bin/env python3
"""Exercise exact identity readers from the packaged script with synthetic /proc data.

No real process, Root, VPN or network actions are invoked. Shell cat/liveness
adapters access only this test's temporary fixtures; production parsing is intact.
"""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "android-app/app/src/main/assets/hetu-root.sh"
BOOT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
ADAPTERS = r'''
set -u
root(){ :; }
cat(){
  case "$1" in
    /proc/142/stat) command cat "$FIXTURES/stat";;
    /proc/sys/kernel/random/boot_id) command cat "$FIXTURES/boot";;
    "$PIDFILE") command cat "$FIXTURES/pid";;
    "$MODEFILE") command cat "$FIXTURES/mode";;
    *) return 1;;
  esac
}
core_maybe_alive(){ [ "$OWNED" = 1 ]; }
kill(){ [ "$OWNED" = 1 ]; }
'''

class RuntimeIdentityScriptTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="hetu-identity-proc-")
        self.path = Path(self.directory.name)
        self.stat("90112")
        (self.path / "boot").write_text(BOOT)
        (self.path / "pid").write_text("142")
        (self.path / "mode").write_text("tproxy")
        source = ASSET.read_text()
        self.functions = source[source.index("process_start_ticks(){"):source.index("start_stage(){")]

    def tearDown(self): self.directory.cleanup()

    def stat(self, ticks, name="core ) with (spaces)"):
        # State is field 3; the twentieth token after comm is starttime (field 22).
        (self.path / "stat").write_text("142 (" + name + ") " + " ".join(["S"]+["0"]*18+[ticks,"8192"]))

    def call(self, command, owned="1"):
        setup = 'FIXTURES="$1"; OWNED="$2"; PIDFILE=fixture-pid; MODEFILE=fixture-mode\n'
        return subprocess.run(["sh","-c",ADAPTERS+setup+self.functions+"\n"+command,"identity-test",str(self.path),owned],
                              capture_output=True,text=True,timeout=3)

    def test_kernel_stat_parsing_handles_spaces_and_parentheses(self):
        for name in ("core", "core with spaces", "core ) with (spaces)", "a) b) c"):
            self.stat("90112",name)
            result=self.call("process_start_ticks 142")
            self.assertEqual((result.returncode,result.stdout,result.stderr),(0,"90112",""))

    def test_invalid_pid_cannot_become_a_path_or_command(self):
        for value in ("0","../142","142/other","$(printf injection)","142;false",""):
            command="process_start_ticks '"+value+"'"
            result=self.call(command)
            self.assertNotEqual(result.returncode,0)
            self.assertEqual(result.stdout,"")

    def test_missing_or_malformed_stat_stays_unknown(self):
        for value in ("", "142 (core) S 1 2", "wrong (core) "+"0 "*30):
            (self.path / "stat").write_text(value)
            result=self.call("process_start_ticks 142")
            self.assertNotEqual(result.returncode,0)
            self.assertEqual(result.stdout,"")
        (self.path / "stat").unlink()
        self.assertNotEqual(self.call("process_start_ticks 142").returncode,0)

    def test_boot_identity_must_be_a_real_uuid_shape(self):
        self.assertEqual(self.call("process_boot_id").stdout,BOOT)
        for value in ("", "unknown", BOOT+'"', "../boot", BOOT+"\nextra"):
            (self.path / "boot").write_text(value)
            result=self.call("process_boot_id")
            self.assertNotEqual(result.returncode,0)
            self.assertEqual(result.stdout,"")

    def test_completed_start_returns_its_original_process_identity(self):
        command='START_PID=142; START_PROCESS_TICKS=90112; START_BOOT_ID='+BOOT+'; start_ok ready'
        value=json.loads(self.call(command).stdout)
        self.assertEqual(value["pid"],142)
        self.assertEqual(value["processStartTicks"],"90112")
        self.assertEqual(value["bootId"],BOOT)
        self.stat("90200")
        changed=json.loads(self.call(command).stdout)
        self.assertTrue(changed["ok"])
        self.assertEqual(changed["processStartTicks"],"")
        self.assertEqual(changed["bootId"],"")

    def test_process_observation_never_confirms_a_foreign_or_dead_pid(self):
        valid=json.loads(self.call("process_identity_json").stdout)
        self.assertTrue(valid["running"])
        self.assertEqual(valid["processStartTicks"],"90112")
        foreign=json.loads(self.call("process_identity_json",owned="0").stdout)
        self.assertFalse(foreign["running"])
        self.assertEqual(foreign["processStartTicks"],"")
        (self.path / "pid").write_text("invalid")
        invalid=json.loads(self.call("process_identity_json").stdout)
        self.assertFalse(invalid["running"])
        self.assertEqual(invalid["pid"],0)

if __name__ == "__main__": unittest.main(verbosity=2)
