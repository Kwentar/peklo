package ru.ryadom.calendar;
import java.time.*;
public final class Plans {
 private Plans(){}
 public static long reminderAt(Event e,long now,boolean delivered){if(e.allDay||e.reminderMinutes<0||!e.copyToken.isEmpty()||(e.reminderMinutes==0?e.start<=now-60000:e.start<=now)||delivered)return 0;long at=e.start-e.reminderMinutes*60000L;return at<=now?now+1000:at;}
 public static long nextSummary(long now){ZonedDateTime z=Instant.ofEpochMilli(now).atZone(Event.ZONE),morning=z.toLocalDate().atTime(10,0).atZone(Event.ZONE);return (!morning.isAfter(z)?morning.plusDays(1):morning).toInstant().toEpochMilli();}
}
