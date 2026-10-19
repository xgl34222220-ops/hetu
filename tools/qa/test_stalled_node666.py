#!/usr/bin/env python3
"""6.6.6 stalled-node host regression for hetu-root.sh (no Root, no device).

Field report (2026-10-20 diagnostics, OnePlus PLK110 / ColorOS, Mihomo 1.19.31): "一直外网没网，网络测试显示连接
超时，策略组和首页又有延迟", then "自动组不就是自动切走的吗？而且我测试都有延迟啊". The capture worked (apps'
connections reached the core and matched their rules, Google via 🇸🇬Singapore 01 answered 204), but the
connections through 故障转移 / 流媒体 / 最终兜底, all on 移动 [无限], were abandoned without data while that node
still answered its delay test. Mihomo's url-test / fallback groups only count a failed test URL or a failed
dial against a node, and the watchdog's probes all went through the Google group, so nothing switched.

* wd_stall_scan reads GET /connections once: a node whose TCP connections to two or more sites sent data and
  received nothing for WD_STALL_AGE s, with no young connection receiving anything, is stalled.
* wd_stall_heal pins the url-test / fallback groups on it to the first answering member (fallback order) or the
  fastest (url-test), never to the stalled node, a node recently seen stalled, a group ending in one or DIRECT;
  selector groups and a node the user pinned stay as they are and are named in the notice; then only the
  connections through the stalled node close. Nothing happens while DIRECT fails too.
* wd_stall_unpin gives the groups back to automatic selection after the pin time (doubled when the same node
  stalls again soon), and forgets a pin the user has changed since.
* Fixed on the way: wd_conn_match cut every "chains" array at the first "]" (node names like "[两年]"), so the
  watchdog had never closed a connection through such a node ("closed 0 of 41"); wd_groups lost a group's
  "now"/"fixed" whenever its delay history was not empty; google_firewall_cleanup passed newline-separated UIDs
  to awk -v, which One True Awk (Android's awk) rejects ("newline in string": 239 lines in the report).

HETU_TEST_MIHOMO=<mihomo binary> adds an end-to-end run against a real core with a stalling node.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit
import urllib.request
import datetime
import json
import os
import select
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_netloss93 import ShellCase, TEXT, function, shq, free_port  # noqa: E402
from test_foreign_outage600 import DP_VARS  # noqa: E402
from test_netloss93 import top_assignment  # noqa: E402

BAD = '移动 [无限]'
SG = '\U0001F1F8\U0001F1ECSingapore 01 [两年]'
JP = '\U0001F1EF\U0001F1F5Japan 02 [两年]'
HK = '香港 03 & 备用 [无限]'
FAILOVER = '故障转移'
AUTO = '自动选择'
MEDIA = '流媒体'
FINAL = '最终兜底'


def iso(seconds_ago, tz=0):
    """An RFC 3339 start time `seconds_ago` before now, in the UTC offset `tz` hours (Mihomo uses local time)."""
    t = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=tz))) - datetime.timedelta(seconds=seconds_ago)
    s = t.isoformat(timespec='microseconds')
    return s.replace('+00:00', 'Z') if tz == 0 else s


def conn(cid, chains, age, down=0, up=517, host='www.reddit.com', network='tcp', tz=8, dst=''):
    return {'id': cid, 'chains': chains, 'age': age, 'down': down, 'up': up, 'host': host, 'network': network, 'tz': tz, 'dst': dst}


def go_json(obj):
    """Go's encoding/json: sorted map keys, HTML characters escaped."""
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(',', ':')).replace('&', '\\u0026').replace('<', '\\u003c').replace('>', '\\u003e')


