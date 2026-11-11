import hashlib,json,secrets,threading,time
from datetime import datetime,date,timedelta
from zoneinfo import ZoneInfo
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse
import requests
import vault
import copy
import writes,web_state
from event_rules import visible,cancelled,identity
from ews import fetch_events as fetch_ews, EWSError, normalize_endpoint
from yandex import fetch_events as fetch_yandex
from ics import parse_events,fetch_calendar

TZ=ZoneInfo('Europe/Moscow'); PORT=8766; TOKEN=secrets.token_urlsafe(24)
lock=threading.RLock()
view_start=datetime.now(TZ).date();view_end=view_start+timedelta(days=1)
credentials={};versions={};providers={}
notices=[];notice_sequence=0;mirror_status=""

for key,name in [('ews','Работа · Exchange'),('yandex','Яндекс'),('ics','ICS')]:
    versions[key]=0
    providers[key]=dict(id=key,name=name,connected=False,busy=False,checked=None,success=None,next=None,error=None,events=[],calendars=[],history=[],checks=0,digest=None,due=0,rangeStart=None,rangeEnd=None)

def restore_connections():
    with lock:
        for key in providers:
            try:
                config=vault.load(key)
                if config is None:continue
                if key=='ews':config['endpoint']=normalize_endpoint(config.get('endpoint'))
                if key in ('ews','yandex') and not all(isinstance(config.get(k),str) and config[k] for k in ('login','password')):raise ValueError()
                if key=='ics':
                    parsed=urlparse(config.get('url',''))
                    if parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.password:raise ValueError()
                web_state.bind_connection(key,config)
                credentials[key]=config
                providers[key].update(connected=True,error=None,due=0)
            except Exception:
                providers[key].update(connected=False,error='Сохранённое подключение не восстановлено. Проверьте доступ к хранилищу ОС или подключитесь заново.')

def now():return datetime.now(TZ).isoformat()

def poll(key):
    with web_state.operation_lock:_poll(key)

def _poll(key):
    with lock:
        config=credentials.get(key)
        if not config:return
        version=versions[key];first,last=view_start,view_end
        p=providers[key];p['busy']=True;started=time.monotonic()
    halted=False;error=None
    try:
        if key=='ews':
            events=fetch_ews(config['login'],config['password'],first,last,endpoint=config['endpoint'],include_cancelled=True)
            calendars=[{'id':'ews:main','name':'Работа'}]
            for e in events:e.update(id='ews|'+e['id'],calendarId='ews:main',calendarName='Работа',provider='ews')
        elif key=='yandex':events,calendars=fetch_yandex(config['login'],config['password'],first,last,include_private=True)
        else:
            events=parse_events(fetch_calendar(config['url']),first,last);calendars=[{'id':'ics:main','name':'ICS'}]
            for e in events:e.update(id='ics|'+e['id'],calendarId='ics:main',calendarName='ICS',provider='ics')
        if key=='ics':
            for e in events:e['stableId']='ics|'+e.get('stableId',e['id'])
        events.sort(key=lambda e:(e['start'],e['id']))
        digest=hashlib.sha256(json.dumps(events,sort_keys=True).encode()).hexdigest()
    except EWSError as e:error=str(e);halted=e.halt
    except requests.HTTPError as e:error=f'HTTP {e.response.status_code}: сервер не отдал календарь. Повторим через минуту.'
    except requests.RequestException:error='Нет ответа сервера: проверьте сеть, VPN и сертификат. Повторим через минуту.'
    except Exception:error='Ответ календаря не удалось обработать. Последнее расписание сохранено.'
    with lock:
        p=providers[key];p['busy']=False
        if version!=versions[key]:return
        stamp=now();p.update(checked=stamp,error=error,checks=p['checks']+1,due=started+60)
        if halted:
            credentials.pop(key,None);p.update(connected=False,next=None)
        else:p['next']=(datetime.now(TZ)+timedelta(seconds=max(0,p['due']-time.monotonic()))).isoformat()
        if error:message=error
        else:
            old={e['id']:e for e in p['events'] if datetime.fromisoformat(e['start']).date()<last and datetime.fromisoformat(e['end']).date()>=first};new={e['id']:e for e in events}
            if p['digest'] is not None and p['rangeStart']==str(first) and p['rangeEnd']==str(last):record_changes(key,old,new,config)
            changed=sum(old[k]!=new[k] for k in old.keys()&new.keys())
            message='Расписание загружено' if p['digest'] is None else f'Добавлено {len(new.keys()-old.keys())}, изменено {changed}, удалено {len(old.keys()-new.keys())}' if p['digest']!=digest else 'Без изменений'
            known={identity(e) for e in events};window_start=datetime.combine(first,datetime.min.time(),TZ);window_end=datetime.combine(last,datetime.min.time(),TZ)
            retained=[e for e in p['events'] if identity(e) not in known and (datetime.fromisoformat(e['end'])<=window_start or datetime.fromisoformat(e['start'])>=window_end)]
            p.update(events=retained+events,calendars=calendars,success=stamp,digest=digest,rangeStart=str(first),rangeEnd=str(last))
        p['history']=([dict(time=stamp,message=message)]+p['history'])[:40]
    if not error:
        global mirror_status
        mirror_status=web_state.sync(providers,credentials)

