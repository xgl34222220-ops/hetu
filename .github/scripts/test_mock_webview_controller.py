"""Host-only verification of the disposable Android WebView fixture."""
import http.client
import json
import unittest
from urllib.parse import quote
from mock_webview_controller import controller_fixture

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
if __name__=='__main__':unittest.main()
