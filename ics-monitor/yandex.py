"""Read-only CalDAV discovery and calendar-query for Yandex."""
import hashlib
import xml.etree.ElementTree as ET
from urllib.parse import urljoin,urlparse
from datetime import datetime,time,timezone
from zoneinfo import ZoneInfo
import requests
from ics import parse_events
from ews import EWSError
BASE='https://caldav.yandex.ru/'
NS={'d':'DAV:','c':'urn:ietf:params:xml:ns:caldav'}
TZ=ZoneInfo('Europe/Moscow')

def safe_url(href,base=BASE):
    url=urljoin(base,href)
    p=urlparse(url)
    if p.scheme!='https' or p.hostname!='caldav.yandex.ru' or p.port not in (None,443) or p.username or p.password:
        raise EWSError('CalDAV вернул адрес вне caldav.yandex.ru; переход отклонён.',True)
    return url

def xml_request(session,method,url,body,depth='0'):
    with session.request(method,safe_url(url),data=body.encode(),headers={'Content-Type':'application/xml; charset=utf-8','Depth':depth},timeout=(10,25),allow_redirects=False,stream=True) as r:
        if r.status_code in (401,403):raise EWSError(f'Яндекс: HTTP {r.status_code}. Проверьте логин и пароль приложения «Календарь / CalDAV». Автоопрос остановлен.',True)
        if 300<=r.status_code<400:raise EWSError('Яндекс перенаправил запрос. Учётные данные не пересланы; проверьте подключение.',True)
        r.raise_for_status();parts=[];size=0
        for chunk in r.iter_content(65536):
            size+=len(chunk)
            if size>10*1024*1024:raise EWSError('Ответ CalDAV превышает 10 МБ.')
            parts.append(chunk)
    raw=b''.join(parts)
    if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():raise EWSError('Некорректный XML CalDAV.')
    try:return ET.fromstring(raw)
    except ET.ParseError:raise EWSError('Яндекс вернул ответ, который не удалось прочитать как CalDAV.')

def props(root):
    for response in root.findall('d:response',NS):
        href=response.findtext('d:href',namespaces=NS)
        found=[]
        for ps in response.findall('d:propstat',NS):
            if ' 200 ' in ps.findtext('d:status',default='',namespaces=NS):
                prop=ps.find('d:prop',NS)
                if prop is not None:found.append(prop)
        yield href,found

def propfind(session,url,fields,depth='0'):
    return xml_request(session,'PROPFIND',url,f'<d:propfind xmlns:d="DAV:" xmlns:c="{NS["c"]}"><d:prop>{fields}</d:prop></d:propfind>',depth)

def discover(session):
    root=propfind(session,BASE,'<d:current-user-principal/>')
    principal=next((p.findtext('d:current-user-principal/d:href',namespaces=NS) for _,ps in props(root) for p in ps if p.find('d:current-user-principal/d:href',NS) is not None),None)
    if not principal:raise EWSError('Яндекс не вернул адрес пользователя CalDAV.')
    root=propfind(session,safe_url(principal),'<c:calendar-home-set/>')
    home=next((p.findtext('c:calendar-home-set/d:href',namespaces=NS) for _,ps in props(root) for p in ps if p.find('c:calendar-home-set/d:href',NS) is not None),None)
    if not home:raise EWSError('Яндекс не вернул список календарей.')
    home=safe_url(home)
    root=propfind(session,home,'<d:displayname/><d:resourcetype/><c:supported-calendar-component-set/>','1')
    calendars=[]
    for href,ps in props(root):
        if not href:continue
        for p in ps:
            if p.find('d:resourcetype/c:calendar',NS) is None:continue
            components=p.findall('c:supported-calendar-component-set/c:comp',NS)
            if components and not any(c.get('name')=='VEVENT' for c in components):continue
            url=safe_url(href,home)
            calendars.append({'id':'yandex:'+hashlib.sha256(url.encode()).hexdigest()[:16],'name':p.findtext('d:displayname',default='Яндекс',namespaces=NS) or 'Яндекс','url':url})
    return calendars

def fetch_events(login,password,start,end):
    with requests.Session() as session:
        session.auth=(login,password)
        session.headers['User-Agent']='LocalCalendarAggregator/1.0'
        calendars=discover(session);events=[]
        first=datetime.combine(start,time.min,TZ).astimezone(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        last=datetime.combine(end,time.min,TZ).astimezone(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        query=f'<c:calendar-query xmlns:d="DAV:" xmlns:c="{NS["c"]}"><d:prop><c:calendar-data/></d:prop><c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range start="{first}" end="{last}"/></c:comp-filter></c:comp-filter></c:filter></c:calendar-query>'
        for cal in calendars:
            root=xml_request(session,'REPORT',cal['url'],query,'1')
            for href,ps in props(root):
                for p in ps:
                    raw=p.findtext('c:calendar-data',namespaces=NS)
                    if raw:
                        for event in parse_events(raw.encode(),start,end):
                            event.update(id=cal['id']+'|'+event['id'],calendarId=cal['id'],calendarName=cal['name'],provider='yandex')
                            events.append(event)
        return events,[{k:v for k,v in c.items() if k!='url'} for c in calendars]
