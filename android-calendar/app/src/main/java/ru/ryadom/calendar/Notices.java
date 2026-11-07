package ru.ryadom.calendar;
import android.app.*;import android.content.*;import android.net.Uri;import android.os.Build;import org.json.*;import java.time.*;import java.util.*;
public class Notices {
 public static final String REMIND="remind",SUMMARY="summary",NEW="new",MOVED="moved";
 static NotificationManager manager(Context c){return c.getSystemService(NotificationManager.class);}
 public static void channels(Context c){for(String[] value:new String[][]{{REMIND,"Встреча через 15 минут"},{SUMMARY,"План дня в 10:00"},{NEW,"Новые встречи"},{MOVED,"Переносы встреч"}}){NotificationChannel channel=new NotificationChannel(value[0],value[1],NotificationManager.IMPORTANCE_HIGH);channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager(c).createNotificationChannel(channel);}}
 static PendingIntent alarmIntent(Context c,String action){return PendingIntent.getBroadcast(c,0,new Intent(c,AlarmReceiver.class).setAction(action).setData(Uri.parse("ryadom://alarm/"+action)),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 static void alarm(Context c,String action,long when){AlarmManager am=c.getSystemService(AlarmManager.class);PendingIntent pi=alarmIntent(c,action);am.cancel(pi);if(when<=0)return;if(Build.VERSION.SDK_INT<31||am.canScheduleExactAlarms())am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,when,pi);}
 public static boolean exact(Context c){return Build.VERSION.SDK_INT<31||c.getSystemService(AlarmManager.class).canScheduleExactAlarms();}
 public static synchronized void schedule(Context c){JSONObject d=Store.read(c);long now=System.currentTimeMillis(),next=Long.MAX_VALUE;JSONObject reminded=Store.object(d,"reminded");if(d.optBoolean("reminders",true))for(Event e:Sync.visible(d,false)){long at=Plans.reminderAt(e,now,reminded.has(e.reminderKey()));if(at>0)next=Math.min(next,at);}alarm(c,REMIND,next==Long.MAX_VALUE?0:next);
  alarm(c,SUMMARY,d.optBoolean("summary",true)?Plans.nextSummary(now):0);
 }
 static boolean enabled(Context c){return (Build.VERSION.SDK_INT<33||c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)&&manager(c).areNotificationsEnabled();}
 static String blockedReason(Context c,String channel){
  if(!enabled(c))return "Уведомления запрещены в Android";
  NotificationChannel nc=manager(c).getNotificationChannel(channel);
  if(nc!=null&&nc.getImportance()==NotificationManager.IMPORTANCE_NONE)return "Канал «"+nc.getName()+"» выключен в Android";
  return "";
 }
 static void record(Context c,String message){Store.edit(c,d->d.put("notificationStatus",message).put("notificationChecked",System.currentTimeMillis()));}
 static boolean show(Context c,String channel,int id,String title,String text,LocalDate day,String eventKey){
  channels(c);String blocked=blockedReason(c,channel);if(!blocked.isEmpty()){record(c,blocked);return false;}
  Intent intent=new Intent(c,MainActivity.class).setAction("OPEN_DAY").setData(Uri.parse("ryadom://open/"+channel+"/"+id)).putExtra("day",day.toString()).putExtra("event",eventKey).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
  PendingIntent open=PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification.Builder n=new Notification.Builder(c,channel).setSmallIcon(ru.ryadom.calendar.R.drawable.ic_calendar).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT).setVisibility(Notification.VISIBILITY_PRIVATE).setShowWhen(true);
  try{manager(c).notify(channel,id,n.build());record(c,"Передано Android: "+title);return true;}catch(SecurityException e){record(c,"Android отклонил уведомление: проверь разрешения");return false;}
 }
 public static void newEvents(Context c,JSONObject account,List<Event> events){
  if(!Store.read(c).optBoolean("newEvents",true))return;
  Store.edit(c,d->{JSONObject pending=Store.object(d,"pendingNew");for(Event e:events)pending.put(e.key(),e.json());d.put("pendingNew",pending);});flushNew(c);
 }
 public static synchronized void flushNew(Context c){
  JSONObject data=Store.read(c);JSONObject pending=Store.object(data,"pendingNew");if(pending.length()==0)return;
  Map<String,Event> current=new HashMap<>();for(Event e:Sync.visible(data,false))current.put(e.key(),e);
  List<Event> deliver=new ArrayList<>();List<String> remove=new ArrayList<>();Iterator<String> keys=pending.keys();long now=System.currentTimeMillis();
  while(keys.hasNext()){String key=keys.next();Event e=current.get(key);if(e==null||e.end<=now||!data.optBoolean("newEvents",true))remove.add(key);else deliver.add(e);}
  deliver.sort(Comparator.comparingLong(e->e.start));
  if(!deliver.isEmpty()){
   Event first=deliver.get(0);boolean posted=show(c,NEW,41,deliver.size()==1?"Новая встреча":"Новых встреч: "+deliver.size(),first.title+" · "+Instant.ofEpochMilli(first.start).atZone(Event.ZONE).toLocalDate()+" "+Event.time(first.start),Instant.ofEpochMilli(first.start).atZone(Event.ZONE).toLocalDate(),first.key());
   if(posted)for(Event e:deliver)remove.add(e.key());
  }
  if(!remove.isEmpty())Store.edit(c,d->{JSONObject p=Store.object(d,"pendingNew");for(String key:remove)p.remove(key);d.put("pendingNew",p);});
 }
 public static synchronized void flushMoves(Context c){
  JSONObject d=Store.read(c),pending=Store.object(d,"pendingMoves");Map<String,Event> current=new HashMap<>();for(Event e:Sync.visible(d,false))current.put(e.key(),e);List<String> remove=new ArrayList<>();Iterator<String> keys=pending.keys();
  while(keys.hasNext()){String key=keys.next();JSONObject change=pending.optJSONObject(key);Event e=current.get(key);if(e==null||e.end<=System.currentTimeMillis()||!d.optBoolean("moves",true)){remove.add(key);continue;}Event before=Event.from(change.optJSONObject("before"));if(!Moves.changed(before,e)||show(c,MOVED,key.hashCode(),"Перенос · "+e.title,Moves.interval(before)+" → "+Moves.interval(e)+" (МСК)",Instant.ofEpochMilli(e.start).atZone(Event.ZONE).toLocalDate(),key))remove.add(key);}
  if(!remove.isEmpty())Store.edit(c,data->{JSONObject q=Store.object(data,"pendingMoves");for(String key:remove)q.remove(key);data.put("pendingMoves",q);});
 }
 public static void deliverReminders(Context c){JSONObject d=Store.read(c);if(!d.optBoolean("reminders",true))return;long now=System.currentTimeMillis();JSONObject reminded=Store.object(d,"reminded");List<Event> due=new ArrayList<>();for(Event e:Sync.visible(d,false))if(!e.allDay&&e.start>now&&e.start-15*60000L<=now+1000&&!reminded.has(e.reminderKey()))due.add(e);
  if(enabled(c)){for(Event e:due){long minutes=Math.max(1,(e.start-now+59999)/60000);show(c,REMIND,e.key().hashCode(),"Через "+minutes+" мин · "+e.title,Event.time(e.start)+(e.location.isEmpty()?"":" · "+e.location),Instant.ofEpochMilli(e.start).atZone(Event.ZONE).toLocalDate(),e.key());}}
  // Mark even when permission is off to avoid a one-second alarm loop.
  Store.edit(c,store->{JSONObject r=Store.object(store,"reminded");for(Event e:due)r.put(e.reminderKey(),now);List<String> remove=new ArrayList<>();Iterator<String> keys=r.keys();while(keys.hasNext()){String key=keys.next();if(r.optLong(key)<now-45*86400000L)remove.add(key);}for(String key:remove)r.remove(key);store.put("reminded",r);});
 }
 public static void summary(Context c){JSONObject d=Store.read(c);if(!d.optBoolean("summary",true))return;LocalDate day=LocalDate.now(Event.ZONE);List<Event> events=new ArrayList<>();for(Event e:Sync.visible(d,false))if(e.on(day))events.add(e);JSONObject statuses=Store.object(d,"statuses");Iterator<String> keys=statuses.keys();boolean stale=Store.array(d,"accounts").length()==0||statuses.length()<Store.array(d,"accounts").length();while(keys.hasNext()){JSONObject s=statuses.optJSONObject(keys.next());if(s!=null&&(System.currentTimeMillis()-s.optLong("success")>60*60000L||!s.optString("error").isEmpty()))stale=true;}
  String text=events.isEmpty()?"На сегодня встреч нет":events.size()+" событий · "+(events.get(0).allDay?"есть события на весь день":"первая в "+Event.time(events.get(0).start));if(stale)text+=" · данные могут быть устаревшими";show(c,SUMMARY,10,"План на сегодня",text,day,"");
 }
 public static void test(Context c){show(c,REMIND,999,"Пекло · тест уведомления","Нажми, чтобы открыть сегодняшний план",LocalDate.now(Event.ZONE),"");}
}
