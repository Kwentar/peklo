import copy,unittest
from unittest.mock import patch,MagicMock
from datetime import date
import xml.etree.ElementTree as ET
import writes,ews,web_state,app
from test_ews import ITEM,response
from test_app import calendar,event
from event_rules import visible
CONFIG={'login':'fixture','password':'fixture-secret','endpoint':'https://mail.example.org'}
DESIRED=dict(title='Fixture',start='2026-11-12T12:00:00+03:00',end='2026-11-12T13:00:00+03:00',reminderMinutes=37,description='Details',location='Room',allDay=False)
def source():return dict(DESIRED,id='ics|fixture',stableId='ics|fixture',provider='ics',calendarId='ics:main',calendarName='Source')
def result(body):return ET.fromstring(f'<r xmlns:t="{ews.NS["t"]}">{body}</r>')
class WritesTests(unittest.TestCase):
 def test_ews_updates_are_conditional_and_no_invites(self):
  before={'id':'ews|fixture','etag':'v1'}
  with patch('writes.soap',return_value=result('<t:ItemId Id="fixture" ChangeKey="v2"/>')) as soap,patch('writes.ews_detail',return_value=DESIRED):
   writes.ews_save(CONFIG,DESIRED,before,'edit-'+'a'*20)
  body=soap.call_args.args[1];self.assertIn('NeverOverwrite',body);self.assertIn('ChangeKey="v1"',body);self.assertIn('SendToNone',body);self.assertIn('>37<',body);self.assertNotIn('Attendees',body)
 def test_create_retry_recovers_without_second_create(self):
  with patch('writes.ews_recover',return_value=DESIRED),patch('writes.soap') as soap:
   self.assertEqual(writes.ews_save(CONFIG,DESIRED,None,'edit-'+'a'*20),DESIRED);soap.assert_not_called()
 def test_ews_detail_reads_reminder_and_copy(self):
  item=ET.fromstring(f'<r xmlns:t="{ews.NS["t"]}">'+ITEM.replace('<t:IsCancelled>false','<t:ReminderIsSet>false</t:ReminderIsSet><t:IsCancelled>false')+'</r>')[0]
  parsed=ews.parse_item(item);self.assertEqual(parsed['reminderMinutes'],-1)
 def test_dav_preserves_unknown_fields_and_etag(self):
  raw=calendar(event('UID:fixture\r\nDTSTART:20261112T090000Z\r\nDTEND:20261112T100000Z\r\nSUMMARY:Original\r\nX-CUSTOM:keep\r\nATTENDEE:mailto:guest@example.test\r\n'))
  before=dict(id='fixture',remoteUri='https://caldav.yandex.ru/cal/fixture.ics',calendarId='cal',calendarName='Fixture',etag='"v1"')
  with patch('writes.dav_request',side_effect=[(raw,'"v1"'),(b'','"v2"'),(raw,'"v2"')]) as request:
   writes.dav_save(CONFIG,{'id':'cal','name':'Fixture'},DESIRED,before,'edit-'+'a'*20)
  args=request.call_args_list[1].args;self.assertEqual(args[1],'PUT');self.assertEqual(args[4],'"v1"');self.assertIn(b'X-CUSTOM:keep',args[3]);self.assertIn(b'TRIGGER:-PT37M',args[3]);self.assertIn(b'guest@example.test',args[3])
 def test_dav_conflict_never_puts(self):
  before=dict(etag='old')
  with patch('writes.dav_detail',return_value=({'etag':'new'},None)),patch('writes.dav_request') as request:
   with self.assertRaises(ValueError):writes.dav_save(CONFIG,{},DESIRED,before,'edit-'+'a'*20)
   request.assert_not_called()
 def test_dav_transport_preconditions_and_no_redirect(self):
  with patch('writes.requests.request') as request:
   r=request.return_value.__enter__.return_value;r.status_code=201;r.iter_content.return_value=[];r.headers={}
   writes.dav_request(CONFIG,'PUT','https://caldav.yandex.ru/cal/fixture.ics',b'fixture',create=True)
   self.assertEqual(request.call_args.kwargs['headers']['If-None-Match'],'*');self.assertFalse(request.call_args.kwargs['allow_redirects'])
   r.status_code=412
   with self.assertRaises(ValueError):writes.dav_request(CONFIG,'PUT','https://caldav.yandex.ru/cal/fixture.ics',b'fixture',etag='v1')
 def test_bad_source_never_receives_password(self):
  with patch('writes.requests.request') as request:
   with self.assertRaises(ews.EWSError):writes.dav_request(CONFIG,'GET','https://other.example.test/cal')
   request.assert_not_called()
 def test_filter_exact_only_exchange(self):
  for provider in ('ews','ics','yandex'):
   for title in ('Без встреч','Без встреч ','без встреч','Без встреч сегодня'):
    self.assertEqual(visible(dict(provider=provider,title=title,calendarId='cal'),{},True),not(provider=='ews' and title=='Без встреч'))

