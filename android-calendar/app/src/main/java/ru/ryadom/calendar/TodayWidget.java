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
 public static void refresh(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);int[] ids=m.getAppWidgetIds(new ComponentName(c,TodayWidget.class));if(ids.length>0){render(c,m,ids);if(Build.VERSION.SDK_INT<31)m.notifyAppWidgetViewDataChanged(ids,R.id.widget_events);}}
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
  v.setOnClickPendingIntent(R.id.widget_root,open(c,id,day,null,false));v.setOnClickPendingIntent(R.id.widget_blank,open(c,id,day,null,false));
  v.setOnClickPendingIntent(R.id.widget_prev,navigation(c,id,-1));v.setOnClickPendingIntent(R.id.widget_next,navigation(c,id,1));v.setOnClickPendingIntent(R.id.widget_today,navigation(c,id,0));v.setOnClickPendingIntent(R.id.widget_date,open(c,id,day,null,true));
  List<Event> events=new ArrayList<>();for(Event e:Sync.visible(data,false))if(e.on(day))events.add(e);
  Map<String,String> types=new HashMap<>();long last=Long.MAX_VALUE;int errors=0;boolean covered=true;JSONArray accounts=Store.array(data,"accounts");JSONObject statuses=Store.object(data,"statuses");long begin=day.atStartOfDay(Event.ZONE).toInstant().toEpochMilli(),finish=day.plusDays(1).atStartOfDay(Event.ZONE).toInstant().toEpochMilli();
  for(int i=0;i<accounts.length();i++){JSONObject a=accounts.optJSONObject(i);types.put(a.optString("id"),a.optString("type"));JSONObject s=statuses.optJSONObject(a.optString("id"));if(s==null||s.optLong("success")==0||!s.optString("error").isEmpty())errors++;if(s!=null&&s.optLong("success")>0)last=Math.min(last,s.optLong("success"));if(s==null||s.optLong("rangeStart",Long.MAX_VALUE)>begin||s.optLong("rangeEnd",0)<finish)covered=false;}
  v.setTextViewText(R.id.widget_meta,events.size()+" встреч · Москва UTC+3"+(errors>0?" · есть ошибки":""));v.setTextViewText(R.id.widget_status,(last==Long.MAX_VALUE?"Нет загруженных данных":"Обновлено "+Instant.ofEpochMilli(last).atZone(Event.ZONE).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")))+(!covered?" · неполный кэш":""));
  if(Build.VERSION.SDK_INT>=31){
   RemoteViews.RemoteCollectionItems.Builder items=new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(false).setViewTypeCount(4);
   List<RemoteViews> rows=rows(c,day,data,height-(compact?94:120));for(int i=0;i<rows.size();i++)items.addItem(i,rows.get(i));v.setRemoteAdapter(R.id.widget_events,items.build());
  }else{
   Intent adapter=new Intent(c,WidgetListService.class).putExtra("day",day.toString()).putExtra("available",height-(compact?94:120)).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("peklo://widget-list/"+id+"/"+day+"/"+height));v.setRemoteAdapter(R.id.widget_events,adapter);
  }
  Intent click=new Intent(c,MainActivity.class).setData(Uri.parse("peklo://widget-click/"+id+"/"+day)).putExtra("day",day.toString());
  v.setPendingIntentTemplate(R.id.widget_events,PendingIntent.getActivity(c,0,click,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0)));
  return v;
 }
 static List<RemoteViews> rows(Context c,LocalDate day,JSONObject data){return rows(c,day,data,0);}
 static List<RemoteViews> rows(Context c,LocalDate day,JSONObject data,int available){
  float used=0;
  List<RemoteViews> rows=new ArrayList<>();List<Event> events=new ArrayList<>();for(Event e:Sync.visible(data,false))if(e.on(day))events.add(e);
  if(events.isEmpty()){RemoteViews note=new RemoteViews(c.getPackageName(),R.layout.widget_note);note.setTextViewText(R.id.note_text,Store.array(data,"accounts").length()==0?"Подключите календарь":"В кэше этого дня нет встреч");note.setOnClickFillInIntent(R.id.note_text,new Intent().putExtra("day",day.toString()));rows.add(note);if(available>54)rows.add(spacer(c,available-54,day));return rows;}
  float scale=Math.max(1,c.getResources().getConfiguration().fontScale);WidgetPlan plan=new WidgetPlan(events,day,Integer.MAX_VALUE,scale);int cursor=plan.start;
  for(WidgetPlan.Group g:plan.groups){
   if(!g.allDay&&g.start>cursor){RemoteViews gap=gap(c,cursor,g.start);gap.setOnClickFillInIntent(R.id.gap_text,new Intent().putExtra("day",day.toString()));rows.add(gap);used+=14*scale;}
   if(g.events.size()>1){OverlapPlan overlap=new OverlapPlan(g.events);rows.add(overlapRow(c,day,data,g,overlap,scale));used+=overlap.height*54*scale+2;cursor=Math.max(cursor,g.end);continue;}
   Event e=g.events.get(0);RemoteViews row=new RemoteViews(c.getPackageName(),R.layout.widget_row);row.setTextViewText(R.id.row_hour,g.allDay?"День":WidgetPlan.time(g.start));
   boolean shortEvent=!e.allDay&&e.end-e.start<30*60000L;RemoteViews card=new RemoteViews(c.getPackageName(),shortEvent?R.layout.widget_short:R.layout.widget_event);
   card.setInt(R.id.event_root,"setMinimumHeight",(int)(g.height()*scale*c.getResources().getDisplayMetrics().density));
   card.setTextViewText(R.id.event_time,(e.allDay?"Весь день":Event.time(e.start)+"–"+Event.time(e.end))+(!shortEvent?" · "+CalendarStyle.name(data,e.source,e.calendar):""));card.setTextViewText(R.id.event_title,e.title);
   card.setInt(R.id.event_root,"setBackgroundResource",CalendarStyle.DRAWABLES[CalendarStyle.color(data,e.source,e.calendar)]);
   card.setContentDescription(R.id.event_root,CalendarStyle.name(data,e.source,e.calendar)+" · "+(e.allDay?"Весь день":Event.time(e.start)+"–"+Event.time(e.end))+" "+e.title);
   row.removeAllViews(R.id.row_cards);row.addView(R.id.row_cards,card);row.setOnClickFillInIntent(R.id.row_root,new Intent().putExtra("day",day.toString()).putExtra("event",e.key()));rows.add(row);used+=g.height()*scale+2;
   if(!g.allDay)cursor=Math.max(cursor,g.end);
  }
  if(cursor<plan.end){RemoteViews gap=gap(c,cursor,plan.end);gap.setOnClickFillInIntent(R.id.gap_text,new Intent().putExtra("day",day.toString()));rows.add(gap);used+=14*scale;}if(available>used)rows.add(spacer(c,available-used,day));return rows;
 }
 static RemoteViews spacer(Context c,float height,LocalDate day){RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget_spacer);v.setInt(R.id.widget_spacer,"setHeight",Math.max(1,Math.round(height*c.getResources().getDisplayMetrics().density)));v.setOnClickFillInIntent(R.id.widget_spacer,new Intent().putExtra("day",day.toString()));return v;}
 static RemoteViews overlapRow(Context c,LocalDate day,JSONObject data,WidgetPlan.Group group,OverlapPlan plan,float scale){
  RemoteViews row=new RemoteViews(c.getPackageName(),R.layout.widget_row);row.setTextViewText(R.id.row_hour,WidgetPlan.time(group.start));row.removeAllViews(R.id.row_cards);row.setOnClickFillInIntent(R.id.row_root,new Intent().putExtra("day",day.toString()));float unit=54*scale,density=c.getResources().getDisplayMetrics().density;
  for(int lane=0;lane<plan.columns;lane++){RemoteViews column=new RemoteViews(c.getPackageName(),R.layout.widget_column);column.removeAllViews(R.id.overlap_column);float cursor=0;
   for(OverlapPlan.Placement p:plan.items)if(p.lane==lane){if(p.top>cursor)column.addView(R.id.overlap_column,spacer(c,(p.top-cursor)*unit,day));Event e=p.event;RemoteViews card=new RemoteViews(c.getPackageName(),R.layout.widget_overlap_card);String time=Event.time(e.start)+"–"+Event.time(e.end);android.text.SpannableString label=new android.text.SpannableString(time+"\n"+e.title);label.setSpan(new android.text.style.RelativeSizeSpan(.75f),0,time.length(),0);card.setTextViewText(R.id.overlap_card,label);card.setInt(R.id.overlap_card,"setHeight",Math.max(1,Math.round(p.height*unit*density)));card.setInt(R.id.overlap_card,"setBackgroundResource",CalendarStyle.DRAWABLES[CalendarStyle.color(data,e.source,e.calendar)]);card.setContentDescription(R.id.overlap_card,time+" "+e.title);card.setOnClickFillInIntent(R.id.overlap_card,new Intent().putExtra("day",day.toString()).putExtra("event",e.key()));column.addView(R.id.overlap_column,card);cursor=p.top+p.height;}
   if(cursor<plan.height)column.addView(R.id.overlap_column,spacer(c,(plan.height-cursor)*unit,day));row.addView(R.id.row_cards,column);
  }return row;
 }
 static RemoteViews gap(Context c,int from,int to){RemoteViews gap=new RemoteViews(c.getPackageName(),R.layout.widget_gap);int delta=to-from;String duration=delta>=60?delta/60+" ч"+(delta%60>0?" "+delta%60+" мин":""):delta+" мин";gap.setTextViewText(R.id.gap_text,WidgetPlan.time(from)+"–"+WidgetPlan.time(to)+" · "+duration+" свободно");return gap;}
}
