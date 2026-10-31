package dev.kreijstal.darlingpocket;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The manager: installs the runtime and IPAs, and opens the home screen, where the apps are. */
public final class MainActivity extends Activity {
    private static final int PICK_RUNTIME = 1, PICK_IPA = 2, SAVE_CLIENT = 3;

    private Runtime runtime;
    private TextView status;
    private Button home, adminToggle, adminCopy;
    private TextView adminStatus;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("Darling Pocket " + BuildConfig.VERSION_NAME);
        runtime = new Runtime(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        layout.addView(status);
        home = button("Open the home screen", v -> startActivity(new Intent(this, ScreenActivity.class)));
        layout.addView(home);
        layout.addView(button("Install the runtime", v -> installRuntime()));
        layout.addView(button("Install the runtime from a file", v -> pick(PICK_RUNTIME)));
        layout.addView(button("Install an IPA", v -> pick(PICK_IPA)));
        layout.addView(button("Stop the running app", v -> startService(
                new Intent(this, PocketService.class).setAction(PocketService.ACTION_STOP))));

        // Termux debugging: runtime/termux/pocketctl.py runs commands as this app.
        adminStatus = new TextView(this);
        adminStatus.setPadding(0, pad, 0, 0);
        layout.addView(adminStatus);
        adminToggle = button("", v -> {
            setAdmin(!PocketService.admin(this).getBoolean("enabled", false), false);
        });
        layout.addView(adminToggle);
        adminCopy = button("Copy the connection token", v -> copyToken());
        layout.addView(adminCopy);
        layout.addView(button("Save the Termux client (pocketctl.py)", v -> startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("text/x-python").putExtra(Intent.EXTRA_TITLE, "pocketctl.py"), SAVE_CLIENT)));
        layout.addView(button("Revoke the connection token", v -> setAdmin(
                PocketService.admin(this).getBoolean("enabled", false), true)));
        TextView adminHelp = new TextView(this);
        adminHelp.setText("Termux debugging lets Termux run commands as this app: its logs, its files, and "
                + "Darling's container. In Termux: python pocketctl.py pair (paste the token), then "
                + "pocketctl.py logcat, shell or darling. It listens on 127.0.0.1:" + AdminServer.DEFAULT_PORT
                + " only, and stays on until it is turned off; the token is the key to it.");
        layout.addView(adminHelp);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        if (saved == null && !runtime.installed())
            installRuntime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        return button;
    }

    private void refresh() {
        if (!busy)
            status.setText(runtime.installed() ? "The runtime is installed." : "The runtime is not installed yet. It "
                    + "downloads from " + BuildConfig.RUNTIME_URL + ", or push " + Runtime.ARCHIVE_NAME + " to "
                    + Runtime.pushedArchive(this) + ", or pick it.");
        home.setEnabled(runtime.installed() && !busy);
        boolean enabled = PocketService.admin(this).getBoolean("enabled", false);
        adminStatus.setText(enabled ? PocketService.adminStatus : "Termux debugging is off.");
        adminToggle.setText(enabled ? "Turn off Termux debugging" : "Turn on Termux debugging");
        adminCopy.setEnabled(enabled);
    }

    private void setAdmin(boolean enable, boolean revoke) {
        startService(new Intent(this, PocketService.class).setAction(PocketService.ACTION_ADMIN)
                .putExtra(PocketService.EXTRA_ENABLE, enable).putExtra(PocketService.EXTRA_REVOKE, revoke));
        if (revoke)
            Toast.makeText(this, "Pair Termux again with the new token", Toast.LENGTH_LONG).show();
        // The service answers on its own thread.
        new Handler(Looper.getMainLooper()).postDelayed(this::refresh, 300);
    }

    private void copyToken() {
        ClipData clip = ClipData.newPlainText("Darling Pocket connection token", PocketService.adminToken(this));
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
        Toast.makeText(this, "Paste it into pocketctl.py pair in Termux", Toast.LENGTH_LONG).show();
    }

    private void installRuntime() {
        File pushed = Runtime.pushedArchive(this);
        if (pushed != null && pushed.isFile()) {
            background("Installing the runtime from " + pushed, () -> {
                try (InputStream in = new FileInputStream(pushed)) {
                    runtime.install(in, this::show);
                }
                pushed.delete();
                runtime.createContainer(this::show);
                show("The runtime is installed.");
            });
        } else {
            background("Downloading the runtime", () -> {
                try (InputStream in = Runtime.download(this::show)) {
                    runtime.install(in, this::show);
                }
                runtime.createContainer(this::show);
                show("The runtime is installed.");
            });
        }
    }

    private void pick(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, request);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null)
            return;
        final Uri uri = data.getData();
        if (request == SAVE_CLIENT) {
            try (InputStream in = getAssets().open("termux/pocketctl.py");
                    OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null)
                    throw new IOException("Cannot open " + uri);
                Runtime.copy(in, out);
                Toast.makeText(this, "Saved pocketctl.py", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "Cannot save pocketctl.py: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (request == PICK_RUNTIME) {
            background("Installing the runtime", () -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    runtime.install(in, this::show);
                }
                runtime.createContainer(this::show);
                show("The runtime is installed.");
            });
        } else if (request == PICK_IPA) {
            background("Installing the IPA", () -> {
                if (!runtime.installed())
                    throw new IOException("Install the runtime first");
                runtime.createContainer(this::show);
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    File bundle = Ipa.install(in, runtime.applications());
                    show("Installed " + bundle.getName() + ": it is on the home screen.");
                }
            });
        }
    }

    private interface Work {
        void run() throws IOException;
    }

    private void background(String what, Work work) {
        if (busy)
            return;
        busy = true;
        show(what);
        new Thread(() -> {
            try {
                work.run();
            } catch (IOException e) {
                show("Failed: " + e.getMessage());
            } finally {
                busy = false;
                runOnUiThread(() -> {
                    CharSequence last = status.getText();
                    refresh();
                    status.setText(last);
                });
            }
        }, what).start();
    }

    private void show(String text) {
        runOnUiThread(() -> status.setText(text));
    }
}
