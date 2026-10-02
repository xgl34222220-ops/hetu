"""Isolated CI-only controller fixture. Never packaged in the application.
Binds host loopback only; the caller owns and removes its temporary adb reverse.
All names/counters below are synthetic; PUT deliberately fails for the error state.
"""
import json
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from urllib.parse import unquote

NODES = ['香港 01', '香港 02', '日本 01', '日本 02', '新加坡 01', '台湾 01']
GROUPS = ['节点选择', '手动选择', '故障转移', 'AI 稳定', '香港节点', '日本节点', '台湾节点', '新加坡节点', '其他节点', '流媒体', '自动选择', '备用选择']
DATA = {
 '/version': {'version': 'v1.19.0'},
 '/proxies': {'proxies': {name: {'type': 'Selector' if i == 1 else 'URLTest' if i == 0 else 'Fallback', 'now': NODES[i % 6], 'all': NODES} for i, name in enumerate(GROUPS)}},
 '/connections': {'uploadTotal': 624 * 1024, 'downloadTotal': 165 * 1024 * 1024, 'connections': [
   {'metadata': {'host': ['api.example.com', 'download.example.com', '203.0.113.20', 'updates.example.com', '192.0.2.53'][i % 5]},
    'chains': ['节点选择', NODES[i % 6]], 'upload': (i+1)*1024, 'download': (i+1)*1024*1024} for i in range(18)]}}

@contextmanager
def controller_fixture():
 requests = []
 class Handler(BaseHTTPRequestHandler):
  def log_message(self, *_): pass
  def reply(self, status, body):
   raw = json.dumps(body, ensure_ascii=False).encode()
   self.send_response(status); self.send_header('Content-Type', 'application/json; charset=utf-8')
   self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
  def do_GET(self):
   requests.append({'method': 'GET', 'path': self.path})
   self.reply(200, DATA[self.path]) if self.path in DATA else self.reply(404, {'error': 'Unexpected test route'})
  def do_PUT(self):
   size = int(self.headers.get('Content-Length', '0'))
   if not 0 < size <= 4096: return self.reply(400, {'error': 'Invalid fixture body'})
   body = json.loads(self.rfile.read(size))
   name = unquote(self.path.removeprefix('/proxies/'))
   valid = self.path.startswith('/proxies/') and name in GROUPS and body.get('name') in NODES
   requests.append({'method': 'PUT', 'path': self.path, 'valid_fixture_request': valid})
   self.reply(503 if valid else 400, {'error': 'Controlled test rejection'})
 server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
 thread = Thread(target=server.serve_forever, daemon=True); thread.start()
 try: yield server.server_port, requests
 finally: server.shutdown(); server.server_close(); thread.join(timeout=5)
