#!/usr/bin/env python3
"""Execute Root controller process identity, cleanup, and local reply regressions."""
import ipaddress
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'android-app/app/src/main/assets/hetu-root.sh'
FAKE_NET = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
root = Path(os.environ['HETU_TEST_DIR'])
with (root / 'calls').open('a') as log:
    log.write(json.dumps([name, *args]) + '\n')
if name == 'ip':
    if args[:3] == ['-o', '-6', 'address']:
        print('2: wlan0    inet6 2001:db8::10/64 scope global')
        print('2: wlan0    inet6 fe80::10/64 scope link')
    if args[:3] == ['-o', '-4', 'address']:
        print('2: wlan0    inet 192.0.2.10/24 scope global')
    sys.exit(0)
if args[:1] == ['-w']:
    args = args[2:]
assert args[:1] == ['-t'], args
table, op, *rest = args[1:]
key = name + ':' + table
state_path = root / 'network.json'
state = json.loads(state_path.read_text())
chains = state[key]
if op == '-S':
    if os.environ.get('HETU_FAIL_SNAPSHOT') == key:
        sys.exit(1)
    for chain, rules in chains.items():
        print(('-P ' + chain + ' ACCEPT') if chain in ('OUTPUT','PREROUTING','FORWARD') else '-N ' + chain)
        for rule in rules:
            print('-A ' + chain + ' ' + ' '.join(rule))
    sys.exit(0)
chain, *rule = rest
if op == '-N':
    if chain in chains: sys.exit(1)
    chains[chain] = []
elif op == '-A':
    if chain not in chains: sys.exit(1)
    if 'addrtype' in rule and os.environ.get('HETU_NO_ADDRTYPE') == '1': sys.exit(1)
    if 'conntrack' in rule and os.environ.get('HETU_NO_CONNTRACK') == '1': sys.exit(1)
    chains[chain].append(rule)
elif op in ('-C','-D'):
    if chain not in chains or rule not in chains[chain]: sys.exit(1)
    if op == '-C': sys.exit(0)
    chains[chain].remove(rule)
elif op == '-F':
    if chain not in chains: sys.exit(1)
    chains[chain] = []
elif op == '-X':
    if chain not in chains: sys.exit(1)
    if any(['-j',chain] == r[-2:] for rs in chains.values() for r in rs): sys.exit(1)
    del chains[chain]
else:
    raise AssertionError(args)