def worker(key):
    while True:
        with lock:ready=key in credentials and providers[key]['due']<=time.monotonic()
        if ready:poll(key)
        time.sleep(.5)

def record_changes(key,old,new,config):
    global notice_sequence
    settings=web_state.settings()
    old_identity={identity(e):e for e in old.values()};new_identity={identity(e) for e in new.values()}
    for eid,e in new.items():
        before=old.get(eid) or old_identity.get(identity(e));kind=None
        if datetime.fromisoformat(e['end'])<datetime.now(TZ):continue
        if web_state.is_copy(e):continue
        if before and not cancelled(before) and cancelled(e):kind='cancellations'
        elif visible(e,settings):
            if before and (e['start'],e['end'])!=(before['start'],before['end']):kind='moves'
            elif before is None:kind='new'
        if kind:
            notice_sequence+=1;notices.append(dict(sequence=notice_sequence,kind=kind,event=e,before=before))
    pending=copy.deepcopy(web_state.state.get('missing',{}))
    for eid,e in old.items():
        if identity(e) in new_identity or datetime.fromisoformat(e['end'])<datetime.now(TZ) or web_state.is_copy(e) or cancelled(e) or not visible(e,settings):continue
        pending[identity(e)]=e
    for eid in new_identity:pending.pop(eid,None)
    changed=pending!=web_state.state.get('missing',{})
    if changed:
        value=copy.deepcopy(web_state.state);value['missing']=pending
        try:web_state.persist(value)
        except vault.VaultError:return
    checked=0
    for eid,e in list(pending.items()):
        if e['provider']!=key:continue
        if datetime.fromisoformat(e['end'])<datetime.now(TZ):pending.pop(eid);continue
        if checked>=20:break
        checked+=1
        try:deleted=writes.confirmed_deleted(config,e)
        except Exception:continue
        if deleted:
            notice_sequence+=1;notices.append(dict(sequence=notice_sequence,kind='cancellations',event=e));pending.pop(eid)
        elif not e.get('recurring') and key!='ics':
            # Existing source may have moved outside the requested window.
            pending.pop(eid)
    if pending!=web_state.state.get('missing',{}):
        value=copy.deepcopy(web_state.state);value['missing']=pending
        try:web_state.persist(value)
        except vault.VaultError:pass
    del notices[:-100]

def snapshot():
    with lock:
        public=[]
        for p in providers.values():
            value={k:copy.deepcopy(v) for k,v in p.items() if k not in ('due','digest')}
            value['calendars']=[{k:v for k,v in c.items() if k!='url'} for c in value['calendars']]
            for event in value['events']:
                if web_state.is_copy(event):event['copyToken']='copy-known'
            public.append(value)
        return dict(version='0.6.0',timezone=str(TZ),start=str(view_start),end=str(view_end),providers=public,settings=web_state.settings(),settingsError=web_state.error,mirrorStatus=mirror_status,notices=copy.deepcopy(notices),noticeSequence=notice_sequence)

