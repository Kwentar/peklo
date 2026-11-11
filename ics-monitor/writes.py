"""Explicit conditional writes. Mirrors never add attendees or send invitations."""
import re,hashlib
from datetime import datetime,timedelta,timezone
from xml.sax.saxutils import escape,quoteattr
import xml.etree.ElementTree as ET
import requests
from icalendar import Calendar,Event,Alarm
import ews,yandex
from ics import parse_events,fetch_calendar,TZ
TOKEN_FIELD='<t:ExtendedFieldURI DistinguishedPropertySetId="PublicStrings" PropertyName="PekloToken" PropertyType="String"/>'
class Missing(Exception):pass

def validate(e):
    if not isinstance(e.get('title'),str) or not e['title'].strip() or len(e['title'])>1000:raise ValueError('Укажите название (до 1000 символов).')
    for k in ('description','location'):
        if not isinstance(e.get(k,''),str) or len(e.get(k,''))>10000:raise ValueError('Слишком длинное описание или место.')
    try:
        start=datetime.fromisoformat(e['start']);end=datetime.fromisoformat(e['end'])
        if not start.tzinfo or not end.tzinfo or end<=start:raise ValueError()
        n=e.get('reminderMinutes',15)
        if type(n)!=int or not -1<=n<=525600:raise ValueError()
        if type(e.get('allDay',False))!=bool:raise ValueError()
        if e.get('allDay') and (start.astimezone(TZ).time().isoformat()!='00:00:00' or end.astimezone(TZ).time().isoformat()!='00:00:00'):raise ValueError()
    except (KeyError,TypeError,ValueError):raise ValueError('Проверьте время и напоминание (−1 либо 0–525600 минут).') from None
    return {k:e.get(k,default) for k,default in [('title',''),('start',''),('end',''),('allDay',False),('description',''),('location',''),('reminderMinutes',15)]}

def bounded(response):
    parts=[];size=0
    for part in response.iter_content(65536):
        size+=len(part)
        if size>10*1024*1024:raise ValueError('Ответ превышает 10 МБ.')
        parts.append(part)
    return b''.join(parts)

def soap(config,body,action):
    raw=f'<s:Envelope xmlns:s="{ews.NS["s"]}" xmlns:m="{ews.NS["m"]}" xmlns:t="{ews.NS["t"]}"><s:Header><t:RequestServerVersion Version="Exchange2010_SP2"/></s:Header><s:Body>{body}</s:Body></s:Envelope>'
    with requests.post(ews.normalize_endpoint(config['endpoint']),data=raw.encode(),auth=(config['login'],config['password']),headers={'Content-Type':'text/xml; charset=utf-8','SOAPAction':f'"{ews.NS["m"]}/{action}"'},allow_redirects=False,timeout=(10,25),stream=True) as r:
        if r.status_code in (401,403) or 300<=r.status_code<400:raise ews.EWSError('Exchange отклонил доступ или перенаправил запрос.')
        r.raise_for_status();data=bounded(r)
    if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():raise ValueError('Некорректный XML.')
    root=ET.fromstring(data);codes=root.findall('.//m:ResponseCode',ews.NS)
    if not codes:raise ValueError('Сервер не подтвердил операцию.')
    for code in codes:
        if code.text=='ErrorItemNotFound':raise Missing()
        if code.text!='NoError':raise ews.EWSError('Exchange: '+(code.text if re.fullmatch('[A-Za-z0-9]{1,100}',code.text or '') else 'ошибка записи'))
    return root

def tag(name,value):return f'<t:{name}>{escape(str(value))}</t:{name}>'
def itemid(e):return '<t:ItemId Id='+quoteattr(e.get('remoteId',e['id'].removeprefix('ews|')))+((' ChangeKey='+quoteattr(e['etag'])) if e.get('etag') else '')+'/>'
def ews_detail(config,e):
    root=soap(config,'<m:GetItem><m:ItemShape><t:BaseShape>AllProperties</t:BaseShape><t:BodyType>Text</t:BodyType><t:AdditionalProperties>'+TOKEN_FIELD+'</t:AdditionalProperties></m:ItemShape><m:ItemIds>'+itemid(e)+'</m:ItemIds></m:GetItem>','GetItem')
    item=root.find('.//t:CalendarItem',ews.NS)
    if item is None:raise ValueError('Событие недоступно.')
    result=ews.parse_item(item);result.update(provider='ews',calendarId='ews:main',calendarName='Работа',id='ews|'+result['id'])
    return result

