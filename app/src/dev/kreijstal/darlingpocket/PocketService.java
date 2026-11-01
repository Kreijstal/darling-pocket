package dev.kreijstal.darlingpocket;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Keeps Darling's container and the display alive while the screen's
 * activity comes and goes: the display server and every app run in this
 * process, as a foreground service.
 */
public final class PocketService extends Service {
    static final String TAG = "DarlingPocket";
    static final String ACTION_LAUNCH = "dev.kreijstal.darlingpocket.LAUNCH";
    static final String ACTION_STOP = "dev.kreijstal.darlingpocket.STOP";
    /** Turns Termux debugging on or off ({@link #EXTRA_ENABLE}), or makes a new token ({@link #EXTRA_REVOKE}). */
    static final String ACTION_ADMIN = "dev.kreijstal.darlingpocket.ADMIN";
    static final String EXTRA_ENABLE = "enable", EXTRA_REVOKE = "revoke";
    /** The app's executable, as the guest names it. */
    static final String EXTRA_EXECUTABLE = "executable";
    /**
     * The home screen: the container's setup if it is not done yet, then
     * frontboardd, which starts SpringBoard and the apps launched from it, and
     * brings SpringBoard back when they go (cydia/home).
     */
    static final String HOME_SCREEN = "/usr/libexec/darling-pocket/home";

    private static final String CHANNEL = "container";

    private static PocketService instance;
    /** What Termux debugging is doing, for the manager. */
    static volatile String adminStatus = "Termux debugging is off.";

    /** The boot screen's line while the home screen starts, null once it shows (or never started). */
    private static volatile String bootStage;
    private static volatile File bootLog;
    private static final String STAGE = "darling-pocket: stage ";

    private AdminServer admin;

    private Runtime runtime;
    private Process running;
    private String runningName;

    /** Whether the home screen runs, and with it usernotificationsd, which shows the apps' notifications. */
    static boolean homeScreenRunning() {
        PocketService service = instance;
        if (service == null)
            return false;
        synchronized (service) {
            return service.running != null && service.running.isAlive() && HOME_SCREEN.equals(service.runningName);
        }
    }

    /**
     * What the boot screen says, or null when there is nothing to wait for:
     * the latest stage the home script wrote to its log, or why it stopped.
     */
    static String bootStage() {
        String stage = bootStage;
        File log = bootLog;
        if (stage == null || log == null)
            return stage;
        String line = lastStage(log);
        return line != null && !stage.startsWith("The home screen stopped") ? line : stage;
    }

    /** Shown at once, before the service has started the home screen. */
    static void expectHomeScreen() {
        if (!homeScreenRunning())
            bootStage = "Starting the container";
    }

    static void firstFrame() {
        bootStage = null;
        Log.i(TAG, "The home screen shows");
    }

    private static String lastStage(File log) {
        long length = log.length();
        if (length <= 0)
            return null;
        byte[] tail = new byte[(int) Math.min(length, 8192)];
        try (RandomAccessFile file = new RandomAccessFile(log, "r")) {
            file.seek(length - tail.length);
            file.readFully(tail);
        } catch (IOException e) {
            return null;
        }
        String text = new String(tail, StandardCharsets.UTF_8);
        int at = text.lastIndexOf(STAGE);
        if (at < 0)
            return null;
        int end = text.indexOf('\n', at);
        return text.substring(at + STAGE.length(), end < 0 ? text.length() : end).trim();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        runtime = new Runtime(this);
        startForeground(1, notification("Starting"));
        installConfiguration();
        startDisplay(this);
        WebHost.start(this);
        Alarms.watch(this);
        // It stays on across the app's restarts (a crash among them), until turned off.
        if (admin(this).getBoolean("enabled", false))
            openAdmin();
    }

    /** Starts the display at the phone's size; the screen's activity corrects it to the view's. */
    static void startDisplay(Context context) {
        Runtime runtime = new Runtime(context);
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        WebHost.start(context).setScale(scaleMilli(metrics.density) / 1000f);
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
        else if (intent != null && ACTION_ADMIN.equals(intent.getAction()))
            setAdmin(intent.getBooleanExtra(EXTRA_ENABLE, false), intent.getBooleanExtra(EXTRA_REVOKE, false));
        return START_STICKY;
    }

