package com.bubbleladder.shapeaiv2;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoDrawService extends Service{
    public static final String CHANNEL_ID="bubble_shape_ai_v2_live";
    public static final int NOTI_ID=5202;
    private final Handler h=new Handler(Looper.getMainLooper());
    private final ExecutorService ex=Executors.newSingleThreadExecutor();
    private boolean syncing=false; private int retry=0;
    private static final int MAX_LATE_RETRY=12; private static final long LATE_RETRY_MS=10000L;
    private final Runnable notificationTick=new Runnable(){@Override public void run(){updateNotification();long left=FlowCore.millisToNextDraw();h.postDelayed(this,left<=30000L?1000L:5000L);}};
    private final Runnable fetchTask=new Runnable(){@Override public void run(){doSync();}};

    @Override public void onCreate(){super.onCreate();createChannel();startForeground(NOTI_ID,buildNotification());h.post(notificationTick);h.post(fetchTask);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){if(!FlowCore.prefs(this).getBoolean(FlowCore.K_AUTO,true)){stopSelf();return START_NOT_STICKY;}if(!syncing){h.removeCallbacks(fetchTask);h.post(fetchTask);}return START_STICKY;}
    private void doSync(){if(syncing)return;syncing=true;ex.execute(()->{boolean advanced=false;try{advanced=FlowCore.sync(this).newRoundResolved;}catch(Exception ignored){}final boolean ok=advanced;h.post(()->{syncing=false;sendBroadcast(new Intent(FlowCore.ACTION_UPDATED).setPackage(getPackageName()));updateNotification();h.removeCallbacks(fetchTask);if(ok){retry=0;scheduleAtNextDraw();}else if(retry<MAX_LATE_RETRY){retry++;h.postDelayed(fetchTask,LATE_RETRY_MS);}else{retry=0;scheduleAtNextDraw();}});});}
    private void scheduleAtNextDraw(){h.postDelayed(fetchTask,FlowCore.millisToNextDraw()+7000L);}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"Shape AI V2 자동추첨",NotificationManager.IMPORTANCE_LOW);ch.setDescription("보글사다리 모양 AI 최고1픽과 다음 추첨 시간을 표시합니다.");getSystemService(NotificationManager.class).createNotificationChannel(ch);}}
    private Notification buildNotification(){Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);android.content.SharedPreferences sp=FlowCore.prefs(this);String pick=sp.getString(FlowCore.K_LAST_PICK,"분석 대기");String text="다음 "+FlowCore.countdownText()+" · 최고1픽 "+pick+" · 실전 "+FlowCore.liveRate(this);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);return b.setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("보글사다리 Shape AI V2 · 백그라운드 ON").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build();}
    private void updateNotification(){((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTI_ID,buildNotification());}
    @Override public void onDestroy(){h.removeCallbacksAndMessages(null);ex.shutdownNow();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
