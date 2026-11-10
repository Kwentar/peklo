package ru.ryadom.calendar;
import java.util.*;
/** Shared compact clock. Boundaries within five minutes align without chained drift. */
public final class OverlapPlan {
 public static final class Placement {public final Event event;public int lane;public float top,height;Placement(Event e){event=e;}}
 public final List<Placement> items=new ArrayList<>();public int columns;public float height;
 static Map<Long,Long> aligned(List<Event> events,boolean starts){TreeSet<Long> values=new TreeSet<>();for(Event e:events)values.add(starts?e.start:Math.max(e.start+1,e.end));Map<Long,Long> map=new HashMap<>();long anchor=Long.MIN_VALUE;for(long value:values){if(anchor==Long.MIN_VALUE||value-anchor>300000L)anchor=value;map.put(value,anchor);}return map;}
 public OverlapPlan(List<Event> events){
  List<Event> sorted=new ArrayList<>(events);sorted.sort(Comparator.comparingLong((Event e)->e.start).thenComparingLong(e->e.end));Map<Long,Long> starts=aligned(sorted,true),endsMap=aligned(sorted,false);TreeSet<Long> points=new TreeSet<>();Map<Event,long[]> bounds=new IdentityHashMap<>();
  for(Event e:sorted){long start=starts.get(e.start),end=Math.max(start+1,endsMap.get(Math.max(e.start+1,e.end)));bounds.put(e,new long[]{start,end});points.add(start);points.add(end);}
  List<Long> times=new ArrayList<>(points);int[] active=new int[Math.max(0,times.size()-1)];int peak=0;
  for(int i=0;i<active.length;i++){for(long[] b:bounds.values())if(b[0]<=times.get(i)&&b[1]>times.get(i))active[i]++;peak=Math.max(peak,active[i]);}
  Map<Long,Float> y=new HashMap<>();float cursor=0;for(int i=0;i<times.size();i++){y.put(times.get(i),cursor);if(i<active.length)cursor+=active[i]==peak?1f:.5f;}height=cursor;
  List<Long> laneEnds=new ArrayList<>();for(Event e:sorted){long[] b=bounds.get(e);Placement p=new Placement(e);int lane=0;while(lane<laneEnds.size()&&laneEnds.get(lane)>b[0])lane++;if(lane==laneEnds.size())laneEnds.add(b[1]);else laneEnds.set(lane,b[1]);p.lane=lane;p.top=y.get(b[0]);p.height=y.get(b[1])-p.top;items.add(p);}columns=laneEnds.size();
 }
}
