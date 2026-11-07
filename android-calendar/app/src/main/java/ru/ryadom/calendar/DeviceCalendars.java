package ru.ryadom.calendar;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.CalendarContract;
import java.time.LocalDate;
import java.util.*;

/** Read-only discovery; no event text or credentials enter diagnostics. */
final class DeviceCalendars {
 static final String[] BASIC={"_id","calendar_displayName","account_name"};
 static final String[] DETAILS={"_id","calendar_displayName","account_name","account_type","visible","sync_events","calendar_access_level"};
 static final class Entry {
  String id,name,account,type="";int visible=-1,sync=-1,access=-1;
  Entry(Cursor c){id=c.getString(0);name=c.isNull(1)?"Без названия":c.getString(1);account=c.isNull(2)?"":c.getString(2);
   if(c.getColumnCount()>3){type=c.getString(3);visible=c.getInt(4);sync=c.getInt(5);access=c.getInt(6);}}
  boolean matches(String query){return name.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));}
 }
 static List<Entry> read(ContentResolver resolver,boolean detailed,CancellationSignal cancel){
  List<Entry> rows=new ArrayList<>();
  try(Cursor c=resolver.query(CalendarContract.Calendars.CONTENT_URI,detailed?DETAILS:BASIC,null,null,null,cancel)){
   if(c==null)throw new IllegalStateException("Empty provider response");
   while(c.moveToNext()){cancel.throwIfCanceled();rows.add(new Entry(c));}
  }
  rows.sort(Comparator.comparing((Entry e)->e.name,String.CASE_INSENSITIVE_ORDER).thenComparing(e->e.id));return rows;
 }
 static final class Report {
  List<Entry> basic=Collections.emptyList(),details=Collections.emptyList();
  Map<String,Integer> instances=new TreeMap<>();Set<String> matchingInstances=new HashSet<>();
  String basicStatus="OK",detailStatus="OK",instanceStatus="OK";boolean workProfile;int sdk;
  String text(String query){
   StringBuilder s=new StringBuilder("Пекло 0.4.2 · диагностика календарей\nAndroid API ").append(sdk).append("\nПрофиль: ").append(workProfile?"рабочий":"личный");
   s.append("\nЧтение без фильтров: ").append(basicStatus).append(" · ").append(basic.size()).append(" календарей");
   s.append("\nРасширенное чтение: ").append(detailStatus).append(" · ").append(details.size()).append(" календарей");
   if(!query.trim().isEmpty())s.append("\nСовпадений с введённым названием: ").append(basic.stream().filter(e->e.matches(query)).count()).append(" / ").append(details.stream().filter(e->e.matches(query)).count()).append(" / ").append(matchingInstances.size()).append(" (список / расширенный / события)");
   Set<String> basicIds=new TreeSet<>(),detailIds=new TreeSet<>();for(Entry e:basic)basicIds.add(e.id);for(Entry e:details)detailIds.add(e.id);
   s.append("\nНаборы ID совпадают: ").append(basicStatus.equals("OK")&&detailStatus.equals("OK")?(basicIds.equals(detailIds)?"да":"нет"):"не проверено");
   s.append("\nСобытия за −7…+31 день: ").append(instanceStatus).append(" · календарей ").append(instances.size());Set<String> absent=new TreeSet<>(instances.keySet());absent.removeAll(basicIds);s.append("\nID календарей событий вне списка: ").append(absent.isEmpty()?"нет":absent);
   s.append("\n\nID | аккаунт | тип | виден | синхр. | доступ | событий");Map<String,Integer> accounts=new LinkedHashMap<>();
   for(Entry e:details){String key=e.account+"|"+e.type;accounts.putIfAbsent(key,accounts.size()+1);s.append("\n").append(e.id).append(" | #").append(accounts.get(key)).append(" | ").append("com.google".equals(e.type)?"Google":CalendarContract.ACCOUNT_TYPE_LOCAL.equals(e.type)?"Local":"Other").append(" | ").append(e.visible).append(" | ").append(e.sync).append(" | ").append(e.access).append(" | ").append(instanceStatus.equals("OK")?instances.getOrDefault(e.id,0):"?");}
   s.append("\n\nНазвания календарей, адреса аккаунтов и содержимое встреч исключены. Запросы ничего не изменяли.");return s.toString();
  }
 }
 static Report diagnose(Context c,String query,CancellationSignal cancel){Report r=new Report();r.sdk=Build.VERSION.SDK_INT;r.workProfile=c.getSystemService(UserManager.class).isManagedProfile();ContentResolver resolver=c.getContentResolver();
  try{r.basic=read(resolver,false,cancel);}catch(OperationCanceledException e){throw e;}catch(Exception e){r.basicStatus=e.getClass().getSimpleName();}
  try{r.details=read(resolver,true,cancel);}catch(OperationCanceledException e){throw e;}catch(Exception e){r.detailStatus=e.getClass().getSimpleName();}
  LocalDate today=LocalDate.now(Event.ZONE);Uri.Builder uri=CalendarContract.Instances.CONTENT_URI.buildUpon();ContentUris.appendId(uri,today.minusDays(7).atStartOfDay(Event.ZONE).toInstant().toEpochMilli());ContentUris.appendId(uri,today.plusDays(31).atStartOfDay(Event.ZONE).toInstant().toEpochMilli());
  try(Cursor events=resolver.query(uri.build(),new String[]{"calendar_id","calendar_displayName"},null,null,null,cancel)){
   if(events==null)throw new IllegalStateException("Empty provider response");int scanned=0;
   while(events.moveToNext()){cancel.throwIfCanceled();if(++scanned>20000){r.instanceStatus="LIMIT: 20000";break;}String id=events.getString(0);r.instances.put(id,r.instances.getOrDefault(id,0)+1);String name=events.isNull(1)?"":events.getString(1);if(!query.trim().isEmpty()&&name.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT)))r.matchingInstances.add(id);}
  }catch(OperationCanceledException e){throw e;}catch(Exception e){r.instanceStatus=e.getClass().getSimpleName();}
  return r;
 }
}
