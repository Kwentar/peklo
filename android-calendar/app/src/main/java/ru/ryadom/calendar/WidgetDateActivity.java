package ru.ryadom.calendar;
import android.app.*;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import java.time.LocalDate;
/** A system date picker launched directly from the widget PendingIntent. */
public class WidgetDateActivity extends Activity {
 DatePickerDialog picker;
 @Override public void onCreate(Bundle state){super.onCreate(state);int id=getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);if(id<0){finish();return;}LocalDate day=TodayWidget.selectedDay(this,id);DatePickerDialog dialog=picker=new DatePickerDialog(this,(view,y,m,d)->{TodayWidget.selectDay(this,id,LocalDate.of(y,m+1,d));finish();},day.getYear(),day.getMonthValue()-1,day.getDayOfMonth());dialog.setOnCancelListener(d->finish());dialog.setOnDismissListener(d->finish());dialog.show();}
}
