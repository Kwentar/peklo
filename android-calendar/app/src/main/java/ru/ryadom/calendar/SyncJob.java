package ru.ryadom.calendar;
import android.app.job.*;import android.content.*;
public class SyncJob extends JobService {
 public static void register(Context c){JobInfo info=new JobInfo.Builder(60,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60000L).setPersisted(true).build();c.getSystemService(JobScheduler.class).schedule(info);}
 @Override public boolean onStartJob(JobParameters params){new Thread(()->{Sync.run(getApplicationContext());jobFinished(params,false);}).start();return true;}
 @Override public boolean onStopJob(JobParameters params){return true;}
}
