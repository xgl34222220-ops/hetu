#!/usr/bin/env python3
"""Exercise live TCP sockets across TPROXY startup in disposable Linux netns.

sudo python3 tools/test_tcp_startup_continuity.py --baseline

The optional control run disables the three TCP ownership helpers, restoring
test.133's interception behavior, and requires already-established streams to
break. The unmodified working tree must then preserve them. Each run has two new
unnamed network namespaces joined by a veth, never a host route/firewall change.
Unlike an echo-only TPROXY fixture, the old socket really connects to a different
network namespace before Hetu starts. A distinct transparent listener proves
that new selected sockets still enter the proxy. Both address families, UID
selection, DNS TCP/UDP redirection, and unrelated connmark bits are checked.
"""

import argparse
import contextlib
import json
import os
from pathlib import Path
import select
import shlex
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(__file__).resolve()
CONTROLLER = ROOT / 'android-app/app/src/main/assets/hetu-root.sh'
DESTINATIONS = {4: '198.51.100.24', 6: '2001:db8:2::24'}
TIMEOUT = 4


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=20)
    if result.returncode:
        raise AssertionError(f'{args}: {result.stdout}\n{result.stderr}')
    return result.stdout


def isolated():
    assert os.readlink('/proc/self/ns/net') != os.readlink('/proc/1/ns/net'), \
        'Refuse to change the host network namespace'


def emit(value):
    print(json.dumps(value), flush=True)


def receive(process, timeout=TIMEOUT + 3):
    assert select.select([process.stdout], [], [], timeout)[0], \
        f'Child {process.pid} did not reply in {timeout}s'
    line = process.stdout.readline()
    if not line:
        detail = process.stderr.read() if process.poll() is not None else ''
        raise AssertionError(f'Child {process.pid} closed its output: {detail}')
    return json.loads(line)


def command(process, value):
    process.stdin.write(json.dumps(value) + '\n')
    process.stdin.flush()
    return receive(process)


def stop(process):
    if process.poll() is None:
        if process.stdin:
            process.stdin.close()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=2)
    for stream in (process.stdout, process.stderr):
        if stream:
            stream.close()


def spawn(stack, *args):
    process = subprocess.Popen(args, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, bufsize=1)
    stack.callback(stop, process)
    return process


def listener(stack, family, port, identity, udp=False, transparent=False,
             local_only=False, bind_address=None):
    af = socket.AF_INET if family == 4 else socket.AF_INET6
    sock = stack.enter_context(socket.socket(af, socket.SOCK_DGRAM if udp else socket.SOCK_STREAM))
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if family == 6:
        sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
    if transparent:
        sock.setsockopt(socket.SOL_IP if family == 4 else socket.IPPROTO_IPV6,
                        19 if family == 4 else 75, 1)  # IP{V6}_TRANSPARENT
    address = bind_address or (('127.0.0.1' if family == 4 else '::1') if local_only else ('0.0.0.0' if family == 4 else '::'))
    sock.bind((address, port))

    def serve_stream(connection):
        try:
            with connection, connection.makefile('rb') as reader:
                connection.sendall((identity + '\n').encode())
                while True:
                    payload = reader.readline(4096)
                    if not payload:
                        break
                    # Separate downstream frames also exercise messages from
                    # the server after a client request on the same socket.
                    connection.sendall(b'echo:' + identity.encode() + b':' + payload)
                    connection.sendall(b'push:' + identity.encode() + b':' + payload)
        except OSError:
            pass  # Expected when the intentionally broken baseline resets.

    def serve():
        try:
            if udp:
                while True:
                    payload, peer = sock.recvfrom(4096)
                    sock.sendto(identity.encode() + b':' + payload, peer)
            else:
                while True:
                    connection, _ = sock.accept()
                    threading.Thread(target=serve_stream, args=(connection,), daemon=True).start()
        except OSError:
            pass  # Listener closed by ExitStack.

    if not udp:
        sock.listen(32)
    threading.Thread(target=serve, daemon=True).start()


def configure_local():
    isolated()
    run('ip', 'link', 'set', 'lo', 'up')
    run('ip', 'addr', 'add', '192.0.2.2/24', 'dev', 'ht-app')
    run('ip', '-6', 'addr', 'add', '2001:db8:1::2/64', 'dev', 'ht-app', 'nodad')
    run('ip', 'link', 'set', 'ht-app', 'up')
    run('ip', 'route', 'add', DESTINATIONS[4] + '/32', 'via', '192.0.2.1')
    run('ip', '-6', 'route', 'add', DESTINATIONS[6] + '/128', 'via', '2001:db8:1::1')
    for iface in ('all', 'default', 'lo', 'ht-app'):
        run('sysctl', '-qw', f'net.ipv4.conf.{iface}.rp_filter=0')