def known_calendars():return {c['id']:(key,c) for key,p in providers.items() for c in p['calendars']}
def find_event(eid):
    for p in providers.values():
        for e in p['events']:
            if e['id']==eid:return copy.deepcopy(e)
    raise ValueError('Событие больше не загружено. Обновите расписание.')

def event_action(path,data):
    cid=data.get('calendarId');known=known_calendars()
    if path=='/api/event-detail':
        event=find_event(data.get('id'));return writes.detail(credentials[event['provider']],event)
    if cid not in known:raise ValueError('Календарь недоступен.')
    key,cal=known[cid]
    if key not in credentials or key=='ics':raise ValueError('Нет подключения с правом записи.')
    desired=None;token=data.get('operation','');request_hash=None;operation=None
    if path=='/api/event-save':
        desired=writes.validate(data.get('event',{}))
        if not isinstance(token,str) or not __import__('re').fullmatch(r'edit-[a-zA-Z0-9-]{16,80}',token):raise ValueError('Обновите форму.')
        request_hash=web_state.fingerprint(dict(calendar=cid,before=data.get('id'),event=desired,connection={k:credentials[key].get(k) for k in ('login','endpoint','url')}))
        operation=web_state.state['operations'].get(token)
        if operation:
            if operation['request']!=request_hash:raise ValueError('Повтор операции отличается от исходного. Откройте новую форму.')
            if operation.get('result'):return operation['result']
    before=find_event(data['id']) if data.get('id') else None
    if before and (before['calendarId']!=cid or before.get('recurring') or web_state.is_copy(before)):raise ValueError('Редактируйте оригинал одиночной встречи в его календаре.')
    if before:
        before=writes.detail(credentials[key],before)
        if before.get('recurring') or before.get('copyToken'):raise ValueError('Редактируйте оригинал одиночной встречи.')
        if not data.get('etag') or data['etag']!=before.get('etag'):
            if operation and writes.validate(before)==desired:
                web_state.journal(token,dict(request=request_hash,result=before));return before
            raise ValueError('Событие изменилось. Откройте заново.')
    if path=='/api/event-delete':
        if not before:raise ValueError('Выберите событие.')
        writes.delete(credentials[key],before);saved=None
    else:
        web_state.journal(token,dict(request=request_hash))
        saved=writes.save(credentials[key],key,cal,desired,before,token)
        web_state.journal(token,dict(request=request_hash,result=saved))
    with lock:
        p=providers[key];p['events']=[e for e in p['events'] if e['id'] not in {data.get('id'),saved['id'] if saved else None}]
        if saved:p['events'].append(saved)
        p['due']=0
    return saved or {'ok':True}

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def send(self,value,status=200,content='application/json'):
        data=value if isinstance(value,bytes) else json.dumps(value,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Length',str(len(data)));self.send_header('Content-Type',content+'; charset=utf-8');self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('X-Frame-Options','DENY');self.end_headers();self.wfile.write(data)
    def valid_host(self):return self.headers.get('Host') in (f'127.0.0.1:{PORT}',f'localhost:{PORT}')
    def do_GET(self):
        if not self.valid_host():return self.send({},403)
        assets={'/':('index.html','text/html'),'/calendar.js':('calendar.js','application/javascript'),'/calendar.css':('calendar.css','text/css')}
        if self.path in assets:
            name,mime=assets[self.path];raw=Path(__file__).with_name(name).read_bytes()
            return self.send(raw.replace(b'__TOKEN__',TOKEN.encode()) if name=='index.html' else raw,content=mime)
        if self.path=='/api/state':
            if self.headers.get('X-App-Token')!=TOKEN:return self.send({},403)
            return self.send(snapshot())
        self.send({},404)
    def do_POST(self):
        with web_state.operation_lock:self.post_locked()
    def post_locked(self):
        global view_start,view_end
        if not self.valid_host() or self.headers.get('X-App-Token')!=TOKEN:return self.send({},403)
        try:
            n=int(self.headers.get('Content-Length',0))
            if not 0<=n<=65536:return self.send({},413)
            data=json.loads(self.rfile.read(n) or '{}')
            if not isinstance(data,dict):raise ValueError()
        except Exception:return self.send({'error':'Некорректный запрос'},400)
        if self.path in ('/api/event-detail','/api/event-save','/api/event-delete','/api/settings'):
            try:
                if self.path=='/api/settings':
                    web_state.configure(data,known_calendars())
                    for p in providers.values():p['due']=0
                    return self.send({'ok':True})
                return self.send(event_action(self.path,data))
            except (ValueError,EWSError,vault.VaultError) as e:return self.send({'error':str(e)},400)
            except writes.Missing:return self.send({'error':'Событие удалено или больше недоступно.'},404)
            except Exception:return self.send({'error':'Запись не подтверждена. Проверьте подключение и повторите с той же формой.'},503)
        if self.path=='/api/range':
            try:
                first=date.fromisoformat(data['start']);last=date.fromisoformat(data['end'])
                if not 0<(last-first).days<=62:raise ValueError()
            except (KeyError,ValueError,TypeError):return self.send({'error':'Выберите период до 62 дней.'},400)
            with lock:
                if (first,last)!=(view_start,view_end):
                    view_start,view_end=first,last
                    for key,p in providers.items():versions[key]+=1;p.update(digest=None,due=0,rangeStart=None,rangeEnd=None)
        elif self.path in ('/api/connect-ews','/api/connect-yandex','/api/connect-ics'):
            key=self.path.split('-')[-1]
            if key=='ics':
                url=str(data.get('url','')).strip().replace('webcal://','https://',1);parsed=urlparse(url)
                if parsed.scheme not in ('https','http') or not parsed.hostname or parsed.username or parsed.password:return self.send({'error':'Нужна ICS-ссылка HTTP(S).'},400)
                config={'url':url}
            else:
                login=str(data.get('login','')).strip();password=data.get('password','')
                if not login or not isinstance(password,str) or not password or len(login)>300 or len(password)>2000:return self.send({'error':'Введите логин и пароль.'},400)
                config={'login':login,'password':password}
                if key=='ews':
                    try:config['endpoint']=normalize_endpoint(data.get('endpoint'))
                    except EWSError as e:return self.send({'error':str(e)},400)
            with lock:
                try:
                    web_state.bind_connection(key,config)
                    vault.save(key,config)
                except ValueError as e:return self.send({'error':str(e)},400)
                except vault.VaultError as e:return self.send({'error':str(e)},503)
                versions[key]+=1;credentials[key]=config
                providers[key].update(connected=True,error=None,events=[],calendars=[],history=[],checks=0,success=None,checked=None,digest=None,due=0,next=None,rangeStart=None,rangeEnd=None)
        elif self.path in ('/api/refresh','/api/stop'):
            if self.path=='/api/refresh' and data.get('selected'):
                try:
                    anchor=date.fromisoformat(data['selected']);first=min(view_start,anchor-timedelta(days=14));last=max(view_end,anchor+timedelta(days=31))
                    if (last-first).days>62:first=anchor-timedelta(days=14);last=anchor+timedelta(days=31)
                    view_start,view_end=first,last
                except (ValueError,TypeError,OverflowError):return self.send({'error':'Некорректная дата.'},400)
                for key in providers:versions[key]+=1
            selected=data.get('provider')
            if selected is not None and selected not in providers:return self.send({},400)
            with lock:
                for key in ([selected] if selected else list(providers)):
                    if self.path=='/api/refresh':providers[key]['due']=0
                    else:
                        try:vault.delete(key)
                        except vault.VaultError as e:return self.send({'error':str(e)},503)
                        versions[key]+=1;credentials.pop(key,None);providers[key].update(connected=False,events=[],calendars=[],error=None,next=None,success=None)
        else:return self.send({},404)
        self.send({'ok':True})

if __name__=='__main__':
    web_state.restore()
    restore_connections()
    for key in providers:threading.Thread(target=worker,args=(key,),daemon=True).start()
    print(f'Calendar: http://127.0.0.1:{PORT}',flush=True)
    ThreadingHTTPServer(('127.0.0.1',PORT),Handler).serve_forever()