state_path.write_text(json.dumps(state))
'''


def network():
    return {f'{binary}:{table}': {chain: [] for chain in builtins}
            for binary in ('iptables', 'ip6tables')
            for table, builtins in (
                ('mangle', ('OUTPUT', 'PREROUTING')),
                ('nat', ('OUTPUT', 'PREROUTING')),
                ('filter', ('OUTPUT', 'FORWARD')))}


def dispatch(rules, *, address, interface, mark=0, protocol='tcp', local_addresses=(),
             direction='ORIGINAL', connection_mark=0, connection_state='NEW',
             uid=10001, gid=10001, destination_port=443, packet_state=None,
             local_route_mark=0x200000):
    """Match emitted rules and execute non-terminal mark targets in chain order.

    nfmark and ctmark are separate values. The optional state makes subsequent
    packets in one connection testable without assuming every packet is NEW.
    """
    state = packet_state if packet_state is not None else {}
    state.setdefault('mark', mark)
    state.setdefault('connection_mark', connection_mark)

    def mask_value(value):
        parts = value.split('/')
        return int(parts[0], 0), int(parts[1], 0) if len(parts) > 1 else 0xffffffff

    for rule in rules:
        matches = True
        i = 0
        negate = False
        module = None
        while i < len(rule):
            arg = rule[i]
            if arg == '!':
                negate = True
                i += 1
                continue
            if arg == '-j':
                if matches:
                    target = rule[i + 1]
                    options = rule[i + 2:]
                    if target in ('MARK', 'CONNMARK'):
                        assert len(options) == 2 and options[0] == '--set-xmark', rule
                        number, mask = mask_value(options[1])
                        key = 'mark' if target == 'MARK' else 'connection_mark'
                        state[key] = (state[key] & (~mask & 0xffffffff)) ^ number
                        # These targets continue to later rules; they are not a
                        # verdict and must not stop before the TPROXY target.
                    elif target == 'TPROXY':
                        index = options.index('--tproxy-mark')
                        number, mask = mask_value(options[index + 1])
                        state['mark'] = (state['mark'] & (~mask & 0xffffffff)) ^ number
                        return target
                    elif target in ('RETURN', 'ACCEPT', 'DROP', 'REJECT'):
                        return target
                    else:
                        raise AssertionError(rule)
                break
            if arg == '-m':
                module = rule[i + 1]
                assert module in ('mark', 'connmark', 'conntrack', 'addrtype', 'owner'), rule
                i += 2
                continue
            value = rule[i + 1]
            if arg in ('-i', '-o'):
                result = interface == value
            elif arg == '--dst-type':
                # Only the configured Hetu routing bit makes the destination
                # LOCAL; an unrelated firewall's packet mark does not.
                result = address in local_addresses or bool(state['mark'] & local_route_mark)
            elif arg == '-d':
                result = ipaddress.ip_address(address) in ipaddress.ip_network(value, strict=False)
            elif arg == '-p':
                result = protocol == value
            elif arg == '--mark':
                assert module in ('mark', 'connmark'), rule
                number, mask = mask_value(value)
                actual = state['connection_mark'] if module == 'connmark' else state['mark']
                result = actual & mask == number
            elif arg == '--ctdir':
                result = direction == value
            elif arg == '--ctstate':
                result = connection_state in value.split(',')
            elif arg in ('--uid-owner', '--gid-owner'):
                parts = value.split('-')
                lower, upper = int(parts[0]), int(parts[-1])
                result = lower <= (uid if arg == '--uid-owner' else gid) <= upper
            elif arg == '--dport':
                result = destination_port == int(value)
            else:
                raise AssertionError(rule)
            matches &= not result if negate else result
            negate = False
            i += 2
    return 'RETURN'


def main():
    checks = 0
    with tempfile.TemporaryDirectory(prefix='hetu-root-controller-') as temporary:
        directory = Path(temporary)
        functions = directory / 'functions.sh'
        functions.write_text(SCRIPT.read_text().split('case "${1:-status}" in', 1)[0])
        fake_bin = directory / 'fake-bin'
        fake_bin.mkdir()
        for name in ('iptables', 'ip6tables', 'ip'):
            path = fake_bin / name
            path.write_text(FAKE_NET)
            path.chmod(0o755)
        state_path = directory / 'network.json'

        def shell(body, **extra):
            environment = dict(os.environ, HETU_TEST_DIR=temporary,
                               HETU_TEST_FUNCTIONS=str(functions),
                               PATH=str(fake_bin) + os.pathsep + os.environ['PATH'], **extra)
            prefix = '''. "$HETU_TEST_FUNCTIONS"
