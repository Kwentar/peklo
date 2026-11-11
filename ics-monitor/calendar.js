const $=id=>document.getElementById(id), token=document.querySelector('meta[name="app-token"]').content;
const tz='Europe/Moscow', dayFormat=new Intl.DateTimeFormat('sv-SE',{timeZone:tz,year:'numeric',month:'2-digit',day:'2-digit'});
const today=()=>dayFormat.format(new Date()), dateObj=s=>new Date(s+'T12:00:00Z'), plus=(s,n)=>{let d=dateObj(s);d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10)}, format=(s,opts)=>dateObj(s).toLocaleDateString('ru-RU',{timeZone:'UTC',...opts}), clock=s=>new Date(s).toLocaleTimeString('ru-RU',{timeZone:tz,hour:'2-digit',minute:'2-digit'});
let selected=today(),view='week',state={providers:[]},hidden=new Set(),provider='yandex',lastState='',loading=false,rangeVersion=0;
function node(tag,text='',cls=''){const n=document.createElement(tag);n.textContent=text;if(cls)n.className=cls;return n}
function button(text,fn,cls=''){const n=node('button',text,cls);n.onclick=fn;return n}
async function api(path,data){const r=await fetch('/api/'+path,{method:data===undefined?'GET':'POST',headers:{'X-App-Token':token,'Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)});const j=await r.json();if(!r.ok)throw Error(j.error||'Запрос отклонён. Обновите страницу.');return j}
function range(){if(view==='day')return [selected,plus(selected,1)];let first=view==='month'?selected.slice(0,7)+'-01':selected;first=plus(first,-((dateObj(first).getUTCDay()+6)%7));return [first,plus(first,view==='month'?42:7)]}
function cancelledByTitle(e){return e.provider==='ews' && /^\s*(?:canceled|cancelled|отменено)\s*:/iu.test(e.title||'')}
function events(){const [start,end]=range();return state.providers.flatMap(p=>p.events).filter(e=>new Date(e.start)<new Date(end+'T00:00:00+03:00')&&new Date(e.end)>=new Date(start+'T00:00:00+03:00')&&!e.copyToken&&!calendarStyle(e.calendarId).hidden&&!(e.provider==='ews'&&e.title==='Без встреч')&&($('show-cancelled').checked&&e.provider==='ews'||!e.cancelled&&!cancelledByTitle(e))).sort((a,b)=>a.start.localeCompare(b.start)||a.title.localeCompare(b.title))}
function onDay(e,day){const start=new Date(day+'T00:00:00+03:00').getTime(),end=start+86400000,b=new Date(e.start).getTime(),f=new Date(e.end).getTime();return b<end&&(f>start||(b===f&&b>=start))}
function openEvent(e){selectedEvent=e;$('event-source').textContent=calendarName(e.calendarId,e.calendarName)+' · '+({ews:'Exchange',yandex:'Яндекс',ics:'ICS'}[e.provider]);$('event-title').textContent=e.title;$('event-time').textContent=e.allDay?format(dayFormat.format(new Date(e.start)),{day:'numeric',month:'long'})+' · весь день':new Date(e.start).toLocaleString('ru-RU',{timeZone:tz,day:'numeric',month:'long',hour:'2-digit',minute:'2-digit'})+' — '+new Date(e.end).toLocaleString('ru-RU',{timeZone:tz,day:'numeric',month:'long',hour:'2-digit',minute:'2-digit'});$('event-location').textContent=e.location||'Место не указано';$('event-link').hidden=e.provider!=='yandex';$('event-link').href='https://calendar.yandex.ru/';$('event-reminder').textContent=e.reminderMinutes<0?'Без напоминания':'Напоминание за '+e.reminderMinutes+' мин';$('event-description').textContent=e.description||'';const editable=e.provider!=='ics'&&!e.recurring&&!e.copyToken;$('edit-event').hidden=$('delete-event').hidden=!editable;$('event-edit-note').textContent=editable?'':'Серии и ICS доступны для просмотра; изменяйте оригинал у провайдера.';$('event-dialog').showModal()}
function eventCard(e,compact=false){const n=button('',()=>openEvent(e),'event '+e.provider);n.title=e.title;applyPalette(n,e.calendarId,e.provider);n.append(node('small',e.allDay?'Весь день':clock(e.start)+'–'+clock(e.end)),node('b',e.title));if(!compact&&e.location)n.append(node('span',e.location));return n}
// Split overnight events at local midnight and assign lanes to overlapping intervals.
function layoutDay(items,day){
 const midnight=new Date(day+'T00:00:00+03:00').getTime();
 const blocks=items.filter(e=>!e.allDay&&onDay(e,day)).map(e=>({event:e,start:Math.max(0,(new Date(e.start)-midnight)/60000),end:Math.min(1440,(new Date(e.end)-midnight)/60000)})).sort((a,b)=>a.start-b.start||b.end-a.end);
 let group=[],groupEnd=-1,lanes=[];
 function finish(){for(const block of group)block.columns=lanes.length;group=[];lanes=[]}
 for(const block of blocks){
  block.end=Math.min(1440,Math.max(block.start+1,block.end));
  if(block.start>=groupEnd){finish();groupEnd=-1}
  let lane=lanes.findIndex(end=>end<=block.start);
  if(lane<0)lane=lanes.length;
  lanes[lane]=block.end;block.lane=lane;group.push(block);groupEnd=Math.max(groupEnd,block.end);
 }
 finish();return blocks;
}
let scrollPeriod='',savedScroll=640;
function renderTimeGrid(root,all,start,end){
 const days=[];for(let day=start;day<end;day=plus(day,1))days.push(day);
 const scroller=node('div','','time-scroll');scroller.tabIndex=0;scroller.setAttribute('aria-label','Почасовой календарь, прокрутка от 00:00 до 24:00');
 const grid=node('div','','time-grid');grid.style.setProperty('--days',days.length);
 const heading=node('div','','time-heading');heading.append(node('div','МСК','axis-corner'));
 for(const day of days){const head=node('div','','day-heading'+(day===today()?' is-today':''));head.append(node('small',format(day,{weekday:'short'})),node('strong',String(Number(day.slice(-2)))));heading.append(head)}
 grid.append(heading);
 const allday=node('div','','all-day-row');allday.append(node('div','Весь день','all-day-label'));
 for(const day of days){const cell=node('div','','all-day-cell');all.filter(e=>e.allDay&&onDay(e,day)).forEach(e=>cell.append(eventCard(e,true)));allday.append(cell)}
 grid.append(allday);
 const blocks=layoutDay(all,start);const gaps=[];if(days.length===1){let end=-1;for(const b of blocks){if(end>=0&&b.start>end&&b.start-end<=15)gaps.push([end,b.start]);end=Math.max(end,b.end);}}const y=m=>m*4/3-gaps.reduce((v,[a,b])=>v+Math.max(0,Math.min(m,b)-a)*(4/3-1/(b-a)),0);
 const body=node('div','','time-body'),axis=node('div','','time-axis');
 for(let h=0;h<24;h++){const label=node('span',String(h).padStart(2,'0')+':00');label.style.top=y(h*60)+'px';axis.append(label)}body.append(axis);
 body.style.height=y(1440)+'px';
 for(const day of days){const col=node('div','','time-column'+(day===today()?' is-today':''));col.style.height=y(1440)+'px';for(let h=0;h<48;h++){const line=node('div','','grid-line');line.style.top=y(h*30)+'px';col.append(line)}
  for(const block of layoutDay(all,day)){
   const card=eventCard(block.event,true);card.classList.add('timed-event');
   card.style.top=y(block.start)+'px';card.style.height=Math.max(2,y(block.end)-y(block.start)-1)+'px';
   card.style.left=`calc(${block.lane*100/block.columns}% + 2px)`;card.style.width=`calc(${100/block.columns}% - 4px)`;
   if(block.end-block.start<30){card.classList.add('short-event');card.querySelector('b').textContent=clock(block.event.start)+'–'+clock(block.event.end)+'  '+block.event.title;}
   card.title=clock(block.event.start)+'–'+clock(block.event.end)+' · '+block.event.title;
   card.setAttribute('aria-label',card.title);col.append(card);
  }
  if(day===today()){const marker=node('div','','now-line');marker.style.top=y((Date.now()-new Date(day+'T00:00:00+03:00'))/60000)+'px';marker.dataset.day=day;marker.update=()=>{marker.style.top=y((Date.now()-new Date(day+'T00:00:00+03:00'))/60000)+'px'};col.append(marker)}
  body.append(col);
 }
 grid.append(body);scroller.append(grid);root.append(scroller);
 const period=view+start;scroller.scrollTop=period===scrollPeriod?savedScroll:640;scrollPeriod=period;savedScroll=scroller.scrollTop;
 scroller.onscroll=()=>{savedScroll=scroller.scrollTop};
}
function renderCalendar(){const all=events(),[start,end]=range(),root=$('calendar');
 const old=root.querySelector('.time-scroll');if(old)savedScroll=old.scrollTop;
 root.replaceChildren();root.className=view;$('date').value=selected;
 $('title').textContent=view==='day'?format(selected,{day:'numeric',month:'long',year:'numeric'}):view==='month'?format(selected,{month:'long',year:'numeric'}):format(start,{day:'numeric',month:'short'})+' — '+format(plus(end,-1),{day:'numeric',month:'short',year:'numeric'});
 document.querySelectorAll('[data-view]').forEach(b=>{b.classList.toggle('active',b.dataset.view===view);b.setAttribute('aria-pressed',b.dataset.view===view)});
 if(view!=='month')renderTimeGrid(root,all,start,end);
 else{
  for(const d of ['ПН','ВТ','СР','ЧТ','ПТ','СБ','ВС'])root.append(node('div',d,'weekday'));
  for(let day=start;day<end;day=plus(day,1)){
   const target=day,es=all.filter(e=>onDay(e,day)),col=node('section','','month-cell');
   if(day===today())col.classList.add('today');if(day.slice(0,7)!==selected.slice(0,7))col.classList.add('outside');
   col.append(button(String(Number(day.slice(-2))),()=>{selected=target;view='day';navigate()},'date-button'));
   es.slice(0,3).forEach(e=>col.append(eventCard(e,true)));
   if(es.length>3)col.append(button('Ещё '+(es.length-3),()=>{selected=target;view='day';navigate()},'more'));
   root.append(col);
  }
 }
 const any=state.providers.some(p=>p.connected||p.events.length);$('empty').textContent=!any?'Подключи Exchange или Яндекс: встречи появятся здесь, каждая со своим цветом.':loading?'Загружаем выбранный период…':'';$('summary').textContent=all.length+' событий в выбранном периоде';
}
function renderSources(){$('sources').replaceChildren();for(const p of state.providers){if(p.id==='ics'&&!p.connected&&!p.error)continue;const item=node('div','','provider'),head=node('div','','provider-head');head.append(node('span',p.name));item.append(head);const status=p.busy?'Обновляется…':p.error?p.error:p.connected?p.success?'Обновлён в '+clock(p.success):'Подключаемся…':'Не подключён';const statusNode=node('div',status,'provider-status'+(p.error?' bad':''));statusNode.dataset.providerStatus=p.id;item.append(statusNode);for(const cal of p.calendars){const label=node('label','','cal-toggle'),check=document.createElement('input');check.type='checkbox';check.checked=!calendarStyle(cal.id).hidden;check.onchange=async()=>{try{await saveCalendar(cal.id,{hidden:!check.checked});await update(true)}catch(e){showError(e.message)}};label.append(check,node('span',calendarName(cal.id,cal.name)));item.append(label,button('Настроить · '+(calendarStyle(cal.id).role==='satellite'?'Сателлит':'Основной'),()=>openCalendar(cal),'tiny'))}const actions=node('div','','provider-actions');actions.append(button(p.connected?'Переподключить':'Подключить',()=>openConnect(p.id),'tiny'));if(p.connected||p.events.length)actions.append(button('Отключить',async()=>{try{await api('stop',{provider:p.id});await update(true)}catch(e){showError(e.message)}},'tiny'));item.append(actions);$('sources').append(item)}}
function showError(message){$('notice').textContent=message}
function render(){renderSources();renderCalendar();const errors=state.providers.filter(p=>p.error);$('notice').textContent=[state.settingsError,state.mirrorStatus,errors.map(p=>p.name+': '+p.error+(p.success?' Показаны данные последней успешной загрузки.':'')).join(' ')].filter(Boolean).join(' ');$('logs').replaceChildren();const entries=state.providers.flatMap(p=>p.history.map(e=>({...e,name:p.name}))).sort((a,b)=>b.time.localeCompare(a.time));for(const e of entries){const n=node('div','','log-entry');n.append(node('small',e.name+' · '+clock(e.time)),node('div',e.message));$('logs').append(n)}if(!entries.length)$('logs').append(node('p','Проверок пока нет.'))}
async function update(force=false){try{const s=await api('state');const signature=JSON.stringify({...s,providers:s.providers.map(p=>({...p,busy:undefined,checked:undefined,next:undefined,checks:undefined,history:undefined,success:undefined})),notices:undefined,noticeSequence:undefined});state=s;document.querySelectorAll('[data-provider-status]').forEach(n=>{const p=s.providers.find(p=>p.id===n.dataset.providerStatus);if(p)n.textContent=p.busy?'Обновляется…':p.error|| (p.connected?(p.success?'Обновлён в '+clock(p.success):'Подключаемся…'):'Не подключён')});deliverNotifications();if(signature!==lastState||force){lastState=signature;render()}}catch(e){showError('Локальный сервер недоступен или был перезапущен. Обновите страницу.')}}
async function navigate(){const current=++rangeVersion;loading=true;renderCalendar();try{const [start,end]=range();await api('range',{start,end});if(current===rangeVersion){loading=false;await update(true)}}catch(e){loading=false;showError(e.message)}}
function openConnect(kind='yandex'){provider=kind;$('form-error').hidden=true;$('password').value='';$('login').value='';$('url').value='';$('endpoint').value='';$('endpoint-label').hidden=kind!=='ews';$('endpoint').required=kind==='ews';document.querySelectorAll('[data-provider]').forEach(b=>b.classList.toggle('active',b.dataset.provider===kind));$('login-label').hidden=$('password-label').hidden=kind==='ics';$('url-label').hidden=kind!=='ics';$('login').required=$('password').required=kind!=='ics';$('url').required=kind==='ics';$('login').placeholder=kind==='yandex'?'name@yandex.ru':'Рабочая почта или DOMAIN\\login';$('provider-help').textContent=kind==='yandex'?'caldav.yandex.ru · введи логин Яндекса и созданный пароль приложения «Календарь / CalDAV».':kind==='ews'?'Укажи HTTPS-адрес своего Exchange, логин и пароль. Чтение и запись EWS; переходы на другие адреса запрещены.':'Вставь опубликованную ICS-ссылку. Скорость обновления зависит от источника.';$('submit').textContent='Подключить '+({yandex:'Яндекс',ews:'Exchange',ics:'ICS'}[kind]);if(!$('connect-dialog').open)$('connect-dialog').showModal()}
$('connect-form').onsubmit=async e=>{e.preventDefault();$('submit').disabled=true;try{await api('connect-'+provider,provider==='ics'?{url:$('url').value}:{login:$('login').value,password:$('password').value,...(provider==='ews'?{endpoint:$('endpoint').value}:{})});$('password').value='';$('url').value='';$('connect-dialog').close();await update(true)}catch(e){$('form-error').hidden=false;$('form-error').textContent=e.message}finally{$('submit').disabled=false}};
$('connections').onclick=()=>openConnect();document.querySelectorAll('[data-provider]').forEach(b=>b.onclick=()=>openConnect(b.dataset.provider));document.querySelectorAll('.close').forEach(b=>b.onclick=()=>b.closest('dialog').close());$('connect-dialog').addEventListener('close',()=>{$('password').value='';$('url').value=''});
$('date').value=selected;$('date').onchange=()=>{if($('date').value){selected=$('date').value;navigate()}};$('today').onclick=()=>{selected=today();navigate()};
function step(direction){if(view==='month'){const d=dateObj(selected.slice(0,7)+'-01');d.setUTCMonth(d.getUTCMonth()+direction);selected=d.toISOString().slice(0,10)}else selected=plus(selected,direction*(view==='week'?7:1));navigate()}
$('prev').onclick=()=>step(-1);$('next').onclick=()=>step(1);document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>{view=b.dataset.view;navigate()});$('journal').onclick=()=>{render();$('log-dialog').showModal()};$('refresh').onclick=async()=>{try{await api('refresh',{selected});await update(true)}catch(e){showError(e.message)}};
$('show-cancelled').checked=localStorage.getItem('peklo-show-cancelled')==='true';$('show-cancelled').onchange=()=>{localStorage.setItem('peklo-show-cancelled',$('show-cancelled').checked);renderCalendar()};

const palette=[['Шалфей','#E6EFDF','#7D9B68'],['Лаванда','#EEE9FA','#9D84BC'],['Песок','#FFF3DC','#C59745'],['Небо','#E1EDF7','#6C98BD'],['Роза','#F7E3E7','#BF7E8F'],['Мята','#DFF0E9','#65A58C'],['Оранжевый','#FFDFC2','#DF792B']];
function calendarStyle(id){return state.settings?.calendars?.[id]||{}}
function calendarName(id,fallback){return calendarStyle(id).name||fallback}
function applyPalette(element,id,provider){const color=palette[calendarStyle(id).color??({ews:0,yandex:2,ics:1}[provider]||0)];element.style.backgroundColor=color[1];element.style.borderColor=color[2]}
function allCalendars(){return state.providers.flatMap(p=>p.calendars.map(c=>({...c,provider:p.id,connected:p.connected})))}
let selectedEvent=null,editing=null,operation='',editingCalendar=null;
const localTime=s=>new Date(new Date(s).getTime()+3*3600000).toISOString().slice(0,16);
async function editEvent(event=null){
 try{
  editing=event?await api('event-detail',{id:event.id}):null;operation='edit-'+crypto.randomUUID();
  const calendars=allCalendars().filter(c=>c.connected&&c.provider!=='ics'&&(!editing||c.id===editing.calendarId));
  if(!calendars.length)throw Error('Подключите календарь Exchange или Яндекс с правом записи.');
  $('edit-calendar').replaceChildren(...calendars.map(c=>{const option=node('option',calendarName(c.id,c.name));option.value=c.id;return option}));
  $('edit-calendar').disabled=!!editing;if(editing)$('edit-calendar').value=editing.calendarId;
  $('editor-title').textContent=editing?'Изменить встречу':'Новая встреча';$('edit-title').value=editing?.title||'';
  $('edit-start').value=editing?localTime(editing.start):selected+'T10:00';$('edit-end').value=editing?localTime(editing.end):selected+'T11:00';
  $('edit-all-day').checked=!!editing?.allDay;$('edit-location').value=editing?.location||'';$('edit-description').value=editing?.description||'';
  $('edit-none').checked=(editing?.reminderMinutes??15)<0;$('edit-reminder').value=Math.max(0,editing?.reminderMinutes??15);$('edit-reminder').disabled=$('edit-none').checked;
  $('editor-error').textContent='';$('event-dialog').close();$('editor-dialog').showModal();
 }catch(e){showError(e.message)}
}
$('new-event').onclick=()=>editEvent();$('edit-event').onclick=()=>editEvent(selectedEvent);
$('edit-none').onchange=()=>{$('edit-reminder').disabled=$('edit-none').checked};
$('edit-all-day').onchange=()=>{if($('edit-all-day').checked){const day=$('edit-start').value.slice(0,10);$('edit-start').value=day+'T00:00';$('edit-end').value=plus(day,1)+'T00:00'}};
$('event-form').onsubmit=async e=>{e.preventDefault();$('editor-save').disabled=true;try{
 const event={title:$('edit-title').value,start:$('edit-start').value+':00+03:00',end:$('edit-end').value+':00+03:00',allDay:$('edit-all-day').checked,location:$('edit-location').value,description:$('edit-description').value,reminderMinutes:$('edit-none').checked?-1:Number($('edit-reminder').value)};
 await api('event-save',{calendarId:$('edit-calendar').value,id:editing?.id,etag:editing?.etag,operation,event});$('editor-dialog').close();await update(true);
}catch(error){$('editor-error').textContent=error.message}finally{$('editor-save').disabled=false}};
$('delete-event').onclick=async()=>{if(!selectedEvent||!confirm('Удалить эту встречу из исходного календаря? Приглашения об отмене не отправляются.'))return;try{const event=await api('event-detail',{id:selectedEvent.id});await api('event-delete',{id:event.id,etag:event.etag,calendarId:event.calendarId});$('event-dialog').close();await update(true)}catch(e){showError(e.message)}};
async function saveCalendar(id,changes){const c=allCalendars().find(c=>c.id===id);return api('settings',{calendarId:id,settings:{name:'',color:{ews:0,yandex:2,ics:1}[c.provider],role:'primary',anonymous:true,hidden:false,...calendarStyle(id),...changes}})}
function openCalendar(cal){editingCalendar=cal;$('calendar-name').value=calendarStyle(cal.id).name||'';$('calendar-color').replaceChildren(...palette.map((p,i)=>{const option=node('option',p[0]);option.value=i;return option}));$('calendar-color').value=calendarStyle(cal.id).color??({ews:0,yandex:2,ics:1}[cal.provider]);$('calendar-role').value=calendarStyle(cal.id).role||'primary';$('calendar-anonymous').checked=calendarStyle(cal.id).anonymous!==false;$('calendar-error').textContent='';previewColor();$('calendar-role').onchange();$('calendar-dialog').showModal()}
function previewColor(){const p=palette[Number($('calendar-color').value)];$('color-preview').style.backgroundColor=p[1];$('color-preview').style.borderColor=p[2]}
$('calendar-color').onchange=previewColor;$('calendar-role').onchange=()=>{$('calendar-anonymous').disabled=$('calendar-role').value!=='satellite'};
$('calendar-form').onsubmit=async e=>{e.preventDefault();const submit=e.target.querySelector('button');submit.disabled=true;try{await saveCalendar(editingCalendar.id,{name:$('calendar-name').value,color:Number($('calendar-color').value),role:$('calendar-role').value,anonymous:$('calendar-anonymous').checked});$('calendar-dialog').close();await update(true)}catch(error){$('calendar-error').textContent=error.message}finally{submit.disabled=false}};
const notificationNames={reminders:'Напоминания о начале',new:'Новые встречи',moves:'Переносы: старое → новое время',cancellations:'Встреча отменена',summary:'Расписание сегодня в 10:00'};
$('preferences').onclick=()=>{$('notification-options').replaceChildren(...Object.entries(notificationNames).map(([key,title])=>{const label=node('label','','check'),input=node('input');input.type='checkbox';input.dataset.setting=key;input.checked=!!state.settings?.notifications?.[key];label.append(input,node('span',title));return label}));$('preferences-error').textContent=state.settingsError||'';$('preferences-dialog').showModal()};
$('notification-permission').onclick=async()=>{if(!('Notification' in window)){ $('preferences-error').textContent='Браузер не поддерживает уведомления.';return}const result=await Notification.requestPermission();$('preferences-error').textContent=result==='granted'?'Уведомления разрешены.':'Разрешите уведомления в настройках браузера.'};
$('preferences-form').onsubmit=async e=>{e.preventDefault();try{const notifications={};document.querySelectorAll('[data-setting]').forEach(c=>notifications[c.dataset.setting]=c.checked);await api('settings',{notifications});$('preferences-dialog').close();await update(true)}catch(error){$('preferences-error').textContent=error.message}};
let noticeCursor=null;
function notifyOnce(key,title,body,event){if(!('Notification' in window)||Notification.permission!=='granted')return;const storageKey='peklo-notice-'+key;if(localStorage.getItem(storageKey))return;const n=new Notification(title,{body,tag:key});localStorage.setItem(storageKey,String(Date.now()));n.onclick=()=>{window.focus();if(event)openEvent(event)};}
function interval(e){return new Date(e.start).toLocaleString('ru-RU',{timeZone:tz,day:'numeric',month:'short',hour:'2-digit',minute:'2-digit'})+'–'+clock(e.end)}
function deliverNotifications(){
 const settings=state.settings?.notifications||{};
 if(noticeCursor===null)noticeCursor=state.noticeSequence||0;
 for(const notice of state.notices||[]){if(notice.sequence<=noticeCursor||!settings[notice.kind]||calendarStyle(notice.event.calendarId).hidden)continue;const e=notice.event;notifyOnce(notice.kind+'-'+e.id+'-'+e.start+'-'+e.end,(notice.kind==='moves'?'Перенос · ':notice.kind==='cancellations'?'Встреча отменена · ':'Новая встреча · ')+e.title,notice.before?interval(notice.before)+' → '+interval(e):interval(e),e)}
 noticeCursor=state.noticeSequence||0;
 const all=state.providers.flatMap(p=>p.events).filter(e=>!e.copyToken&&!calendarStyle(e.calendarId).hidden&&!e.cancelled&&!cancelledByTitle(e)&&!(e.provider==='ews'&&e.title==='Без встреч'));
 const now=Date.now();if(settings.reminders)for(const e of all){const at=new Date(e.start).getTime()-e.reminderMinutes*60000;if(e.reminderMinutes>=0&&now>=at&&now<new Date(e.start).getTime()+60000)notifyOnce('remind-'+e.id+'-'+e.start+'-'+e.reminderMinutes,e.title,interval(e),e)}
 if(settings.summary&&clock(new Date().toISOString())==='10:00'){const items=all.filter(e=>onDay(e,today()));if(items.length)notifyOnce('summary-'+today(),'Сегодня · '+items.length+' встреч',items.slice(0,5).map(e=>clock(e.start)+' '+e.title).join('\n'))}
 // Keep the browser's deduplication journal bounded to the last month.
 for(let i=localStorage.length-1;i>=0;i--){const k=localStorage.key(i);if(k?.startsWith('peklo-notice-')&&Number(localStorage.getItem(k))<now-31*86400000)localStorage.removeItem(k)}
}
render();navigate();setInterval(update,2000);setInterval(()=>{document.querySelectorAll('.now-line').forEach(n=>n.update?.());deliverNotifications()},30000);
