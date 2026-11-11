package ru.ryadom.calendar;

import android.app.Dialog;
import android.content.*;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Fresh read on open/refresh; selection survives filtering and refresh. */
final class DeviceCalendarPicker {
 final MainActivity a;
 final Set<String> selected=new HashSet<>();
 final List<DeviceCalendars.Entry> rows=new ArrayList<>(),filtered=new ArrayList<>();
 final TextView status;final EditText search;final ListView list;
 final Button diagnostic,refresh,add;final Dialog dialog;
 ArrayAdapter<String> adapter;CancellationSignal pending;boolean busy;
 String readTime="";
 DeviceCalendarPicker(MainActivity activity){
  a=activity;dialog=new Dialog(a);LinearLayout content=surface("Календари телефона");
  status=a.label("Читаем календари телефона…",12,0xff59644f,false);status.setTag("calendar-count");content.addView(status);a.gap(content,8);
  search=new EditText(a);search.setSingleLine(true);search.setTextSize(15);search.setHint("Найти по названию");search.setTag("calendar-search");content.addView(search,new LinearLayout.LayoutParams(-1,-2));
  LinearLayout tools=new LinearLayout(a);diagnostic=action("Диагностика",this::diagnose);diagnostic.setTag("calendar-diagnostics");refresh=action("Перечитать",this::reload);tools.addView(refresh,new LinearLayout.LayoutParams(0,a.dp(44),1));tools.addView(diagnostic,new LinearLayout.LayoutParams(0,a.dp(44),1));content.addView(tools);a.gap(content,8);
  list=new ListView(a);list.setTag("calendar-list");list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);list.setVerticalScrollBarEnabled(true);list.setScrollbarFadingEnabled(false);
  adapter=new ArrayAdapter<String>(a,android.R.layout.simple_list_item_multiple_choice,new ArrayList<>()){
   @Override public View getView(int position,View old,ViewGroup parent){CheckedTextView view=(CheckedTextView)super.getView(position,old,parent);view.setTextSize(14);view.setMaxLines(4);view.setEllipsize(TextUtils.TruncateAt.END);view.setPadding(a.dp(8),a.dp(10),a.dp(8),a.dp(10));return view;}
  };list.setAdapter(adapter);content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout footer=new LinearLayout(a);footer.addView(action("Закрыть",dialog::dismiss),new LinearLayout.LayoutParams(0,a.dp(48),1));add=action("Добавить",this::add);footer.addView(add,new LinearLayout.LayoutParams(0,a.dp(48),1));content.addView(footer);
  list.setOnItemClickListener((parent,view,position,id)->{String key=filtered.get(position).id;if(list.isItemChecked(position))selected.add(key);else selected.remove(key);});
  search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){filter();}public void afterTextChanged(Editable e){}});
  dialog.setContentView(content);dialog.setOnDismissListener(d->{if(pending!=null)pending.cancel();});
 }
 LinearLayout surface(String title){LinearLayout layout=a.column();layout.setPadding(a.dp(16),a.dp(16),a.dp(16),a.dp(8));layout.setBackgroundColor(0xfffaf8f4);layout.addView(a.label(title,20,0xff282724,true));a.gap(layout,12);return layout;}
 Button action(String title,Runnable click){Button b=a.button(title,click);b.setSingleLine(true);b.setHorizontallyScrolling(false);b.setPadding(a.dp(4),0,a.dp(4),0);b.setAutoSizeTextTypeUniformWithConfiguration(10,13,1,android.util.TypedValue.COMPLEX_UNIT_SP);return b;}
 void size(Dialog d){Window w=d.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setLayout(Math.min(a.dp(520),a.getResources().getDisplayMetrics().widthPixels-a.dp(24)),Math.min(a.dp(700),(int)(a.getResources().getDisplayMetrics().heightPixels*.85f)));w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}}
 void show(){dialog.show();size(dialog);reload();}
 void filter(){
  filtered.clear();adapter.clear();String q=search.getText().toString();
  for(DeviceCalendars.Entry e:rows)if(e.matches(q)){filtered.add(e);adapter.add(e.name+(e.account.isEmpty()||e.name.equals(e.account)?"":"\n"+e.account));}
  list.clearChoices();for(int i=0;i<filtered.size();i++)list.setItemChecked(i,selected.contains(filtered.get(i).id));adapter.notifyDataSetChanged();
  if(!busy)status.setText("Найдено: "+rows.size()+" · показано: "+filtered.size()+"\nПрочитано в "+readTime);
 }
 void loading(boolean value){busy=value;diagnostic.setEnabled(!value);refresh.setEnabled(!value);add.setEnabled(!value);}
 void reload(){
  if(busy)return;pending=new CancellationSignal();CancellationSignal token=pending;loading(true);status.setText("Заново читаем системный список…");
  new Thread(()->{try{
   List<DeviceCalendars.Entry> result=DeviceCalendars.read(a.getContentResolver(),false,token);
   a.runOnUiThread(()->{if(!alive(token))return;rows.clear();for(DeviceCalendars.Entry e:result)if(!DeviceWrites.exported(a,e.id))rows.add(e);Set<String> present=new HashSet<>();for(DeviceCalendars.Entry e:rows)present.add(e.id);selected.retainAll(present);readTime=LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));loading(false);filter();});
  }catch(OperationCanceledException ignored){}catch(Exception e){a.runOnUiThread(()->{if(!alive(token))return;rows.clear();loading(false);filter();status.setText("Не удалось прочитать список. Откройте диагностику.");});}},"calendar-discovery").start();
 }
 boolean alive(CancellationSignal token){return !token.isCanceled()&&!a.isFinishing()&&!a.isDestroyed()&&dialog.isShowing();}
 void diagnose(){
  if(busy)return;String query=search.getText().toString();pending=new CancellationSignal();CancellationSignal token=pending;loading(true);status.setText("Проверяем список и доступность событий…");
  new Thread(()->{try{
   String text=DeviceCalendars.diagnose(a,query,token).text(query);
   a.runOnUiThread(()->{if(!alive(token))return;loading(false);filter();showReport(text);});
  }catch(OperationCanceledException ignored){}catch(Exception e){a.runOnUiThread(()->{if(!alive(token))return;loading(false);status.setText("Диагностика не завершилась. Проверьте разрешение на чтение календарей.");});}},"calendar-diagnostics").start();
 }
 void showReport(String text){
  Dialog report=new Dialog(a);LinearLayout content=surface("Диагностика");TextView body=a.text(text,13,0xff282724);body.setTextIsSelectable(true);ScrollView scroll=new ScrollView(a);scroll.addView(body);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));a.gap(content,8);
  LinearLayout footer=new LinearLayout(a);footer.addView(action("Закрыть",report::dismiss),new LinearLayout.LayoutParams(0,a.dp(48),1));footer.addView(action("Копировать",()->{a.getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Пекло: диагностика",text));a.toast("Отчёт скопирован");}),new LinearLayout.LayoutParams(0,a.dp(48),1));content.addView(footer);report.setContentView(content);report.show();size(report);
 }
 void add(){
  if(selected.isEmpty()){a.toast("Выберите календарь");return;}
  Set<String> existing=new HashSet<>();JSONArray all=Store.array(Store.read(a),"accounts");for(int i=0;i<all.length();i++){JSONObject account=all.optJSONObject(i);if("device".equals(account.optString("type"))){JSONArray ids=account.optJSONArray("calendarIds");if(ids!=null)for(int j=0;j<ids.length();j++)existing.add(ids.optString(j));}}JSONArray choice=new JSONArray();for(String id:selected)if(!existing.contains(id))choice.put(id);if(choice.length()==0){a.toast("Эти календари уже подключены");return;}
  Store.edit(a,data->{JSONArray accounts=Store.array(data,"accounts");accounts.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("type","device").put("calendarIds",choice).put("active",true));data.put("accounts",accounts);});dialog.dismiss();SyncJob.watch(a);a.render();Sync.async(a);
 }
}
