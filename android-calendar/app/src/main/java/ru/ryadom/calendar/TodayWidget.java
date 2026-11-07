package ru.ryadom.calendar;
import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.os.Bundle;
import android.widget.RemoteViews;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class TodayWidget extends AppWidgetProvider {
 public static void refresh(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);int[] ids=m.getAppWidgetIds(new ComponentName(c,TodayWidget.class));if(ids.length>0)render(c,m,ids);}
 @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){render(c,m,ids);}
 @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,Bundle options){render(c,m,new int[]{id});}
 static void render(Context c,AppWidgetManager m,int[] ids){
  LocalDate day=LocalDate.now(Event.ZONE);JSONObject data;try{data=Store.read(c);}catch(Exception e){return;}
  List<Event> events=new ArrayList<>();for(Event e:Sync.visible(data,false))if(e.on(day))events.add(e);
  long last=Long.MAX_VALUE;int errors=0;JSONArray accounts=Store.array(data,"accounts");JSONObject statuses=Store.object(data,"statuses");
  for(int i=0;i<accounts.length();i++){JSONObject s=statuses.optJSONObject(accounts.optJSONObject(i).optString("id"));if(s==null||!s.optString("error").isEmpty()||s.optLong("success")==0)errors++;if(s!=null&&s.optLong("success")>0)last=Math.min(last,s.optLong("success"));}
  for(int id:ids){RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_today);v.setTextViewText(R.id.widget_title,"Сегодня · "+day.format(DateTimeFormatter.ofPattern("d MMM",new Locale("ru"))));
   int height=m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,180);int limit=Math.max(1,Math.min(8,(height-75)/43));StringBuilder text=new StringBuilder();
   for(int i=0;i<Math.min(limit,events.size());i++){Event e=events.get(i);if(i>0)text.append("\n\n");text.append(e.allDay?"Весь день":Event.time(e.start)+"–"+Event.time(e.end)).append("  ").append(e.title.replace('\n',' '));}
   if(events.size()>limit)text.append("\nЕщё ").append(events.size()-limit).append(" · открыть день");
   if(events.isEmpty())text.append(accounts.length()==0?"Подключите календарь в приложении":last==Long.MAX_VALUE?"Ожидаем загрузку календарей":"На сегодня встреч нет");
   v.setTextViewText(R.id.widget_events,text.toString());v.setTextViewText(R.id.widget_status,(last==Long.MAX_VALUE?"Нет загруженных данных":"Обновлено "+Instant.ofEpochMilli(last).atZone(Event.ZONE).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")))+(errors>0?" · есть ошибки":"")+" · МСК");
   Intent open=new Intent(c,MainActivity.class).putExtra("today",true).setAction("ru.ryadom.calendar.TODAY");v.setOnClickPendingIntent(R.id.widget_root,PendingIntent.getActivity(c,71,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));m.updateAppWidget(id,v);
  }
 }
}
