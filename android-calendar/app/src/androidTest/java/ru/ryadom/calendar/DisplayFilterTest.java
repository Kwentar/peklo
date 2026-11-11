package ru.ryadom.calendar;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class DisplayFilterTest {
 @Test public void exactExchangePlaceholderIsHiddenEvenWhenCancelledAreShown() throws Exception {
  for(String type:new String[]{"ews","device","yandex"}){
   for(String title:new String[]{"Без встреч","без встреч","Без встреч "," Без встреч","Без встреч сегодня","Canceled: Review"}){
    Event e=new Event();e.id="fixture";e.source="source";e.calendar="main";e.title=title;e.start=1000;e.end=2000;
    JSONObject data=new JSONObject().put("accounts",new JSONArray().put(new JSONObject().put("id","source").put("type",type))).put("events",new JSONArray().put(e.json()));
    boolean excluded=type.equals("ews")&&title.equals("Без встреч");
    assertEquals(title,!excluded,!Sync.visible(data,true).isEmpty());
    assertEquals(title,!excluded&&!e.isCancelled(type),!Sync.visible(data,false).isEmpty());
    if(excluded)assertFalse("A hidden placeholder is not a cancelled meeting",e.isCancelled(type));
    assertEquals("Source remains cached",1,data.getJSONArray("events").length());
   }
  }
 }
}
