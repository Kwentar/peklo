package ru.ryadom.calendar;
import android.content.*;import org.json.*;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
public class Sync {
 public static final AtomicBoolean busy=new AtomicBoolean(false);
 static final java.util.concurrent.atomic.AtomicReference<LocalDate> requestedDay=new java.util.concurrent.atomic.AtomicReference<>();
 public static void async(Context c,LocalDate day){requestedDay.set(day);async(c);}
 public static final String UPDATED="ru.ryadom.calendar.UPDATED";
 public static void async(Context c){new Thread(()->run(c.getApplicationContext()),"calendar-sync").start();}
 interface Loader{Providers.Result load(Context c,JSONObject account,long from,long to)throws Exception;}
 interface DeletionChecker{boolean deleted(Context c,JSONObject account,Event event)throws Exception;}
 public static void run(Context c){run(c,Providers::load,Providers::deleted);}
 static void run(Context c,Loader loader,DeletionChecker checker){if(!busy.compareAndSet(false,true))return;try{
  JSONObject initial=Store.read(c);JSONArray accounts=Store.array(initial,"accounts");LocalDate requested=requestedDay.getAndSet(null);LocalDate today=requested==null?LocalDate.now(Event.ZONE):requested;long from=today.minusDays(14).atStartOfDay(Event.ZONE).toInstant().toEpochMilli(),to=today.plusDays(31).atStartOfDay(Event.ZONE).toInstant().toEpochMilli();
  for(int i=0;i<accounts.length();i++){JSONObject account=accounts.getJSONObject(i);String id=account.getString("id");if(!account.optBoolean("active",true))continue;
   try{Providers.Result result=loader.load(c,account,from,to);List<Event> additions=new ArrayList<>();
    Store.edit(c,data->{boolean exists=false;for(int j=0;j<Store.array(data,"accounts").length();j++)if(Store.array(data,"accounts").getJSONObject(j).getString("id").equals(id))exists=true;if(!exists)return;
     JSONObject statuses=Store.object(data,"statuses"),old=statuses.optJSONObject(id);long previousEnd=old==null?0:old.optLong("rangeEnd");boolean first=old==null||old.optLong("success")==0;
     List<Event> previous=new ArrayList<>();for(int j=0;j<Store.array(data,"events").length();j++){Event e=Event.from(Store.array(data,"events").getJSONObject(j));if(e.source.equals(id))previous.add(e);}
     JSONObject moves=Store.object(data,"pendingMoves"),cancellations=Store.object(data,"pendingCancellations"),checks=Store.object(data,"deletionChecks");
     Map<String,Event> candidates=new LinkedHashMap<>();for(Event e:previous)candidates.put(e.key(),e);Iterator<String> checkKeys=checks.keys();List<String> sourceKeys=new ArrayList<>();while(checkKeys.hasNext()){String key=checkKeys.next();Event e=Event.from(checks.getJSONObject(key));if(id.equals(e.source)){candidates.putIfAbsent(key,e);sourceKeys.add(key);}}for(String key:sourceKeys)checks.remove(key);
     if(!first&&data.optBoolean("cancellations",true))for(Event e:Cancellations.missing(new ArrayList<>(candidates.values()),result.events,account.optString("type"),from,to,System.currentTimeMillis(),result.calendars.keySet()))if(!Store.object(data,"hidden").optBoolean(id+"|"+e.calendar))checks.put(e.key(),e.json());data.put("deletionChecks",checks);
     Set<String> loadedIds=new HashSet<>(),loadedStable=new HashSet<>();for(Event loaded:result.events){loadedIds.add(loaded.id);if(!loaded.stableId.isEmpty())loadedStable.add(loaded.stableId);}
     JSONObject seen=Store.object(data,"seen"),hidden=Store.object(data,"hidden");long now=System.currentTimeMillis();JSONArray merged=new JSONArray();for(int j=0;j<Store.array(data,"events").length();j++){JSONObject e=Store.array(data,"events").getJSONObject(j);if(!e.optString("source").equals(id)||((e.optLong("start")>=to||e.optLong("end")<=from&&e.optLong("start")<from)&&!loadedIds.contains(e.optString("id"))&&!loadedStable.contains(e.optString("stableId"))))merged.put(e);}
     for(Event e:result.events){if(Mirror.isCopy(data,e)&&e.copyToken.isEmpty())e.copyToken="copy-known";Event before=Moves.previous(e,previous,result.events,account.optString("type"));boolean moved=before!=null&&Moves.changed(before,e);if(!first&&data.optBoolean("cancellations",true)&&Cancellations.newlyCancelled(before,e,account.optString("type"),now)&&!hidden.optBoolean(id+"|"+e.calendar))cancellations.put(before.key(),before.json());if(!first&&moved&&data.optBoolean("moves",true)&&e.end>now&&!e.isCancelled(account.optString("type"))&&!hidden.optBoolean(id+"|"+e.calendar)){JSONObject queued=moves.optJSONObject(before.key());if(!before.key().equals(e.key()))moves.remove(before.key());moves.put(e.key(),new JSONObject().put("before",queued==null?before.json():queued.getJSONObject("before")).put("after",e.json()));}if(!first&&!moved&&!seen.has(e.key())&&e.start<previousEnd&&e.end>now&&!e.isCancelled(account.optString("type"))&&!hidden.optBoolean(id+"|"+e.calendar))additions.add(e);seen.put(e.key(),now);merged.put(e.json());}
     Iterator<String> keys=seen.keys();List<String> expired=new ArrayList<>();while(keys.hasNext()){String key=keys.next();if(seen.optLong(key)<now-90*86400000L)expired.add(key);}for(String key:expired)seen.remove(key);
     JSONObject calendars=new JSONObject();for(Map.Entry<String,String> cal:result.calendars.entrySet())calendars.put(cal.getKey(),cal.getValue());
     if(data.optBoolean("newEvents",true)){JSONObject pending=Store.object(data,"pendingNew");for(Event event:additions)pending.put(event.key(),event.json());data.put("pendingNew",pending);}
     data.put("pendingMoves",moves).put("pendingCancellations",cancellations);
     statuses.put(id,new JSONObject().put("newEventsDetected",additions.size()).put("initialImport",first).put("success",now).put("error","").put("rangeStart",from).put("rangeEnd",to).put("calendars",calendars));data.put("statuses",statuses).put("events",merged).put("seen",seen);
    });
    // Publish the successful snapshot before optional per-item network checks.
    TodayWidget.refresh(c);c.sendBroadcast(new Intent(UPDATED).setPackage(c.getPackageName()));
    checkDeletions(c,account,checker);
    if(!additions.isEmpty())Notices.flushNew(c);
   }catch(Exception failure){boolean halt=failure instanceof Providers.Failure&&((Providers.Failure)failure).auth;String message=failure instanceof Providers.Failure?failure.getMessage():"Не удалось обновить: проверьте сеть, VPN или доступ к календарю.";
    Store.edit(c,data->{JSONObject statuses=Store.object(data,"statuses"),status=statuses.optJSONObject(id);if(status==null)status=new JSONObject();status.put("error",message);statuses.put(id,status);data.put("statuses",statuses);if(halt){JSONArray arr=Store.array(data,"accounts");for(int j=0;j<arr.length();j++)if(arr.getJSONObject(j).optString("id").equals(id))arr.getJSONObject(j).put("active",false);}});
   }
  }
  Mirror.run(c,checker);
  try{DeviceWrites.publish(c,Store.read(c));Store.edit(c,d->d.put("exportStatus","Системный календарь обновлён"));}catch(Exception error){Store.edit(c,d->d.put("exportStatus",error instanceof Providers.Failure?error.getMessage():"Не удалось обновить системный календарь."));}
  Notices.flushCancellations(c);
  Notices.flushMoves(c);
  Notices.flushNew(c);
  Notices.schedule(c);
 }catch(Exception ignored){}finally{busy.set(false);try{TodayWidget.refresh(c);}catch(Exception ignored){}c.sendBroadcast(new Intent(UPDATED).setPackage(c.getPackageName()));if(requestedDay.get()!=null)async(c);}}
 static void checkDeletions(Context c,JSONObject account,DeletionChecker checker){
  String source=account.optString("id");JSONObject queue=Store.object(Store.read(c),"deletionChecks");Iterator<String> keys=queue.keys();int attempted=0;String warning="";
  while(keys.hasNext()){String key=keys.next();Event event=Event.from(queue.optJSONObject(key));if(!source.equals(event.source))continue;if(attempted++>=3){warning="Часть удалений ещё проверяется; расписание обновлено.";break;}
   try{boolean deleted=checker.deleted(c,account,event);Store.edit(c,d->{boolean exists=false;JSONArray accounts=Store.array(d,"accounts");for(int i=0;i<accounts.length();i++)if(source.equals(accounts.getJSONObject(i).optString("id")))exists=true;JSONObject pending=Store.object(d,"deletionChecks");if(!pending.has(key))return;pending.remove(key);d.put("deletionChecks",pending);if(deleted&&exists&&d.optBoolean("cancellations",true)&&!Store.object(d,"hidden").optBoolean(source+"|"+event.calendar)){JSONObject notices=Store.object(d,"pendingCancellations");notices.put(key,event.json());d.put("pendingCancellations",notices);}});}
   catch(Exception error){warning="Проверка удалений отложена; расписание обновлено.";break;}
  }
  final String message=warning;Store.edit(c,d->{JSONObject status=Store.object(d,"statuses").optJSONObject(source);if(status!=null)status.put("deletionWarning",message);});
 }
 public static List<Event> visible(JSONObject d,boolean includeCancelled){Map<String,String> types=new HashMap<>();JSONArray accounts=Store.array(d,"accounts");for(int i=0;i<accounts.length();i++){JSONObject a=accounts.optJSONObject(i);types.put(a.optString("id"),a.optString("type"));}JSONObject hidden=Store.object(d,"hidden");List<Event> out=new ArrayList<>();JSONArray events=Store.array(d,"events");for(int i=0;i<events.length();i++){Event e=Event.from(events.optJSONObject(i));if(e.isDisplayExcluded(types.get(e.source))||Mirror.isCopy(d,e)||!types.containsKey(e.source)||hidden.optBoolean(e.source+"|"+e.calendar))continue;if(e.isCancelled(types.get(e.source))&&!(includeCancelled&&"ews".equals(types.get(e.source))))continue;out.add(e);}out.sort(Comparator.comparingLong(e->e.start));return out;}
}
