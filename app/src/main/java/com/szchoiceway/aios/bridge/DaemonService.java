package com.szchoiceway.aios.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.widget.Toast;

import com.szchoiceway.aios.bridge.receiver.PhoneBookReceive;

import androidx.core.app.ActivityOptionsCompat;
import androidx.core.app.NotificationCompat;

import static com.szchoiceway.aios.bridge.util.AppUtil.getAppActivityName;

//https://developer.android.com/guide/components/services
//https://github.com/madebyfrancisco/SplitScreenLauncher
public class DaemonService extends Service {
    private static final String CHANNEL_ID = "mynotificationchannel";

    public static final String LAUNCH_APPS = "launch_apps";
    public static final String BOOT = "boot";

    public static final String WINDOW_MODE = "android.activity.windowingMode";
    public static final int SPLIT_PRIMARY = 3;
    public static final int SPLIT_SECONDARY = 4;
    public static final String CREATE_MODE = "android:activity.splitScreenCreateMode";
    public static final int SPLIT_TOP = 0;
    public static final int SPLIT_BOTTOM=1;

    private class Launcher implements Runnable {
        private Context ctx;
        private boolean boot;

        public Launcher(Context context, boolean boot){
            this.ctx = context;
            this.boot = boot;
        }

        @Override
        public void run(){
            startApps(ctx, boot);
        }
    }

    PhoneBookReceive receive = null;

    @Override
    public void onCreate() {
        super.onCreate();
        Data.addLogData(getApplicationContext(), "DaemonService.onCreate().");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Data.addLogData(getApplicationContext(), "DaemonService.onStartCommand().");
        Toast.makeText(this, "service starting", Toast.LENGTH_SHORT).show();

        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Foreground Service Started")
                .setTicker("This is a ticker")
                .setContentText("Click to start App")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();

        startForeground(1001, notification);


        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);

        if (receive == null) {
            receive = new PhoneBookReceive();
            getApplicationContext().registerReceiver(receive, intentFilter);
        }

        if (null != intent) {
            boolean launch = intent.getBooleanExtra(LAUNCH_APPS, false);
            if (launch) {
                boolean boot = intent.getBooleanExtra(BOOT, true);
                Launcher l = new Launcher(getApplicationContext(), boot);
                new Thread(l).start();
            }
        }

