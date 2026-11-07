package ru.ryadom.calendar;
import java.time.*;
public final class Plans {
 private Plans(){}
 public static long reminderAt(Event e,long now,boolean delivered){if(e.allDay||e.start<=now||delivered)return 0;long at=e.start-15*60000L;return at<=now?now+1000:at;}
 public static long nextSummary(long now){ZonedDateTime z=Instant.ofEpochMilli(now).atZone(Event.ZONE),morning=z.toLocalDate().atTime(10,0).atZone(Event.ZONE);return (!morning.isAfter(z)?morning.plusDays(1):morning).toInstant().toEpochMilli();}
}
