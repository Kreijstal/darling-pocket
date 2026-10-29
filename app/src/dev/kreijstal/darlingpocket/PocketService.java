package dev.kreijstal.darlingpocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keeps Darling's container and the display alive while the screen's
 * activity comes and goes: the display server and every app run in this
 * process, as a foreground service.
 */
public final class PocketService extends Service {
    static final String TAG = "DarlingPocket";
    static final String ACTION_LAUNCH = "dev.kreijstal.darlingpocket.LAUNCH";
    static final String ACTION_STOP = "dev.kreijstal.darlingpocket.STOP";
    /** The app's executable, as the guest names it. */
    static final String EXTRA_EXECUTABLE = "executable";

    private static final String CHANNEL = "container";

    private Runtime runtime;
    private Process running;
    private String runningName;

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = new Runtime(this);
        startForeground(1, notification("Starting"));
        installConfiguration();
        startDisplay(this);
    }

    /** Starts the display at the phone's size; the screen's activity corrects it to the view's. */
    static void startDisplay(Context context) {
        Runtime runtime = new Runtime(context);
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        if (!PocketDisplay.start(runtime.display.getAbsolutePath(), metrics.widthPixels, metrics.heightPixels,
                scaleMilli(metrics.density), 0, 0, 0, 0))
            Log.e(TAG, "The display cannot listen on " + runtime.display);
    }

    /**
     * iOS apps expect a whole scale (their images come at @2x and @3x), so the
     * phone's density is rounded.
     */
    static int scaleMilli(float density) {
        return Math.max(1, Math.min(3, Math.round(density))) * 1000;
    }

    // fontconfig's file, from the APK.
    private void installConfiguration() {
        File target = new File(runtime.files, "etc/fonts/fonts.conf");
        try (InputStream in = getAssets().open("etc/fonts/fonts.conf")) {
            Runtime.ensureFile(in, target);
        } catch (IOException e) {
            Log.e(TAG, "Cannot install fonts.conf", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_LAUNCH.equals(intent.getAction()))
            launch(intent.getStringExtra(EXTRA_EXECUTABLE));
        else if (intent != null && ACTION_STOP.equals(intent.getAction()))
            stopApp();
        return START_STICKY;
    }

    private synchronized void launch(String executable) {
        if (executable == null || !runtime.installed())
            return;
        if (running != null && running.isAlive()) {
            if (executable.equals(runningName))
                return; // already there
            stopApp();
        }
        File logs = new File(runtime.files, "logs");
        logs.mkdirs();
        String name = new File(executable).getName();
        File log = new File(logs, name + ".log");
        try {
            ProcessBuilder builder = runtime.shell(executable);
            builder.redirectOutput(log);
            running = builder.start();
            runningName = executable;
            Log.i(TAG, "Started " + executable + ", output in " + log);
            updateNotification(name);
            watch(running, name);
        } catch (IOException e) {
            Log.e(TAG, "Cannot start " + executable, e);
            appendLog(log, "Cannot start: " + e);
        }
    }

    private void watch(final Process process, final String name) {
        Thread thread = new Thread(() -> {
            try {
                int status = process.waitFor();
                Log.i(TAG, name + " exited with " + status);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (PocketService.this) {
                if (running == process) {
                    running = null;
                    runningName = null;
                    updateNotification(null);
                }
            }
        }, "watch " + name);
        thread.setDaemon(true);
        thread.start();
    }

    private synchronized void stopApp() {
        if (running == null)
            return;
        // `darling shell` is only the client: the container has to stop the app.
        running.destroy();
        running = null;
        runningName = null;
        try {
            runtime.shutdown().start().waitFor();
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "darling shutdown failed", e);
        }
        updateNotification(null);
    }

    private static void appendLog(File log, String line) {
        try (OutputStream out = new FileOutputStream(log, true)) {
            out.write((line + "\n").getBytes("UTF-8"));
        } catch (IOException e) {
            // nothing more to do
        }
    }

    private void updateNotification(String app) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(1, notification(app == null ? "No app running" : app + " is running"));
    }

    @SuppressWarnings("deprecation")
    private Notification notification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Container", NotificationManager.IMPORTANCE_LOW));
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent open = new Intent(this, ScreenActivity.class);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        return builder.setContentTitle("Darling Pocket")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, immutable))
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        stopApp();
        PocketDisplay.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