class StallController:
    """Clash API stand-in with Mihomo's shapes: /connections, /proxies (nested extra/history), group delay,
    PUT/DELETE /proxies/<group>, DELETE /connections/<id>."""

    def __init__(self, secret='s3cr3t'):
        self.secret = secret
        self.requests = []
        self.conns = []
        self.closed = []
        self.direct_ok = True
        self.put_code = 204
        self.delays = {}
        self.groups = {}
        owner = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def handle_any(self):
                n = int(self.headers.get('Content-Length') or 0)
                body = self.rfile.read(n).decode() if n else ''
                owner.requests.append((self.command, self.path, body))
                if self.headers.get('Authorization', '') != 'Bearer ' + owner.secret:
                    return self.reply(401, '{"message":"Unauthorized"}')
                url = urlsplit(self.path)
                parts = [unquote(p) for p in url.path.split('/')[1:]]
                if parts == ['connections'] and self.command == 'GET':
                    return self.reply(200, owner.connections_json())
                if len(parts) == 2 and parts[0] == 'connections' and self.command == 'DELETE':
                    owner.closed.append(parts[1])
                    owner.conns = [c for c in owner.conns if c['id'] != parts[1]]
                    return self.reply(204, '')
                if parts == ['proxies'] and self.command == 'GET':
                    return self.reply(200, go_json({'proxies': {n: owner.proxy(n) for n in owner.names()}}))
                if len(parts) == 3 and parts[0] == 'proxies' and parts[2] == 'delay':
                    if parts[1] == 'DIRECT':
                        return self.reply(200, '{"delay":31}') if owner.direct_ok else self.reply(504, '{"message":"Timeout"}')
                    return self.reply(200, '{"delay":88}')
                if len(parts) == 3 and parts[0] == 'group' and parts[2] == 'delay':
                    d = owner.delays.get(parts[1], {})
                    return self.reply(200, go_json(d)) if d else self.reply(504, '{"message":"get delay: all proxies timeout"}')
                if len(parts) == 2 and parts[0] == 'proxies':
                    g = owner.groups.get(parts[1])
                    if self.command == 'GET':
                        return self.reply(200, go_json(owner.proxy(parts[1]))) if parts[1] in owner.names() else self.reply(404, '{"message":"not found"}')
                    if g is None:
                        return self.reply(404, '{"message":"not found"}')
                    if self.command == 'PUT':
                        if owner.put_code != 204:
                            return self.reply(owner.put_code, '{"message":"Must be a Selector"}')
                        name = json.loads(body)['name']
                        if name not in g['all']:
                            return self.reply(400, '{"message":"proxy not exist"}')
                        if g['type'] in ('URLTest', 'Fallback'):
                            g['fixed'] = name
                        g['now'] = name
                        return self.reply(204, '')
                    if self.command == 'DELETE':
                        g['fixed'] = ''
                        g['now'] = g.get('auto', g['all'][0])
                        return self.reply(204, '')
                self.reply(404, '{"message":"not found"}')

            def reply(self, code, body):
                data = body.encode()
                self.send_response(code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                if data:
                    self.wfile.write(data)

            do_GET = do_PUT = do_POST = do_DELETE = handle_any

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), H)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def names(self):
        nodes = {m for g in self.groups.values() for m in g['all'] if m not in self.groups}
        return sorted(nodes | set(self.groups) | {'DIRECT'})

    def proxy(self, name):
        hist = [{'time': '2026-10-20T05:21:00.000000000+08:00', 'delay': 77}]
        extra = {'http://www.gstatic.com/generate_204': {'alive': True, 'history': hist}}
        g = self.groups.get(name)
        if g is None:
            return {'alive': True, 'extra': extra, 'history': hist, 'name': name, 'type': 'Direct' if name == 'DIRECT' else 'Vless', 'udp': True}
        obj = {'alive': True, 'all': g['all'], 'extra': extra, 'hidden': False, 'history': hist, 'icon': '', 'name': name,
               'now': g['now'], 'testUrl': g.get('testUrl', 'https://cp.cloudflare.com/generate_204?a=1&b=2'), 'type': g['type'], 'udp': True}
        if g['type'] in ('URLTest', 'Fallback'):
            obj['fixed'] = g.get('fixed', '')
        return obj

    def connections_json(self):
        items = []
        for c in self.conns:
            items.append({'id': c['id'], 'metadata': {'network': c['network'], 'type': 'Tproxy', 'sourceIP': '192.168.2.105',
                          'destinationIP': c['dst'], 'sourceGeoIP': None, 'host': c['host'], 'dnsMode': 'fake-ip', 'uid': 10385,
                          'process': 'io.github.xgl34222220.hetu', 'sniffHost': ''},
                          'upload': c['up'], 'download': c['down'], 'start': iso(c['age'], c['tz']), 'chains': c['chains'],
                          'providerChains': [''] * len(c['chains']), 'rule': 'RuleSet', 'rulePayload': 'GeolocationNotCN'})
        # A struct in Mihomo (TrackerInfo): field order as declared, not sorted like the /proxies maps.
        return json.dumps({'downloadTotal': 1, 'uploadTotal': 2, 'connections': items, 'memory': 1}, ensure_ascii=False,
                          separators=(',', ':')).replace('&', '\\u0026')

    def puts(self):
        return [(unquote(p), json.loads(b)['name']) for m, p, b in self.requests if m == 'PUT']

    def deletes(self, prefix='/proxies/'):
        return [unquote(p[len(prefix):]) for m, p, b in self.requests if m == 'DELETE' and p.startswith(prefix)]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def standard_groups():
    return {
        FAILOVER: {'type': 'Fallback', 'all': [BAD, MEDIA + '-not-a-member', SG, JP], 'now': BAD, 'fixed': ''},
        AUTO: {'type': 'URLTest', 'all': [BAD, SG, JP], 'now': BAD, 'fixed': ''},
        MEDIA: {'type': 'Selector', 'all': [BAD, FAILOVER, SG], 'now': BAD},
        FINAL: {'type': 'Selector', 'all': [FAILOVER, SG], 'now': FAILOVER},
        'Google': {'type': 'Selector', 'all': [SG, FAILOVER], 'now': SG},
    }


