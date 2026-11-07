import hashlib,json,secrets,threading,time
from datetime import datetime,date,timedelta
from zoneinfo import ZoneInfo
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse
import requests
import vault
from ews import fetch_events as fetch_ews, EWSError, normalize_endpoint
from yandex import fetch_events as fetch_yandex
from ics import parse_events,fetch_calendar

TZ=ZoneInfo('Europe/Moscow'); PORT=8766; TOKEN=secrets.token_urlsafe(24)
lock=threading.RLock()
view_start=datetime.now(TZ).date();view_end=view_start+timedelta(days=1)
credentials={};versions={};providers={}
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
                credentials[key]=config
                providers[key].update(connected=True,error=None,due=0)
            except Exception:
                providers[key].update(connected=False,error='Сохранённое подключение не восстановлено. Проверьте доступ к хранилищу ОС или подключитесь заново.')

def now():return datetime.now(TZ).isoformat()

def poll(key):
    with lock:
        config=credentials.get(key)
        if not config:return
        version=versions[key];first,last=view_start,view_end
        p=providers[key];p['busy']=True;started=time.monotonic()
    halted=False;error=None
    try:
        if key=='ews':
            events=fetch_ews(config['login'],config['password'],first,last,endpoint=config['endpoint'])
            calendars=[{'id':'ews:main','name':'Работа'}]
            for e in events:e.update(id='ews|'+e['id'],calendarId='ews:main',calendarName='Работа',provider='ews')
        elif key=='yandex':events,calendars=fetch_yandex(config['login'],config['password'],first,last)
        else:
            events=parse_events(fetch_calendar(config['url']),first,last);calendars=[{'id':'ics:main','name':'ICS'}]
            for e in events:e.update(id='ics|'+e['id'],calendarId='ics:main',calendarName='ICS',provider='ics')
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
            old={e['id']:e for e in p['events']};new={e['id']:e for e in events}
            changed=sum(old[k]!=new[k] for k in old.keys()&new.keys())
            message='Расписание загружено' if p['digest'] is None else f'Добавлено {len(new.keys()-old.keys())}, изменено {changed}, удалено {len(old.keys()-new.keys())}' if p['digest']!=digest else 'Без изменений'
            p.update(events=events,calendars=calendars,success=stamp,digest=digest,rangeStart=str(first),rangeEnd=str(last))
        p['history']=([dict(time=stamp,message=message)]+p['history'])[:40]

def worker(key):
    while True:
        with lock:ready=key in credentials and providers[key]['due']<=time.monotonic()
        if ready:poll(key)
        time.sleep(.5)

def snapshot():
    with lock:
        return dict(timezone=str(TZ),start=str(view_start),end=str(view_end),providers=[{k:v for k,v in p.items() if k not in ('due','digest')} for p in providers.values()])

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def send(self,value,status=200,content='application/json'):
        data=value if isinstance(value,bytes) else json.dumps(value,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Type',content+'; charset=utf-8');self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('X-Frame-Options','DENY');self.end_headers();self.wfile.write(data)
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
        global view_start,view_end
        if not self.valid_host() or self.headers.get('X-App-Token')!=TOKEN:return self.send({},403)
        try:
            n=int(self.headers.get('Content-Length',0))
            if not 0<=n<=16384:return self.send({},413)
            data=json.loads(self.rfile.read(n) or '{}')
            if not isinstance(data,dict):raise ValueError()
        except Exception:return self.send({'error':'Некорректный запрос'},400)
        if self.path=='/api/range':
            try:
                first=date.fromisoformat(data['start']);last=date.fromisoformat(data['end'])
                if not 0<(last-first).days<=62:raise ValueError()
            except (KeyError,ValueError,TypeError):return self.send({'error':'Выберите период до 62 дней.'},400)
            with lock:
                if (first,last)!=(view_start,view_end):
                    view_start,view_end=first,last
                    for key,p in providers.items():versions[key]+=1;p.update(events=[],digest=None,due=0,rangeStart=None,rangeEnd=None)
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
                try:vault.save(key,config)
                except vault.VaultError as e:return self.send({'error':str(e)},503)
                versions[key]+=1;credentials[key]=config
                providers[key].update(connected=True,error=None,events=[],calendars=[],history=[],checks=0,success=None,checked=None,digest=None,due=0,next=None,rangeStart=None,rangeEnd=None)
        elif self.path in ('/api/refresh','/api/stop'):
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
    restore_connections()
    for key in providers:threading.Thread(target=worker,args=(key,),daemon=True).start()
    print(f'Calendar: http://127.0.0.1:{PORT}',flush=True)
    ThreadingHTTPServer(('127.0.0.1',PORT),Handler).serve_forever()