class MirrorTests(unittest.TestCase):
 def setUp(self):
  self.previous=copy.deepcopy(web_state.state);self.error=web_state.error;web_state.error='';web_state.state={'calendars':{'ics:main':{'role':'satellite','anonymous':True}},'notifications':{},'links':{},'operations':{}}
  self.vault=patch('web_state.vault.save');self.vault.start()
  self.providers={'ics':dict(success='ok',error=None,events=[source()],calendars=[dict(id='ics:main',name='Source')]),'ews':dict(success='ok',error=None,events=[],calendars=[dict(id='ews:main',name='Target')])};self.credentials={'ics':{},'ews':CONFIG}
 def tearDown(self):web_state.state=self.previous;web_state.error=self.error;self.vault.stop()
 def test_anonymous_idempotency_and_mode_change(self):
  def save(config,provider,cal,desired,before,token):return dict(desired,id='ews|copy',provider='ews',calendarId='ews:main',copyToken=token,etag='v1')
  with patch('writes.recover',return_value=None),patch('writes.save',side_effect=save) as writer:
   self.assertEqual(web_state.sync(self.providers,self.credentials),'');self.assertEqual(writer.call_count,1)
   desired=writer.call_args.args[3];self.assertEqual(desired['title'],'Занят');self.assertEqual(desired['description'],'');self.assertEqual(desired['reminderMinutes'],-1)
   self.assertEqual(web_state.sync(self.providers,self.credentials),'');self.assertEqual(writer.call_count,1)
   web_state.state['calendars']['ics:main']['anonymous']=False;web_state.sync(self.providers,self.credentials)
   self.assertEqual(writer.call_count,2);self.assertEqual(writer.call_args.args[3]['title'],'Fixture');self.assertEqual(writer.call_args.args[3]['reminderMinutes'],-1)
 def test_pending_link_survives_lost_response_and_recovers(self):
  with patch('writes.recover',return_value=None),patch('writes.save',side_effect=TimeoutError):
   self.assertTrue(web_state.sync(self.providers,self.credentials));self.assertEqual(len(web_state.state['links']),1)
  recovered=dict(id='ews|copy',etag='v1',copyToken='copy-fixture')
  with patch('writes.recover',return_value=recovered),patch('writes.save',return_value=recovered) as writer:
   web_state.sync(self.providers,self.credentials);self.assertEqual(writer.call_args.args[4]['id'],'ews|copy')
 def test_window_absence_does_not_delete_and_confirmed_delete_does(self):
  original=source();original['provider']='ews';original['calendarId']='ews:main'
  target=dict(id='yandex|copy',provider='yandex',etag='v1')
  web_state.state['calendars']={'ews:main':{'role':'satellite'}}
  web_state.state['links']={'copy-fixture':dict(original=original,sourceIdentity=original['stableId'],targetProvider='yandex',targetCalendar={'id':'yandex:main'},target=target)}
  self.providers['ics']['events']=[];self.providers['yandex']=dict(success='ok',error=None,events=[],calendars=[]);self.credentials['yandex']=CONFIG
  with patch('writes.confirmed_deleted',return_value=False),patch('writes.delete') as delete:
   web_state.sync(self.providers,self.credentials);delete.assert_not_called()
  with patch('writes.confirmed_deleted',return_value=True),patch('writes.delete') as delete:
   web_state.sync(self.providers,self.credentials);delete.assert_called_once();self.assertFalse(web_state.state['links'])
 def test_vault_failure_prevents_remote_write(self):
  with patch('web_state.vault.save',side_effect=app.vault.VaultError('Unavailable')),patch('writes.save') as save:
   self.assertTrue(web_state.sync(self.providers,self.credentials));save.assert_not_called()
 def test_copies_do_not_replicate(self):
  self.providers['ics']['events'][0]['copyToken']='copy-synthetic'
  with patch('writes.save') as save:web_state.sync(self.providers,self.credentials);save.assert_not_called()

