#!/usr/bin/env python3
"""Supply one new Java dependency to the frozen V20.90 soak, without changing its assertions."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DRIVER = 'tools/qa/run_network_soak90.py'
DRIVER_SHA256 = '1e43cbd2b9b9aad758d84d70a4549f6e386e300492ff13562785b743c8b467ab'
ORIGINAL_SOURCES = "for name in ('MihomoControllerClient', 'NetworkEpoch', 'ProxyTaskCoalescer', 'ProxyNetworkState'):"
CURRENT_SOURCES = "for name in ('MihomoControllerClient', 'LatencyProbeBudget', 'NetworkEpoch', 'ProxyTaskCoalescer', 'ProxyNetworkState'):"


def adapt(source):
    if hashlib.sha256(source.encode()).hexdigest() != DRIVER_SHA256:
        raise ValueError('Frozen V20.90 soak bytes differ; do not silently rewrite its checks')
    if source.count(ORIGINAL_SOURCES) != 1:
        raise ValueError('Expected exactly one original Java source inventory')
    adjusted = source.replace(ORIGINAL_SOURCES, CURRENT_SOURCES, 1)
    assert adjusted.replace(CURRENT_SOURCES, ORIGINAL_SOURCES, 1) == source
    return adjusted


def run(duration, interval, output, root=ROOT):
    root, output = Path(root), Path(output)
    source_path = root / DRIVER
    source = source_path.read_text()
    adjusted = adapt(source)
    namespace = {'__file__': str(source_path.resolve()), '__name__': 'hetu_current_network_soak'}
    exec(compile(adjusted, str(source_path), 'exec'), namespace)
    output.mkdir(parents=True, exist_ok=True)
    proof = output / 'current-driver-adapter.json'
    if proof.exists():
        raise ValueError('Preserve prior adapter evidence; choose a new output directory')
    proof.write_text(json.dumps({'frozenDriver': DRIVER, 'frozenDriverSha256': DRIVER_SHA256,
        'transformation': 'Append LatencyProbeBudget to the verbatim Java compilation/source-hash inventory only',
        'harnessAssertionsChanged': False, 'requestedDurationSeconds': duration,
        'dependencySha256': hashlib.sha256((namespace['SOURCE'] / 'LatencyProbeBudget.java').read_bytes()).hexdigest()}, indent=2) + '\n')
    try:
        return namespace['run'](duration, interval, output)
    except subprocess.CalledProcessError as error:
        # The frozen driver captures javac stderr. Preserve and expose it without
        # swallowing its nonzero status or claiming the soak ever started.
        (output / 'compiler-stdout.txt').write_text(error.stdout or '')
        (output / 'compiler-stderr.txt').write_text(error.stderr or '')
        if error.stderr:
            print(error.stderr, file=sys.stderr, end='')
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--driver', choices=(DRIVER,), required=True)
    parser.add_argument('--duration', type=float, default=900)
    parser.add_argument('--interval', type=float, default=3)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.duration <= 0 or args.interval < 0:
        parser.error('duration must be positive; interval must be nonnegative')
    run(args.duration, args.interval, args.output)