def ews_recover(config,token):
    root=soap(config,'<m:FindItem Traversal="Shallow"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape><m:Restriction><t:IsEqualTo>'+TOKEN_FIELD+'<t:FieldURIOrConstant><t:Constant Value='+quoteattr(token)+'/></t:FieldURIOrConstant></t:IsEqualTo></m:Restriction><m:ParentFolderIds><t:DistinguishedFolderId Id="calendar"/></m:ParentFolderIds></m:FindItem>','FindItem')
    ids=root.findall('.//t:ItemId',ews.NS)
    if len(ids)>1:raise ValueError('Найдены дубли, автоматическая запись остановлена.')
    return ews_detail(config,dict(id=ids[0].get('Id'))) if ids else None

def ews_save(config,desired,before,token):
    e=validate(desired)
    if before is None:
        recovered=ews_recover(config,token)
        if recovered:return recovered
    fields=[('item:Subject',tag('Subject',e['title'])),('item:Body','<t:Body BodyType="Text">'+escape(e['description'])+'</t:Body>'),('item:ReminderIsSet',tag('ReminderIsSet',str(e['reminderMinutes']>=0).lower())),('item:ReminderMinutesBeforeStart',tag('ReminderMinutesBeforeStart',max(0,e['reminderMinutes']))),('calendar:Start',tag('Start',e['start'])),('calendar:End',tag('End',e['end'])),('calendar:IsAllDayEvent',tag('IsAllDayEvent',str(e['allDay']).lower())),('calendar:Location',tag('Location',e['location']))]
    if before:
        if not before.get('etag'):raise ValueError('Обновите событие перед сохранением.')
        updates=''.join('<t:SetItemField><t:FieldURI FieldURI='+quoteattr(uri)+'/>'+ '<t:CalendarItem>'+value+'</t:CalendarItem></t:SetItemField>' for uri,value in fields)
        body='<m:UpdateItem ConflictResolution="NeverOverwrite" SendMeetingInvitationsOrCancellations="SendToNone"><m:ItemChanges><t:ItemChange>'+itemid(before)+'<t:Updates>'+updates+'</t:Updates></t:ItemChange></m:ItemChanges></m:UpdateItem>';action='UpdateItem'
    else:
        values=''.join(v for _,v in fields[:4])+'<t:ExtendedProperty>'+TOKEN_FIELD+tag('Value',token)+'</t:ExtendedProperty>'+''.join(v for _,v in fields[4:])
        body='<m:CreateItem SendMeetingInvitations="SendToNone"><m:SavedItemFolderId><t:DistinguishedFolderId Id="calendar"/></m:SavedItemFolderId><m:Items><t:CalendarItem>'+values+'</t:CalendarItem></m:Items></m:CreateItem>';action='CreateItem'
    root=soap(config,body,action);item=root.find('.//t:ItemId',ews.NS)
    if item is None:raise ValueError('Сервер не подтвердил запись. Повторите с той же формой.')
    return ews_detail(config,dict(id=item.get('Id')))

def dav_request(config,method,url,body=None,etag=None,create=False):
    headers={'Content-Type':'text/calendar; charset=utf-8'}
    if etag:headers['If-Match']=etag
    if create:headers['If-None-Match']='*'
    with requests.request(method,yandex.safe_url(url),auth=(config['login'],config['password']),data=body,headers=headers,timeout=(10,25),allow_redirects=False,stream=True) as r:
        if r.status_code==404:raise Missing()
        if r.status_code==412:raise ValueError('Событие изменилось на сервере. Откройте его заново.')
        if 300<=r.status_code<400:raise ValueError('Переадресация CalDAV запрещена.')
        r.raise_for_status();return bounded(r),r.headers.get('ETag','')

def dav_detail(config,e):
    raw,etag=dav_request(config,'GET',e['remoteUri']);cal=Calendar.from_ical(raw)
    originals=cal.walk('VEVENT')
    if len(originals)!=1 or any(k in originals[0] for k in ('RRULE','RDATE','RECURRENCE-ID')):raise ValueError('Серии редактируются в исходном календаре.')
    begin=originals[0].decoded('DTSTART');day=begin.astimezone(TZ).date() if isinstance(begin,datetime) else begin
    events=parse_events(raw,day,day+timedelta(days=2),include_cancelled=True)
    if len(events)!=1:raise ValueError('Не удалось прочитать одиночную встречу.')
    result={**e,**events[0], 'etag':etag,'remoteUri':e['remoteUri'],'provider':'yandex'};result['id']=e['calendarId']+'|'+events[0]['id'];result['stableId']=e['calendarId']+'|'+events[0]['stableId']
    return result,cal

