package ru.ryadom.calendar;
import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;
public class WidgetPlanTest {
 final LocalDate day=LocalDate.of(2026,11,8);
 Event event(int start,int end){Event e=new Event();e.start=day.atStartOfDay(Event.ZONE).toInstant().toEpochMilli()+start*60000L;e.end=day.atStartOfDay(Event.ZONE).toInstant().toEpochMilli()+end*60000L;return e;}
 @Test public void extendsBoundsAndPreservesOverlap(){WidgetPlan p=new WidgetPlan(Arrays.asList(event(510,540),event(600,660),event(615,645),event(1320,1350)),day,2000,1);assertEquals(510,p.start);assertEquals(1350,p.end);assertEquals(3,p.groups.size());assertEquals(2,p.groups.get(1).events.size());}
 @Test public void halfHourHasTwoLines(){WidgetPlan p=new WidgetPlan(Arrays.asList(event(600,630),event(660,689)),day,2000,1);assertFalse(p.groups.get(0).shortEvent());assertTrue(p.groups.get(1).shortEvent());}
 @Test public void denseDaysNeverLoseEvents(){List<Event> events=new ArrayList<>();for(int i=0;i<40;i++)events.add(event(480+i*20,495+i*20));WidgetPlan p=new WidgetPlan(events,day,350,1.3f);int count=0;for(WidgetPlan.Group g:p.groups)count+=g.events.size();assertEquals(40,count);assertEquals(40,p.groups.size());assertFalse(p.groups.stream().anyMatch(g->g.summary));assertTrue(p.cost(1.3f)>350);}
 @Test public void defaultRangeAndMidnightClamp(){WidgetPlan p=new WidgetPlan(Arrays.asList(event(-60,30),event(1410,1500)),day,2000,1);assertEquals(0,p.start);assertEquals(1440,p.end);WidgetPlan empty=new WidgetPlan(Collections.emptyList(),day,500,1);assertEquals(600,empty.start);assertEquals(1320,empty.end);}
}