def peer():
    isolated()
    emit({'pid': os.getpid(), 'namespace': os.readlink('/proc/self/ns/net')})
    assert json.loads(sys.stdin.readline()) == {'configure': True}
    run('ip', 'link', 'set', 'lo', 'up')
    run('ip', 'addr', 'add', '192.0.2.1/24', 'dev', 'ht-peer')
    run('ip', '-6', 'addr', 'add', '2001:db8:1::1/64', 'dev', 'ht-peer', 'nodad')
    run('ip', 'addr', 'add', DESTINATIONS[4] + '/32', 'dev', 'lo')
    run('ip', '-6', 'addr', 'add', DESTINATIONS[6] + '/128', 'dev', 'lo', 'nodad')
    run('ip', 'link', 'set', 'ht-peer', 'up')
    with contextlib.ExitStack() as stack:
        for family in (4, 6):
            listener(stack, family, 24443, 'remote')
            # Connected UDP clients accept replies only from their configured
            # destination. A wildcard socket would select the peer's veth
            # address for sendto(), instead of this remote resolver address.
            listener(stack, family, 53, 'remote-dns', bind_address=DESTINATIONS[family])
            listener(stack, family, 53, 'remote-dns', udp=True,
                     bind_address=DESTINATIONS[family])
        emit({'ready': True})
        # EOF on the private control pipe ends the child and its netns even if
        # the test process fails. No named namespaces or persistent mounts.
        sys.stdin.read()


def client(args):
    os.setgroups([])
    os.setgid(args.uid)
    os.setuid(args.uid)
    af = socket.AF_INET if args.family == 4 else socket.AF_INET6
    try:
        with socket.socket(af, socket.SOCK_DGRAM if args.udp else socket.SOCK_STREAM) as sock:
            sock.settimeout(TIMEOUT)
            sock.connect((DESTINATIONS[args.family], args.port))
            with contextlib.ExitStack() as stack:
                reader = None if args.udp else stack.enter_context(sock.makefile('rb'))
                identity = '' if args.udp else reader.readline(4096).decode().strip()
                emit({'connected': True, 'identity': identity})
                for line in sys.stdin:
                    nonce = json.loads(line)['nonce']
                    payload = nonce.encode() + b'\n'
                    sock.sendall(payload)
                    if args.udp:
                        result = sock.recv(4096).decode().strip()
                        identity, echoed = result.split(':', 1)
                        assert echoed == nonce, (echoed, nonce)
                    else:
                        echo = reader.readline(4096).decode().strip()
                        push = reader.readline(4096).decode().strip()
                        assert echo == f'echo:{identity}:{nonce}', echo
                        assert push == f'push:{identity}:{nonce}', push
                    emit({'ok': True, 'identity': identity, 'nonce': nonce})
    except (OSError, AssertionError, ValueError) as failure:
        emit({'ok': False, 'error': type(failure).__name__, 'detail': str(failure)})


def connect(stack, family, uid=10001, port=24443, udp=False, expected='remote'):
    args = [sys.executable, str(SCRIPT), '--client', '--family', str(family),
            '--uid', str(uid), '--port', str(port)]
    if udp:
        args.append('--udp')
    process = spawn(stack, *args)
    result = receive(process)
    assert result.get('connected'), (family, uid, port, result)
    if not udp:
        assert result['identity'] == expected, (family, uid, port, result)
    return process


def exchange(process, identity, label, rounds=3):
    for index in range(rounds):
        nonce = f'{label}-{index}'
        result = command(process, {'nonce': nonce})
        assert result == {'ok': True, 'identity': identity, 'nonce': nonce}, \
            (label, index, identity, result)
        time.sleep(0.04)  # Let later packets exercise established conntrack state.


