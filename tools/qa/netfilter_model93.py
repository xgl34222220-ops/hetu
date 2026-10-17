#!/usr/bin/env python3
"""Stateful, isolated model of iptables/ip6tables(-restore) and `ip rule/route` for host tests.

State lives in $HETU_NETMODEL (JSON). Nothing here touches a real firewall or routing table.
Invoked as: netfilter_model93.py <tool> [args...] where tool is iptables, ip6tables,
iptables-restore, ip6tables-restore, iptables-save, ip6tables-save or ip.
"""
import copy
import json
import os
from pathlib import Path
import shlex
import sys
import time

BUILTIN = {'mangle': ['PREROUTING', 'INPUT', 'FORWARD', 'OUTPUT', 'POSTROUTING'],
           'nat': ['PREROUTING', 'INPUT', 'OUTPUT', 'POSTROUTING'],
           'filter': ['INPUT', 'FORWARD', 'OUTPUT']}


def empty():
    fams = {}
    for fam in ('4', '6'):
        fams[fam] = {t: {'order': list(c), 'rules': {n: [] for n in c}} for t, c in BUILTIN.items()}
    return {'xt': fams, 'rules': {'4': [], '6': []}, 'routes': {'4': {}, '6': {}}}


class Fail(Exception):
    pass


def path():
    return Path(os.environ['HETU_NETMODEL'])


def load():
    p = path()
    return json.loads(p.read_text()) if p.exists() else empty()


def save(state):
    path().write_text(json.dumps(state, indent=1, sort_keys=True))


def log(entry):
    with open(str(path()) + '.calls', 'a') as out:
        out.write(json.dumps(entry) + '\n')


def dump(table):
    lines = []
    for chain in table['order']:
        if chain in BUILTIN_ALL:
            lines.append('-P %s ACCEPT' % chain)
    for chain in table['order']:
        if chain not in BUILTIN_ALL:
            lines.append('-N %s' % chain)
    for chain in table['order']:
        for spec in table['rules'][chain]:
            lines.append('-A %s %s' % (chain, spec))
    return lines


BUILTIN_ALL = {c for chains in BUILTIN.values() for c in chains}


def targets(spec):
    words = spec.split()
    return [words[i + 1] for i, w in enumerate(words[:-1]) if w in ('-j', '-g')]


def apply(table, action, chain, rest):
    rules = table['rules']
    spec = ' '.join(rest)
    if action == '-N':
        if chain in rules:
            raise Fail('Chain already exists')
        rules[chain] = []
        table['order'].append(chain)
    elif action in ('-A', '-I', '-C', '-D'):
        if chain not in rules:
            raise Fail("Couldn't load target / No chain by that name")
        if action == '-I':
            pos = 1
            if rest and rest[0].isdigit():
                pos, rest = int(rest[0]), rest[1:]
                spec = ' '.join(rest)
        for target in targets(spec):
            if target.startswith(('HETU_', 'BICHEN_', 'fw_')) and target not in rules:
                raise Fail("Couldn't load target `%s'" % target)
        if action == '-A':
            rules[chain].append(spec)
        elif action == '-I':
            rules[chain].insert(pos - 1, spec)
        elif action == '-C':
            if spec not in rules[chain]:
                raise Fail('Bad rule (does a matching rule exist in that chain?)')
        else:
            if spec not in rules[chain]:
                raise Fail('Bad rule (does a matching rule exist in that chain?)')
            rules[chain].remove(spec)
    elif action == '-F':
        if chain not in rules:
            raise Fail('No chain/target/match by that name')
        rules[chain] = []
    elif action == '-X':
        if chain not in rules or chain in BUILTIN_ALL:
            raise Fail('No chain/target/match by that name')
        if rules[chain]:
            raise Fail('Directory not empty')
        for other, specs in rules.items():
            if any(chain in targets(s) for s in specs):
                raise Fail('Too many links')
        del rules[chain]
        table['order'].remove(chain)
    else:
        raise Fail('unsupported action ' + action)


def strip_wait(args):
    out, i = [], 0
    while i < len(args):
        if args[i] == '-w':
            i += 2 if i + 1 < len(args) and args[i + 1].isdigit() else 1
            continue
        out.append(args[i]); i += 1
    return out


