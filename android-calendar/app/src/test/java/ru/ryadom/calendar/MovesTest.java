package ru.ryadom.calendar;
import org.junit.Test;import java.util.*;import static org.junit.Assert.*;
public class MovesTest {
 Event event(String id,long start){Event e=new Event();e.id=id;e.source="source";e.calendar="calendar";e.start=start;e.end=start+3600000;return e;}
 @Test public void deviceMoveMatchesButRepeatIsUnchanged(){Event old=event("42|1000",1000),fresh=event("42|2000",2000);assertSame(old,Moves.previous(fresh,List.of(old),List.of(fresh),"device"));assertTrue(Moves.changed(old,fresh));assertFalse(Moves.changed(fresh,fresh));}
 @Test public void recurringInstancesAreNotGuessed(){Event old=event("42|1000",1000),other=event("42|3000",3000),fresh=event("42|2000",2000);assertNull(Moves.previous(fresh,List.of(old,other),List.of(fresh,other),"device"));}
 @Test public void differentCalendarAndNewEventDoNotMatch(){Event old=event("42|1000",1000),fresh=event("43|2000",2000);assertNull(Moves.previous(fresh,List.of(old),List.of(fresh),"device"));fresh.id="42|2000";fresh.calendar="another";assertNull(Moves.previous(fresh,List.of(old),List.of(fresh),"device"));}
 @Test public void endOnlyChangeIsMove(){Event old=event("stable",1000),fresh=event("stable",1000);fresh.end+=60000;assertTrue(Moves.changed(old,fresh));assertSame(old,Moves.previous(fresh,List.of(old),List.of(fresh),"ews"));}
}