def inside(args):
    isolated()
    with contextlib.ExitStack() as stack:
        remote = spawn(stack, 'unshare', '--net', sys.executable, str(SCRIPT), '--peer')
        info = receive(remote)
        assert info['namespace'] != os.readlink('/proc/self/ns/net')
        run('ip', 'link', 'add', 'ht-app', 'type', 'veth', 'peer', 'name', 'ht-peer')
        run('ip', 'link', 'set', 'ht-peer', 'netns', str(info['pid']))
        configure_local()
        assert command(remote, {'configure': True}) == {'ready': True}
        for family in (4, 6):
            listener(stack, family, 19898, 'proxy', transparent=True)
            listener(stack, family, 11053, 'local-dns', local_only=True)
            listener(stack, family, 11053, 'local-dns', udp=True, local_only=True)

        # Turn conntrack on BEFORE the first SYN. A real Android device already
        # has tracked streams when Hetu starts. An unrelated mark ensures the
        # ownership guard checks only Hetu's bit, not whether ctmark == 0.
        for binary in ('iptables', 'ip6tables'):
            run(binary, '-t', 'mangle', '-A', 'OUTPUT', '-p', 'tcp', '-m', 'owner',
                '--uid-owner', '10001', '-j', 'CONNMARK', '--set-xmark', '0x4000/0x4000')
        previous = {}
        for family in (4, 6):
            previous[family] = connect(stack, family)
            exchange(previous[family], 'remote', f'v{family}-before-start')

        directory = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix='hetu-tcp-netns-')))
        functions = directory / 'functions.sh'
        functions.write_text(Path(args.source).read_text().split('case "${1:-status}" in')[0])
        (directory / 'run').mkdir()
        # A behavioral control needs no Git history (CI uses shallow checkout).
        # Only the continuity fix is disabled; routing, UID policy, real sockets
        # and all other rules remain the same. This control MUST fail delivery.
        control = '''
preserve_existing_tcp(){ return 0; }
remember_local_tcp(){ return 0; }
remember_shared_tcp(){ return 0; }
''' if args.expect_broken else ''
        install = f'''. {shlex.quote(str(functions))}
{control}
BASE={shlex.quote(str(directory))}; RUN="$BASE/run"
MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500
install_mangle4 19898 tproxy 1 1 redirect whitelist 10001 0 '' '' '' || exit 1
install_mangle6 19898 tproxy 1 1 redirect whitelist 10001 0 '' '' '' || exit 1
install_dns_redirect4 11053 whitelist 10001 0 '' || exit 1
install_dns_redirect6 11053 whitelist 10001 0 '' || exit 1
'''
        result = subprocess.run(['sh'], input=install, text=True, capture_output=True, timeout=30)
        assert result.returncode == 0, (result.stdout, result.stderr)

        for binary in ('iptables', 'ip6tables'):
            # Counter-only audit after Hetu: no extra ACCEPT/RETURN that could
            # accidentally make a broken interception implementation pass.
            run(binary, '-t', 'mangle', '-N', 'HETU_TEST_CLOBBER')
            run(binary, '-t', 'mangle', '-A', 'HETU_TEST_CLOBBER', '-j', 'RETURN')
            run(binary, '-t', 'mangle', '-A', 'OUTPUT', '-p', 'tcp', '-m', 'owner',
                '--uid-owner', '10001', '-m', 'connmark', '!', '--mark', '0x4000/0x4000',
                '-j', 'HETU_TEST_CLOBBER')
        for family in (4, 6):
            if args.expect_broken:
                result = command(previous[family], {'nonce': f'v{family}-baseline-after-start'})
                assert result.get('ok') is False, \
                    f'Baseline unexpectedly preserved IPv{family} direct stream: {result}'
                assert result['error'] in ('TimeoutError', 'ConnectionResetError',
                                           'BrokenPipeError', 'AssertionError'), result
                print(f'IPv{family} baseline reproduces midstream interception: {result["error"]}', flush=True)
            else:
                exchange(previous[family], 'remote', f'v{family}-after-start', rounds=5)
                print(f'IPv{family}: established direct stream survives TPROXY startup', flush=True)

        for family in (4, 6):
            selected = connect(stack, family, expected='proxy')
            exchange(selected, 'proxy', f'v{family}-new-selected', rounds=5)
            bypass = connect(stack, family, uid=10002)
            exchange(bypass, 'remote', f'v{family}-unselected')
            for udp in (False, True):
                for uid, identity in ((10001, 'local-dns'), (10002, 'remote-dns')):
                    dns = connect(stack, family, uid=uid, port=53, udp=udp, expected=identity)
                    exchange(dns, identity, f'v{family}-dns-{uid}-{udp}')
            print(f'IPv{family}: new selected TCP enters proxy; other UID stays direct; TCP/UDP DNS scope retained', flush=True)
        for binary in ('iptables', 'ip6tables'):
            counters = run(binary, '-t', 'mangle', '-L', 'HETU_TEST_CLOBBER', '-n', '-v', '-x')
            rows = [line.split() for line in counters.splitlines() if 'RETURN' in line]
            assert len(rows) == 1 and int(rows[0][0]) == 0, counters
        print('Unrelated OEM connmark bits preserved in both address families', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', action='store_true',
                        help='First prove the bug with TCP continuity helpers disabled in a separate netns')
    parser.add_argument('--inside', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--peer', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--client', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--source', default=str(CONTROLLER), help=argparse.SUPPRESS)
    parser.add_argument('--expect-broken', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--family', type=int, choices=(4, 6), help=argparse.SUPPRESS)
    parser.add_argument('--uid', type=int, help=argparse.SUPPRESS)
    parser.add_argument('--port', type=int, help=argparse.SUPPRESS)
    parser.add_argument('--udp', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.client:
        return client(args)
    if args.peer:
        return peer()
    if args.inside:
        return inside(args)
    if os.geteuid() != 0:
        parser.error('Run via sudo; all network changes occur only inside unshare --net')
    for tool in ('unshare', 'ip', 'iptables', 'ip6tables', 'sysctl'):
        assert shutil.which(tool), f'Required Linux test tool not installed: {tool}'
    if args.baseline:
        subprocess.run(['unshare', '--net', sys.executable, str(SCRIPT), '--inside',
                        '--expect-broken'], check=True, timeout=100)
    subprocess.run(['unshare', '--net', sys.executable, str(SCRIPT), '--inside'],
                   check=True, timeout=100)
    print('PASS: live IPv4/IPv6 direct TCP continuity, new TPROXY streams, UID scope, DNS scope and connmark isolation')


if __name__ == '__main__':
    main()
