package ru.ryadom.calendar;
import android.app.*;import android.content.*;import android.net.Uri;import android.os.Build;import android.view.View;import android.widget.ScrollView;import androidx.test.platform.app.InstrumentationRegistry;import org.junit.Test;import org.json.*;import java.time.*;import static org.junit.Assert.*;
public class NavigationTest {
 @Test public void calendarLinksAndStableAuxiliaryScroll()throws Exception{
  assertTrue(Build.HARDWARE.equals("ranchu")||Build.HARDWARE.equals("goldfish"));Instrumentation ins=InstrumentationRegistry.getInstrumentation();Context c=ins.getTargetContext();
  JSONArray accounts=new JSONArray();JSONObject statuses=new JSONObject();for(int n=0;n<12;n++){String id="navigation-"+n;accounts.put(new JSONObject().put("id",id).put("type","device").put("active",false));statuses.put(id,new JSONObject().put("success",1).put("calendars",new JSONObject().put("fixture","Fixture")));}
  Store.edit(c,d->{d.put("accounts",accounts);d.put("statuses",statuses);d.put("events",new JSONArray());d.put("notificationStatus","Fixture\n".repeat(40));});
  long time=LocalDate.of(2027,2,3).atStartOfDay(Event.ZONE).toInstant().toEpochMilli();Intent link=new Intent(Intent.ACTION_VIEW,Uri.parse("content://com.android.calendar/time/"+time)).setPackage(c.getPackageName());assertNotNull(c.getPackageManager().resolveActivity(link,0));assertNotNull(c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR).setPackage(c.getPackageName()),0));assertNotNull(c.getPackageManager().resolveActivity(new Intent(link).setDataAndType(link.getData(),"time/epoch"),0));assertNotNull(c.getPackageManager().resolveActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.calendar/events/1"),"vnd.android.cursor.item/event").setPackage(c.getPackageName()),0));assertNull(CalendarOpen.resolve(c,new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.test/time/1")),Store.read(c)).day);
  MainActivity a=(MainActivity)ins.startActivitySync(link.setClass(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));ins.waitForIdleSync();assertEquals(LocalDate.of(2027,2,3),a.day);
  try{for(String screen:new String[]{"accounts","settings"}){
   ins.runOnMainSync(()->{a.handler.removeCallbacks(a.ticking);a.switchPage(screen);});ins.waitForIdleSync();Thread.sleep(200);ScrollView[] sv={null};View[] root={null};int[] y={0};ins.runOnMainSync(()->{sv[0]=a.auxiliaryScroll;root[0]=a.root;sv[0].scrollTo(0,a.dp(180));y[0]=sv[0].getScrollY();assertTrue(y[0]>0);});
   for(int n=0;n<4;n++){Store.edit(c,d->Store.object(d,"statuses").getJSONObject("navigation-0").put("success",System.currentTimeMillis()));c.sendBroadcast(new Intent(Sync.UPDATED).setPackage(c.getPackageName()));Thread.sleep(180);}
   ins.runOnMainSync(()->{assertSame(root[0],a.root);assertSame(sv[0],a.auxiliaryScroll);assertEquals(y[0],a.auxiliaryScroll.getScrollY());});
   Store.edit(c,d->{if(screen.equals("accounts"))Store.object(d,"statuses").getJSONObject("navigation-0").put("error","Fixture error");else d.put("notificationStatus","Updated fixture\n".repeat(40));});ins.runOnMainSync(a::refreshFromSync);ins.waitForIdleSync();Thread.sleep(200);ins.runOnMainSync(()->{assertNotSame(root[0],a.root);assertEquals(y[0],a.auxiliaryScroll.getScrollY());});
  }
  ins.runOnMainSync(()->a.onNewIntent(new Intent(Intent.ACTION_VIEW,Uri.parse("content://com.android.calendar/time/"+(time+86400000L)))));ins.waitForIdleSync();assertEquals(LocalDate.of(2027,2,4),a.day);
  }finally{ins.runOnMainSync(a::finish);}
 }
}
