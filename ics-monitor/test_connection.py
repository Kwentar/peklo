"""Local API integration tests; workers are not started and providers are mocked."""
import http.client
import json
import threading
import unittest
from unittest.mock import patch
import app

class ConnectionTests(unittest.TestCase):
    def setUp(self):
        self.server=app.ThreadingHTTPServer(('127.0.0.1',0),app.Handler)
        self.port=self.server.server_address[1]
        self.port_patch=patch.object(app,'PORT',self.port);self.port_patch.start()
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.vault_patch=patch('app.vault.save');self.vault_patch.start()
        app.credentials.clear()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();self.port_patch.stop();self.vault_patch.stop();app.credentials.clear()
    def post(self,body,token=None):
        c=http.client.HTTPConnection('127.0.0.1',self.port)
        c.request('POST','/api/connect-ews',json.dumps(body),{'X-App-Token':app.TOKEN if token is None else token,'Content-Type':'application/json'})
        r=c.getresponse();status=r.status;r.read();c.close();return status
    def test_requires_server_and_rejects_insecure_before_poll(self):
        for endpoint in [None,'http://mail.example.org','https://user:password@mail.example.org']:
            self.assertEqual(self.post({'login':'test-user','password':'test-password','endpoint':endpoint}),400)
            self.assertNotIn('ews',app.credentials)
    def test_endpoint_passed_to_provider_and_not_exposed_in_snapshot(self):
        self.assertEqual(self.post({'login':'test-user','password':'test-password','endpoint':'https://mail.example.org'}),200)
        with patch('app.fetch_ews',return_value=[]) as fetch:
            app.poll('ews');self.assertEqual(fetch.call_args.kwargs['endpoint'],'https://mail.example.org/EWS/Exchange.asmx')
        snapshot=json.dumps(app.snapshot())
        for secret in ['test-user','test-password','mail.example.org']:self.assertNotIn(secret,snapshot)
    def test_requires_app_token(self):
        self.assertEqual(self.post({'login':'test-user','password':'test-password','endpoint':'https://mail.example.org'},token='invalid'),403)
        self.assertNotIn('ews',app.credentials)
