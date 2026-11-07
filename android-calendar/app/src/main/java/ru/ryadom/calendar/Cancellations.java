package ru.ryadom.calendar;
import java.util.*;
final class Cancellations {
 static List<Event> missing(List<Event> old,List<Event> fresh,String type,long from,long to,long now,Set<String> calendars){
  List<Event> out=new ArrayList<>();for(Event before:old){if(before.isCancelled(type)||before.end<=now||before.start>=to||before.end<from||!calendars.contains(before.calendar))continue;boolean found=false;for(Event e:fresh){Event match=Moves.previous(e,old,fresh,type);if(match!=null&&match.key().equals(before.key())){found=true;break;}}if(!found)out.add(before);}return out;
 }
 static boolean newlyCancelled(Event before,Event after,String type,long now){return before!=null&&!before.isCancelled(type)&&after.isCancelled(type)&&before.end>now;}
}
