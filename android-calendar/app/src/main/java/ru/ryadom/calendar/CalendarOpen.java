package ru.ryadom.calendar;
import android.Manifest;import android.content.*;import android.content.pm.PackageManager;import android.database.Cursor;import android.net.Uri;import android.provider.CalendarContract;import org.json.*;import java.time.*;import java.util.*;
/** Only standard calendar navigation; incoming links never write calendar data. */
final class CalendarOpen {
 static final class Target {LocalDate day;Event event;String message="";}
 static Target resolve(Context c,Intent intent,JSONObject data){Target result=new Target();
  if(Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_APP_CALENDAR)){result.day=LocalDate.now(Event.ZONE);return result;}
  Uri uri=intent.getData();if(!Intent.ACTION_VIEW.equals(intent.getAction())||uri==null||!"content".equals(uri.getScheme())||!("com.android.calendar".equals(uri.getAuthority())||"calendar".equals(uri.getAuthority())))return result;
  List<String> path=uri.getPathSegments();try{
   if(path.size()==2&&path.get(0).equals("time")){result.day=day(Long.parseLong(path.get(1)));return result;}
   if(path.size()!=2||!path.get(0).equals("events"))return result;long id=Long.parseLong(path.get(1));if(id<0)return result;Long begin=intent.hasExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME)?intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,0):null;if(begin!=null)result.day=day(begin);
   for(Event e:Sync.visible(data,true))if("device".equals(type(data,e.source))&&e.id.startsWith(id+"|")&&(begin==null||e.start==begin)){result.event=e;result.day=day(e.start);return result;}
   if(c.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED){result.message="Разрешите чтение календарей, чтобы открыть эту встречу.";return result;}
   // Never query an untrusted incoming URI; reconstruct the official provider URI from the ID.
   try(Cursor row=c.getContentResolver().query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,id),new String[]{"title","dtstart","dtend","allDay","eventLocation","description","calendar_id","_sync_id","deleted","eventStatus"},null,null,null)){
    if(row==null||!row.moveToFirst()||row.getInt(8)!=0){result.message="Встреча больше недоступна.";return result;}
    if(DeviceWrites.exported(c,row.getString(6))){String key=row.getString(7);for(Event e:Sync.visible(data,true))if(Mirror.hash(e.source+"|"+(e.stableId.isEmpty()?e.id:e.stableId)).equals(key)){result.event=e;result.day=day(e.start);return result;}}
    Event e=new Event();e.id=id+"|"+(begin==null?row.getLong(1):begin);e.source="";e.calendar=row.getString(6);e.title=row.isNull(0)?"Без названия":row.getString(0);e.start=begin==null?row.getLong(1):begin;e.end=intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME,row.isNull(2)?e.start:row.getLong(2));e.allDay=row.getInt(3)!=0;e.location=row.isNull(4)?"":row.getString(4);e.description=row.isNull(5)?"":row.getString(5);e.cancelled=row.getInt(9)==CalendarContract.Events.STATUS_CANCELED;if(e.allDay){e.start=Instant.ofEpochMilli(e.start).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(Event.ZONE).toInstant().toEpochMilli();e.end=Instant.ofEpochMilli(e.end).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(Event.ZONE).toInstant().toEpochMilli();}result.event=e;result.day=day(e.start);
   }
  }catch(Exception ignored){result.message="Не удалось открыть календарную ссылку.";}return result;
 }
 static LocalDate day(long time){LocalDate day=Instant.ofEpochMilli(time).atZone(Event.ZONE).toLocalDate();if(day.getYear()<1900||day.getYear()>2100)throw new IllegalArgumentException();return day;}
 static String type(JSONObject data,String source){JSONObject a=Mirror.accounts(data).get(source);return a==null?"":a.optString("type");}
}
