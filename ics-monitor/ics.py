from datetime import datetime, time as dt_time, timedelta
from zoneinfo import ZoneInfo
import requests
from icalendar import Calendar
import recurring_ical_events
TZ=ZoneInfo("Europe/Moscow")

def parse_events(raw, day=None, end_day=None, include_cancelled=False):
    cal = Calendar.from_ical(raw)
    if cal.name != 'VCALENDAR': raise ValueError('not calendar')
    day = day or datetime.now(TZ).date()
    start = datetime.combine(day, dt_time.min, TZ)
    end = datetime.combine(end_day,dt_time.min,TZ) if end_day else start + timedelta(days=1)
    result = []
    recurring_uids={str(e.get('UID','')) for e in cal.walk('VEVENT') if any(k in e for k in ('RRULE','RDATE','RECURRENCE-ID'))}
    for ev in recurring_ical_events.of(cal).between(start, end):
        if str(ev.get('STATUS', '')).upper() == 'CANCELLED' and not include_cancelled: continue
        begin = ev.decoded('DTSTART')
        finish = ev.decoded('DTEND', None)
        all_day = not isinstance(begin, datetime)
        def aware(v):
            if not isinstance(v, datetime): return datetime.combine(v, dt_time.min, TZ)
            return v.replace(tzinfo=TZ) if v.tzinfo is None else v.astimezone(TZ)
        b = aware(begin)
        f = aware(finish) if finish is not None else b + ev.decoded('DURATION', timedelta(days=1) if all_day else timedelta())
        if b >= end or (f <= start and b < start): continue
        reminders=[int(-a.decoded('TRIGGER').total_seconds()/60) for a in ev.subcomponents if a.name=='VALARM' and isinstance(a.decoded('TRIGGER',None),timedelta) and a.decoded('TRIGGER')<=timedelta()]
        uid=str(ev.get('UID',''));token=str(ev.get('X-PEKLO-TOKEN',''))
        result.append(dict(uid=uid,stableId=uid+('|' + str(ev.get('RECURRENCE-ID',begin)) if uid in recurring_uids else ''),recurring=uid in recurring_uids,description=str(ev.get('DESCRIPTION','')),reminderMinutes=min(reminders) if reminders else -1,writeToken=token,copyToken=token if token.startswith('copy-') else '',cancelled=str(ev.get('STATUS','')).upper()=='CANCELLED',id=str(ev.get('UID', ''))+'|'+str(ev.get('RECURRENCE-ID', begin)), title=str(ev.get('SUMMARY', 'Без названия')), start=b.isoformat(), end=f.isoformat(), allDay=all_day, location=str(ev.get('LOCATION', ''))))
    return sorted(result, key=lambda x:x['start'])

def fetch_calendar(url):
    with requests.get(url, timeout=(10,25), headers={'Cache-Control':'no-cache', 'Pragma':'no-cache', 'User-Agent':'LocalICSMonitor/1.0'}, stream=True) as r:
        r.raise_for_status()
        parts=[]; size=0
        for part in r.iter_content(65536):
            size+=len(part)
            if size>10*1024*1024: raise ValueError('too large')
            parts.append(part)
        return b''.join(parts)