def stalled_conns(node=BAD, groups=(FAILOVER, FINAL), n=4):
    hosts = ['www.reddit.com', 'www.netflix.com', 'open.spotify.com', 'www.cloudflare.com', 'www.twitch.tv']
    return [conn('s%d' % i, [node, *groups], age=12 + i, host=hosts[i % len(hosts)]) for i in range(n)]


class Case(ShellCase):
    def setUp(self):
        super().setUp()
        self.bin = self.dir / 'fakebin'
        self.bin.mkdir()
        self.notes = self.dir / 'notify.log'
        (self.bin / 'cmd').write_text('#!/bin/sh\nprintf "%%s\\n" "$*" >> %s\n' % shq(self.notes))
        (self.bin / 'cmd').chmod(0o755)

    def prelude(self, extra=''):
        return super().prelude('') + 'SYSTEM_DNS=exempt\nFAKE_IP_V4=""\n' + ''.join(top_assignment(v) for v in DP_VARS) + extra

    def sh(self, body, extra='', env=None, timeout=60):
        env = {'PATH': str(self.bin) + ':' + os.environ['PATH'], **(env or {})}
        return super().sh(body + '\nwait\n', extra=extra, env=env, timeout=timeout)

    def notifications(self):
        return self.notes.read_text().splitlines() if self.notes.exists() else []

    def ctl(self):
        c = StallController()
        self.cleanups.append(c.close)
        self.session(cp=c.port)
        return c

    def init(self):
        return 'COREPID=$$; wd_selfheal_init; WD_DIRECT_URLS=http://direct.test/generate_204; wd_now\n'

    def scan(self, c):
        r = self.sh(self.init() + 'wd_stall_scan "$(date +%s)"; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, r.stderr)
        return [line.split('\t') for line in r.stdout.splitlines() if '\t' in line]

    def pins(self):
        f = self.run_dir / 'state/stall-pins'
        return [line.split('\t') for line in f.read_text().splitlines()] if f.exists() else []


def uptime():
    return int(float(Path('/proc/uptime').read_text().split()[0]))


def boot_id():
    return Path('/proc/sys/kernel/random/boot_id').read_text().strip()


class Scan(Case):
    def test_stalled_node_found_with_brackets_emoji_and_html_escapes(self):
        c = self.ctl()
        c.conns = stalled_conns() + stalled_conns(HK, (MEDIA,), 3)
        # A node whose young connection is receiving data is alive, however many of its others wait.
        c.conns += [conn('j%d' % i, [JP, AUTO], age=20, host='h%d.test' % i) for i in range(4)] + [conn('jl', [JP], age=5, down=900)]
        got = sorted(self.scan(c))
        self.assertEqual(got, sorted([[BAD, '4', '4'], [HK.replace('\\u0026', '&'), '3', '3']]))

    def test_age_boundary_and_time_zones(self):
        c = self.ctl()
        for tz in (0, 8, -5):
            with self.subTest(tz=tz):
                c.conns = [conn('a%d' % i, [BAD], age=5, host='h%d' % i, tz=tz) for i in range(5)]
                self.assertEqual(self.scan(c), [], 'younger than WD_STALL_AGE is not stalled yet')
                c.conns = [conn('a%d' % i, [BAD], age=11, host='h%d' % i, tz=tz) for i in range(3)]
                self.assertEqual(self.scan(c), [[BAD, '3', '3']])
                c.conns = [conn('a%d' % i, [BAD], age=400, host='h%d' % i, tz=tz) for i in range(5)]
                self.assertEqual(self.scan(c), [], 'older than 300 s belongs to another episode')

    def test_what_does_not_count(self):
        c = self.ctl()
        cases = {
            'one site only': [conn('x%d' % i, [BAD], age=20, host='same.test') for i in range(5)],
            'two connections': [conn('x%d' % i, [BAD], age=20, host='h%d' % i) for i in range(2)],
            'nothing sent yet': [conn('x%d' % i, [BAD], age=20, up=0, host='h%d' % i) for i in range(5)],
            'udp': [conn('x%d' % i, [BAD], age=20, network='udp', host='h%d' % i) for i in range(5)],
            'direct': [conn('x%d' % i, ['DIRECT'], age=20, host='h%d' % i) for i in range(5)],
            'reject': [conn('x%d' % i, ['REJECT', 'ads'], age=20, host='h%d' % i) for i in range(5)],
            'young one receiving': [conn('x%d' % i, [BAD], age=20, host='h%d' % i) for i in range(5)] + [conn('y', [BAD, FAILOVER], age=30, down=5)],
        }
        for name, conns in cases.items():
            with self.subTest(name):
                c.conns = conns
                self.assertEqual(self.scan(c), [])
        c.conns = cases['two connections'] + [conn('old', [BAD], age=200, down=10 ** 6), conn('ip', [BAD], age=15, host='', dst='104.16.148.4')]
        self.assertEqual(self.scan(c), [[BAD, '3', '3']], 'an old busy connection does not prove the node works now; an IP is a site')

    def test_unreadable_list(self):
        r = self.sh('WD_CP=%d; WD_SECRET=x; WD_TMP="$RUN/.wd"; wd_stall_scan "$(date +%%s)"; echo "rc=$?"' % free_port())
        self.assertIn('rc=1', r.stdout)


class ParsersFixed(Case):
    def test_groups_keep_now_and_fixed_behind_nested_history(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.groups[AUTO]['fixed'] = JP
        c.groups[AUTO]['now'] = JP
        r = self.sh(self.init() + 'WD_DP_GROUP_MAX=50; wd_groups; cat "$WD_TMP.groups"')
        rows = {l.split('\t')[1]: l.split('\t') for l in r.stdout.splitlines()}
        self.assertEqual(rows[AUTO], ['URLTest', AUTO, JP, JP])
        self.assertEqual(rows[FAILOVER], ['Fallback', FAILOVER, BAD, ''])
        self.assertEqual(rows[MEDIA][:3], ['Selector', MEDIA, BAD])
        self.assertNotIn('DIRECT', rows)

    def test_close_through_matches_names_with_brackets(self):
        c = self.ctl()
        c.conns = [conn('a', [SG, 'AI 稳定'], age=3, down=9), conn('b', [BAD, FAILOVER, FINAL], age=3, down=9),
                   conn('c', ['DIRECT'], age=3, down=9), conn('d', [], age=3)]
        r = self.sh(self.init() + 'wd_close_through "$(printf "AI 稳定\\t")"; echo "closed=$WD_CLOSED seen=$WD_CLOSE_SEEN"; '
                    'wd_close_through "$(printf "%s\\t" "移动 [无限]")"; echo "closed=$WD_CLOSED"')
        self.assertIn('closed=1 seen=4', r.stdout, r.stderr)
        self.assertIn('closed=1\n', r.stdout + '\n')
        self.assertEqual(c.closed, ['a', 'b'])

    def test_http_body_curl_and_nc(self):
        c = self.ctl()
        c.groups = standard_groups()
        for tools in ('curl', 'nc'):
            with self.subTest(tools=tools):
                c.requests.clear()
                r = self.sh(self.init() + 'WD_HTTP_TOOLS=%s; WD_HTTP_BODY=$(wd_json_name %s); wd_http PUT "/proxies/$(wd_urlenc %s)" "$RUN/out"; '
                            'echo "code=$WD_CODE"; WD_HTTP_BODY=""; wd_http GET /proxies "$RUN/out"; echo "code=$WD_CODE"'
                            % (tools, shq('Q"\\' + SG), shq(AUTO)))
                self.assertIn('code=400', r.stdout, r.stderr)
                self.assertIn('code=200', r.stdout)
                self.assertEqual(c.requests[0][2], json.dumps({'name': 'Q"\\' + SG}, ensure_ascii=False, separators=(',', ':')))
                self.assertEqual(c.requests[1][2], '', 'no body without WD_HTTP_BODY')


class Heal(Case):
    def heal(self, c, node=BAD, extra=''):
        return self.sh(self.init() + extra + 'wd_stall_heal %s 4 4; echo "rc=$?"' % shq(node))

    def test_auto_groups_pinned_selectors_named_only_its_connections_closed(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.delays = {FAILOVER: {BAD: 40, SG: 300, JP: 90}, AUTO: {BAD: 40, SG: 300, JP: 90}}
        c.conns = stalled_conns() + [conn('g', [SG, 'Google'], age=4, down=7), conn('d', ['DIRECT'], age=4, down=7)]
        r = self.heal(c)
        self.assertIn('rc=0', r.stdout, r.stderr)
        self.assertEqual(sorted(c.puts()), sorted([('/proxies/' + FAILOVER, SG), ('/proxies/' + AUTO, JP)]),
                         'fallback keeps its order, url-test takes the fastest; never the stalled node')
        self.assertEqual(sorted(c.closed), ['s0', 's1', 's2', 's3'])
        self.assertNotIn(MEDIA, [p for p, _ in c.puts()], 'a selector is the user\'s choice')
        pins = {p[0]: p for p in self.pins()}
        self.assertEqual(pins[FAILOVER][1:3], [SG, BAD])
        self.assertEqual(pins[FAILOVER][4:], ['600', boot_id()])
        self.assertTrue(-3 <= int(pins[FAILOVER][3]) - uptime() - 600 <= 1)
        notes = self.notifications()
        self.assertEqual(len(notes), 1)
        self.assertIn('能测出延迟，但经过它的连接收不到数据（4 个连接、4 个网站）', notes[0])
        self.assertIn('「%s」→「%s」' % (FAILOVER, SG), notes[0])
        self.assertIn('约 10 分钟后恢复自动选择', notes[0])
        self.assertIn('手动选择的「%s」也在用它' % MEDIA, notes[0])
        self.assertIn('stalled node: %s (4 connection(s) to 4 site(s) without data' % BAD, self.log_text())
        self.assertIn('closed 4 of 6 core connection(s) through it', self.log_text())

    def test_user_pin_kept_and_selector_only_closes_nothing(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.groups[FAILOVER]['fixed'] = BAD
        del c.groups[AUTO]
        c.conns = stalled_conns()
        self.heal(c)
        self.assertEqual(c.puts(), [])
        self.assertEqual(c.closed, [], 'reconnecting through the same node would not help')
        self.assertIn('「%s」是你固定选择的它，已保持不变' % FAILOVER, self.notifications()[0])

    def test_own_earlier_pin_is_moved_on(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.groups[FAILOVER].update(now=SG, fixed=SG)
        c.delays = {FAILOVER: {BAD: 40, SG: 50, JP: 90}}
        (self.run_dir / 'state/stall-pins').write_text('%s\t%s\t%s\t%d\t600\t%s\n' % (FAILOVER, SG, BAD, uptime() + 500, boot_id()))
        # 移动 stalled a moment ago: neither it nor the now stalled Singapore node is picked.
        self.heal(c, SG, extra='printf "%s\\t%s\\t%s\\n" "移动 [无限]" "$WD_NOW" "$WD_NOW" > "$WD_TMP.stallseen"; ')
        self.assertEqual(c.puts(), [('/proxies/' + FAILOVER, JP)])
        self.assertEqual([p[:3] for p in self.pins()], [[FAILOVER, JP, SG]])

    def test_candidates_skip_groups_ending_in_the_node_direct_and_silent_members(self):
        c = self.ctl()
        c.groups = {
            FAILOVER: {'type': 'Fallback', 'all': [BAD, MEDIA, 'DIRECT', HK, SG], 'now': BAD, 'fixed': ''},
            MEDIA: {'type': 'Selector', 'all': [BAD, SG], 'now': BAD},
        }
        c.delays = {FAILOVER: {BAD: 10, MEDIA: 10, 'DIRECT': 5, SG: 400}}
        self.heal(c)
        self.assertEqual(c.puts(), [('/proxies/' + FAILOVER, SG)], 'not via 流媒体 (ends in 移动), not DIRECT, not the silent 香港 03')

    def test_no_replacement_or_pin_refused(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.conns = stalled_conns()
        c.delays = {FAILOVER: {BAD: 40}}
        self.heal(c)
        self.assertEqual(c.puts(), [])
        self.assertEqual(c.closed, [])
        self.assertIn('「%s」、「%s」里没有其他测速正常的节点可换' % (FAILOVER, AUTO), self.notifications()[0])
        self.assertEqual(self.pins(), [])
        c.requests.clear()
        c.delays = {FAILOVER: {SG: 40}, AUTO: {SG: 40}}
        c.put_code = 400
        self.heal(c, extra='rm -f "$WD_TMP.stallseen"; ')
        self.assertEqual(self.pins(), [], 'sing-box answers 400 for a url-test group: nothing recorded')

    def test_node_no_group_uses_is_only_logged(self):
        c = self.ctl()
        c.groups = standard_groups()
        for g in c.groups.values():
            if g['now'] == BAD:
                g['now'] = SG
        c.conns = stalled_conns()
        self.heal(c)
        self.assertEqual((c.puts(), c.closed, self.notifications()), ([], [], []))
        self.assertIn('is not the current choice of any group; nothing changed', self.log_text())

    def test_dead_network_changes_nothing(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.direct_ok = False
        c.conns = stalled_conns()
        self.heal(c)
        self.assertEqual((c.puts(), c.closed, self.notifications()), ([], [], []))
        self.assertIn('while DIRECT fails too; nothing changed', self.log_text())

    def test_tick_acts_once_per_gap_and_notifies_once(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.groups[FAILOVER]['fixed'] = BAD
        del c.groups[AUTO]
        c.conns = stalled_conns()
        # Nothing is pinned or closed (the user's pin, a selector), so the node stays stalled on every pass.
        r = self.sh(self.init() + 'wd_stall_tick; wd_stall_tick; WD_NOW=$((WD_NOW+61)); wd_stall_tick')
        self.assertEqual(self.log_text().count('stalled node: %s (' % BAD), 2, r.stderr)
        self.assertEqual(len(self.notifications()), 1)
        self.assertEqual(c.puts(), [])

    def test_selfheal_tick_runs_the_scan(self):
        c = self.ctl()
        r = self.sh(self.init() + 'WD_LIVE_AT=$((WD_NOW+999)); WD_DNS_AT=$((WD_NOW+999)); WD_DP_ON=0; WD_EG_AT=$((WD_NOW+999)); '
                    'WD_CACHE_SEEN=1; WD_STALL_AT=0; wd_selfheal_tick; echo "next=$((WD_STALL_AT-WD_NOW))"')
        self.assertIn(('GET', '/connections', ''), c.requests, r.stderr)
        self.assertIn('next=10', r.stdout)


class Unpin(Case):
    def write(self, *rows):
        (self.run_dir / 'state/stall-pins').write_text(''.join('\t'.join(map(str, r)) + '\n' for r in rows))

    def test_expired_returned_live_kept_changed_forgotten(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.groups[FAILOVER].update(now=SG, fixed=SG, auto=BAD)
        c.groups[AUTO].update(now=JP, fixed=SG)
        b, now = boot_id(), uptime()
        self.write([FAILOVER, SG, BAD, now - 1, 600, b], [AUTO, JP, BAD, now - 1, 600, b], ['gone', SG, BAD, now - 1, 600, b],
                   ['Google', SG, BAD, now + 500, 600, b])
        r = self.sh(self.init() + 'wd_stall_unpin')
        self.assertEqual(c.deletes(), [FAILOVER], r.stderr)
        self.assertEqual(c.groups[FAILOVER]['fixed'], '')
        self.assertEqual([p[0] for p in self.pins()], ['Google'], 'only the live pin stays')
        log = self.log_text()
        self.assertIn('%s back to automatic selection (pinned to %s for 600s because %s stalled)' % (FAILOVER, SG, BAD), log)
        self.assertIn('%s is set to %s now, not %s; left as it is' % (AUTO, SG, JP), log)
        self.assertIn('group gone is gone; forgot its pin', log)
        hist = (self.run_dir / 'state/stall-history').read_text().split('\t')
        self.assertEqual(hist[:3] + [hist[4].strip()], [FAILOVER, BAD, '600', b])

    def test_reboot_expires_and_unreachable_core_keeps(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.groups[FAILOVER].update(now=SG, fixed=SG)
        self.write([FAILOVER, SG, BAD, uptime() + 9999, 600, 'old-boot'])
        self.sh(self.init() + 'wd_stall_unpin')
        self.assertEqual(c.deletes(), [FAILOVER])
        self.write([FAILOVER, SG, BAD, 0, 600, boot_id()])
        self.sh(self.init() + 'WD_CP=%d; wd_stall_unpin' % free_port())
        self.assertEqual(len(self.pins()), 1)

    def test_same_node_soon_again_doubles_up_to_max(self):
        c = self.ctl()
        c.groups = standard_groups()
        c.delays = {FAILOVER: {SG: 100}}
        b, now = boot_id(), uptime()
        hist = self.run_dir / 'state/stall-history'
        for previous, returned_ago, boot, want in ((600, 100, b, 1200), (600, 1300, b, 600), (2400, 10, b, 3600), (600, 10, 'other-boot', 600)):
            with self.subTest(previous=previous, returned_ago=returned_ago, boot=boot):
                hist.write_text('%s\t%s\t%d\t%d\t%s\n' % (FAILOVER, BAD, previous, now - returned_ago, boot))
                r = self.sh(self.init() + 'wd_stall_pin %s %s %s; echo "ttl=$WD_PIN_TTL"' % (shq(FAILOVER), shq(SG), shq(BAD)))
                self.assertIn('ttl=%d' % want, r.stdout, r.stderr)
                self.assertEqual(self.pins()[0][4], str(want))


STRICT_AWK = r'''#!/usr/bin/env python3
# One True Awk (Android's awk) refuses a newline inside a -v value: "newline in string ... at source line 1".
import os, sys
args = sys.argv[1:]
for i, a in enumerate(args):
    v = args[i + 1] if a == '-v' and i + 1 < len(args) else (a[2:] if a.startswith('-v') and len(a) > 2 else None)
    if v is not None and '\n' in v.partition('=')[2]:
        sys.stderr.write('awk: newline in string %s... at source line 1\n' % v.partition('=')[2][:20])
        sys.exit(2)
os.execv(os.environ['REAL_AWK'], [os.environ['REAL_AWK'], *args])
'''


class GoogleCleanupOneTrueAwk(unittest.TestCase):
    """google_firewall_cleanup with an awk that rejects newlines in -v (and, when present, the real One True Awk)."""

    def setUp(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import test_google_firewall as g
        self.g = g
        self.case = g.GoogleFirewall('test_both_families_google_reject_and_drop_removed_by_exact_spec')
        self.case.setUp()
        self.addCleanup(self.case.tearDown)

    def run_with(self, awk):
        real = shutil.which('awk')
        wrapper = self.case.bin / 'awk'
        if awk == 'strict':
            wrapper.write_text(STRICT_AWK)
        else:
            wrapper.write_text('#!/bin/sh\nexec %s "$@"\n' % shq(awk))
        wrapper.chmod(0o755)
        self.case.env['REAL_AWK'] = real
        for family in (4, 6):
            self.case.rule(family, 'fw_OUTPUT', ['-A fw_OUTPUT -m owner --uid-owner 10123 -j REJECT', '-A fw_OUTPUT -m owner --uid-owner 10124 -j DROP',
                                                 '-A fw_OUTPUT -m owner --uid-owner 10200 -j REJECT'])
        r = self.case.shell('google_firewall_cleanup')
        self.assertEqual(r.returncode, 0)
        self.assertEqual(len(self.case.deletes()), 4, 'both Google UIDs in both families; 10200 stays')
        self.assertEqual((self.case.run / 'google-firewall-status').read_text(), 'checked=2 removed=4 failed=0\n')

    def test_strict_awk(self):
        self.run_with('strict')

    @unittest.skipUnless(os.environ.get('HETU_TEST_ONETRUE_AWK') or shutil.which('original-awk'), 'no One True Awk on this host')
    def test_real_one_true_awk(self):
        self.run_with(os.environ.get('HETU_TEST_ONETRUE_AWK') or shutil.which('original-awk'))


def fake_nodes(health_port, origin_port):
    """Two HTTP CONNECT proxies: 'stall' answers CONNECT to the health URL's port and then forwards, anything else is
    accepted and never answered; 'good' forwards everything to the local origin (its delay test 300 ms slower)."""
    servers = []

    def origin(port):
        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                body = b'' if self.path.endswith('generate_204') else b'hello'
                self.send_response(204 if not body else 200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        s = ThreadingHTTPServer(('127.0.0.1', port), H)
        threading.Thread(target=s.serve_forever, daemon=True).start()
        servers.append(s)

    def pipe(a, b):
        try:
            while True:
                r, _, _ = select.select([a, b], [], [], 30)
                if not r:
                    return
                for x in r:
                    d = x.recv(65536)
                    if not d:
                        return
                    (b if x is a else a).sendall(d)
        except OSError:
            pass
        finally:
            a.close()
            b.close()

    def proxy(stall):
        ls = socket.socket()
        ls.bind(('127.0.0.1', 0))
        ls.listen(64)
        held = []

        def one(c):
            data = b''
            while b'\r\n\r\n' not in data:
                d = c.recv(4096)
                if not d:
                    return c.close()
                data += d
            meth, target = data.split(b'\r\n')[0].decode().split(' ')[:2]
            if meth != 'CONNECT':
                return c.close()
            port = int(target.rpartition(':')[2])
            c.sendall(b'HTTP/1.1 200 Connection established\r\n\r\n')
            if stall and port != health_port:
                held.append(c)
                return
            if not stall and port == health_port:
                time.sleep(0.3)  # the stalling node answers its delay test faster: url-test starts on it
            u = socket.create_connection(('127.0.0.1', port if port in (health_port, origin_port) else origin_port), timeout=5)
            pipe(c, u)

        def loop():
            while True:
                try:
                    c, _ = ls.accept()
                except OSError:
                    return
                threading.Thread(target=one, args=(c,), daemon=True).start()
        threading.Thread(target=loop, daemon=True).start()
        servers.append(ls)
        return ls.getsockname()[1]

    origin(health_port)
    origin(origin_port)
    return proxy(True), proxy(False), servers


@unittest.skipUnless(os.environ.get('HETU_TEST_MIHOMO'), 'set HETU_TEST_MIHOMO to a Mihomo binary')
class RealMihomo(Case):
    def setUp(self):
        super().setUp()
        self.health, self.origin = free_port(), free_port()
        stall_port, good_port, servers = fake_nodes(self.health, self.origin)
        self.cleanups.append(lambda: [s.shutdown() if hasattr(s, 'shutdown') else s.close() for s in servers])
        self.cp, self.mixed = free_port(), free_port()
        cfg = self.dir / 'mihomo.yaml'
        url = 'http://127.0.0.1:%d/generate_204' % self.health
        cfg.write_text('\n'.join([
            'mixed-port: %d' % self.mixed, 'external-controller: 127.0.0.1:%d' % self.cp, "secret: 's3cr3t'", 'log-level: info',
            'mode: rule', 'profile: {store-selected: true}', 'proxies:',
            '  - {name: "%s", type: http, server: 127.0.0.1, port: %d}' % (BAD, stall_port),
            '  - {name: "%s", type: http, server: 127.0.0.1, port: %d}' % (SG, good_port), 'proxy-groups:',
            '  - {name: %s, type: fallback, proxies: ["%s", "%s"], url: "%s", interval: 300}' % (FAILOVER, BAD, SG, url),
            '  - {name: %s, type: url-test, proxies: ["%s", "%s"], url: "%s", interval: 300, tolerance: 500}' % (AUTO, BAD, SG, url),
            '  - {name: %s, type: select, proxies: ["%s", %s, "%s"]}' % (MEDIA, BAD, FAILOVER, SG),
            '  - {name: %s, type: select, proxies: [%s, "%s"]}' % (FINAL, FAILOVER, SG),
            'rules:', '  - DOMAIN-SUFFIX,media.test,%s' % MEDIA, '  - MATCH,%s' % FINAL, '']))
        home = self.dir / 'mihomo-home'
        home.mkdir()
        self.core = subprocess.Popen([os.environ['HETU_TEST_MIHOMO'], '-d', str(home), '-f', str(cfg)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.cleanups.append(lambda: (self.core.kill(), self.core.wait()))
        for _ in range(100):
            try:
                socket.create_connection(('127.0.0.1', self.cp), timeout=0.2).close()
                break
            except OSError:
                time.sleep(0.1)
        self.session(cp=self.cp)
        self.curls = []

    def api(self, path):
        req = urllib.request.Request('http://127.0.0.1:%d%s' % (self.cp, path), headers={'Authorization': 'Bearer s3cr3t'})
        return json.loads(urllib.request.urlopen(req, timeout=5).read() or b'null')

    def fetch(self, url, timeout):
        p = subprocess.Popen(['curl', '-s', '-o', '/dev/null', '-w', '%{http_code}', '-m', str(timeout), '-x', 'http://127.0.0.1:%d' % self.mixed, url],
                             stdout=subprocess.PIPE, text=True)
        self.cleanups.append(lambda: p.kill())
        return p

    def test_stalled_node_pinned_away_traffic_flows_and_returns_later(self):
        # The stalling node passes its delay test: Mihomo keeps it in both auto groups.
        groups = self.api('/proxies')['proxies']
        self.assertEqual((groups[FAILOVER]['now'], groups[AUTO]['now']), (BAD, BAD))
        self.assertGreater(self.api('/proxies/%s/delay?timeout=3000&url=http://127.0.0.1:%d/generate_204' % (quote(BAD), self.health))['delay'], 0)
        for host in ('www.reddit.com', 'www.netflix.com', 'open.spotify.com', 'x.media.test'):
            self.fetch('https://%s/' % host, 60)
        stuck = self.fetch('http://127.0.0.1:%d/ok' % self.origin, 60)
        time.sleep(10)
        # The DIRECT check alone is stubbed: Mihomo's DIRECT delay test to a loopback server answers once and then
        # errors on every later call (host-observed with 1.19.31); on a phone it goes to mainland probe hosts.
        r = self.sh(self.init() + 'wd_delay(){ [ "$1" = DIRECT ]; }; wd_stall_tick; echo "rc=$?"')
        self.assertIn('rc=0', r.stdout, r.stderr)
        groups = self.api('/proxies')['proxies']
        self.assertEqual((groups[FAILOVER]['now'], groups[FAILOVER]['fixed']), (SG, SG), self.log_text() + r.stderr)
        self.assertEqual((groups[AUTO]['now'], groups[AUTO]['fixed']), (SG, SG))
        self.assertEqual(groups[MEDIA]['now'], BAD, 'the manual selector is left alone')
        stuck.wait(10)  # closed by the watchdog (curl reports an empty reply), not by its own 60 s limit
        self.assertEqual(self.api('/connections')['connections'] or [], [])
        self.assertIn('手动选择的「%s」也在用它' % MEDIA, self.notifications()[0])
        # New requests through 最终兜底 -> 故障转移 now reach the site.
        self.assertEqual(self.fetch('http://127.0.0.1:%d/ok' % self.origin, 10).communicate()[0], '200')
        # With the group's own delay history filled the parser still sees its pin.
        self.assertGreater(self.api('/proxies/%s/delay?timeout=3000&url=http://127.0.0.1:%d/generate_204' % (quote(FAILOVER), self.health))['delay'], 0)
        self.assertTrue(self.api('/proxies')['proxies'][FAILOVER]['history'])
        r = self.sh(self.init() + 'WD_DP_GROUP_MAX=50; wd_groups; cat "$WD_TMP.groups"')
        self.assertIn('Fallback\t%s\t%s\t%s' % (FAILOVER, SG, SG), r.stdout)
        # Time up: both groups go back to automatic selection.
        r = self.sh(self.init() + 'WD_NOW=$((WD_NOW+601)); wd_stall_unpin')
        groups = self.api('/proxies')['proxies']
        self.assertEqual((groups[FAILOVER]['fixed'], groups[AUTO]['fixed']), ('', ''), r.stderr)
        self.assertEqual(groups[FAILOVER]['now'], BAD, 'Mihomo picks its first alive member again')


class Guards(unittest.TestCase):
    def test_source(self):
        self.assertNotIn('split(uids,ids,/\\n/)', TEXT)
        self.assertIn('-v uids="$GF_UID_LIST"', TEXT)
        self.assertNotIn('j = index(s, "]"); if (j) s = substr(s, 1, j - 1)', TEXT, 'chains cut at the first ]')
        body = function('wd_selfheal_tick')
        self.assertIn('wd_stall_tick', body)
        self.assertLess(body.index('wd_dataplane_tick'), body.index('wd_stall_tick'))
        heal = function('wd_stall_heal')
        self.assertLess(heal.index('wd_delay DIRECT'), heal.index('wd_groups'), 'DIRECT is checked before anything changes')
        self.assertLess(heal.index('wd_stall_pin'), heal.index('wd_close_through'), 'pinned first, then connections close')
        self.assertIn('if [ -n "$WSH_SW" ]; then\n    if wd_close_through', heal, 'nothing closes without a pinned group')


if __name__ == '__main__':
    unittest.main()
