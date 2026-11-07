package ru.ryadom.calendar;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.util.SizeF;
import android.widget.RemoteViews;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class TodayWidget extends AppWidgetProvider {
 static final String NAV="ru.ryadom.calendar.WIDGET_NAV";
 static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("widget-days",Context.MODE_PRIVATE);}
 static LocalDate selectedDay(Context c,int id){String saved=prefs(c).getString("day-"+id,null);try{return saved==null?LocalDate.now(Event.ZONE):LocalDate.parse(saved);}catch(Exception e){return LocalDate.now(Event.ZONE);}}
 static void selectDay(Context c,int id,LocalDate day){android.content.SharedPreferences.Editor edit=prefs(c).edit();if(day==null)edit.remove("day-"+id);else edit.putString("day-"+id,day.toString());edit.apply();refresh(c);}
 @Override public void onReceive(Context c,Intent intent){super.onReceive(c,intent);if(NAV.equals(intent.getAction())){int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);AppWidgetProviderInfo info=AppWidgetManager.getInstance(c).getAppWidgetInfo(id);if(info==null||!info.provider.equals(new ComponentName(c,TodayWidget.class)))return;int delta=intent.getIntExtra("delta",0);selectDay(c,id,delta==0?null:selectedDay(c,id).plusDays(delta<0?-1:1));}}
 @Override public void onDeleted(Context c,int[] ids){android.content.SharedPreferences.Editor edit=prefs(c).edit();for(int id:ids)edit.remove("day-"+id);edit.apply();}
 public static void refresh(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);int[] ids=m.getAppWidgetIds(new ComponentName(c,TodayWidget.class));if(ids.length>0)render(c,m,ids);}
 @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){render(c,m,ids);}
 @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,Bundle options){render(c,m,new int[]{id});}
 static PendingIntent navigation(Context c,int id,int delta){Intent i=new Intent(c,TodayWidget.class).setAction(NAV).setData(Uri.parse("peklo://widget/"+id+"/step/"+delta)).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("delta",delta);return PendingIntent.getBroadcast(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 static PendingIntent open(Context c,int id,LocalDate day,String key,boolean picker){Intent i=new Intent(c,picker?WidgetDateActivity.class:MainActivity.class).setData(Uri.parse("peklo://widget/"+id+"/"+(picker?"date":Uri.encode(key==null?"day":key)))).putExtra("day",day.toString()).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);if(key!=null)i.putExtra("event",key);return PendingIntent.getActivity(c,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 static void render(Context c,AppWidgetManager m,int[] ids){JSONObject data;try{data=Store.read(c);}catch(Exception e){return;}for(int id:ids){Bundle options=m.getAppWidgetOptions(id);LocalDate day=selectedDay(c,id);if(Build.VERSION.SDK_INT>=31){ArrayList<SizeF> sizes=options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);if(sizes!=null&&!sizes.isEmpty()){Map<SizeF,RemoteViews> views=new LinkedHashMap<>();for(SizeF size:sizes){if(views.size()>=16)break;views.put(size,build(c,id,day,data,(int)size.getHeight()));}m.updateAppWidget(id,new RemoteViews(views));continue;}}
  int portrait=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,500),landscape=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,300);m.updateAppWidget(id,new RemoteViews(build(c,id,day,data,landscape),build(c,id,day,data,portrait)));
 }}
 static RemoteViews build(Context c,int id,LocalDate day,JSONObject data,int height){
  RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_today);LocalDate today=LocalDate.now(Event.ZONE);boolean compact=height<360;if(compact){v.setViewVisibility(R.id.widget_brand,android.view.View.GONE);v.setViewVisibility(R.id.widget_subtitle,android.view.View.GONE);v.setViewVisibility(R.id.widget_meta,android.view.View.GONE);}
  v.setTextViewText(R.id.widget_title,day.format(DateTimeFormatter.ofPattern("d MMMM",new Locale("ru"))));v.setTextViewText(R.id.widget_subtitle,(day.equals(today)?"Сегодня · ":"")+day.format(DateTimeFormatter.ofPattern("EEEE",new Locale("ru")))+" ▾");
  v.setOnClickPendingIntent(R.id.widget_prev,navigation(c,id,-1));v.setOnClickPendingIntent(R.id.widget_next,navigation(c,id,1));v.setOnClickPendingIntent(R.id.widget_today,navigation(c,id,0));v.setOnClickPendingIntent(R.id.widget_date,open(c,id,day,null,true));
  List<Event> events=new ArrayList<>();for(Event e:Sync.visible(data,false))if(e.on(day))events.add(e);
  Map<String,String> types=new HashMap<>();long last=Long.MAX_VALUE;int errors=0;boolean covered=true;JSONArray accounts=Store.array(data,"accounts");JSONObject statuses=Store.object(data,"statuses");long begin=day.atStartOfDay(Event.ZONE).toInstant().toEpochMilli(),finish=day.plusDays(1).atStartOfDay(Event.ZONE).toInstant().toEpochMilli();
  for(int i=0;i<accounts.length();i++){JSONObject a=accounts.optJSONObject(i);types.put(a.optString("id"),a.optString("type"));JSONObject s=statuses.optJSONObject(a.optString("id"));if(s==null||s.optLong("success")==0||!s.optString("error").isEmpty())errors++;if(s!=null&&s.optLong("success")>0)last=Math.min(last,s.optLong("success"));if(s==null||s.optLong("rangeStart",Long.MAX_VALUE)>begin||s.optLong("rangeEnd",0)<finish)covered=false;}
  v.setTextViewText(R.id.widget_meta,events.size()+" встреч · Москва UTC+3"+(errors>0?" · есть ошибки":""));v.setTextViewText(R.id.widget_status,(last==Long.MAX_VALUE?"Нет загруженных данных":"Обновлено "+Instant.ofEpochMilli(last).atZone(Event.ZONE).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")))+(!covered?" · неполный кэш":""));v.removeAllViews(R.id.widget_events);
  if(events.isEmpty()){note(c,v,accounts.length()==0?"Подключите календарь":!covered?"Этот день ещё не загружен":errors>0?"Нет встреч в кэше. Проверьте источники":"Время для себя\nНа этот день встреч нет",open(c,id,day,null,false));return v;}
  float scale=Math.max(1,c.getResources().getConfiguration().fontScale);WidgetPlan plan=new WidgetPlan(events,day,Math.max(50,height-(int)((compact?120:180)*scale)),scale);int cursor=plan.start;
  for(WidgetPlan.Group g:plan.groups){if(!compact&&!g.allDay&&g.start>cursor)gap(c,v,cursor,g.start);RemoteViews row=new RemoteViews(c.getPackageName(),R.layout.widget_row);row.setTextViewText(R.id.row_hour,g.allDay?"День":WidgetPlan.time(g.start));row.removeAllViews(R.id.row_cards);
   if(g.summary){RemoteViews card=new RemoteViews(c.getPackageName(),R.layout.widget_event);card.setTextViewText(R.id.event_time,g.allDay?"Весь день и встречи":WidgetPlan.time(g.start)+"–"+WidgetPlan.time(g.end));card.setTextViewText(R.id.event_title,g.events.size()+" встреч · открыть день");card.setOnClickPendingIntent(R.id.event_root,open(c,id,day,null,false));row.addView(R.id.row_cards,card);}
   else for(Event e:g.events){boolean shortEvent=!e.allDay&&e.end-e.start<30*60000L;RemoteViews card=new RemoteViews(c.getPackageName(),shortEvent?R.layout.widget_short:R.layout.widget_event);card.setInt(R.id.event_root,"setMinimumHeight",(int)(g.height()*scale*c.getResources().getDisplayMetrics().density));card.setTextViewText(R.id.event_time,e.allDay?"Весь день":Event.time(e.start)+"–"+Event.time(e.end));card.setTextViewText(R.id.event_title,e.title);if(g.events.size()>1&&!shortEvent)card.setInt(R.id.event_title,"setMaxLines",2);String type=types.get(e.source);card.setInt(R.id.event_root,"setBackgroundResource","yandex".equals(type)?R.drawable.widget_personal:"device".equals(type)?R.drawable.widget_device:R.drawable.widget_work);card.setContentDescription(R.id.event_root,(e.allDay?"Весь день":Event.time(e.start)+"–"+Event.time(e.end))+" "+e.title);card.setOnClickPendingIntent(R.id.event_root,open(c,id,day,e.key(),false));row.addView(R.id.row_cards,card);}
   v.addView(R.id.widget_events,row);if(!g.allDay)cursor=g.end;
  }
  if(!compact){if(cursor<plan.end)gap(c,v,cursor,plan.end);note(c,v,WidgetPlan.time(plan.end),null);}return v;
 }
 static void gap(Context c,RemoteViews v,int from,int to){RemoteViews gap=new RemoteViews(c.getPackageName(),R.layout.widget_gap);int delta=to-from;String duration=delta>=60?delta/60+" ч"+(delta%60>0?" "+delta%60+" мин":""):delta+" мин";gap.setTextViewText(R.id.gap_text,(delta>=60?"≈ ":"")+WidgetPlan.time(from)+"–"+WidgetPlan.time(to)+" · "+duration+" свободно");v.addView(R.id.widget_events,gap);}
 static void note(Context c,RemoteViews v,String text,PendingIntent action){RemoteViews note=new RemoteViews(c.getPackageName(),R.layout.widget_note);note.setTextViewText(R.id.note_text,text);if(action!=null)note.setOnClickPendingIntent(R.id.note_text,action);v.addView(R.id.widget_events,note);}
}
