import json
import unittest
from unittest.mock import patch
import app
import vault

class MemoryVault:
    def __init__(self):self.values={}
    def get_password(self,service,key):return self.values.get((service,key))
    def set_password(self,service,key,value):self.values[service,key]=value
    def delete_password(self,service,key):self.values.pop((service,key),None)

class VaultTests(unittest.TestCase):
    def setUp(self):
        self.store=MemoryVault()
        self.patch=patch('vault.backend',return_value=self.store);self.patch.start()
        app.credentials.clear()
    def tearDown(self):self.patch.stop();app.credentials.clear()
    def test_restore_after_memory_loss_and_delete(self):
        config={'login':'synthetic','password':'synthetic-secret','endpoint':'https://mail.example.org'}
        vault.save('ews',config);app.restore_connections()
        self.assertEqual(app.credentials['ews']['password'],'synthetic-secret')
        self.assertEqual(app.credentials['ews']['endpoint'],'https://mail.example.org/EWS/Exchange.asmx')
        self.assertNotIn('synthetic-secret',json.dumps(app.snapshot()))
        app.credentials.clear();app.restore_connections();self.assertIn('ews',app.credentials)
        vault.delete('ews');app.credentials.clear();app.restore_connections();self.assertNotIn('ews',app.credentials)
    def test_read_failure_does_not_overwrite_saved_secret(self):
        vault.save('ics',{'url':'https://example.org/feed'})
        with patch.object(self.store,'get_password',side_effect=RuntimeError('private-detail')):
            app.restore_connections();self.assertNotIn('ics',app.credentials)
            self.assertNotIn('private-detail',json.dumps(app.snapshot()))
        self.assertIsNotNone(vault.load('ics'))
    def test_backend_failure_never_silently_saves(self):
        with patch('vault.backend',side_effect=RuntimeError('private-detail')):
            with self.assertRaises(vault.VaultError) as error:vault.save('ews',{})
            self.assertNotIn('private-detail',str(error.exception))
    def test_invalid_saved_endpoint_does_not_connect(self):
        vault.save('ews',{'login':'synthetic','password':'synthetic','endpoint':'http://example.org'})
        app.restore_connections();self.assertNotIn('ews',app.credentials)
