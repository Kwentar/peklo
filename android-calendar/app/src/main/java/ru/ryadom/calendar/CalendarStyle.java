package ru.ryadom.calendar;
import org.json.*;
final class CalendarStyle {
 static final String[] COLORS={"Шалфей","Лаванда","Песок","Небо","Роза","Мята","Оранжевый"};
 static final int[] FILLS={0xffe6efdf,0xffeee9fa,0xfffff3dc,0xffe1edf7,0xfff7e3e7,0xffdff0e9,0xffffdfc2};
 static final int[] ACCENTS={0xff7d9b68,0xff9d84bc,0xffc59745,0xff6c98bd,0xffbf7e8f,0xff65a58c,0xffdf792b};
 static final int[] DRAWABLES={R.drawable.widget_work,R.drawable.widget_device,R.drawable.widget_personal,R.drawable.widget_sky,R.drawable.widget_rose,R.drawable.widget_mint,R.drawable.widget_orange};
 static JSONObject value(JSONObject data,String source,String calendar){JSONObject value=Store.object(data,"calendarStyles").optJSONObject(source+"|"+calendar);return value==null?new JSONObject():value;}
 static int color(JSONObject data,String source,String calendar){int fallback=0;JSONArray accounts=Store.array(data,"accounts");for(int i=0;i<accounts.length();i++){JSONObject a=accounts.optJSONObject(i);if(source.equals(a.optString("id")))fallback="device".equals(a.optString("type"))?1:"yandex".equals(a.optString("type"))?2:0;}int result=value(data,source,calendar).optInt("color",fallback);return result>=0&&result<FILLS.length?result:fallback;}
 static String name(JSONObject data,String source,String calendar){JSONObject status=Store.object(data,"statuses").optJSONObject(source);JSONObject calendars=status==null?null:status.optJSONObject("calendars");String original=calendars==null?"Календарь":calendars.optString(calendar,"Календарь");String alias=value(data,source,calendar).optString("name").trim();return alias.isEmpty()?original:alias;}
}
