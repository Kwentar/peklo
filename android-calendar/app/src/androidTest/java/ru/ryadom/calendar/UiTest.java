package ru.ryadom.calendar;

import android.app.*;
import android.appwidget.*;
import android.os.Bundle;
import android.util.SizeF;
import java.util.ArrayList;
import android.widget.*;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.time.*;
import static org.junit.Assert.*;

/** Destructive synthetic fixture: run ONLY on a dedicated empty emulator. */
@RunWith(AndroidJUnit4.class)
public class UiTest {
    void screenshot(Instrumentation ins, String name) throws Exception {
        ins.waitForIdleSync();
        Thread.sleep(500);
        Bitmap bitmap=ins.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File dir=new File(ins.getTargetContext().getExternalFilesDir(null),"ui-checks");
        dir.mkdirs();
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){
            bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
        }
        bitmap.recycle();
    }
    void assertFits(View root){
        ViewGroup list=root.findViewById(R.id.widget_events);
        assertNotNull(list);
        assertTrue(list instanceof ListView);
        assertTrue(((ListView)list).getCount()>0);
        checkTitles(root);
    }
    void checkTitles(View view){
        if(view.getId()==R.id.row_cards)assertTrue("Cards or overlap columns",((ViewGroup)view).getChildCount()>=1);
        if(view instanceof TextView&&view.getId()==R.id.event_title){TextView t=(TextView)view;assertNotNull(t.getLayout());assertTrue("Title clipped vertically",t.getLayout().getHeight()<=t.getHeight()-t.getPaddingTop()-t.getPaddingBottom());}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)checkTitles(((ViewGroup)view).getChildAt(i));
    }
    @Test public void calendarScreens() throws Exception {
  assertTrue("Synthetic data tests require a dedicated emulator",android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish"));
        Instrumentation ins=InstrumentationRegistry.getInstrumentation();
        Context c=ins.getTargetContext();
        LocalDate day=LocalDate.now(Event.ZONE);
        long midnight=day.atStartOfDay(Event.ZONE).toInstant().toEpochMilli();
        JSONArray events=new JSONArray();
        int[][] times={{510,540},{570,600},{645,675},{675,705},{720,735},{780,840},{795,825}};
        String[] titles={"План на день","Обсуждение макетов","Проверка новой версии","Перерыв и прогулка","Короткая встреча","Длинное название встречи для проверки переноса строк","Параллельная встреча"};
        for(int i=0;i<times.length;i++){
            Event e=new Event();e.source="ui-demo";e.calendar="main";e.id="demo-"+i;
            e.title=titles[i];e.start=midnight+times[i][0]*60000L;e.end=midnight+times[i][1]*60000L;e.location="Тестовая комната";
            events.put(e.json());
        }
        JSONObject account=new JSONObject().put("id","ui-demo").put("type","ews").put("login","demo@example.test").put("active",false);
        Store.edit(c,d->{d.put("accounts",new JSONArray().put(account));d.put("events",events);d.put("pendingNew",new JSONObject());d.put("reminders",false);d.put("summary",false);d.put("statuses",new JSONObject().put("ui-demo",new JSONObject().put("rangeStart",midnight-7*86400000L).put("rangeEnd",midnight+31*86400000L).put("success",System.currentTimeMillis()).put("calendars",new JSONObject().put("main","Демонстрационный календарь"))));});
        MainActivity a=(MainActivity)ins.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ins.waitForIdleSync();
        screenshot(ins,"day");
        ins.runOnMainSync(()->a.details(Event.from(events.optJSONObject(0))));
        screenshot(ins,"details");
        ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        ins.runOnMainSync(()->a.switchPage("accounts"));screenshot(ins,"accounts");
        ins.runOnMainSync(()->a.switchPage("settings"));screenshot(ins,"settings");
        ins.runOnMainSync(()->a.switchPage("day"));
        screenshot(ins,"day-final");
        AppWidgetManager manager=AppWidgetManager.getInstance(c);
        AppWidgetHost host=new AppWidgetHost(c,704);
        int id=host.allocateAppWidgetId();
        ComponentName provider=new ComponentName(c,TodayWidget.class);
        Bundle options=new Bundle();options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,380);options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,380);int hostHeight=(int)(c.getResources().getDisplayMetrics().heightPixels/c.getResources().getDisplayMetrics().density)-16;options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,hostHeight);options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,hostHeight);
        assertTrue("Grant bind permission on the dedicated emulator",manager.bindAppWidgetIdIfAllowed(id,provider,options));
        if(android.os.Build.VERSION.SDK_INT>=31){ArrayList<SizeF> sizes=new ArrayList<>();sizes.add(new SizeF(380,hostHeight));options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,sizes);manager.updateAppWidgetOptions(id,options);}
        AppWidgetHostView[] view=new AppWidgetHostView[1];
        try {
            ins.runOnMainSync(()->{a.handler.removeCallbacks(a.ticking);host.startListening();view[0]=host.createView(a,id,manager.getAppWidgetInfo(id));a.setContentView(view[0]);TodayWidget.refresh(c);});
            screenshot(ins,"widget-full");
            ins.runOnMainSync(()->assertFits(view[0]));
            ins.runOnMainSync(()->{a.handler.removeCallbacks(a.ticking);RemoteViews small=TodayWidget.build(c,id,day,Store.read(c),260);FrameLayout container=new FrameLayout(a);((ViewGroup)view[0].getParent()).removeView(view[0]);container.addView(view[0],new FrameLayout.LayoutParams(a.dp(280),a.dp(260)));a.setContentView(container);manager.updateAppWidget(id,small);});
            screenshot(ins,"widget-small");
            ins.runOnMainSync(()->assertFits(view[0]));
            ins.runOnMainSync(()->{((ViewGroup)view[0].getParent()).removeView(view[0]);a.setContentView(view[0]);TodayWidget.refresh(c);});ins.waitForIdleSync();
            ins.runOnMainSync(()->{assertNotNull(view[0].findViewById(R.id.widget_title));assertEquals(day,TodayWidget.selectedDay(c,id));view[0].findViewById(R.id.widget_next).performClick();});
            ins.waitForIdleSync();Thread.sleep(400);
            assertEquals(day.plusDays(1),TodayWidget.selectedDay(c,id));screenshot(ins,"widget-next-day");
            ins.runOnMainSync(()->view[0].findViewById(R.id.widget_prev).performClick());ins.waitForIdleSync();Thread.sleep(400);assertEquals(day,TodayWidget.selectedDay(c,id));
            ins.runOnMainSync(()->view[0].findViewById(R.id.widget_today).performClick());ins.waitForIdleSync();Thread.sleep(400);assertFalse(TodayWidget.prefs(c).contains("day-"+id));
            android.app.Instrumentation.ActivityMonitor monitor=ins.addMonitor(WidgetDateActivity.class.getName(),null,false);
            ins.runOnMainSync(()->view[0].findViewById(R.id.widget_date).performClick());
            WidgetDateActivity picker=(WidgetDateActivity)ins.waitForMonitorWithTimeout(monitor,3000);assertNotNull(picker);screenshot(ins,"widget-date-picker");
            LocalDate target=day.plusDays(2);ins.runOnMainSync(()->{picker.picker.updateDate(target.getYear(),target.getMonthValue()-1,target.getDayOfMonth());picker.picker.getButton(Dialog.BUTTON_POSITIVE).performClick();});ins.waitForIdleSync();assertEquals(target,TodayWidget.selectedDay(c,id));ins.removeMonitor(monitor);ins.waitForIdleSync();Thread.sleep(700);
            TodayWidget.open(c,id,day,"ui-demo|demo-0",false).send();screenshot(ins,"widget-event");ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);ins.waitForIdleSync();Thread.sleep(300);

        } finally {ins.runOnMainSync(host::stopListening);host.deleteAppWidgetId(id);}
    }
}