def xtables(fam, args):
    state = load()
    args = strip_wait(args)
    table = 'filter'
    if args[:1] == ['-t']:
        table, args = args[1], args[2:]
    t = state['xt'][fam][table]
    action = args[0]
    if action == '-S':
        lines = dump(t)
        if len(args) > 1:
            if args[1] not in t['rules']:
                raise Fail('No chain/target/match by that name')
            lines = [l for l in lines if l.split()[1] == args[1]]
        print('\n'.join(lines))
        return
    apply(t, action, args[1], args[2:])
    if action != '-C':
        save(state)


def restore(fam, args):
    assert '--noflush' in args, args
    if os.environ.get('HETU_NETMODEL_RESTORE_DELAY') and Path(os.environ['HETU_NETMODEL_RESTORE_DELAY']).exists():
        time.sleep(float(Path(os.environ['HETU_NETMODEL_RESTORE_DELAY']).read_text() or '2'))
    if os.environ.get('HETU_NETMODEL_RESTORE_FAIL') and Path(os.environ['HETU_NETMODEL_RESTORE_FAIL']).exists():
        raise Fail('restore refused by fixture')
    state = load()
    work = copy.deepcopy(state)
    table = None
    for raw in sys.stdin.read().splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('*'):
            table = line[1:]; continue
        if line == 'COMMIT':
            table = None; continue
        words = shlex.split(line)
        apply(work['xt'][fam][table], words[0], words[1], words[2:])
    save(work)


def ip(args):
    state = load()
    fam = '4'
    if args and args[0] in ('-4', '-6'):
        fam, args = args[0][1], args[1:]
    if args[:1] == ['-o']:
        args = args[1:]
        if args and args[0] in ('-4', '-6'):
            fam, args = args[0][1], args[1:]
    rules, routes = state['rules'][fam], state['routes'][fam]
    obj, verb, rest = args[0], args[1] if len(args) > 1 else 'show', args[2:]
    if obj == 'rule':
        if verb in ('show', 'list'):
            print('0:\tfrom all lookup local')
            for r in rules:
                print('%s:\tfrom all fwmark %s lookup %s' % (r['pref'], r['mark'], r['table']))
            print('32766:\tfrom all lookup main')
            return
        opts = dict(zip(rest[::2], rest[1::2]))
        if 'lookup' in opts:
            opts['table'] = opts['lookup']
        if verb == 'add':
            rules.append({'pref': opts['pref'], 'mark': opts['fwmark'], 'table': opts['table']})
            rules.sort(key=lambda r: int(r['pref']))
        elif verb == 'del':
            for r in rules:
                if all(r.get(k if k != 'fwmark' else 'mark') == v for k, v in opts.items() if k in ('pref', 'fwmark', 'table')):
                    rules.remove(r); break
            else:
                raise Fail('RTNETLINK answers: No such file or directory')
        else:
            raise Fail('rule ' + verb)
    elif obj == 'route':
        table = rest[rest.index('table') + 1] if 'table' in rest else 'main'
        if verb in ('show', 'list'):
            if table == 'all':
                for t, items in sorted(routes.items()):
                    for item in items:
                        print('%s table %s' % (item, t))
            else:
                for item in routes.get(table, []):
                    print(item)
            return
        if verb == 'replace':
            dest = rest[1]
            text = 'local %s dev lo scope host' % ('default' if dest in ('0.0.0.0/0', '::/0') else dest)
            items = routes.setdefault(table, [])
            if text not in items:
                items.append(text)
        elif verb == 'del':
            dest = rest[1]
            text = 'local %s dev lo scope host' % ('default' if dest in ('0.0.0.0/0', '::/0') else dest)
            if text not in routes.get(table, []):
                raise Fail('RTNETLINK answers: No such process')
            routes[table].remove(text)
        elif verb == 'flush':
            routes.pop(table, None)
        else:
            raise Fail('route ' + verb)
    elif obj == 'link':
        if verb == 'show':
            raise Fail('Device does not exist')
        return
    elif obj == 'address':
        print('1: lo    inet 127.0.0.1/8 scope host lo')
        return
    else:
        raise Fail('ip ' + obj)
    save(state)


def main():
    tool, args = sys.argv[1], sys.argv[2:]
    log([tool] + args)
    try:
        if tool in ('iptables', 'ip6tables'):
            xtables('4' if tool == 'iptables' else '6', args)
        elif tool in ('iptables-restore', 'ip6tables-restore'):
            restore('4' if tool == 'iptables-restore' else '6', args)
        elif tool in ('iptables-save', 'ip6tables-save'):
            return 0
        elif tool == 'ip':
            ip(args)
        else:
            raise Fail('unknown tool ' + tool)
    except Fail as error:
        print('%s: %s' % (tool, error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
