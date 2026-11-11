"""Private settings and write journal in OS credential storage, outside Git."""
import copy,hashlib,json,threading
import vault,writes
from event_rules import cancelled,identity
operation_lock=threading.RLock()
state={'calendars':{},'notifications':{'reminders':False,'new':False,'moves':False,'cancellations':False,'summary':False},'links':{},'operations':{}}
error=''

def restore():
    global state,error
    try:
        saved=vault.load('web-state')
        if saved:state.update(saved)
        error=''
    except vault.VaultError as e:error=str(e)
def persist(value):
    global state,error
    if error:raise vault.VaultError(error)
    vault.save('web-state',value);state=value

def settings():return {k:copy.deepcopy(state[k]) for k in ('calendars','notifications')}
def configure(data,known):
    value=copy.deepcopy(state)
    if 'calendarId' in data:
        cid=data['calendarId']
        if cid not in known:raise ValueError('Календарь недоступен.')
        style=data.get('settings',{})
        if not isinstance(style,dict):raise ValueError('Некорректные настройки.')
        name=style.get('name','');color=style.get('color',0);role=style.get('role','primary')
        if not isinstance(name,str) or len(name)>120 or type(color)!=int or not 0<=color<7 or role not in ('primary','satellite'):raise ValueError('Проверьте имя, цвет и роль.')
        if any(k in style and type(style[k])!=bool for k in ('anonymous','hidden')):raise ValueError('Некорректный переключатель.')
        value['calendars'][cid]=dict(name=name,color=color,role=role,anonymous=bool(style.get('anonymous',True)),hidden=bool(style.get('hidden',False)))
    if 'notifications' in data:
        if not isinstance(data['notifications'],dict) or any(type(v)!=bool for v in data['notifications'].values()):raise ValueError('Некорректные настройки уведомлений.')
        value['notifications']={k:data['notifications'].get(k,False) for k in value['notifications']}
    persist(value)

def journal(token,entry):
    value=copy.deepcopy(state);value['operations'][token]=entry
    # Retain operation identity for retries across process restarts.
    persist(value)

def link(token,entry):
    value=copy.deepcopy(state)
    if entry is None:value['links'].pop(token,None)
    else:value['links'][token]=entry
    persist(value)

def fingerprint(e):return hashlib.sha256(json.dumps(e,sort_keys=True).encode()).hexdigest()
def roles(cid):return state['calendars'].get(cid,{})
def is_copy(e):
    return e.get('copyToken','').startswith('copy-') or any(v.get('target',{}).get('id')==e['id'] for v in state['links'].values())

def sync(providers,credentials):
    """Idempotent routes; absence from a window is never proof of deletion."""
    if error:return error
    healthy={k for k,p in providers.items() if k in credentials and p['success'] and not p['error']}
    destinations={cal['id']:(key,cal) for key,p in providers.items() if key in healthy and key!='ics' for cal in p['calendars'] if roles(cal['id']).get('role','primary')=='primary'}
    originals={identity(e):e for p in providers.values() for e in p['events'] if not is_copy(e)}
    wanted=set();changes=0
    try:
        for source_id,source in originals.items():
            origin=source['provider'];style=roles(source['calendarId'])
            if origin not in healthy or style.get('role','primary')!='satellite' or cancelled(source):continue
            for cid,(key,cal) in destinations.items():
                if cid==source['calendarId']:continue
                token='copy-'+hashlib.sha256((source_id+'|'+cid).encode()).hexdigest();wanted.add(token)
                old=state['links'].get(token)
                source_signature=fingerprint({'source':source,'anonymous':style.get('anonymous',True)})
                if old and old.get('signature')==source_signature and old.get('target'):continue
                if changes>=30:continue
                original=source if style.get('anonymous',True) else writes.detail(credentials[origin],source) if origin=='ews' else source
                desired={k:original.get(k,default) for k,default in [('title',''),('description',''),('location',''),('start',''),('end',''),('allDay',False)]}
                desired['reminderMinutes']=-1
                if style.get('anonymous',True):desired.update(title='Занят',description='',location='')
                entry=copy.deepcopy(old) if old else dict(original=source,targetProvider=key,targetCalendar=cal,sourceIdentity=source_id)
                link(token,entry) # Durable BEFORE any remote write.
                before=entry.get('target')
                if before is None:before=writes.recover(credentials[key],key,cal,token)
                if before:
                    # Copies are deliberately editable only inside this engine.
                    before={**before,'copyToken':'','recurring':False}
                saved=writes.save(credentials[key],key,cal,desired,before,token)
                entry.update(original=source,target=saved,signature=source_signature);link(token,entry);changes+=1
        for token,entry in list(state['links'].items()):
            if token in wanted:continue
            source=entry['original'];origin=source['provider'];key=entry['targetProvider']
            if origin not in healthy or key not in healthy or changes>=30:continue
            current=originals.get(entry['sourceIdentity'])
            route_removed=roles(source['calendarId']).get('role','primary')!='satellite' or roles(entry['targetCalendar']['id']).get('role','primary')!='primary'
            if not route_removed and not (current and cancelled(current)):
                if current or not writes.confirmed_deleted(credentials[origin],source):continue
            target=entry.get('target') or writes.recover(credentials[key],key,entry['targetCalendar'],token)
            if target:writes.delete(credentials[key],target)
            link(token,None);changes+=1
        return 'Часть копий ожидает следующего прохода.' if changes>=30 else ''
    except (ValueError,vault.VaultError,writes.ews.EWSError) as e:return str(e)
    except Exception:return 'Не удалось синхронизировать копии. Связь сохранена, повтор — при следующем обновлении.'


def bind_connection(key,config):
    signature=fingerprint({k:config.get(k) for k in ('login','endpoint','url')})
    bindings=state.get('bindings',{})
    if bindings.get(key)==signature:return
    if key in bindings:
        if any(v['original']['provider']==key or v['targetProvider']==key for v in state['links'].values()):
            raise ValueError('Сначала верните прежнее подключение и отключите его маршруты сателлитов, чтобы завершить работу с копиями.')
    value=copy.deepcopy(state)
    if key in bindings:value['calendars']={cid:s for cid,s in value['calendars'].items() if not cid.startswith(key+':')}
    value.setdefault('bindings',{})[key]=signature
    persist(value)
