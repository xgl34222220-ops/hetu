"""Host-only verification of the disposable Android WebView fixture."""
import http.client
import json
import unittest
from urllib.parse import quote
from mock_webview_controller import controller_fixture, panel_controller_fixture, PanelControllerMode, PANEL_AUTH_SECRET
from http.server import ThreadingHTTPServer
from unittest.mock import patch

class MockWebViewControllerTest(unittest.TestCase):
 def test_fixed_routes_and_rejected_switch_keep_original_selection(self):
  with controller_fixture() as (port, requests):
   def request(method, path, body=None):
    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=10)
    try:
     connection.request(method,path,json.dumps(body).encode() if body else None)
     response=connection.getresponse();return response.status,json.loads(response.read())
    finally:connection.close()
   self.assertEqual((200,{'version':'v1.19.0'}),request('GET','/version'))
   status,groups=request('GET','/proxies');self.assertEqual(200,status);self.assertEqual(12,len(groups['proxies']))
   status,connections=request('GET','/connections');self.assertEqual(200,status);self.assertEqual(18,len(connections['connections']))
   self.assertEqual(404,request('GET','/unexpected')[0])
   self.assertEqual(503,request('PUT','/proxies/'+quote('节点选择'),{'name':'香港 02'})[0])
   self.assertEqual(400,request('PUT','/proxies/unknown',{'name':'香港 02'})[0])
   self.assertEqual('香港 01',request('GET','/proxies')[1]['proxies']['节点选择']['now'])
   self.assertEqual(7,len(requests))
 def test_native_panel401_recovers_complete_synthetic_snapshot(self):
  with panel_controller_fixture() as (port,requests,mode):
   def request(path):
    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=10)
    try:
     connection.request('GET',path,headers={'Authorization':'Bearer '+PANEL_AUTH_SECRET})
     response=connection.getresponse();return response.status,json.loads(response.read())
    finally:connection.close()
   self.assertEqual(401,request('/configs')[0])
   self.assertEqual(401,request('/proxies')[0])
   mode.set_status(200)
   self.assertEqual((200,{'mode':'rule'}),request('/configs'))
   self.assertEqual(12,len(request('/proxies')[1]['proxies']))
   self.assertEqual((200,{'providers':{}}),request('/providers/proxies'))
   self.assertEqual(18,len(request('/connections')[1]['connections']))
   self.assertEqual(3,len(request('/rules')[1]['rules']))
   self.assertEqual(404,request('/unexpected')[0])
   self.assertEqual([401,401,200,200,200,200,200,404],[r['status'] for r in requests])
   self.assertTrue(all('timeMonotonic' in r and set(r)=={'method','path','status','timeMonotonic','valid_fixture_authorization'} for r in requests))
   self.assertTrue(all(r['valid_fixture_authorization'] for r in requests))
 def test_native_panel200_requires_actual_bearer_and_logs_no_token(self):
  with panel_controller_fixture() as (port,requests,mode):
   mode.set_status(200)
   for authorization,expected in [(None,401),('Bearer wrong-fixture-token',401),(PANEL_AUTH_SECRET,401),('Bearer '+PANEL_AUTH_SECRET,200)]:
    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=10)
    try:
     connection.request('GET','/configs',headers={} if authorization is None else {'Authorization':authorization})
     response=connection.getresponse();self.assertEqual(expected,response.status);response.read()
    finally:connection.close()
   self.assertEqual([False,False,False,True],[r['valid_fixture_authorization'] for r in requests])
   logged=json.dumps(requests)
   self.assertNotIn(PANEL_AUTH_SECRET,logged)
   self.assertNotIn('wrong-fixture-token',logged)
 def test_panel_mode_cannot_turn_into_uncontrolled_status_fixture(self):
  mode=PanelControllerMode()
  self.assertEqual(401,mode.get_status())
  with self.assertRaises(ValueError):mode.set_status(503)
  self.assertEqual(401,mode.get_status())
 def test_native_panel_server_binds_only_host_loopback(self):
  with patch('mock_webview_controller.ThreadingHTTPServer',wraps=ThreadingHTTPServer) as server:
   with panel_controller_fixture():pass
  self.assertEqual(('127.0.0.1',0),server.call_args.args[0])
if __name__=='__main__':unittest.main()
