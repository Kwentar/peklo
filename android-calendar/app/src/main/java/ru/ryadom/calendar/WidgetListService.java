package ru.ryadom.calendar;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.time.LocalDate;
import java.util.*;
/** Collection adapter for Android 8–11; Android 12+ receives inline collection items. */
public class WidgetListService extends RemoteViewsService {
 @Override public RemoteViewsFactory onGetViewFactory(Intent intent){
  LocalDate day=LocalDate.parse(intent.getStringExtra("day"));
  return new RemoteViewsFactory(){
   List<RemoteViews> rows=Collections.emptyList();
   public void onCreate(){onDataSetChanged();}
   public void onDataSetChanged(){rows=TodayWidget.rows(WidgetListService.this,day,Store.read(WidgetListService.this),intent.getIntExtra("available",0));}
   public void onDestroy(){}
   public int getCount(){return rows.size();}
   public RemoteViews getViewAt(int position){return position>=0&&position<rows.size()?rows.get(position):null;}
   public RemoteViews getLoadingView(){return null;}
   public int getViewTypeCount(){return 4;}
   public long getItemId(int position){return position;}
   public boolean hasStableIds(){return false;}
  };
 }
}
