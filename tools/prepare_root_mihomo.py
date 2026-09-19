#!/usr/bin/env python3
"""Install the small, pinned Root-only Mihomo keepalive policy adaptation."""
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_SETTER = '''func SetDisableKeepAlive(disable bool) {
\tif runtime.GOOS == "android" {
\t\tsetDisableKeepAlive(true)
\t} else {
\t\tsetDisableKeepAlive(disable)
\t}
}'''
HETU_SETTER = '''func SetDisableKeepAlive(disable bool) {
\tsetDisableKeepAlive(hetuDisableKeepAlive(disable, runtime.GOOS))
}'''


def prepare(upstream: Path):
    package = upstream / 'component/keepalive'
    source = package / 'tcp_keepalive.go'
    text = source.read_text()
    if text.count(UPSTREAM_SETTER) == 1:
        source.write_text(text.replace(UPSTREAM_SETTER, HETU_SETTER, 1))
    elif text.count(HETU_SETTER) != 1:
        raise RuntimeError('Upstream keepalive implementation changed; review the Root adaptation before building')
    for policy in sorted((ROOT / 'native/root/keepalive').glob('*.go')):
        shutil.copyfile(policy, package / policy.name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('upstream', type=Path)
    prepare(parser.parse_args().upstream)
