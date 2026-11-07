package ru.ryadom.calendar;
import android.app.job.*;import android.content.*;
public class SyncJob extends JobService {
 public static void watch(Context c){if(c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;JobInfo job=new JobInfo.Builder(61,new ComponentName(c,SyncJob.class)).addTriggerContentUri(new JobInfo.TriggerContentUri(android.provider.CalendarContract.CONTENT_URI,JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)).setTriggerContentUpdateDelay(1000).setTriggerContentMaxDelay(5000).build();c.getSystemService(JobScheduler.class).schedule(job);}
 public static void register(Context c){watch(c);JobInfo info=new JobInfo.Builder(60,new ComponentName(c,SyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60000L).setPersisted(true).build();c.getSystemService(JobScheduler.class).schedule(info);}
 @Override public boolean onStartJob(JobParameters params){new Thread(()->{Sync.run(getApplicationContext());jobFinished(params,false);if(params.getJobId()==61)watch(getApplicationContext());}).start();return true;}
 @Override public boolean onStopJob(JobParameters params){return true;}
}