        // If we get killed, after returning from here, restart
        return START_STICKY;
    }


    public DaemonService() {
        //createNotificationChannel();
        //Data.addData(this, "DaemonService constructor.");
    }

    private void startApps(Context context, boolean boot){
        long allDelay = 0;
        if (boot){
            allDelay = Data.getLongPreference(context, "delay_after_boot");
        } else {
            allDelay = Data.getLongPreference(context, "delay_after_fast_boot");
        }
        if (allDelay > 0) {
            allDelay = allDelay * 1000;
            try {
                Thread.sleep(allDelay);
            } catch (InterruptedException e) {
                Data.addLogData(context, "DaemonService --- Interrupted!");
            }
        }

        //start app
        Data.addLogData(context, "DaemonService -- Starting Apps!");

        String background_apps = Data.getPreference(context, Data.BACKGROUND_APPS);
        if (null != background_apps && !background_apps.isEmpty()){
            String[] apps = background_apps.split(App.APP_SEP);
            if (null != apps && apps.length > 0){
                long delay = 4;
                long tmp = Data.getLongPreference(context,"delay_after_app_launch");
                if (tmp > 0){
                    delay = tmp;
                }
                for (String app : apps) {
                    if (null != app && !app.isEmpty()) {
                        App tmpApp = new App(app);
                        startApplication(context, tmpApp, null, "DaemonService -- Launching Background App ");
                        //Give the background apps a little time to start before starting the foreground apps.
                        SystemClock.sleep(delay * 1000);
                    }
                }
            }
        }

        long afterServiceDelay = Data.getLongPreference(context, "delay_after_service_launch");

        String services = Data.getPreference(context, Data.SERVICE_APPS);
        if (null != services && !services.isEmpty()){
            String[] srvs = services.split(App.APP_SEP);
            if (null != srvs && srvs.length > 0){
                for (String ser : srvs){
                    if (null != ser && !ser.isEmpty()){
                        App tmpApp = new App(ser);
                        startService(context, tmpApp);
                        //Give the background apps a little time to start before starting the foreground apps.
                        if (afterServiceDelay > 0) {
                            SystemClock.sleep(afterServiceDelay * 1000);
                        }
                    }
                }
            }
        }


        String top = Data.getPreference(context, Data.TOP_APP);
        App topApp = new App(top);
        Bundle bundle = ActivityOptionsCompat.makeBasic().toBundle();
        if (bundle != null) {
            bundle.putInt(WINDOW_MODE, SPLIT_PRIMARY);
            bundle.putInt(CREATE_MODE, SPLIT_TOP);
        }
        startApplication(context, topApp, bundle, "DaemonService -- Launching Top App ");

        String bottom = Data.getPreference(context, Data.BOTTOM_APP);
        App btmApp = new App(bottom);
        bundle = ActivityOptionsCompat.makeBasic().toBundle();
        if (bundle != null) {
            bundle.putInt(WINDOW_MODE, SPLIT_SECONDARY);
            bundle.putInt(CREATE_MODE, SPLIT_BOTTOM);
        }
        startApplication(context, btmApp, bundle, "DaemonService -- Launching Bottom App ");
    }


    private void startService(Context ctx, App app){
        if (null != app && null != app.getPkg() && !app.getPkg().isEmpty() && null != app.getService() && !app.getService().isEmpty()){
            Intent in = new Intent();
            in.setComponent(new ComponentName(app.getPkg(), app.getService()));
            try {
                ComponentName cn = ctx.startService(in);
                Data.addLogData(ctx, "DaemonService.StartService " + app.getPrefString());
            } catch(Exception e){
                Data.addLogData(ctx, "Unable to start service(1): " + app.getPkg() + " - " + app.getService());
                Data.addLogData(ctx, "Unable to start service(2): " + e.getMessage());
            }
        }
    }

    //https://github.com/commonsguy/cw-omnibus/blob/master/Introspection/Launchalot/app/src/main/java/com/commonsware/android/launchalot/Launchalot.java
    //https://stackoverflow.com/questions/30446052/getlaunchintentforpackage-is-null-for-some-apps
    //https://github.com/SubhamTyagi/Last-Launcher/blob/master/app/src/main/java/io/github/subhamtyagi/lastlauncher/LauncherActivity.java
    private void startApplication(Context ctx, App app, Bundle bundle, String msg){
        if (null != app && null != app.getPkg() && !app.getPkg().isEmpty() && null != ctx) {
            ComponentName cn;
            if (null != app.getActivityName()){
                cn = new ComponentName(app.getPkg(), app.getActivityName());
            } else {
                String actName = getAppActivityName(ctx.getPackageManager(), app.getPkg());
                cn = new ComponentName(app.getPkg(), actName);
            }
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            i.setComponent(cn);
            if (null == bundle){
                ctx.startActivity(i);
            } else {
                ctx.startActivity(i, bundle);
            }
            Data.addLogData(ctx, msg);
            Data.addLogData(ctx, "DaemonService.StartApplication " + app.getPrefString());
        } else {
            Data.addLogData(ctx, "DaemonService.StartApplication called with null/empty package.");
        }
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        Data.addLogData(getApplicationContext(), "DaemonService destructor.");
        Toast.makeText(this, "service done", Toast.LENGTH_SHORT).show();
        if (null != receive) {
            unregisterReceiver(receive);
            receive = null;
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        Data.addLogData(getApplicationContext(), "DaemonService.onBind().");
        // We don't provide binding, so return null
        return null;
    }
}
