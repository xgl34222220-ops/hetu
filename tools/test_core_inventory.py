#!/usr/bin/env python3
"""Execute the shipped read-only inventory against synthetic /proc and sysfs only."""
import os
from pathlib import Path
import subprocess
import tempfile

SCRIPT = Path(__file__).resolve().parents[1] / 'android-app/app/src/main/assets/hetu-core-inventory.sh'
BOOT = '00000000-0000-4000-8000-000000000001'

with tempfile.TemporaryDirectory(prefix='hetu-core-inventory-') as directory:
    root = Path(directory)
    proc = root / 'proc'
    net = root / 'net'
    proc.mkdir(); net.mkdir()
    boot = proc / 'sys/kernel/random/boot_id'
    boot.parent.mkdir(parents=True); boot.write_text(BOOT + '\n')
    own = '/data/adb/hetu/bin/core'

    def process(pid, name='mihomo', exe=own, uid=0, ticks=100, parent=1):
        path = proc / str(pid); path.mkdir(exist_ok=True)
        (path / 'comm').write_text(name + '\n')
        (path / 'stat').write_text(f'{pid} ({name} closing ) bracket) S ' + ' '.join(['0'] * 18) + f' {ticks} 0\n')
        (path / 'status').write_text(f'Name:\t{name}\nPPid:\t{parent}\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n')
        (path / 'exe').symlink_to(exe)
        # Sensitive command-line data must never be read or emitted.
        (path / 'cmdline').write_bytes(b'mihomo\0-secret\0SYNTHETIC_PRIVATE_TOKEN\0/config/private-subscription.yaml\0')
        return path

    def run(extra=None):
        result = subprocess.run(['sh', str(SCRIPT), str(proc), str(net), own],
                                capture_output=True, text=True, timeout=10,
                                env=dict(os.environ, **(extra or {})), check=True)
        assert 'SYNTHETIC_PRIVATE' not in result.stdout
        assert 'private-subscription' not in result.stdout
        assert result.stdout.startswith('inventory\t1\n')
        assert result.stdout.rstrip().splitlines()[-1].startswith('complete\t')
        return result.stdout

    (proc / '1').mkdir(); (proc / '1/comm').write_text('magiskd\n')
    process(101)
    process(202, exe='/data/adb/modules/synthetic/bin/mihomo', ticks=200)
    process(303, name='unrelated-app', exe='/private/secret-path/app', uid=10023)
    (net / 'tun_test').mkdir(); (net / 'tun_test/tun_flags').write_text('1')
    result = run()
    assert 'process\t101\t100\t0\t1\tmihomo\tcore\thetu\tmagiskd' in result
    assert 'process\t202\t200\t0\t1\tmihomo\tmihomo\tmodule\tmagiskd' in result
    assert 'process\t303\t' not in result
    assert 'tun\ttun_test\n' in result
    assert 'synthetic/bin' not in result

    # An unreadable record is a gap, not a fabricated process or empty success.
    (proc / '404').mkdir()
    result = run()
    assert result.rstrip().endswith('\t1\t0')

    # Simulate PID replacement between metadata reads using only this fake proc tree.
    fakebin = root / 'bin'; fakebin.mkdir()
    real_readlink = subprocess.check_output(['sh', '-c', 'command -v readlink'], text=True).strip()
    wrapper = fakebin / 'readlink'
    wrapper.write_text('#!/usr/bin/env python3\nimport os,sys,subprocess,pathlib\n'
        'p=pathlib.Path(sys.argv[1])\n'
        'if p.parent.name=="202":\n'
        ' s=p.parent/"stat"; v=s.read_text(); s.write_text(v.replace(" 200 0\\n"," 201 0\\n"))\n'
        f'os.execv({real_readlink!r},[{real_readlink!r},*sys.argv[1:]])\n')
    wrapper.chmod(0o755)
    result = run({'PATH': str(fakebin) + os.pathsep + os.environ['PATH']})
    assert 'process\t202\t' not in result

    # More candidates never produce unbounded output; permission gaps stay explicit.
    for pid in range(500, 530): process(pid, name='xray', exe='/data/user/0/synthetic/files/xray', uid=10100)
    result = run()
    assert len([line for line in result.splitlines() if line.startswith('process\t')]) == 16
    assert result.rstrip().endswith('\t1')
    assert len(result) < 8192
    assert 'kill ' not in SCRIPT.read_text() and '/cmdline' not in SCRIPT.read_text()
print('Core inventory: dual core, ownership hints, unrelated process, privacy, missing reads, PID reuse and bounds passed')
