package ru.ryadom.calendar;
import android.content.*;
public class AlarmReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){PendingResult result=goAsync();new Thread(()->{try{if(Notices.SUMMARY.equals(i.getAction()))Notices.summary(c);else Notices.deliverReminders(c);Notices.schedule(c);}finally{result.finish();}}).start();}
}