BASE="$HETU_TEST_DIR/private"; RUN="$BASE/run"; mkdir -p "$RUN"
PIDFILE="$RUN/core.pid"; MODEFILE="$RUN/mode"; NET_STATE="$RUN/net.state"
LOCK_DIR="$RUN/lock"
'''
            result = subprocess.run(['sh'], input=prefix + body, text=True,
                                    capture_output=True, env=environment)
            assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
            return result.stdout

        def reset(state=None):
            state_path.write_text(json.dumps(network() if state is None else state))
            (directory / 'calls').write_text('')

        reset()
        shell('cleanup\n')
        calls = [json.loads(line) for line in (directory / 'calls').read_text().splitlines()]
        firewall_calls = [call for call in calls if call[0] in ('iptables', 'ip6tables')]
        assert len(firewall_calls) == 6, firewall_calls
        assert all(call[-1] == '-S' for call in firewall_calls)
        checks += 1

        initial = network()
        initial['iptables:mangle']['HETU_MOUT'] = [['-j', 'RETURN']]
        initial['iptables:mangle']['OUTPUT'] = [['-j', 'HETU_MOUT'], ['-j', 'OTHER_APP']]
        initial['iptables:mangle']['OTHER_APP'] = [['-j', 'RETURN']]
        initial['iptables:nat']['HETU_NOUT'] = []
        initial['ip6tables:filter']['BICHEN_KOUT'] = [['-j', 'REJECT']]
        initial['ip6tables:filter']['OUTPUT'] = [['-j', 'BICHEN_KOUT']]
        reset(initial)
        shell('cleanup\n')
        result = json.loads(state_path.read_text())
        assert all(not chain.startswith(('HETU_', 'BICHEN_')) for table in result.values() for chain in table)
        assert result['iptables:mangle']['OTHER_APP'] == initial['iptables:mangle']['OTHER_APP']
        assert result['iptables:mangle']['OUTPUT'] == [['-j', 'OTHER_APP']]
        checks += 3
        reset(initial)
        shell('cleanup\n', HETU_FAIL_SNAPSHOT='iptables:mangle')
        result = json.loads(state_path.read_text())
        assert 'HETU_MOUT' not in result['iptables:mangle'], 'Failed snapshots must retain real cleanup'
        assert 'OTHER_APP' in result['iptables:mangle']
        checks += 2

        for family, binary, local, remote in ((4, 'iptables', '192.0.2.10', '198.51.100.20'),
                                              (6, 'ip6tables', '2001:db8::10', '2001:db8:1::20')):
            for fallback in ('0', '1'):
                reset()
                shell(f'''MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500
v6supported(){{ return 0; }}
install_mangle{family} 19898 tproxy 1 1 redirect core '' 1 '' '' '' || exit 1
''', HETU_NO_ADDRTYPE=fallback)
                rules = json.loads(state_path.read_text())[binary + ':mangle']['HETU_MPRE']
                for protocol in ('tcp', 'udp'):
                    assert dispatch(rules, address=local, interface='wlan0', protocol=protocol,
                                    local_addresses=(local,)) == 'RETURN'
                    assert dispatch(rules, address=remote, interface='lo', mark=0x200000,
                                    protocol=protocol, local_addresses=(local,)) == 'TPROXY'
                    assert dispatch(rules, address=remote, interface='swlan0', protocol=protocol,
                                    local_addresses=(local,)) == 'TPROXY'
                    checks += 3
                guard_index = next(index for index, rule in enumerate(rules)
                                   if rule[:3] == ['!', '-i', 'lo'])
                assert guard_index < next(index for index, rule in enumerate(rules) if 'TPROXY' in rule)
                checks += 1
                # The previous dispatch has no local reply exemption: reproduce
                # the fault instead of merely asserting a newly added string.
                old_rules = [rule for rule in rules if rule[:3] != ['!', '-i', 'lo']]
                assert dispatch(old_rules, address=local, interface='wlan0', local_addresses=(local,)) == 'TPROXY'
                checks += 1
                if fallback == '1':
                    guards = [rule for rule in rules if rule[:3] == ['!', '-i', 'lo']]
                    assert all('/24' not in rule and '/64' not in rule for rule in guards)
                    checks += 1
                    # A new uplink address after startup is absent from the
                    # static list, but replies must still avoid interception.
                    later_local = '192.0.2.11' if family == 4 else '2001:db8:2::10'
                    assert dispatch(rules, address=later_local, interface='rmnet0', direction='REPLY') == 'RETURN'
                    checks += 1

                outgoing = json.loads(state_path.read_text())[binary + ':mangle']['HETU_MOUT']
                private_bit, foreign_bit = 0x200000, 0x4000
                # Old TCP retains its original path even with an unrelated
                # nonzero connmark; matching the packet's mark instead is wrong.
                old = {'mark': 0, 'connection_mark': foreign_bit}
                assert dispatch(outgoing, address=remote, interface='wlan0',
                                connection_state='ESTABLISHED', packet_state=old) == 'RETURN'
                assert old == {'mark': 0, 'connection_mark': foreign_bit}
                assert dispatch(rules, address=remote, interface='swlan0',
                                connection_state='ESTABLISHED', packet_state=old) == 'RETURN'
                checks += 3
                # The selected first packet writes only Hetu's private bits.
                # Later packets have no packet mark but inherit this ctmark.
                first = {'mark': foreign_bit, 'connection_mark': foreign_bit}
                dispatch(outgoing, address=remote, interface='wlan0', packet_state=first)
                assert first == {'mark': private_bit | foreign_bit,
                                 'connection_mark': private_bit | foreign_bit}
                later = {'mark': foreign_bit, 'connection_mark': first['connection_mark']}
                dispatch(outgoing, address=remote, interface='wlan0',
                         connection_state='ESTABLISHED', packet_state=later)
                assert later == first
                assert dispatch(rules, address=remote, interface='lo',
                                connection_state='ESTABLISHED', packet_state=later) == 'TPROXY'
                checks += 3
                # Shared new TCP reaches the target after CONNMARK continues.
                shared = {'mark': foreign_bit, 'connection_mark': foreign_bit}
                assert dispatch(rules, address=remote, interface='swlan0', packet_state=shared) == 'TPROXY'
                assert shared == {'mark': private_bit | foreign_bit,
                                  'connection_mark': private_bit | foreign_bit}
                checks += 2
                # UDP must not accidentally inherit the TCP preservation rule.
                udp = {'mark': 0, 'connection_mark': foreign_bit}
                assert dispatch(rules, address=remote, interface='swlan0', protocol='udp',
                                connection_state='ESTABLISHED', packet_state=udp) == 'TPROXY'
                assert udp['connection_mark'] == foreign_bit
                checks += 2
                for protocol in ('tcp', 'udp'):
                    dns = {'mark': 0, 'connection_mark': foreign_bit}
                    assert dispatch(outgoing, address=remote, interface='wlan0', protocol=protocol,
                                    destination_port=53, packet_state=dns) == 'RETURN'
                    assert dns == {'mark': 0, 'connection_mark': foreign_bit}
                    checks += 2

            reset()
            shell(f'''MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500
