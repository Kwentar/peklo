package ru.ryadom.calendar;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

public class Event {
 public String id,source,calendar,title,location=""; public long start,end; public boolean allDay,cancelled,recurring; public String description="",remoteUri="",etag="",copyToken="",stableId=""; public int reminderMinutes=15;
 public static final ZoneId ZONE=ZoneId.of("Europe/Moscow");
 private static final Pattern CANCEL=Pattern.compile("^\\s*(?:Canceled|Cancelled|Отменено)\\s*:",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
 public boolean isDisplayExcluded(String type){return "ews".equals(type)&&"Без встреч".equals(title);}
 public boolean isCancelled(String type){return cancelled || ("ews".equals(type)&&CANCEL.matcher(title==null?"":title).find());}
 public boolean on(LocalDate d){long s=d.atStartOfDay(ZONE).toInstant().toEpochMilli(),e=d.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();return start<e&&(end>s||(start==end&&start>=s));}
 public JSONObject json() throws JSONException {return new JSONObject().put("id",id).put("source",source).put("calendar",calendar).put("title",title).put("location",location).put("start",start).put("end",end).put("allDay",allDay).put("cancelled",cancelled).put("recurring",recurring).put("description",description).put("remoteUri",remoteUri).put("etag",etag).put("copyToken",copyToken).put("stableId",stableId).put("reminderMinutes",reminderMinutes);}
 public static Event from(JSONObject j){Event e=new Event();e.id=j.optString("id");e.source=j.optString("source");e.calendar=j.optString("calendar");e.title=j.optString("title","Без названия");e.location=j.optString("location");e.start=j.optLong("start");e.end=j.optLong("end");e.allDay=j.optBoolean("allDay");e.cancelled=j.optBoolean("cancelled");e.recurring=j.optBoolean("recurring");e.description=j.optString("description");e.remoteUri=j.optString("remoteUri");e.etag=j.optString("etag");e.copyToken=j.optString("copyToken");e.stableId=j.optString("stableId");e.reminderMinutes=j.optInt("reminderMinutes",15);return e;}
 public String key(){return source+"|"+id;}
 public String reminderKey(){return key()+"|"+start+(reminderMinutes==15?"":"|"+reminderMinutes);}
 public static String time(long t){return Instant.ofEpochMilli(t).atZone(ZONE).toLocalTime().toString().substring(0,5);}
}
