"""Read-only EWS CalendarView client. No discovery, redirects, or disk credentials."""
import re
from urllib.parse import urlsplit, urlunsplit
import xml.etree.ElementTree as ET
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo
import requests

def normalize_endpoint(value):
    """Accept an explicit HTTPS server or EWS URL; never discover another host."""
    message = 'Укажите HTTPS-адрес Exchange: https://mail.example.org/EWS/Exchange.asmx (порт 443).'
    if not isinstance(value, str): raise EWSError(message, True)
    value = value.strip()
    if not value or re.search(r'[\\\s\x00-\x1f\x7f]', value): raise EWSError(message, True)
    try:
        u = urlsplit(value)
        if (u.scheme != 'https' or not u.hostname or u.username is not None or
            u.password is not None or u.port not in (None, 443) or '?' in value or '#' in value or
            not re.fullmatch(r'[A-Za-z0-9.-]+', u.hostname) or '%' in u.netloc or
            u.path.lower() not in ('', '/', '/ews/exchange.asmx')):
            raise ValueError()
        return urlunsplit(('https', u.hostname.lower(), '/EWS/Exchange.asmx', '', ''))
    except ValueError:
        raise EWSError(message, True) from None
TZ = ZoneInfo('Europe/Moscow')
NS = {'s':'http://schemas.xmlsoap.org/soap/envelope/', 'm':'http://schemas.microsoft.com/exchange/services/2006/messages', 't':'http://schemas.microsoft.com/exchange/services/2006/types'}

class EWSError(Exception):
    def __init__(self, message, halt=False):
        super().__init__(message)
        self.halt = halt

def request_body(day=None, end_day=None):
    start=datetime.combine(day or datetime.now(TZ).date(),time.min,TZ)
    end=datetime.combine(end_day,time.min,TZ) if end_day else start+timedelta(days=1)
    fields=''.join(f'<t:FieldURI FieldURI="{field}"/>' for field in ['item:Subject','calendar:Start','calendar:End','calendar:IsAllDayEvent','calendar:IsCancelled','calendar:Location'])
    return f'''<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="{NS['s']}" xmlns:m="{NS['m']}" xmlns:t="{NS['t']}">
<s:Header><t:RequestServerVersion Version="Exchange2010_SP2"/></s:Header>
<s:Body><m:FindItem Traversal="Shallow">
<m:ItemShape><t:BaseShape>IdOnly</t:BaseShape><t:AdditionalProperties>{fields}</t:AdditionalProperties></m:ItemShape>
<m:CalendarView MaxEntriesReturned="1000" StartDate="{start.isoformat()}" EndDate="{end.isoformat()}"/>
<m:ParentFolderIds><t:DistinguishedFolderId Id="calendar"/></m:ParentFolderIds>
</m:FindItem></s:Body></s:Envelope>'''.encode()

def parse_response(raw):
    if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
        raise EWSError('EWS вернул неподдерживаемый XML.')
    try: root=ET.fromstring(raw)
    except ET.ParseError: raise EWSError('Вместо EWS XML получен другой ответ, возможно страница SSO.',True)
    if root.find('.//s:Fault',NS) is not None:
        raise EWSError('EWS вернул SOAP Fault: запрос календаря отклонён.')
    messages=root.findall('.//m:FindItemResponseMessage',NS)
    if not messages: raise EWSError('Нет ответа FindItem. Возможно, сервер требует другой способ авторизации.',True)
    for message in messages:
        code=message.findtext('m:ResponseCode',default='',namespaces=NS)
        if code!='NoError' or message.get('ResponseClass')!='Success':
            safe=code if re.fullmatch(r'[A-Za-z0-9]{1,100}',code) else 'UnknownError'
            raise EWSError('EWS отклонил чтение календаря: '+safe,code in ('ErrorAccessDenied','ErrorInvalidClientAccessToken','ErrorAccountDisabled'))
        folder=message.find('m:RootFolder',NS)
        if folder is None: raise EWSError('EWS не вернул папку календаря.')
        if folder.get('IncludesLastItemInRange','true').lower()!='true':
            raise EWSError('В выбранном периоде больше 1000 записей: сервер вернул неполный результат.')
    events=[]
    for item in root.findall('.//t:CalendarItem',NS):
        def value(key,default=''):return item.findtext('t:'+key,default=default,namespaces=NS)
        if value('IsCancelled').lower()=='true': continue
        try:
            begin=datetime.fromisoformat(value('Start').replace('Z','+00:00'))
            end=datetime.fromisoformat(value('End').replace('Z','+00:00'))
            if begin.tzinfo is None or end.tzinfo is None: raise ValueError()
            item_id=item.find('t:ItemId',NS).attrib['Id']
        except (ValueError,AttributeError,KeyError):raise EWSError('EWS вернул событие без корректного времени или ID.')
        events.append(dict(id=item_id,title=value('Subject','Без названия'),start=begin.astimezone(TZ).isoformat(),end=end.astimezone(TZ).isoformat(),allDay=value('IsAllDayEvent').lower()=='true',location=value('Location')))
    return sorted(events,key=lambda x:(x['start'],x['id']))

def fetch_events(login,password,day=None,end_day=None,*,endpoint):
    endpoint = normalize_endpoint(endpoint)
    # Explicit auth is sent only to the configured HTTPS endpoint. Never follow SSO redirects.
    with requests.post(endpoint,data=request_body(day,end_day),auth=(login,password),headers={'Content-Type':'text/xml; charset=utf-8','SOAPAction':'"http://schemas.microsoft.com/exchange/services/2006/messages/FindItem"','User-Agent':'LocalCalendarMonitor/1.0'},allow_redirects=False,timeout=(10,25),stream=True) as response:
        if response.status_code in (401,403):
            raise EWSError(f'HTTP {response.status_code}: вход или доступ к EWS отклонён. Проверьте логин; сервер также может запрещать этот способ входа. Автоопрос остановлен.',True)
        if 300<=response.status_code<400:
            raise EWSError('EWS перенаправляет на другую страницу. Пароль туда не отправлен; требуется другой способ входа.',True)
        response.raise_for_status()
        chunks=[];size=0
        for chunk in response.iter_content(65536):
            size+=len(chunk)
            if size>10*1024*1024:raise EWSError('Ответ EWS превышает 10 МБ.')
            chunks.append(chunk)
    return parse_response(b''.join(chunks))
