const $=id=>document.getElementById(id), token=document.querySelector('meta[name="app-token"]').content;
const tz='Europe/Moscow', dayFormat=new Intl.DateTimeFormat('sv-SE',{timeZone:tz,year:'numeric',month:'2-digit',day:'2-digit'});
const today=()=>dayFormat.format(new Date()), dateObj=s=>new Date(s+'T12:00:00Z'), plus=(s,n)=>{let d=dateObj(s);d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10)}, format=(s,opts)=>dateObj(s).toLocaleDateString('ru-RU',{timeZone:'UTC',...opts}), clock=s=>new Date(s).toLocaleTimeString('ru-RU',{timeZone:tz,hour:'2-digit',minute:'2-digit'});
let selected=today(),view='week',state={providers:[]},hidden=new Set(),provider='yandex',lastState='',loading=false,rangeVersion=0;
function node(tag,text='',cls=''){const n=document.createElement(tag);n.textContent=text;if(cls)n.className=cls;return n}
function button(text,fn,cls=''){const n=node('button',text,cls);n.onclick=fn;return n}
async function api(path,data){const r=await fetch('/api/'+path,{method:data===undefined?'GET':'POST',headers:{'X-App-Token':token,'Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)});const j=await r.json();if(!r.ok)throw Error(j.error||'Запрос отклонён. Обновите страницу.');return j}
function range(){if(view==='day')return [selected,plus(selected,1)];let first=view==='month'?selected.slice(0,7)+'-01':selected;first=plus(first,-((dateObj(first).getUTCDay()+6)%7));return [first,plus(first,view==='month'?42:7)]}
function cancelledByTitle(e){return e.provider==='ews' && /^\s*(?:canceled|cancelled|отменено)\s*:/iu.test(e.title||'')}
function events(){const [start,end]=range();return state.providers.filter(p=>p.rangeStart===start&&p.rangeEnd===end).flatMap(p=>p.events).filter(e=>!hidden.has(e.calendarId)&&($('show-cancelled').checked||!cancelledByTitle(e))).sort((a,b)=>a.start.localeCompare(b.start)||a.title.localeCompare(b.title))}
function onDay(e,day){const start=new Date(day+'T00:00:00+03:00').getTime(),end=start+86400000,b=new Date(e.start).getTime(),f=new Date(e.end).getTime();return b<end&&(f>start||(b===f&&b>=start))}
function openEvent(e){$('event-source').textContent=e.calendarName+' · '+({ews:'Exchange',yandex:'Яндекс',ics:'ICS'}[e.provider]);$('event-title').textContent=e.title;$('event-time').textContent=e.allDay?format(dayFormat.format(new Date(e.start)),{day:'numeric',month:'long'})+' · весь день':new Date(e.start).toLocaleString('ru-RU',{timeZone:tz,day:'numeric',month:'long',hour:'2-digit',minute:'2-digit'})+' — '+new Date(e.end).toLocaleString('ru-RU',{timeZone:tz,day:'numeric',month:'long',hour:'2-digit',minute:'2-digit'});$('event-location').textContent=e.location||'Место не указано';$('event-link').hidden=e.provider!=='yandex';$('event-link').href='https://calendar.yandex.ru/';$('event-dialog').showModal()}
function eventCard(e,compact=false){const n=button('',()=>openEvent(e),'event '+e.provider);n.title=e.title;n.append(node('small',e.allDay?'Весь день':clock(e.start)+'–'+clock(e.end)),node('b',e.title));if(!compact&&e.location)n.append(node('span',e.location));return n}
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
let scrollPeriod='',savedScroll=480;
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
 const body=node('div','','time-body'),axis=node('div','','time-axis');
 for(let h=0;h<24;h++){const label=node('span',String(h).padStart(2,'0')+':00');label.style.top=(h*60)+'px';axis.append(label)}body.append(axis);
 for(const day of days){const col=node('div','','time-column'+(day===today()?' is-today':''));
  for(const block of layoutDay(all,day)){
   const card=eventCard(block.event,true);card.classList.add('timed-event');
   card.style.top=block.start+'px';card.style.height=Math.max(2,block.end-block.start-3)+'px';
   card.style.left=`calc(${block.lane*100/block.columns}% + 2px)`;card.style.width=`calc(${100/block.columns}% - 4px)`;
   if(block.end-block.start<35)card.classList.add('short-event');
   card.title=clock(block.event.start)+'–'+clock(block.event.end)+' · '+block.event.title;
   card.setAttribute('aria-label',card.title);col.append(card);
  }
  if(day===today()){const marker=node('div','','now-line');marker.style.top=((Date.now()-new Date(day+'T00:00:00+03:00'))/60000)+'px';col.append(marker)}
  body.append(col);
 }
 grid.append(body);scroller.append(grid);root.append(scroller);
 const period=view+start;scroller.scrollTop=period===scrollPeriod?savedScroll:480;scrollPeriod=period;savedScroll=scroller.scrollTop;
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
function renderSources(){$('sources').replaceChildren();for(const p of state.providers){if(p.id==='ics'&&!p.connected&&!p.error)continue;const item=node('div','','provider'),head=node('div','','provider-head');head.append(node('span',p.name));item.append(head);const status=p.busy?'Обновляется…':p.error?p.error:p.connected?p.success?'Обновлён в '+clock(p.success):'Подключаемся…':'Не подключён';item.append(node('div',status,'provider-status'+(p.error?' bad':'')));for(const cal of p.calendars){const label=node('label','','cal-toggle'),check=document.createElement('input');check.type='checkbox';check.checked=!hidden.has(cal.id);check.onchange=()=>{if(check.checked)hidden.delete(cal.id);else hidden.add(cal.id);renderCalendar()};label.append(check,node('span',cal.name));item.append(label)}const actions=node('div','','provider-actions');actions.append(button(p.connected?'Переподключить':'Подключить',()=>openConnect(p.id),'tiny'));if(p.connected||p.events.length)actions.append(button('Отключить',async()=>{try{await api('stop',{provider:p.id});await update(true)}catch(e){showError(e.message)}},'tiny'));item.append(actions);$('sources').append(item)}}
function showError(message){$('notice').textContent=message}
function render(){renderSources();renderCalendar();const errors=state.providers.filter(p=>p.error);$('notice').textContent=errors.map(p=>p.name+': '+p.error+(p.success?' Показаны данные последней успешной загрузки.':'')).join(' ');$('logs').replaceChildren();const entries=state.providers.flatMap(p=>p.history.map(e=>({...e,name:p.name}))).sort((a,b)=>b.time.localeCompare(a.time));for(const e of entries){const n=node('div','','log-entry');n.append(node('small',e.name+' · '+clock(e.time)),node('div',e.message));$('logs').append(n)}if(!entries.length)$('logs').append(node('p','Проверок пока нет.'))}
async function update(force=false){try{const s=await api('state');const signature=JSON.stringify(s);state=s;if(signature!==lastState||force){lastState=signature;render()}}catch(e){showError('Локальный сервер недоступен или был перезапущен. Обновите страницу.')}}
async function navigate(){const current=++rangeVersion;loading=true;renderCalendar();try{const [start,end]=range();await api('range',{start,end});if(current===rangeVersion){loading=false;await update(true)}}catch(e){loading=false;showError(e.message)}}
function openConnect(kind='yandex'){provider=kind;$('form-error').hidden=true;$('password').value='';$('login').value='';$('url').value='';$('endpoint').value='';$('endpoint-label').hidden=kind!=='ews';$('endpoint').required=kind==='ews';document.querySelectorAll('[data-provider]').forEach(b=>b.classList.toggle('active',b.dataset.provider===kind));$('login-label').hidden=$('password-label').hidden=kind==='ics';$('url-label').hidden=kind!=='ics';$('login').required=$('password').required=kind!=='ics';$('url').required=kind==='ics';$('login').placeholder=kind==='yandex'?'name@yandex.ru':'Рабочая почта или DOMAIN\\login';$('provider-help').textContent=kind==='yandex'?'caldav.yandex.ru · введи логин Яндекса и созданный пароль приложения «Календарь / CalDAV».':kind==='ews'?'Укажи HTTPS-адрес своего Exchange, логин и пароль. Только чтение EWS; переходы на другие адреса запрещены.':'Вставь опубликованную ICS-ссылку. Скорость обновления зависит от источника.';$('submit').textContent='Подключить '+({yandex:'Яндекс',ews:'Exchange',ics:'ICS'}[kind]);if(!$('connect-dialog').open)$('connect-dialog').showModal()}
$('connect-form').onsubmit=async e=>{e.preventDefault();$('submit').disabled=true;try{await api('connect-'+provider,provider==='ics'?{url:$('url').value}:{login:$('login').value,password:$('password').value,...(provider==='ews'?{endpoint:$('endpoint').value}:{})});$('password').value='';$('url').value='';$('connect-dialog').close();await update(true)}catch(e){$('form-error').hidden=false;$('form-error').textContent=e.message}finally{$('submit').disabled=false}};
$('connections').onclick=()=>openConnect();document.querySelectorAll('[data-provider]').forEach(b=>b.onclick=()=>openConnect(b.dataset.provider));document.querySelectorAll('.close').forEach(b=>b.onclick=()=>b.closest('dialog').close());$('connect-dialog').addEventListener('close',()=>{$('password').value='';$('url').value=''});
$('date').value=selected;$('date').onchange=()=>{if($('date').value){selected=$('date').value;navigate()}};$('today').onclick=()=>{selected=today();navigate()};
function step(direction){if(view==='month'){const d=dateObj(selected.slice(0,7)+'-01');d.setUTCMonth(d.getUTCMonth()+direction);selected=d.toISOString().slice(0,10)}else selected=plus(selected,direction*(view==='week'?7:1));navigate()}
$('prev').onclick=()=>step(-1);$('next').onclick=()=>step(1);document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>{view=b.dataset.view;navigate()});$('journal').onclick=()=>$('log-dialog').showModal();$('refresh').onclick=async()=>{try{await api('refresh',{});await update(true)}catch(e){showError(e.message)}};
$('show-cancelled').onchange=renderCalendar;
render();navigate();setInterval(update,2000);
