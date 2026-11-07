package ru.ryadom.calendar;
import android.content.*;import org.json.*;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
public class Sync {
 public static final AtomicBoolean busy=new AtomicBoolean(false);
 public static final String UPDATED="ru.ryadom.calendar.UPDATED";
 public static void async(Context c){new Thread(()->run(c.getApplicationContext()),"calendar-sync").start();}
 public static void run(Context c){if(!busy.compareAndSet(false,true))return;try{
  JSONObject initial=Store.read(c);JSONArray accounts=Store.array(initial,"accounts");LocalDate today=LocalDate.now(Event.ZONE);long from=today.minusDays(7).atStartOfDay(Event.ZONE).toInstant().toEpochMilli(),to=today.plusDays(31).atStartOfDay(Event.ZONE).toInstant().toEpochMilli();
  for(int i=0;i<accounts.length();i++){JSONObject account=accounts.getJSONObject(i);String id=account.getString("id");if(!account.optBoolean("active",true))continue;
   try{Providers.Result result=Providers.load(c,account,from,to);List<Event> additions=new ArrayList<>();
    Store.edit(c,data->{boolean exists=false;for(int j=0;j<Store.array(data,"accounts").length();j++)if(Store.array(data,"accounts").getJSONObject(j).getString("id").equals(id))exists=true;if(!exists)return;
     JSONObject statuses=Store.object(data,"statuses"),old=statuses.optJSONObject(id);long previousEnd=old==null?0:old.optLong("rangeEnd");boolean first=old==null||old.optLong("success")==0;
     JSONObject seen=Store.object(data,"seen"),hidden=Store.object(data,"hidden");long now=System.currentTimeMillis();JSONArray merged=new JSONArray();for(int j=0;j<Store.array(data,"events").length();j++){JSONObject e=Store.array(data,"events").getJSONObject(j);if(!e.optString("source").equals(id))merged.put(e);}
     for(Event e:result.events){if(!first&&!seen.has(e.key())&&e.start<previousEnd&&e.end>now&&!e.isCancelled(account.optString("type"))&&!hidden.optBoolean(id+"|"+e.calendar))additions.add(e);seen.put(e.key(),now);merged.put(e.json());}
     Iterator<String> keys=seen.keys();List<String> expired=new ArrayList<>();while(keys.hasNext()){String key=keys.next();if(seen.optLong(key)<now-90*86400000L)expired.add(key);}for(String key:expired)seen.remove(key);
     JSONObject calendars=new JSONObject();for(Map.Entry<String,String> cal:result.calendars.entrySet())calendars.put(cal.getKey(),cal.getValue());
     if(data.optBoolean("newEvents",true)){JSONObject pending=Store.object(data,"pendingNew");for(Event event:additions)pending.put(event.key(),event.json());data.put("pendingNew",pending);}
     statuses.put(id,new JSONObject().put("newEventsDetected",additions.size()).put("initialImport",first).put("success",now).put("error","").put("rangeStart",from).put("rangeEnd",to).put("calendars",calendars));data.put("statuses",statuses).put("events",merged).put("seen",seen);
    });
    if(!additions.isEmpty())Notices.flushNew(c);
   }catch(Exception failure){boolean halt=failure instanceof Providers.Failure&&((Providers.Failure)failure).auth;String message=failure instanceof Providers.Failure?failure.getMessage():"Не удалось обновить: проверьте сеть, VPN или доступ к календарю.";
    Store.edit(c,data->{JSONObject statuses=Store.object(data,"statuses"),status=statuses.optJSONObject(id);if(status==null)status=new JSONObject();status.put("error",message);statuses.put(id,status);data.put("statuses",statuses);if(halt){JSONArray arr=Store.array(data,"accounts");for(int j=0;j<arr.length();j++)if(arr.getJSONObject(j).optString("id").equals(id))arr.getJSONObject(j).put("active",false);}});
   }
  }
  Notices.flushNew(c);
  Notices.schedule(c);
 }catch(Exception ignored){}finally{busy.set(false);try{TodayWidget.refresh(c);}catch(Exception ignored){}c.sendBroadcast(new Intent(UPDATED).setPackage(c.getPackageName()));}}
 public static List<Event> visible(JSONObject d,boolean includeCancelled){Map<String,String> types=new HashMap<>();JSONArray accounts=Store.array(d,"accounts");for(int i=0;i<accounts.length();i++){JSONObject a=accounts.optJSONObject(i);types.put(a.optString("id"),a.optString("type"));}JSONObject hidden=Store.object(d,"hidden");List<Event> out=new ArrayList<>();JSONArray events=Store.array(d,"events");for(int i=0;i<events.length();i++){Event e=Event.from(events.optJSONObject(i));if(!types.containsKey(e.source)||hidden.optBoolean(e.source+"|"+e.calendar))continue;if(e.isCancelled(types.get(e.source))&&!(includeCancelled&&"ews".equals(types.get(e.source))))continue;out.add(e);}out.sort(Comparator.comparingLong(e->e.start));return out;}
}
