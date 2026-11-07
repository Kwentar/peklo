package ru.ryadom.calendar;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.CalendarContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CalendarDiscoveryTest {
 static final String ACCOUNT="discovery@example.test";
 Uri calendarUri(){return CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,"true").appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME,ACCOUNT).appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.ACCOUNT_TYPE_LOCAL).build();}
 long insert(Context c,String name,int visible){ContentValues v=new ContentValues();v.put("account_name",ACCOUNT);v.put("account_type",CalendarContract.ACCOUNT_TYPE_LOCAL);v.put("name",name);v.put("calendar_displayName",name);v.put("calendar_color",0xff998877);v.put("calendar_access_level",CalendarContract.Calendars.CAL_ACCESS_OWNER);v.put("ownerAccount",ACCOUNT);v.put("visible",visible);v.put("sync_events",visible);return ContentUris.parseId(c.getContentResolver().insert(calendarUri(),v));}
 void waitLoaded(Instrumentation ins,DeviceCalendarPicker picker)throws Exception {for(int i=0;i<100;i++){boolean[] busy={true};ins.runOnMainSync(()->busy[0]=picker.busy);if(!busy[0])return;Thread.sleep(50);}fail("Discovery timeout");}
 @Test public void newlyCreatedCalendarAppearsAndReportIsRedacted()throws Exception {
  assertTrue("Dedicated emulator only",Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));
  Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();List<Long> created=new ArrayList<>();DeviceCalendarPicker[] picker={null};
  ins.getUiAutomation().adoptShellPermissionIdentity("android.permission.READ_CALENDAR","android.permission.WRITE_CALENDAR");
  try {
   int before=DeviceCalendars.read(c.getContentResolver(),false,new CancellationSignal()).size();
   long visible=insert(c,"Рабочий — тест",1),hidden=insert(c,"Скрытый — тест",0);created.add(visible);created.add(hidden);
   MainActivity a=(MainActivity)ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
   ins.runOnMainSync(()->{picker[0]=new DeviceCalendarPicker(a);picker[0].show();});waitLoaded(ins,picker[0]);
   ins.runOnMainSync(()->{assertEquals(before+2,picker[0].rows.size());picker[0].selected.add(String.valueOf(visible));});
   long personal=insert(c,"Личное — тест",1);created.add(personal);
   long start=LocalDate.now(Event.ZONE).atTime(10,0).atZone(Event.ZONE).toInstant().toEpochMilli();ContentValues event=new ContentValues();event.put("calendar_id",personal);event.put("dtstart",start);event.put("dtend",start+3600000);event.put("eventTimezone",Event.ZONE.getId());event.put("title","Synthetic private event text");c.getContentResolver().insert(CalendarContract.Events.CONTENT_URI,event);
   ins.runOnMainSync(picker[0]::reload);waitLoaded(ins,picker[0]);
   ins.runOnMainSync(()->{assertEquals(before+3,picker[0].rows.size());assertTrue(picker[0].selected.contains(String.valueOf(visible)));picker[0].search.setText("Личное");assertEquals(1,picker[0].filtered.size());assertEquals(String.valueOf(personal),picker[0].filtered.get(0).id);});
   DeviceCalendars.Report report=DeviceCalendars.diagnose(c,"Личное",new CancellationSignal());assertEquals("OK",report.basicStatus);assertEquals("OK",report.detailStatus);assertEquals("OK",report.instanceStatus);assertTrue(report.matchingInstances.contains(String.valueOf(personal)));assertEquals(Integer.valueOf(1),report.instances.get(String.valueOf(personal)));
   String text=report.text("Личное");assertFalse(text.contains(ACCOUNT));assertFalse(text.contains("Личное"));assertFalse(text.contains("Synthetic private event text"));assertTrue(text.contains("1 / 1 / 1"));assertTrue(report.details.stream().anyMatch(e->e.id.equals(String.valueOf(hidden))&&e.visible==0&&e.sync==0));
   new UiTest().screenshot(ins,"calendar-picker-filtered");ins.runOnMainSync(()->picker[0].search.setText(""));new UiTest().screenshot(ins,"calendar-picker-all");
   ins.runOnMainSync(()->{picker[0].search.setText("Личное");picker[0].diagnose();});waitLoaded(ins,picker[0]);new UiTest().screenshot(ins,"calendar-diagnostics");ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
   ins.runOnMainSync(()->{picker[0].dialog.dismiss();a.finish();});
  } finally {if(picker[0]!=null)ins.runOnMainSync(()->picker[0].dialog.dismiss());for(long id:created)c.getContentResolver().delete(calendarUri(),"_id=?",new String[]{String.valueOf(id)});ins.getUiAutomation().dropShellPermissionIdentity();}
 }
}
