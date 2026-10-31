package dev.kreijstal.darlingpocket;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.FileObserver;
import android.os.PowerManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Alarms and other scheduled notifications of the iOS apps, on Android's
 * AlarmManager, so they come when Darling is not running, or the phone sleeps.
 *
 * <p>usernotificationsd writes what is coming to {@code files/alarms.txt}
 * ({@code $USERNOTIFICATIONS_SCHEDULE}; the format is in usernotificationsd.m).
 * The first firing still to come is set as an alarm. When it goes off and the
 * home screen runs, the daemon shows it (the phone is kept awake for it);
 * otherwise this posts it as an Android notification, which opens the home
 * screen. Darling's daemon then delivers it to the app on its next start.
 */
public final class Alarms extends BroadcastReceiver {
    static final String SCHEDULE = "alarms.txt";
    /** Like the daemon, a firing more than this late is not shown. */
    private static final long LATENESS_MS = 10 * 60 * 1000;
    /** How long the phone stays awake for the daemon: it looks at the clock at least once a minute. */
    private static final long AWAKE_MS = 65 * 1000;
    private static final String CHANNEL_ALARMS = "alarms", CHANNEL_NOTIFICATIONS = "notifications";
    private static final String STATE = "alarms", LAST_HANDLED = "lastHandled";

    private static FileObserver observer;

    /** One firing: a line of the schedule. */
    static final class Firing {
        final long time;
        final boolean alarm;
        final String bundle, identifier, title, body;

        Firing(long time, boolean alarm, String bundle, String identifier, String title, String body) {
            this.time = time;
            this.alarm = alarm;
            this.bundle = bundle;
            this.identifier = identifier;
            this.title = title;
            this.body = body;
        }
    }

    static File schedule(Context context) {
        return new File(context.getFilesDir(), SCHEDULE);
    }

    /** Sets the alarm again whenever the daemon writes the schedule. */
    static synchronized void watch(Context context) {
        final Context app = context.getApplicationContext();
        if (observer == null) {
            observer = new FileObserver(app.getFilesDir().getAbsolutePath(),
                    FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
                @Override
                public void onEvent(int event, String path) {
                    if (SCHEDULE.equals(path))
                        update(app);
                }
            };
            observer.startWatching();
        }
        update(app);
    }

    static List<Firing> read(File file) {
        List<Firing> firings = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            for (String line; (line = in.readLine()) != null;) {
                String[] fields = line.split("\t", -1);
                if (fields.length < 6)
                    continue;
                try {
                    firings.add(new Firing(Long.parseLong(fields[0]), "alarm".equals(fields[1]), unescape(fields[2]),
                            unescape(fields[3]), unescape(fields[4]), unescape(fields[5])));
                } catch (NumberFormatException e) {
                    // not a line of ours
                }
            }
        } catch (IOException e) {
            // no schedule: nothing is coming
        }
        return firings;
    }

    static String unescape(String field) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == '\\' && i + 1 < field.length()) {
                char next = field.charAt(++i);
                s.append(next == 't' ? '\t' : next == 'n' ? '\n' : next);
            } else {
                s.append(c);
            }
        }
        return s.toString();
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    /** Sets the alarm for the first firing after the ones already handled, or none. */
    static synchronized void update(Context context) {
        long after = Math.max(state(context).getLong(LAST_HANDLED, 0), System.currentTimeMillis() - 1000);
        Firing next = null;
        for (Firing firing : read(schedule(context)))
            if (firing.time > after && (next == null || firing.time < next.time))
                next = firing;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent fire = PendingIntent.getBroadcast(context, 0, new Intent(context, Alarms.class), immutable());
        if (next == null) {
            alarms.cancel(fire);
            return;
        }
        if (next.alarm) {
            // The status bar shows it as the next alarm, and it is exact even in Doze.
            alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(next.time, openScreen(context)), fire);
        } else {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.time, fire);
        }
        Log.i(PocketService.TAG, "Next notification: " + next.bundle + " " + next.identifier + " at " + next.time);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            update(context);
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences state = state(context);
        long handled = state.getLong(LAST_HANDLED, 0);
        boolean running = PocketService.homeScreenRunning();
        for (Firing firing : read(schedule(context))) {
            if (firing.time <= handled || firing.time > now + 1000)
                continue;
            handled = Math.max(handled, firing.time);
            if (!running && now - firing.time <= LATENESS_MS)
                post(context, firing);
        }
        state.edit().putLong(LAST_HANDLED, handled).apply();
        if (running) {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DarlingPocket:notification").acquire(AWAKE_MS);
        }
        update(context);
    }

    @SuppressWarnings("deprecation")
    private static void post(Context context, Firing firing) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = firing.alarm ? CHANNEL_ALARMS : CHANNEL_NOTIFICATIONS;
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            createChannels(manager);
            builder = new Notification.Builder(context, channel);
        } else {
            builder = new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH);
        }
        String title = firing.title.isEmpty() ? appName(context, firing.bundle) : firing.title;
        PendingIntent open = openScreen(context);
        builder.setContentTitle(title)
                .setContentText(firing.body)
                .setStyle(new Notification.BigTextStyle().bigText(firing.body))
                .setSubText(appName(context, firing.bundle))
                .setSmallIcon(firing.alarm ? android.R.drawable.ic_lock_idle_alarm : android.R.drawable.ic_popup_reminder)
                .setWhen(firing.time)
                .setShowWhen(true)
                .setContentIntent(open)
                .setAutoCancel(true);
        if (firing.alarm) {
            builder.setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(open, true);
            if (Build.VERSION.SDK_INT < 26)
                builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAudio());
        }
        Notification notification = builder.build();
        if (firing.alarm)
            notification.flags |= Notification.FLAG_INSISTENT; // rings until it is dismissed
        manager.notify((firing.bundle + "\n" + firing.identifier).hashCode(), notification);
        Log.i(PocketService.TAG, "Posted " + firing.bundle + " " + firing.identifier);
    }

    private static void createChannels(NotificationManager manager) {
        NotificationChannel alarms = new NotificationChannel(CHANNEL_ALARMS, "Alarms and timers",
                NotificationManager.IMPORTANCE_HIGH);
        alarms.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAudio());
        alarms.setBypassDnd(true);
        manager.createNotificationChannel(alarms);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_NOTIFICATIONS, "App notifications",
                NotificationManager.IMPORTANCE_HIGH));
    }

    private static AudioAttributes alarmAudio() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    }

    /** The app's name from its bundle identifier: the Clock for com.apple.mobiletimer. */
    private static String appName(Context context, String bundle) {
        if ("com.apple.mobiletimer".equals(bundle))
            return "Clock";
        int dot = bundle.lastIndexOf('.');
        return dot >= 0 && dot + 1 < bundle.length() ? bundle.substring(dot + 1) : bundle;
    }

    private static PendingIntent openScreen(Context context) {
        Intent open = new Intent(context, ScreenActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 1, open, immutable());
    }

    private static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