v6supported(){{ return 0; }}
install_mangle{family} 19898 tproxy 1 1 redirect whitelist 10001 0 '' '' '' || exit 1
''')
            outgoing = json.loads(state_path.read_text())[binary + ':mangle']['HETU_MOUT']
            for app_uid, expected in ((10001, 0x204000), (10002, 0x4000)):
                packet = {'mark': 0x4000, 'connection_mark': 0x4000}
                dispatch(outgoing, address=remote, interface='wlan0', uid=app_uid, packet_state=packet)
                assert packet == {'mark': expected, 'connection_mark': expected}, (app_uid, packet)
                checks += 1

        reset()
        shell('''MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500
if install_mangle4 19898 tproxy 1 1 redirect core '' 1 '' '' ''; then exit 1; fi
''', HETU_NO_ADDRTYPE='1', HETU_NO_CONNTRACK='1')
        checks += 1
        print(f'Root controller network rule checks passed: {checks}', flush=True)

        core_dir = directory / 'private/bin'
        core_dir.mkdir(parents=True)
        core = core_dir / 'core'
        shutil.copyfile('/bin/sleep', core)
        core.chmod(0o755)
        real_core = subprocess.Popen([str(core), '60'])
        unrelated = subprocess.Popen(['sh', '-c', 'while :; do sleep 1; done', str(core)])
        try:
            probe = shell(f'pidcore {real_core.pid} && printf real\nif pidcore {unrelated.pid}; then exit 1; fi\n')
            assert 'real' in probe
            checks += 2
            core.unlink()
            shell(f'pidcore {real_core.pid} || exit 1\n')
            checks += 1
            shell(f'''readlink(){{ return 1; }}
pidcore {real_core.pid}; [ "$?" = 2 ] || exit 1
core_maybe_alive {real_core.pid} || exit 1
''')
            checks += 2
            # A stale PID file referring to a wrapper is not authorization to
            # kill it; orphan scanning must still discover the real core.
            shell(f'printf "%s\\n" {unrelated.pid} > "$PIDFILE"\nstopcore\n')
            real_core.wait(timeout=5)
            assert unrelated.poll() is None, 'Core path in shell arguments is not process identity'
            checks += 2
        finally:
            for process in (real_core, unrelated):
                if process.poll() is None:
                    process.terminate()
                process.wait(timeout=5)
    print(f'Root controller regression tests passed: {checks}')


if __name__ == '__main__':
    main()
