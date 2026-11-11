"""Shared display and notification rules; never mutate source events."""
import re
CANCEL=re.compile(r'^\s*(?:Canceled|Cancelled|Отменено)\s*:',re.I)
def cancelled(e):
    return bool(e.get('cancelled')) or (e.get('provider')=='ews' and bool(CANCEL.match(e.get('title',''))))
def visible(e,settings,include_cancelled=False):
    style=settings.get('calendars',{}).get(e['calendarId'],{})
    if style.get('hidden') or e.get('copyToken','').startswith('copy-'):return False
    if e.get('provider')=='ews' and e.get('title')=='Без встреч':return False
    return not cancelled(e) or (include_cancelled and e.get('provider')=='ews')
def identity(e):return e.get('stableId',e['id'])
