import unittest
from unittest.mock import patch
from datetime import date
import xml.etree.ElementTree as ET
import yandex
from ews import EWSError

def response(prop,href='/'):
 return ET.fromstring(f'<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>{href}</d:href><d:propstat><d:prop>{prop}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>')
class YandexTests(unittest.TestCase):
 def test_discover_and_read_recurring(self):
  replies=[response('<d:current-user-principal><d:href>/principals/user/</d:href></d:current-user-principal>'),response('<c:calendar-home-set><d:href>/calendars/user/</d:href></c:calendar-home-set>'),response('<d:displayname>Личный</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>','/calendars/user/main/'),response('<c:calendar-data><![CDATA[BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:test\r\nDTSTART:20261106T090000Z\r\nDTEND:20261106T100000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nSUMMARY:Личное событие\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n]]></c:calendar-data>','/calendars/user/main/item.ics')]
  with patch('yandex.xml_request',side_effect=replies) as request:
   events,cals=yandex.fetch_events('user','secret',date(2026,11,7),date(2026,11,8))
  self.assertEqual(len(events),1);self.assertEqual(cals[0]['name'],'Личный');self.assertNotIn('url',cals[0]);self.assertEqual(events[0]['calendarId'],cals[0]['id']);self.assertEqual([c.args[1] for c in request.call_args_list],['PROPFIND','PROPFIND','PROPFIND','REPORT'])
 def test_reject_external_credentials_destination(self):
  for url in ['http://caldav.yandex.ru/','https://other.example/','https://caldav.yandex.ru:444/','https://user:pass@caldav.yandex.ru/']:
   with self.assertRaises(EWSError):yandex.safe_url(url)
 def test_auth_failure(self):
  with patch('yandex.requests.Session') as factory:
   s=factory.return_value.__enter__.return_value;s.request.return_value.__enter__.return_value.status_code=401
   with self.assertRaises(EWSError) as error:yandex.xml_request(s,'PROPFIND',yandex.BASE,'<xml/>')
   self.assertTrue(error.exception.halt)
   self.assertFalse(s.request.call_args.kwargs['allow_redirects'])
 def test_multiple_providers_and_no_credentials_in_snapshot(self):
  import app
  with app.lock:
   app.credentials['ews']={'login':'test','password':'never-expose-me','endpoint':'https://mail.example.org/EWS/Exchange.asmx'}
   app.credentials['yandex']={'login':'test','password':'never-expose-me'}
  with patch('app.fetch_ews',return_value=[]),patch('app.fetch_yandex',side_effect=EWSError('Denied',True)):
   app.poll('ews');app.poll('yandex')
  self.assertIsNotNone(app.providers['ews']['success'])
  self.assertNotIn('yandex',app.credentials)
  self.assertIn('ews',app.credentials)
  self.assertNotIn('never-expose-me',str(app.snapshot()))
  app.credentials.clear()
if __name__=='__main__':unittest.main()
