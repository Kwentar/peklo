import unittest
from unittest.mock import patch,MagicMock
from datetime import date
import xml.etree.ElementTree as ET
from ews import parse_response,request_body,fetch_events,EWSError,NS,normalize_endpoint

def response(items='',code='NoError',complete='true'):
    return f'''<s:Envelope xmlns:s="{NS['s']}" xmlns:m="{NS['m']}" xmlns:t="{NS['t']}"><s:Body><m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage ResponseClass="{'Success' if code=='NoError' else 'Error'}"><m:ResponseCode>{code}</m:ResponseCode><m:RootFolder IncludesLastItemInRange="{complete}"><t:Items>{items}</t:Items></m:RootFolder></m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse></s:Body></s:Envelope>'''.encode()
ITEM='''<t:CalendarItem><t:ItemId Id="one"/><t:Subject>Meeting &amp; test</t:Subject><t:Start>2026-11-07T09:00:00Z</t:Start><t:End>2026-11-07T10:00:00Z</t:End><t:IsAllDayEvent>false</t:IsAllDayEvent><t:IsCancelled>false</t:IsCancelled><t:Location>Room</t:Location></t:CalendarItem>'''
class EWSTests(unittest.TestCase):
    def test_calendar_view(self):
        root=ET.fromstring(request_body(date(2026,11,7)))
        view=root.find('.//m:CalendarView',NS)
        self.assertEqual(view.get('StartDate'),'2026-11-07T00:00:00+03:00')
        self.assertEqual(view.get('EndDate'),'2026-11-08T00:00:00+03:00')
        self.assertIsNotNone(root.find('.//t:DistinguishedFolderId',NS))
    def test_events_and_cancelled(self):
        events=parse_response(response(ITEM+ITEM.replace('<t:IsCancelled>false','<t:IsCancelled>true')))
        self.assertEqual(len(events),1);self.assertEqual(events[0]['start'],'2026-11-07T12:00:00+03:00');self.assertEqual(events[0]['title'],'Meeting & test')
        self.assertEqual(parse_response(response()),[])
    def test_error_and_truncation(self):
        for raw in [response(code='ErrorAccessDenied'),response(complete='false'),b'<html>SSO</html>',b'broken']:
            with self.assertRaises(EWSError):parse_response(raw)
    def test_auth_stops_and_no_redirect(self):
        for code in [401,403,302]:
            with patch('ews.requests.post') as post:
                post.return_value.__enter__.return_value.status_code=code
                with self.assertRaises(EWSError) as error:fetch_events('test-user','test-password',endpoint='https://mail.example.org')
                self.assertTrue(error.exception.halt)
                self.assertEqual(post.call_args.args[0],'https://mail.example.org/EWS/Exchange.asmx')
                self.assertFalse(post.call_args.kwargs['allow_redirects'])
                self.assertNotIn('test-password',str(error.exception))
    def test_fetch_parses(self):
        with patch('ews.requests.post') as post:
            r=post.return_value.__enter__.return_value;r.status_code=200;r.iter_content.return_value=[response(ITEM)]
            self.assertEqual(len(fetch_events('user','test-password',endpoint='https://mail.example.org')),1)
    def test_configured_endpoint(self):
        for value in ['https://mail.example.org','https://MAIL.example.org:443/','https://mail.example.org/ews/exchange.asmx']:
            self.assertEqual(normalize_endpoint(value),'https://mail.example.org/EWS/Exchange.asmx')
    def test_invalid_endpoint_never_sends_credentials(self):
        for value in [None,'','mail.example.org','http://mail.example.org','https://u:p@mail.example.org',
                      'https://mail.example.org:8443','https://mail.example.org/?token=test',
                      'https://mail.example.org/#fragment','https://mail.example.org/other','https://mail.example.org/?','https://mail.example.org/#',
                      'https://mail.example.org\\@other.example.org','https://mail.example.org/\nEWS/Exchange.asmx',
                      'https://mail%2eexample.org','https://mail.example.org/EWS/%45xchange.asmx']:
            with self.subTest(value=value),patch('ews.requests.post') as post:
                with self.assertRaises(EWSError):fetch_events('test-user','test-password',endpoint=value)
                post.assert_not_called()
    def test_redirect_does_not_forward_password(self):
        for location in ['https://other.example.org/EWS/Exchange.asmx','http://mail.example.org/EWS/Exchange.asmx','https://mail.example.org/owa/']:
            with patch('ews.requests.post') as post:
                r=post.return_value.__enter__.return_value;r.status_code=307;r.headers={'Location':location}
                with self.assertRaises(EWSError):fetch_events('test-user','test-password',endpoint='https://mail.example.org')
                post.assert_called_once();self.assertFalse(post.call_args.kwargs['allow_redirects'])
                self.assertEqual(post.call_args.kwargs['auth'],('test-user','test-password'))
if __name__=='__main__':unittest.main()
