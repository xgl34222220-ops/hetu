#!/usr/bin/env python3
"""Real native Mihomo data-plane soak, only inside the live owned AOSP supervisor.

The original Android x86_64 CLI runs as shell UID 2000 in a new private nonce
folder. All three upstream servers bind host 127.0.0.1; adb transports are owned
and removed individually. No app preferences, Root, VPN, TPROXY, network rules,
accounts, public servers or security permissions are touched. The imported
entry point must run in the existing smoke process whose parent is the runner.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import select
import shlex
import shutil
import signal
import socket
import socketserver
import struct
import subprocess
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
PAYLOAD = 'assets/mihomo-root/x86_64/mihomo'
SERIAL = 'emulator-5554'
MAX_HEADER = 8192
MAX_BODY = 4096
INTERVAL = 3.0
REQUEST_TIMEOUT = 2.5
MAX_RSS_KIB = 384 * 1024
MAX_FDS = 128


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def recv_exact(stream, count):
    result = bytearray()
    while len(result) < count:
        chunk = stream.recv(count - len(result))
        if not chunk:
            raise EOFError('Fixture socket closed before its bounded response')
        result.extend(chunk)
    return bytes(result)


def recv_header(stream):
    result = bytearray()
    while not result.endswith(b'\r\n\r\n'):
        if len(result) >= MAX_HEADER:
            raise ValueError('Fixture HTTP header exceeds bound')
        result.extend(recv_exact(stream, 1))
    return bytes(result)


def payload_bytes(nonce, sample):
    return f'hetu-native-soak90/{nonce}/{sample}\n'.encode('ascii')


class FixtureState:
    def __init__(self, nonce):
        if not re.fullmatch(r'[a-f0-9]{32}', nonce):
            raise ValueError('Invalid fixture nonce')
        self.nonce = nonce
        self.mode = 'normal'
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.events = []
        self.active = set()
        self.permits = threading.BoundedSemaphore(8)
        self.rejected = 0

    @property
    def suffix(self):
        return '.' + self.nonce + '.fixture.invalid'

    def set_mode(self, mode):
        if mode not in ('normal', 'drop', 'hang'):
            raise ValueError('Invalid controlled fixture phase')
        with self.lock:
            self.mode = mode

    def snapshot_mode(self):
        with self.lock:
            return self.mode

    def record(self, **event):
        with self.lock:
            if len(self.events) >= 4000:
                raise RuntimeError('Fixture request evidence exceeded bound')
            self.events.append(dict(atMonotonic=time.monotonic(), **event))

    @contextmanager
    def own_socket(self, stream):
        with self.lock:
            self.active.add(stream)
        try:
            yield stream
        finally:
            with self.lock:
                self.active.discard(stream)
            stream.close()

    def close_sockets(self):
        self.stop.set()
        with self.lock:
            streams = list(self.active)
        for stream in streams:
            try:
                stream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            stream.close()


class BoundedServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = False
    daemon_threads = True
    block_on_close = False

    def process_request(self, request, client_address):
        if not self.state.permits.acquire(blocking=False):
            with self.state.lock:
                self.state.rejected += 1
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.state.permits.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.state.permits.release()

    def handle_error(self, request, client_address):
        self.state.record(kind='fixture-handler-error')


class OriginHandler(socketserver.BaseRequestHandler):
    def handle(self):
        state = self.server.state
        with state.own_socket(self.request) as stream:
            stream.settimeout(4)
            try:
                header = recv_header(stream)
                line = header.split(b'\r\n', 1)[0].decode('ascii')
                match = re.fullmatch(r'GET /payload/' + state.nonce + r'/([0-9]{1,5}) HTTP/1\.1', line)
                if not match:
                    state.record(kind='rejected-origin-request')
                    return
                sample = int(match[1])
                body = payload_bytes(state.nonce, sample)
                stream.sendall(b'HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: '
                               + str(len(body)).encode() + b'\r\n\r\n' + body)
                state.record(kind='origin', sample=sample, payloadSha256=sha256(body))
            except (OSError, EOFError, ValueError, UnicodeError):
                return


class ConnectHandler(socketserver.BaseRequestHandler):
    def handle(self):
        state = self.server.state
        with state.own_socket(self.request) as client:
            client.settimeout(4)
            try:
                line = recv_header(client).split(b'\r\n', 1)[0].decode('ascii')
                match = re.fullmatch(r'CONNECT (s[0-9]{1,5}' + re.escape(state.suffix) + r'):80 HTTP/1\.[01]', line)
                if not match:
                    state.record(kind='rejected-connect-request')
                    return
                sample = int(match[1].split('.', 1)[0][1:])
                mode = state.snapshot_mode()
                state.record(kind='connect', sample=sample, mode=mode, target=match[1] + ':80')
                if mode == 'drop':
                    return
                if mode == 'hang':
                    # Captured per request: a later normal phase cannot turn this
                    # original stalled socket into a successful new observation.
                    if state.stop.wait(REQUEST_TIMEOUT + 1.0):
                        return
                    state.record(kind='late-connect-response', sample=sample)
                with state.own_socket(socket.create_connection(('127.0.0.1', self.server.origin_port), timeout=4)) as origin:
                    client.sendall(b'HTTP/1.1 200 Connection established\r\n\r\n')
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline and not state.stop.is_set():
                        ready, _, _ = select.select([client, origin], [], [], .2)
                        for source in ready:
                            data = source.recv(4096)
                            if not data:
                                return
                            (origin if source is client else client).sendall(data)
            except (OSError, EOFError, ValueError, UnicodeError):
                return


def dns_question(packet):
    if len(packet) < 17:
        raise ValueError('Short DNS question')
    identity, flags, questions, answers, authorities, extra = struct.unpack('!6H', packet[:12])
    if flags & 0x8000 or questions != 1 or answers or authorities:
        raise ValueError('Unexpected DNS query topology')
    labels, offset = [], 12
    while True:
        if offset >= len(packet):
            raise ValueError('Missing DNS label')
        length = packet[offset]
        offset += 1
        if not length:
            break
        if length > 63 or offset + length > len(packet):
            raise ValueError('Invalid DNS label')
        labels.append(packet[offset:offset + length].decode('ascii').lower())
        offset += length
        if len(labels) > 8:
            raise ValueError('Too many fixture DNS labels')
    if offset + 4 > len(packet):
        raise ValueError('Missing DNS question type')
    record_type, record_class = struct.unpack('!2H', packet[offset:offset + 4])
    return identity, '.'.join(labels), record_type, record_class, offset + 4


def dns_name_end(packet, offset):
    """Advance one bounded wire-format name; compression is allowed in answers."""
    for _ in range(128):
        if offset >= len(packet):
            raise ValueError('Incomplete DNS answer name')
        length = packet[offset]
        offset += 1
        if not length:
            return offset
        if length & 0xc0 == 0xc0:
            if offset >= len(packet):
                raise ValueError('Incomplete DNS name pointer')
            return offset + 1
        if length > 63 or offset + length > len(packet):
            raise ValueError('Invalid DNS answer label')
        offset += length
    raise ValueError('DNS answer name exceeds bound')


class DnsHandler(socketserver.BaseRequestHandler):
    def handle(self):
        state = self.server.state
        with state.own_socket(self.request) as stream:
            stream.settimeout(4)
            try:
                # Mihomo may reuse a TCP DNS upstream connection.
                for _ in range(512):
                    size = struct.unpack('!H', recv_exact(stream, 2))[0]
                    if size > 1024:
                        raise ValueError('DNS query exceeds fixture bound')
                    packet = recv_exact(stream, size)
                    identity, name, kind, cls, end = dns_question(packet)
                    valid = bool(re.fullmatch(r's[0-9]{1,5}' + re.escape(state.suffix), name)) and kind == 1 and cls == 1
                    question = packet[12:end]
                    if valid:
                        answer = b'\xc0\x0c' + struct.pack('!HHIH', 1, 1, 0, 4) + socket.inet_aton('127.0.0.1')
                        reply = struct.pack('!6H', identity, 0x8180, 1, 1, 0, 0) + question + answer
                    else:
                        reply = struct.pack('!6H', identity, 0x8185, 1, 0, 0, 0) + question
                    state.record(kind='dns-upstream', name=name, accepted=valid)
                    stream.sendall(struct.pack('!H', len(reply)) + reply)
            except (OSError, EOFError, ValueError, UnicodeError):
                return


@contextmanager
def controlled_fixtures(nonce):
    state = FixtureState(nonce)
    servers, threads = [], []
    try:
        for handler in (OriginHandler, ConnectHandler, DnsHandler):
            server = BoundedServer(('127.0.0.1', 0), handler)
            server.state = state
            if handler is ConnectHandler:
                server.origin_port = servers[0].server_address[1]
            servers.append(server)
            thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .1}, daemon=True)
            thread.start()
            threads.append(thread)
        yield state, servers[1].server_address[1], servers[2].server_address[1]
    finally:
        state.close_sockets()
        for server in servers:
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(timeout=2)
        if any(thread.is_alive() for thread in threads):
            raise RuntimeError('Controlled loopback fixture server did not stop')


def proxy_probe(port, nonce, sample, timeout=REQUEST_TIMEOUT):
    target = f's{sample}.{nonce}.fixture.invalid:80'
    with socket.create_connection(('127.0.0.1', port), timeout=timeout) as stream:
        stream.settimeout(timeout)
        stream.sendall(f'CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n\r\n'.encode('ascii'))
        first = recv_header(stream)
        if not first.startswith(b'HTTP/1.1 200 ') and not first.startswith(b'HTTP/1.0 200 '):
            raise ConnectionError('Native CONNECT did not return HTTP200: ' + first.split(b'\r\n', 1)[0].decode('ascii', errors='replace'))
        stream.sendall(f'GET /payload/{nonce}/{sample} HTTP/1.1\r\nHost: {target}\r\nConnection: close\r\n\r\n'.encode('ascii'))
        header = recv_header(stream)
        if not header.startswith(b'HTTP/1.1 200 '):
            raise ValueError('Fixture origin did not return HTTP200')
        lengths = re.findall(rb'(?im)^Content-Length: ([0-9]+)\r$', header)
        if len(lengths) != 1 or int(lengths[0]) > MAX_BODY:
            raise ValueError('Invalid bounded origin content length')
        body = recv_exact(stream, int(lengths[0]))
        if body != payload_bytes(nonce, sample):
            raise ValueError('Wrong or stale sample payload')
        return {'payloadBytes': len(body), 'payloadSha256': sha256(body)}


def dns_probe(port, nonce, sample, timeout=REQUEST_TIMEOUT):
    identity = sample % 65536
    labels = f's{sample}.{nonce}.fixture.invalid'.split('.')
    question = b''.join(bytes([len(label)]) + label.encode('ascii') for label in labels) + b'\0' + struct.pack('!HH', 1, 1)
    packet = struct.pack('!6H', identity, 0x0100, 1, 0, 0, 0) + question
    with socket.create_connection(('127.0.0.1', port), timeout=timeout) as stream:
        stream.settimeout(timeout)
        stream.sendall(struct.pack('!H', len(packet)) + packet)
        size = struct.unpack('!H', recv_exact(stream, 2))[0]
        if size > 4096:
            raise ValueError('Native DNS response exceeds bound')
        response = recv_exact(stream, size)
    if len(response) < 12:
        raise ValueError('Short native DNS response')
    got_id, flags, questions, answers, _, _ = struct.unpack('!6H', response[:12])
    if got_id != identity or flags & 0x000f or not flags & 0x8000 or questions != 1 or answers != 1:
        raise ValueError('Native DNS result is not a successful single fixture answer')
    if response[12:12 + len(question)] != question:
        raise ValueError('Wrong or stale native DNS answer')
    offset = dns_name_end(response, 12 + len(question))
    if offset + 10 > len(response):
        raise ValueError('Incomplete native DNS answer')
    kind, cls, _, size = struct.unpack('!HHIH', response[offset:offset + 10])
    address = response[offset + 10:offset + 10 + size]
    if kind != 1 or cls != 1 or size != 4 or address != socket.inet_aton('127.0.0.1'):
        raise ValueError('Native DNS returned a different fixture address')
    return {'responseBytes': len(response), 'responseSha256': sha256(response), 'address': '127.0.0.1'}


def configuration(mixed, dns, upstream, dns_upstream):
    # Explicit literal loopback: no provider, geodata rule, subscription or URL
    # can make this fixture access an uncontrolled target.
    ports = (mixed, dns, upstream, dns_upstream)
    if len(set(ports)) != 4 or any(not 1024 < value < 65536 for value in ports):
        raise ValueError('Fixture ports must be distinct unprivileged TCP ports')
    return f'''mixed-port: {mixed}
allow-lan: false
bind-address: 127.0.0.1
mode: rule
log-level: error
ipv6: false
find-process-mode: off
geo-auto-update: false
tun:
  enable: false
profile:
  store-selected: false
  store-fake-ip: false
dns:
  enable: true
  listen: 127.0.0.1:{dns}
  ipv6: false
  enhanced-mode: normal
  use-hosts: false
  use-system-hosts: false
  nameserver:
    - tcp://127.0.0.1:{dns_upstream}
proxies:
  - name: fixture-upstream
    type: http
    server: 127.0.0.1
    port: {upstream}
rules:
  - MATCH,fixture-upstream
'''.encode('ascii')


class Guest:
    def __init__(self, environment):
        self.environment = environment
        self.adb = str(Path(environment['ANDROID_HOME']) / 'platform-tools/adb')

    def run(self, *args, timeout=15, check=True, input=None):
        result = subprocess.run([self.adb, '-s', SERIAL, *args], input=input, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout, check=check, env=self.environment)
        return result.stdout.decode(errors='replace').strip()

    def shell(self, command, **kwargs):
        return self.run('shell', 'sh', '-c', shlex.quote(command), **kwargs)


def owned_target(environment, guest):
    spec = importlib.util.spec_from_file_location('hetu_soak_owned_runner', ROOT / '.github/scripts/run_hetu_emulator.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    owned = module.verify_owned_emulator_target(environment)
    if guest.run('get-serialno') != SERIAL or guest.run('shell', 'id', '-u') != '2000':
        raise RuntimeError('Native soak requires this owned non-root shell emulator')
    if guest.run('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('Native soak requires an emulator guest')
    api = int(guest.run('shell', 'getprop', 'ro.build.version.sdk'))
    if api not in (35, 36) or api != owned['apiLevel']:
        raise RuntimeError('Native soak guest API does not match its owned AOSP configuration')
    if guest.run('shell', 'getprop', 'ro.product.cpu.abi') != 'x86_64':
        raise RuntimeError('Native soak requires the original x86_64 payload ABI')
    return dict(owned, serial=SERIAL, shellUid=2000, guestApi=api, guestAbi='x86_64')


def native_payload():
    source = ROOT / 'android-app/app/src/main' / PAYLOAD
    data = source.read_bytes()
    expected = json.loads((ROOT / 'UI92_RUNTIME146_INPUTS.json').read_text())['original146_payload'][PAYLOAD]
    if sha256(data) != expected:
        raise RuntimeError('Native soak payload differs from the pinned original146 manifest')
    # ELF64/little-endian/x86_64; Android linker rather than a host Linux replacement.
    if data[:6] != b'\x7fELF\x02\x01' or struct.unpack('<H', data[18:20])[0] != 62 or b'/system/bin/linker64\0' not in data:
        raise RuntimeError('Native soak requires the original Android x86_64 executable')
    return source, expected, len(data)


def process_identity(guest, pid, directory):
    if not isinstance(pid, int) or pid <= 0 or not re.fullmatch(r'/data/local/tmp/hetu-soak90-[a-f0-9]{32}', directory):
        raise RuntimeError('Invalid owned guest process identity input')
    stat = guest.run('shell', 'cat', f'/proc/{pid}/stat')
    close = stat.rfind(')')
    fields = stat[close + 2:].split()
    if close < 0 or len(fields) < 20 or stat.split(' ', 1)[0] != str(pid) or fields[0] in ('Z', 'X'):
        raise RuntimeError('Owned native core is no longer live')
    executable = guest.run('shell', 'readlink', f'/proc/{pid}/exe')
    command = guest.shell(f'tr "\\000" "\\n" < /proc/{pid}/cmdline').splitlines()
    if executable != directory + '/mihomo' or command != [directory + '/mihomo', '-d', directory, '-f', directory + '/config.yaml']:
        raise RuntimeError('Guest PID no longer belongs to the exact nonce native core')
    return {'pid': pid, 'startTicks': int(fields[19]), 'executable': executable, 'argv': command}


def live_identity_or_none(guest, identity, directory):
    """Only a missing/exited process is absent; foreign argv/start ticks fail."""
    pid = identity['pid']
    if guest.shell(f'if test -e /proc/{pid}/stat; then printf exists; fi') != 'exists':
        return None
    # Zombies cannot run or receive signals, but are still awaiting their owner.
    stat = guest.run('shell', 'cat', f'/proc/{pid}/stat')
    close = stat.rfind(')')
    fields = stat[close + 2:].split()
    if close >= 0 and len(fields) >= 20 and int(fields[19]) == identity['startTicks'] and fields[0] in ('Z', 'X'):
        return None
    current = process_identity(guest, pid, directory)
    if current != identity:
        raise RuntimeError('Core identity changed; refusing to kill foreign/reused PID')
    return current


def resources(guest, identity, directory):
    current = process_identity(guest, identity['pid'], directory)
    if current != identity:
        raise RuntimeError('Native core PID identity changed during soak')
    status = guest.run('shell', 'cat', f"/proc/{identity['pid']}/status")
    rss = re.search(r'^VmRSS:\s*([0-9]+)\s+kB$', status, re.MULTILINE)
    uids = re.search(r'^Uid:\s*([0-9]+)\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)$', status, re.MULTILINE)
    if not rss or not uids or set(uids.groups()) != {'2000'}:
        raise RuntimeError('Could not verify native core memory or non-root process UID')
    descriptors = guest.run('shell', 'ls', f"/proc/{identity['pid']}/fd").split()
    if not descriptors or any(not value.isdigit() for value in descriptors):
        raise RuntimeError('Could not measure native core descriptor count')
    result = {'atMonotonic': time.monotonic(), 'pid': identity['pid'], 'startTicks': identity['startTicks'],
              'rssKiB': int(rss[1]), 'fileDescriptors': len(descriptors)}
    if result['rssKiB'] > MAX_RSS_KIB or result['fileDescriptors'] > MAX_FDS:
        raise RuntimeError('Native core exceeded the bounded soak resource budget: ' + json.dumps(result))
    return result


def guest_ports(guest):
    used = set()
    for kind in ('forward', 'reverse'):
        for line in guest.run(kind, '--list').splitlines():
            used.update(int(value) for value in re.findall(r'tcp:([0-9]+)', line))
    # Read guest listening TCP sockets as well; never steal an existing listener.
    for filename in ('/proc/net/tcp', '/proc/net/tcp6'):
        for line in guest.run('shell', 'cat', filename).splitlines()[1:]:
            fields = line.split()
            if len(fields) >= 4 and fields[3] == '0A':
                used.add(int(fields[1].rsplit(':', 1)[1], 16))
    chosen = []
    offset = int(uuid.uuid4().hex[:4], 16) % 800
    for port in range(30000 + offset, 32000):
        if port not in used:
            chosen.append(port)
        if len(chosen) == 4:
            return chosen
    raise RuntimeError('No four unused private guest TCP fixture ports')


def phase_for(elapsed, seconds):
    faults = [('drop', seconds * .30, 10.0), ('hang', seconds * .65, 10.0)]
    return next((name for name, start, span in faults if start <= elapsed < start + span), 'normal')


def evaluate(samples, seconds, elapsed, fixture_events, resource_samples):
    if elapsed < seconds or seconds < 900:
        raise RuntimeError('A passing native soak requires at least 900 seconds of actual wall time')
    expected_minimum = int(seconds / INTERVAL * .80)
    if len(samples) < expected_minimum:
        raise RuntimeError('Native soak did not issue enough continuous request samples')
    if any(not sample.get('dnsPassed') for sample in samples):
        raise RuntimeError('Native DNS failed during a controlled proxy-upstream fault or continuous run')
    failures = [sample for sample in samples if sample['phase'] == 'normal' and not sample['proxyPassed']]
    if failures:
        raise RuntimeError('Normal native data-plane request failed: ' + json.dumps(failures[:3]))
    recoveries = []
    for name, begin in [('drop', seconds * .30), ('hang', seconds * .65)]:
        fault = [sample for sample in samples if sample['phase'] == name]
        if len(fault) < 2 or any(sample['proxyPassed'] for sample in fault):
            raise RuntimeError('Controlled ' + name + ' phase was not actually observed as a data-plane failure')
        if name == 'hang' and not all(sample.get('errorType') in ('TimeoutError', 'timeout') for sample in fault):
            raise RuntimeError('Controlled stalled upstream did not exercise an actual client timeout')
        recovered = [sample for sample in samples if sample['phase'] == 'normal' and sample['proxyPassed'] and sample['elapsedStartSeconds'] >= begin + 10]
        if not recovered or recovered[0]['elapsedEndSeconds'] - (begin + 10) > 30:
            raise RuntimeError('Native data plane did not recover within 30 seconds after ' + name)
        recoveries.append({'fault': name, 'faultSamples': len(fault),
                           'firstRecoveredSample': recovered[0]['sample'],
                           'recoverySecondsAfterWindow': recovered[0]['elapsedEndSeconds'] - (begin + 10)})
    connected = {event['sample'] for event in fixture_events if event['kind'] == 'connect'}
    origins = {event['sample']: event['payloadSha256'] for event in fixture_events if event['kind'] == 'origin'}
    late = {event['sample'] for event in fixture_events if event['kind'] == 'late-connect-response'}
    for sample in samples:
        if sample['sample'] not in connected:
            raise RuntimeError('Sample did not traverse the controlled real CONNECT upstream')
        if sample['proxyPassed'] and origins.get(sample['sample']) != sample['payloadSha256']:
            raise RuntimeError('Sample response lacks the matching controlled origin payload hash')
    if not late:
        raise RuntimeError('Stalled upstream did not produce its original late response evidence')
    if not resource_samples:
        raise RuntimeError('No native core resource observations were made')
    return {'recoveries': recoveries, 'normalPassed': sum(sample['phase'] == 'normal' for sample in samples),
            'expectedFaultSamples': sum(sample['phase'] != 'normal' for sample in samples),
            'actualClientTimeouts': sum(sample.get('errorType') in ('TimeoutError', 'timeout') for sample in samples),
            'lateOriginalResponses': len(late), 'resourceBudget': {'rssKiB': MAX_RSS_KIB, 'fileDescriptors': MAX_FDS},
            'maxRssKiB': max(value['rssKiB'] for value in resource_samples),
            'maxFileDescriptors': max(value['fileDescriptors'] for value in resource_samples)}


def run_soak(*, output, seconds=900, environment=None):
    """Called directly inside smoke_hetu_apk.py, retaining runner parent identity.

    Any failure writes results.json before raising; short durations are refused
    before any guest mutation. API35/36 may call this; CI runs API36 once.
    """
    environment = dict(os.environ if environment is None else environment)
    seconds = int(seconds)
    if not 900 <= seconds <= 1800:
        raise ValueError('Native soak must run for 900..1800 real wall-clock seconds')
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    guest = Guest(environment)
    report = {'schema': 1, 'result': 'FAIL', 'startedUtc': utc_now(), 'requestedSeconds': seconds,
              'scope': 'original native Android Mihomo CLI mixed-port CONNECT and TCP DNS data plane',
              'fixtureOnly': True, 'rootMutationActions': 0, 'appPreferencesChanged': False,
              'coreRestarts': 0, 'limitations': 'Owned AOSP shell-UID CLI only. This is not app VPN/TPROXY, OEM background, Wi-Fi/mobile handoff, Google authentication or user-phone evidence.'}
    samples, resource_samples = [], []
    directory = None
    identity = None
    launch = None
    state = None
    started = None
    forwards, reverses = [], []
    marker = uuid.uuid4().hex
    before_forward = before_reverse = None
    old_handlers = {}
    def interrupted(signum, _):
        raise InterruptedError('Native soak interrupted by signal ' + str(signum))
    for signum in (signal.SIGINT, signal.SIGTERM):
        old_handlers[signum] = signal.getsignal(signum)
        signal.signal(signum, interrupted)
    try:
        report['target'] = owned_target(environment, guest)  # Mandatory live proof before any mutation.
        source, digest, size = native_payload()
        report['nativePayload'] = {'path': PAYLOAD, 'sha256': digest, 'bytes': size, 'manifest': 'UI92_RUNTIME146_INPUTS.json/original146_payload'}
        report['nativeManifestSha256'] = sha256((ROOT / 'UI92_RUNTIME146_INPUTS.json').read_bytes())
        report['soakScriptSha256'] = sha256(Path(__file__).read_bytes())
        report['observedRepositoryCommit'] = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT,
                                                          capture_output=True, text=True, check=True, timeout=10).stdout.strip()
        report['declaredCiContext'] = {name: environment[name] for name in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB') if name in environment}
        before_forward = guest.run('forward', '--list').splitlines()
        before_reverse = guest.run('reverse', '--list').splitlines()
        mixed, dns, upstream, dns_upstream = guest_ports(guest)
        report['guestLoopbackPorts'] = {'mixed': mixed, 'dns': dns, 'upstreamReverse': upstream, 'dnsReverse': dns_upstream}
        candidate_directory = '/data/local/tmp/hetu-soak90-' + marker
        guest.shell(f'test ! -e {candidate_directory} && umask 077 && mkdir {candidate_directory} && printf %s {marker} > {candidate_directory}/owner')
        directory = candidate_directory
        if guest.run('shell', 'stat', '-c', '%u:%g:%a', directory) != '2000:2000:700':
            raise RuntimeError('New nonce directory is not private and shell-owned')
        # Set executable mode only on our new HOST copy. adb sync transports its
        # source mode; there is deliberately no guest chmod/chown operation.
        local_binary = output / 'mihomo-host-transport'
        shutil.copyfile(source, local_binary)
        local_binary.chmod(0o700)
        try:
            guest.run('push', str(local_binary), directory + '/mihomo', timeout=90)
        finally:
            local_binary.unlink(missing_ok=True)
        actual_digest = guest.run('shell', 'sha256sum', directory + '/mihomo').split()[0]
        if actual_digest != digest or guest.run('shell', 'stat', '-c', '%u:%g:%a', directory + '/mihomo') != '2000:2000:700':
            raise RuntimeError('Guest native payload hash, ownership or transported mode differs')
        report['guestNativePayloadSha256'] = actual_digest
        version = guest.run('shell', directory + '/mihomo', '-v')
        if 'Mihomo' not in version or 'android' not in version.lower():
            raise RuntimeError('Original native executable did not identify as Android Mihomo')
        report['nativeVersion'] = version
        with controlled_fixtures(marker) as (state, upstream_port, dns_port):
            for device_port, host_port in ((upstream, upstream_port), (dns_upstream, dns_port)):
                guest.run('reverse', '--no-rebind', 'tcp:' + str(device_port), 'tcp:' + str(host_port))
                reverses.append(device_port)
            config = configuration(mixed, dns, upstream, dns_upstream)
            (output / 'config.yaml').write_bytes(config)
            report['configurationSha256'] = sha256(config)
            guest.run('push', str(output / 'config.yaml'), directory + '/config.yaml')
            if guest.run('shell', 'sha256sum', directory + '/config.yaml').split()[0] != report['configurationSha256']:
                raise RuntimeError('Guest soak configuration hash differs')
            tested = guest.run('shell', directory + '/mihomo', '-t', '-d', directory, '-f', directory + '/config.yaml', timeout=30)
            (output / 'config-validation.txt').write_text(tested + '\n')
            # adb remains a child of this smoke process and is explicitly reaped.
            # exec gives the guest its exact native argv; echo captures only its
            # owned PID, then the foreground process remains attached to adb.
            command = f'echo $$ > {directory}/pid; exec {directory}/mihomo -d {directory} -f {directory}/config.yaml'
            with (output / 'core-log.txt').open('wb') as core_log:
                launch = subprocess.Popen([guest.adb, '-s', SERIAL, 'shell', 'sh', '-c', shlex.quote(command)],
                                          stdout=core_log, stderr=subprocess.STDOUT, env=environment)
                ready_until = time.monotonic() + 30
                while time.monotonic() < ready_until:
                    pid_text = guest.run('shell', 'cat', directory + '/pid', check=False)
                    if pid_text.isdigit():
                        try:
                            identity = process_identity(guest, int(pid_text), directory)
                            break
                        except (RuntimeError, subprocess.CalledProcessError):
                            pass
                    if launch.poll() is not None:
                        raise RuntimeError('Original native core exited during startup')
                    time.sleep(.2)
                if identity is None:
                    raise RuntimeError('Could not bind the live guest PID to its nonce native core')
                report['coreIdentity'] = identity
                for device_port in (mixed, dns):
                    host = guest.run('forward', 'tcp:0', 'tcp:' + str(device_port))
                    if not host.isdigit():
                        raise RuntimeError('adb did not allocate an owned host-forward port')
                    forwards.append(int(host))
                # Only a startup grace; warmup is explicitly outside the soak.
                warmup_until = time.monotonic() + 30
                while True:
                    try:
                        dns_probe(forwards[1], marker, 0)
                        proxy_probe(forwards[0], marker, 0)
                        break
                    except (OSError, EOFError, ValueError):
                        if time.monotonic() >= warmup_until:
                            raise
                        time.sleep(.5)
                resource_samples.append(resources(guest, identity, directory))
                started = time.monotonic()
                report['continuousStartedUtc'] = utc_now()
                report['continuousStartMonotonic'] = started
                (output / 'samples.jsonl').touch()
                sample = 0
                next_resource = started + 30
                while time.monotonic() - started < seconds:
                    sample += 1
                    sample_start = time.monotonic()
                    elapsed = sample_start - started
                    phase = phase_for(elapsed, seconds)
                    state.set_mode(phase)
                    record = {'sample': sample, 'phase': phase, 'elapsedStartSeconds': elapsed,
                              'startedUtc': utc_now(), 'dnsPassed': False, 'proxyPassed': False}
                    try:
                        record['dns'] = dns_probe(forwards[1], marker, sample)
                        record['dnsPassed'] = True
                        record.update(proxy_probe(forwards[0], marker, sample))
                        record['proxyPassed'] = True
                    except (OSError, EOFError, ValueError) as error:
                        record['errorType'] = type(error).__name__
                        record['error'] = str(error)[:500]
                    record['elapsedEndSeconds'] = time.monotonic() - started
                    record['latencySeconds'] = time.monotonic() - sample_start
                    samples.append(record)
                    with (output / 'samples.jsonl').open('a') as evidence:
                        evidence.write(json.dumps(record, sort_keys=True) + '\n')
                    if launch.poll() is not None:
                        raise RuntimeError('Original native core exited during continuous requests')
                    if time.monotonic() >= next_resource:
                        resource_samples.append(resources(guest, identity, directory))
                        if (output / 'core-log.txt').stat().st_size > 2 * 1024 * 1024:
                            raise RuntimeError('Native core log exceeded the bounded 2MiB fixture budget')
                        next_resource = time.monotonic() + 30
                    # Bound each wait to one sampling interval, well below60s.
                    wait = min(sample_start + INTERVAL, started + seconds) - time.monotonic()
                    if wait > 0:
                        state.stop.wait(wait)
                report['actualContinuousSeconds'] = time.monotonic() - started
                report['continuousEndedUtc'] = utc_now()
                state.set_mode('normal')
                resource_samples.append(resources(guest, identity, directory))
                if state.rejected:
                    raise RuntimeError('Loopback fixture exceeded its concurrency bound')
                report.update(evaluate(samples, seconds, report['actualContinuousSeconds'], state.events, resource_samples))
                report['samples'] = len(samples)
                report['dnsPassed'] = sum(sample['dnsPassed'] for sample in samples)
                report['sameCoreIdentityAtEnd'] = process_identity(guest, identity['pid'], directory) == identity
                report['result'] = 'PASS'
            (output / 'fixture-requests.json').write_text(json.dumps(state.events, indent=2) + '\n')
    except BaseException as error:
        report['error'] = type(error).__name__ + ': ' + str(error)
        report['result'] = 'FAIL'
        raise
    finally:
        cleanup_errors = []
        cleanup = {'ownedGuestCoreStopped': False, 'adbChildReaped': launch is None,
                   'ownedNonceDirectoryRemoved': directory is None, 'adbForwardRestored': False,
                   'adbReverseRestored': False}
        # Never kill a PID based only on the stored numeric value. Re-read live
        # start ticks and exact executable/argv; an exited or reused PID is not
        # authorized for termination. No killall/pkill/system process discovery.
        if directory is not None:
            try:
                if identity is not None:
                    current = live_identity_or_none(guest, identity, directory)
                    if current == identity:
                        guest.run('shell', 'kill', '-TERM', str(identity['pid']))
                        deadline = time.monotonic() + 8
                        while time.monotonic() < deadline:
                            current = live_identity_or_none(guest, identity, directory)
                            if current != identity:
                                break
                            time.sleep(.2)
                        if current == identity:
                            # Same exact owned process only, after bounded TERM.
                            guest.run('shell', 'kill', '-KILL', str(identity['pid']))
                            deadline = time.monotonic() + 5
                            while time.monotonic() < deadline and live_identity_or_none(guest, identity, directory) is not None:
                                time.sleep(.2)
                            if live_identity_or_none(guest, identity, directory) is not None:
                                raise RuntimeError('Exact owned native core survived bounded KILL')
                        cleanup['ownedGuestCoreStopped'] = True
                    elif current is None:
                        cleanup['ownedGuestCoreStopped'] = True
                    else:
                        raise RuntimeError('Core identity changed; refusing to kill foreign/reused PID')
                elif launch is None:
                    cleanup['ownedGuestCoreStopped'] = True
                else:
                    raise RuntimeError('Launch identity was never bound; supervisor must reap its emulator')
            except BaseException as error:
                cleanup_errors.append('owned core: ' + str(error))
        if launch is not None:
            try:
                try:
                    launch.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    launch.terminate()
                    try:
                        launch.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        launch.kill()
                        launch.wait(timeout=5)
                cleanup['adbChildReaped'] = True
                cleanup['adbChildExitCode'] = launch.returncode
            except BaseException as error:
                cleanup_errors.append('adb child: ' + str(error))
        for port in forwards:
            try:
                guest.run('forward', '--remove', 'tcp:' + str(port))
            except BaseException as error:
                cleanup_errors.append('forward: ' + str(error))
        for port in reverses:
            try:
                guest.run('reverse', '--remove', 'tcp:' + str(port))
            except BaseException as error:
                cleanup_errors.append('reverse: ' + str(error))
        if directory is not None and cleanup['ownedGuestCoreStopped']:
            try:
                # Validate both private ownership and nonce before removing only
                # this new disposable test folder, never retained app data.
                owner = guest.run('shell', 'cat', directory + '/owner')
                private = guest.run('shell', 'stat', '-c', '%u:%g:%a', directory)
                if owner != marker or private != '2000:2000:700':
                    raise RuntimeError('Nonce directory ownership changed; refusing removal')
                log_size = int(guest.run('shell', 'stat', '-c', '%s', directory + '/config.yaml'))
                cleanup['configurationBytesBeforeRemoval'] = log_size
                guest.run('shell', 'rm', '-rf', directory)
                guest.shell('test ! -e ' + directory)
                cleanup['ownedNonceDirectoryRemoved'] = True
            except BaseException as error:
                cleanup_errors.append('nonce directory: ' + str(error))
        if before_forward is not None:
            try:
                cleanup['adbForwardRestored'] = sorted(guest.run('forward', '--list').splitlines()) == sorted(before_forward)
                cleanup['adbReverseRestored'] = sorted(guest.run('reverse', '--list').splitlines()) == sorted(before_reverse)
            except BaseException as error:
                cleanup_errors.append('transport state: ' + str(error))
        cleanup['errors'] = cleanup_errors
        report['cleanup'] = cleanup
        if started is not None and 'actualContinuousSeconds' not in report:
            report['actualContinuousSeconds'] = time.monotonic() - started
            report['continuousEndedUtc'] = utc_now()
            report['continuousRunInterrupted'] = True
        if state is not None:
            (output / 'fixture-requests.json').write_text(json.dumps(state.events, indent=2) + '\n')
        report['resourceObservations'] = resource_samples
        report['finishedUtc'] = utc_now()
        report['executedSamples'] = len(samples)
        if cleanup_errors or not all(cleanup[name] for name in ('ownedGuestCoreStopped', 'adbChildReaped', 'ownedNonceDirectoryRemoved', 'adbForwardRestored', 'adbReverseRestored')):
            report['result'] = 'FAIL'
            report.setdefault('error', 'Native soak cleanup not fully verified')
        (output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
        for signum, handler in old_handlers.items():
            signal.signal(signum, handler)
    if report['result'] != 'PASS':
        raise RuntimeError(report['error'])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'out/android-smoke/native-soak90')
    parser.add_argument('--seconds', type=int, default=900)
    args = parser.parse_args()
    run_soak(output=args.output, seconds=args.seconds)


if __name__ == '__main__':
    main()