class ApiWriteTests(unittest.TestCase):
 def setUp(self):
  self.state=copy.deepcopy(web_state.state);self.providers=copy.deepcopy(app.providers);self.credentials=copy.deepcopy(app.credentials)
  web_state.state={'calendars':{},'notifications':{},'links':{},'operations':{}}
  app.credentials={'ews':CONFIG};app.providers['ews']['calendars']=[{'id':'ews:main','name':'Fixture'}];app.providers['ews']['events']=[]
  self.vault=patch('web_state.vault.save');self.vault.start()
 def tearDown(self):
  web_state.state=self.state;app.providers.clear();app.providers.update(self.providers);app.credentials.clear();app.credentials.update(self.credentials);self.vault.stop()
 def test_create_and_retry_one_remote_write(self):
  payload=dict(calendarId='ews:main',operation='edit-'+'b'*20,event=DESIRED)
  saved=dict(DESIRED,id='ews|fixture',provider='ews',calendarId='ews:main',etag='v1')
  with patch('writes.save',return_value=saved) as save:
   self.assertEqual(app.event_action('/api/event-save',payload),saved)
   self.assertEqual(app.event_action('/api/event-save',payload),saved);self.assertEqual(save.call_count,1)
  self.assertEqual(len(app.providers['ews']['events']),1)
 def test_readonly_and_conflict_no_write(self):
  saved=dict(DESIRED,id='ews|fixture',provider='ews',calendarId='ews:main',etag='v2')
  app.providers['ews']['events']=[saved]
  with patch('writes.detail',return_value=saved),patch('writes.save') as save:
   with self.assertRaises(ValueError):app.event_action('/api/event-save',dict(calendarId='ews:main',id=saved['id'],etag='v1',operation='edit-'+'c'*20,event=DESIRED))
   save.assert_not_called()
 def test_settings_restore_and_no_remote_operations(self):
  with patch('writes.save') as save:
   web_state.configure({'calendarId':'ews:main','settings':{'color':6,'name':'Alias','role':'satellite','anonymous':False}},app.known_calendars());save.assert_not_called()
  saved=copy.deepcopy(web_state.state);web_state.state={}
  with patch('web_state.vault.load',return_value=saved):web_state.restore()
  self.assertEqual(web_state.settings()['calendars']['ews:main']['color'],6)
 def test_caldav_move_matches_stable_id_not_new_notification(self):
  before=dict(source(),id='old-instance',start='2099-11-12T12:00:00+03:00',end='2099-11-12T13:00:00+03:00')
  after=dict(before,id='new-instance',start='2099-11-13T12:00:00+03:00',end='2099-11-13T13:00:00+03:00')
  old_notices=app.notices[:];old_sequence=app.notice_sequence
  try:
   app.notices=[];app.record_changes('ics',{'old-instance':before},{'new-instance':after},{})
   self.assertEqual([n['kind'] for n in app.notices],['moves'])
  finally:app.notices=old_notices;app.notice_sequence=old_sequence
