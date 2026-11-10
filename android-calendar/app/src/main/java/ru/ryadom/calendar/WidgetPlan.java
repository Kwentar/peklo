package ru.ryadom.calendar;

import java.time.LocalDate;
import java.util.*;

/** Every event keeps its own card; overlapping cards share a row. */
public final class WidgetPlan {
 public static final class Group {
  public final List<Event> events=new ArrayList<>();
  public int start,end;public boolean summary,allDay;
  Group(Event e,int start,int end){events.add(e);this.start=start;this.end=end;allDay=e.allDay;}
  public boolean shortEvent(){return !summary&&!allDay&&events.size()==1&&end-start<30;}
  public int height(){return summary||allDay?56:events.size()>1?78:shortEvent()?34:end-start>=60?62:54;}
 }
 public final List<Group> groups=new ArrayList<>();
 public int start=600,end=1320;
 public WidgetPlan(List<Event> input,LocalDate day,int available,float scale){
  long midnight=day.atStartOfDay(Event.ZONE).toInstant().toEpochMilli();
  List<Event> events=new ArrayList<>(input);events.sort(Comparator.comparing((Event e)->!e.allDay).thenComparingLong(e->e.start));
  for(Event e:events){int from=(int)Math.max(0,(e.start-midnight)/60000),to=(int)Math.min(1440,Math.max(from+1,(e.end-midnight)/60000));
   if(!e.allDay){start=Math.min(start,from);end=Math.max(end,to);}
   Group last=groups.isEmpty()?null:groups.get(groups.size()-1);
   if(!e.allDay&&last!=null&&!last.allDay&&from<last.end){last.events.add(e);last.end=Math.max(last.end,to);}else groups.add(new Group(e,from,to));
  }
 }
 public int cost(float scale){int cost=22,cursor=start;for(Group g:groups){if(!g.allDay&&g.start>cursor)cost+=24;cost+=g.height()+4;if(!g.allDay)cursor=g.end;}if(cursor<end)cost+=24;return (int)Math.ceil(cost*scale);}
 public static String time(int minute){return String.format(Locale.ROOT,"%02d:%02d",minute/60,minute%60);}
}
