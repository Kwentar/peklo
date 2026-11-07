package ru.ryadom.calendar;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
final class Moves {
 static boolean changed(Event before,Event after){return before.start!=after.start||before.end!=after.end||before.allDay!=after.allDay;}
 static String identity(Event e){int split=e.id.lastIndexOf('|');return e.source+"|"+e.calendar+"|"+(split<0?e.id:e.id.substring(0,split));}
 static Event previous(Event event,List<Event> old,List<Event> fresh,String type){
  for(Event e:old)if(e.key().equals(event.key()))return e;
  // Legacy device IDs include start time. Never guess between recurring instances.
  if(!"device".equals(type))return null;
  String identity=identity(event);Event candidate=null;int count=0;
  for(Event e:old)if(identity(e).equals(identity)){candidate=e;count++;}
  if(count!=1)return null;count=0;
  for(Event e:fresh)if(identity(e).equals(identity))count++;
  return count==1?candidate:null;
 }
 static String interval(Event e){DateTimeFormatter date=DateTimeFormatter.ofPattern("dd.MM.yyyy");var start=Instant.ofEpochMilli(e.start).atZone(Event.ZONE);var end=Instant.ofEpochMilli(e.end).atZone(Event.ZONE);if(e.allDay)return date.format(start)+" — "+date.format(end.minusDays(1))+" · весь день";return date.format(start)+" "+Event.time(e.start)+"–"+(start.toLocalDate().equals(end.toLocalDate())?"":date.format(end)+" ")+Event.time(e.end);}
}
