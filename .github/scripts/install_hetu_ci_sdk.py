#!/usr/bin/env python3
"""Retry only recognized transient failures of the unchanged official AOSP inputs.

No package removal, cache cleanup, license acceptance, permission mutation or
alternate version/source is performed. Every actual sdkmanager attempt remains
in its own raw log and JSON timing/exit record, including eventual failures.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

MAX_ATTEMPTS = 3
NON_TRANSIENT = (
    r'permission denied|accessdeniedexception',
    r'no space left on device|disk full',
    r'licenses?[^\n]*(?:not accepted|not been accepted)|license[^\n]*declined',
    r'failed to find package|unknown package|package[^\n]*not found',
    r'unknown (?:argument|option)|invalid (?:argument|option|package)',
    r'unsupportedclassversionerror|requires java|cannot create directory',
)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def transient_reason(output):
    text = output.lower()
    if any(re.search(pattern, text) for pattern in NON_TRANSIENT):
        return None
    context = re.search(r'(?:while preparing sdk package|while downloading|failed to download|error downloading)', text)
    if not context:
        return None
    if 'error on zipfile unknown archive' in text:
        return 'sdk_archive_zipfile_unknown_archive'
    if re.search(r'zipexception[^\n]*(?:zip end header not found|invalid (?:loc|cen) header|unexpected end)', text):
        return 'sdk_archive_truncated_zip'
    if re.search(r'java\.net\.sockettimeoutexception|read timed out|connect timed out|connection reset|unexpected end of file from server', text):
        return 'sdk_download_network_timeout_or_reset'
    if re.search(r'http (?:response code|status(?: code)?)\s*:?\s*(?:429|500|502|503|504)\b', text):
        return 'sdk_download_retryable_http_status'
    return None


def packages_for(api):
    if api not in (35, 36):
        raise ValueError('Only the original API35/36 default x86_64 AOSP inputs are allowed')
    return ['platform-tools', 'emulator', f'system-images;android-{api};default;x86_64']


def run_install(*, sdkmanager, api, output, retry_delay_seconds=5, timeout_seconds=600):
    packages = packages_for(api)
    sdkmanager = Path(sdkmanager).resolve(strict=True)
    if not sdkmanager.is_file() or not os.access(sdkmanager, os.X_OK):
        raise ValueError('The pre-existing sdkmanager must be an executable file')
    if not 0 <= retry_delay_seconds <= 10 or not 0 < timeout_seconds <= 600:
        raise ValueError('SDK preparation delay/timeout exceeds the bounded contract')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    command = [str(sdkmanager), *packages]
    report = {'schema': 1, 'result': 'FAIL', 'api': api, 'command': command,
              'packages': packages, 'maxAttempts': MAX_ATTEMPTS, 'startedUtc': utc_now(),
              'attempts': [], 'finalExitCode': 1,
              'scope': 'The original sdkmanager packages; transient installer failures only. No cleanup, license or permission changes.'}
    report_path = output / 'attempts.json'

    def persist():
        report_path.write_text(json.dumps(report, indent=2) + '\n')

    persist()
    for number in range(1, MAX_ATTEMPTS + 1):
        started = time.monotonic()
        row = {'number': number, 'startedUtc': utc_now(), 'command': command, 'retried': False}
        launch_error = None
        try:
            completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       timeout=timeout_seconds, check=False)
            raw, exit_code = completed.stdout, completed.returncode
        except subprocess.TimeoutExpired as error:
            raw, exit_code = error.output or b'', 124
            launch_error = 'sdkmanager_process_timeout'
        except OSError as error:
            raw, exit_code = str(error).encode(), 127
            launch_error = 'sdkmanager_launch_error'
        row.update(endedUtc=utc_now(), actualWallSeconds=time.monotonic() - started,
                   exitCode=exit_code, log=f'attempt-{number:02d}.log',
                   logBytes=len(raw), logSha256=hashlib.sha256(raw).hexdigest())
        (output / row['log']).write_bytes(raw)
        decoded = raw.decode(errors='replace')
        print(f'sdkmanager attempt {number}/{MAX_ATTEMPTS}: exit={exit_code}', flush=True)
        print(decoded, end='' if decoded.endswith('\n') else '\n', flush=True)
        reason = None if launch_error or exit_code < 0 else transient_reason(decoded)
        row['classification'] = 'success' if exit_code == 0 else launch_error or reason or 'nontransient_sdkmanager_failure'
        row['retried'] = exit_code != 0 and reason is not None and number < MAX_ATTEMPTS
        report['attempts'].append(row)
        report['finalExitCode'] = exit_code
        if exit_code == 0:
            report['result'] = 'PASS'
        report['endedUtc'] = utc_now()
        persist()
        if not row['retried']:
            break
        delay = retry_delay_seconds * number
        row['retryDelaySeconds'] = delay
        persist()
        time.sleep(delay)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdkmanager', type=Path, required=True)
    parser.add_argument('--api', type=int, choices=(35, 36), required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = run_install(sdkmanager=args.sdkmanager, api=args.api, output=args.output)
    print(json.dumps(result))
    sys.exit(result['finalExitCode'])