    private synchronized void launch(String executable) {
        if (executable == null || !runtime.installed()) {
            bootStage = null;
            return;
        }
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
            boolean home = HOME_SCREEN.equals(executable);
            if (home) {
                bootLog = log;
                bootStage = "Starting the container";
                PocketDisplay.awaitFrame();
            }
            running = builder.start();
            runningName = executable;
            Log.i(TAG, "Started " + executable + ", output in " + log);
            updateNotification(name);
            watch(running, name);
        } catch (IOException e) {
            Log.e(TAG, "Cannot start " + executable, e);
            appendLog(log, "Cannot start: " + e);
            if (bootStage != null)
                bootStage = "The home screen stopped: cannot start it (" + e.getMessage() + ")";
        }
    }

    static SharedPreferences admin(Context context) {
        return context.getSharedPreferences("admin", MODE_PRIVATE);
    }

    /** The owner key, as Termux's client pastes it: 64 hex digits. */
    static String adminToken(Context context) {
        SharedPreferences prefs = admin(context);
        String token = prefs.getString("key", "");
        if (!token.matches("[0-9a-f]{64}")) {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            token = Runtime.hex(key);
            prefs.edit().putString("key", token).commit();
        }
        return token;
    }

    private synchronized void setAdmin(boolean enable, boolean revoke) {
        if (revoke) {
            admin(this).edit().remove("key").commit();
            enable = admin != null;
        }
        if (admin != null) {
            admin.close();
            admin = null;
        }
        admin(this).edit().putBoolean("enabled", enable).commit();
        adminStatus = "Termux debugging is off.";
        if (enable)
            openAdmin();
    }

    private synchronized void openAdmin() {
        if (admin != null)
            return;
        String token = adminToken(this);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++)
            key[i] = (byte) Integer.parseInt(token.substring(2 * i, 2 * i + 2), 16);
        try {
            admin = new AdminServer(AdminServer.DEFAULT_PORT, key, new AdminServer.Backend() {
                @Override
                public String status() throws JSONException {
                    return adminState();
                }

                @Override
                public String control(boolean start) throws JSONException {
                    if (start)
                        launch(HOME_SCREEN);
                    else
                        stopApp();
                    return adminState();
                }

                @Override
                public Process execute(String cwd, List<String> argv) throws IOException {
                    ProcessBuilder builder;
                    if ("darling".equals(argv.get(0))) {
                        // As the app starts its apps: darling shell, with the display and the rest.
                        builder = runtime.shell(argv.subList(1, argv.size()).toArray(new String[0]));
                        builder.redirectErrorStream(false);
                    } else {
                        builder = new ProcessBuilder(argv);
                        builder.environment().putAll(runtime.shell().environment());
                        builder.environment().put("DARLING", new File(runtime.nativeDir, "libdarling.so").getPath());
                        builder.directory(new File(cwd));
                    }
                    return builder.start();
                }
            });
            adminStatus = "Termux debugging listens on 127.0.0.1:" + admin.port() + ".";
        } catch (IOException | RuntimeException e) {
            adminStatus = "Termux debugging cannot start: " + e.getMessage();
            Log.e(TAG, adminStatus);
        }
    }

    private String adminState() throws JSONException {
        String app;
        synchronized (this) {
            app = running != null && running.isAlive() ? runningName : null;
        }
        return new JSONObject().put("version", BuildConfig.VERSION_NAME)
                .put("runtimeInstalled", runtime.installed())
                .put("running", app == null ? JSONObject.NULL : app)
                .put("data", getApplicationInfo().dataDir)
                .put("logs", new File(runtime.files, "logs").getPath())
                .toString(2);
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
                    if (bootStage != null)
                        bootStage = "The home screen stopped before it showed. Its log is files/logs/" + name + ".log.";
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
        bootStage = null;
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
        synchronized (this) {
            if (admin != null) {
                admin.close();
                admin = null;
            }
        }
        stopApp();
        PocketDisplay.stop();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