def dav_recover(config,calendar,token):
    url=calendar['url'].rstrip('/')+'/peklo-'+token+'.ics'
    try:
        e,_=dav_detail(config,dict(remoteUri=url,calendarId=calendar['id'],calendarName=calendar['name']))
    except Missing:return None
    if e.get('writeToken')!=token:raise ValueError('Ресурс не принадлежит этой операции.')
    return e

def dav_save(config,calendar,desired,before,token):
    e=validate(desired)
    if before:
        current,cal=dav_detail(config,before)
        if not before.get('etag') or before['etag']!=current['etag']:raise ValueError('Событие изменилось на сервере. Откройте заново.')
        event=cal.walk('VEVENT')[0];url=before['remoteUri'];etag=current['etag']
    else:
        recovered=dav_recover(config,calendar,token)
        if recovered:return recovered
        cal=Calendar();cal.add('VERSION','2.0');cal.add('PRODID','-//Peklo//Calendar//RU');event=Event();event.add('UID','peklo-'+token);event.add('X-PEKLO-TOKEN',token);cal.add_component(event);url=calendar['url'].rstrip('/')+'/peklo-'+token+'.ics';etag=None
    for k in ('SUMMARY','DESCRIPTION','LOCATION','DTSTART','DTEND','DURATION','DTSTAMP'):
        event.pop(k,None)
    for name,key in [('SUMMARY','title'),('DESCRIPTION','description'),('LOCATION','location')]:event.add(name,e[key])
    for name,key in [('DTSTART','start'),('DTEND','end')]:
        dt=datetime.fromisoformat(e[key]).astimezone(TZ);event.add(name,dt.date() if e['allDay'] else dt)
    event.add('DTSTAMP',datetime.now(timezone.utc));event.subcomponents=[c for c in event.subcomponents if c.name!='VALARM']
    if e['reminderMinutes']>=0:
        alarm=Alarm();alarm.add('ACTION','DISPLAY');alarm.add('DESCRIPTION',e['title']);alarm.add('TRIGGER',-timedelta(minutes=e['reminderMinutes']));event.add_component(alarm)
    dav_request(config,'PUT',url,cal.to_ical(),etag,create=before is None)
    return dav_detail(config,dict(remoteUri=url,calendarId=calendar['id'],calendarName=calendar['name']))[0]

def detail(config,e):
    if e['provider']=='ews':return ews_detail(config,e)
    if e['provider']=='yandex':return dav_detail(config,e)[0]
    return e

def recover(config,provider,calendar,token):
    return ews_recover(config,token) if provider=='ews' else dav_recover(config,calendar,token)
def save(config,provider,calendar,desired,before,token):
    if before and (before.get('recurring') or before.get('copyToken')):raise ValueError('Редактируйте оригинал одиночной встречи.')
    if not re.fullmatch(r'(?:edit|copy)-[a-zA-Z0-9-]{16,80}',token):raise ValueError('Некорректный идентификатор операции.')
    if provider=='ews':return ews_save(config,desired,before,token)
    if provider=='yandex':return dav_save(config,calendar,desired,before,token)
    raise ValueError('Источник доступен только для чтения.')
def delete(config,e):
    if e['provider']=='ews':
        if not e.get('etag'):raise ValueError('Обновите встречу перед удалением.')
        try:soap(config,'<m:DeleteItem DeleteType="MoveToDeletedItems" SendMeetingCancellations="SendToNone" AffectedTaskOccurrences="SpecifiedOccurrenceOnly"><m:ItemIds>'+itemid(e)+'</m:ItemIds></m:DeleteItem>','DeleteItem')
        except Missing:pass
    elif e['provider']=='yandex':
        if not e.get('etag'):raise ValueError('Обновите встречу перед удалением.')
        try:dav_request(config,'DELETE',e['remoteUri'],etag=e['etag'])
        except Missing:pass
    else:raise ValueError('Источник доступен только для чтения.')

def confirmed_deleted(config,e):
    if e.get('recurring'):return False
    try:
        if e['provider']=='ews':ews_detail(config,e)
        elif e['provider']=='yandex':dav_request(config,'GET',e['remoteUri'])
        elif e['provider']=='ics':
            cal=Calendar.from_ical(fetch_calendar(config['url']))
            if cal.name!='VCALENDAR':raise ValueError('Некорректный ICS.')
            matches=[v for v in cal.walk('VEVENT') if str(v.get('UID',''))==e.get('uid')]
            return not matches or all(str(v.get('STATUS','')).upper()=='CANCELLED' for v in matches)
        else:return False
    except Missing:return True
    return False
