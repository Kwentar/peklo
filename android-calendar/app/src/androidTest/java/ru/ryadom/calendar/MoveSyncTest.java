package ru.ryadom.calendar;
import android.app.*;import android.content.*;import android.net.Uri;import android.os.*;import android.provider.CalendarContract;import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;import org.junit.Test;import org.junit.runner.RunWith;import org.json.*;import java.time.LocalDate;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class MoveSyncTest {
 @Test public void providerMoveReplacesCacheAndNotifiesOnce()throws Exception {
  assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();CalendarDiscoveryTest helper=new CalendarDiscoveryTest();long calendar=-1;
  ins.getUiAutomation().adoptShellPermissionIdentity("android.permission.READ_CALENDAR","android.permission.WRITE_CALENDAR","android.permission.POST_NOTIFICATIONS");
  try {
   while(Sync.busy.get())Thread.sleep(50);calendar=helper.insert(c,"Move test",1);long start=LocalDate.now(Event.ZONE).plusDays(2).atTime(15,0).atZone(Event.ZONE).toInstant().toEpochMilli();
   ContentValues v=new ContentValues();v.put("calendar_id",calendar);v.put("dtstart",start);v.put("dtend",start+3600000);v.put("eventTimezone",Event.ZONE.getId());v.put("title","Synthetic move");Uri event=c.getContentResolver().insert(CalendarContract.Events.CONTENT_URI,v);
   JSONObject account=new JSONObject().put("id","move-test").put("type","device").put("calendarIds",new JSONArray().put(String.valueOf(calendar))).put("active",true);
   Store.edit(c,d->{d.put("accounts",new JSONArray().put(account));d.put("events",new JSONArray());d.put("statuses",new JSONObject());d.put("seen",new JSONObject());d.put("pendingMoves",new JSONObject());d.put("pendingNew",new JSONObject());d.put("moves",true);d.put("newEvents",true);});
   NotificationManager manager=c.getSystemService(NotificationManager.class);manager.cancelAll();Sync.run(c);assertEquals(1,Store.array(Store.read(c),"events").length());assertEquals(0,manager.getActiveNotifications().length);
   v=new ContentValues();v.put("dtstart",start-86400000);v.put("dtend",start-86400000+3600000);c.getContentResolver().update(event,v,null,null);Sync.run(c);
   JSONArray events=Store.array(Store.read(c),"events");assertEquals(1,events.length());assertEquals(start-86400000,events.getJSONObject(0).getLong("start"));assertEquals(0,Store.object(Store.read(c),"pendingNew").length());
   android.service.notification.StatusBarNotification[] notices=manager.getActiveNotifications();assertEquals(1,notices.length);assertEquals(Notices.MOVED,notices[0].getTag());String text=notices[0].getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString();assertTrue(text.contains(" → "));assertTrue(text.contains("15:00–16:00"));
   manager.cancelAll();Sync.run(c);assertEquals(0,manager.getActiveNotifications().length);
   v=new ContentValues();v.put("dtstart",start+90L*86400000);v.put("dtend",start+90L*86400000+3600000);c.getContentResolver().update(event,v,null,null);Sync.run(c);assertEquals(0,Store.array(Store.read(c),"events").length());assertEquals("A move outside the query window is not a cancellation",0,manager.getActiveNotifications().length);
   v=new ContentValues();v.put("dtstart",start);v.put("dtend",start+3600000);c.getContentResolver().update(event,v,null,null);Sync.run(c);manager.cancelAll();
   c.getContentResolver().delete(event,null,null);Sync.run(c);assertEquals(0,Store.array(Store.read(c),"events").length());notices=manager.getActiveNotifications();assertEquals(1,notices.length);assertEquals(Notices.CANCELLED,notices[0].getTag());assertEquals("Встреча отменена",notices[0].getNotification().extras.getString(Notification.EXTRA_TITLE));assertTrue(notices[0].getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Synthetic move"));manager.cancelAll();Sync.run(c);assertEquals(0,manager.getActiveNotifications().length);

  }finally{Store.edit(c,d->d.put("accounts",new JSONArray()).put("events",new JSONArray()));if(calendar>=0)c.getContentResolver().delete(helper.calendarUri(),"_id=?",new String[]{String.valueOf(calendar)});ins.getUiAutomation().dropShellPermissionIdentity();}
 }
}
