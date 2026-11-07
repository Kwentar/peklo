package ru.ryadom.calendar;
import android.app.*;import android.content.*;import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;import org.junit.runner.RunWith;import org.json.*;import java.time.*;import java.util.*;import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class DeviceTest {
 @Test public void notificationQueueAndScreens()throws Exception {
  assertTrue("Synthetic data tests require a dedicated emulator",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
  android.app.Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();Notices.channels(c);
  // This test runs on a clean test emulator only. Use synthetic local events.
  long start=LocalDate.now(Event.ZONE).atTime(13,0).atZone(Event.ZONE).toInstant().toEpochMilli();if(start<System.currentTimeMillis())start+=86400000L;
  Event event=new Event();event.source="test-account";event.id="test-event";event.calendar="main";event.title="Обсудить дизайн календаря";event.location="Видеозвонок";event.start=start;event.end=start+60*60000L;
  JSONObject account=new JSONObject().put("id",event.source).put("type","ews").put("login","demo@example.test").put("active",false);
  Store.edit(c,d->{d.put("accounts",new JSONArray().put(account));d.put("events",new JSONArray().put(event.json()));d.put("pendingNew",new JSONObject());d.put("statuses",new JSONObject().put(event.source,new JSONObject().put("success",System.currentTimeMillis()).put("calendars",new JSONObject().put("main","Работа"))));});
  if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
   Notices.newEvents(c,account,Collections.singletonList(event));assertEquals(1,Store.object(Store.read(c),"pendingNew").length());
   try(android.os.ParcelFileDescriptor pipe=ins.getUiAutomation().executeShellCommand("pm grant "+c.getPackageName()+" android.permission.POST_NOTIFICATIONS")){try(java.io.FileInputStream in=new java.io.FileInputStream(pipe.getFileDescriptor())){while(in.read()!=-1){}}}
  }else Notices.newEvents(c,account,Collections.singletonList(event));
  Notices.flushNew(c);assertEquals(0,Store.object(Store.read(c),"pendingNew").length());boolean found=false;for(android.service.notification.StatusBarNotification n:c.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getTag().equals(Notices.NEW))found=true;assertTrue("New-event notification posted",found);
  Notices.flushNew(c);assertEquals(0,Store.object(Store.read(c),"pendingNew").length());
  Intent intent=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("day",Instant.ofEpochMilli(event.start).atZone(Event.ZONE).toLocalDate().toString());Activity a=ins.startActivitySync(intent);ins.waitForIdleSync();assertNotNull(a);
  ins.runOnMainSync(()->((MainActivity)a).switchPage("accounts"));ins.waitForIdleSync();ins.runOnMainSync(()->((MainActivity)a).switchPage("settings"));ins.waitForIdleSync();ins.runOnMainSync(()->((MainActivity)a).switchPage("day"));ins.waitForIdleSync();
 }
}
