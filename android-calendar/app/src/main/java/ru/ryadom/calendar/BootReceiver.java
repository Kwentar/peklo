package ru.ryadom.calendar;
import android.content.*;
public class BootReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context c,Intent i){PendingResult pending=goAsync();new Thread(()->{try{SyncJob.register(c);Notices.channels(c);Notices.schedule(c);TodayWidget.refresh(c);}catch(Exception ignored){}finally{pending.finish();}}).start();}
}
