package dev.kreijstal.darlingpocket;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** The manager: installs the runtime and IPAs, and lists the apps to open. */
public final class MainActivity extends Activity {
    private static final int PICK_RUNTIME = 1, PICK_IPA = 2;

    private Runtime runtime;
    private TextView status;
    private LinearLayout apps;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        runtime = new Runtime(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        layout.addView(status);
        layout.addView(button("Install the runtime", v -> installRuntime()));
        layout.addView(button("Install an IPA", v -> pick(PICK_IPA)));
        layout.addView(button("Stop the running app", v -> startService(
                new Intent(this, PocketService.class).setAction(PocketService.ACTION_STOP))));
        TextView heading = new TextView(this);
        heading.setText("\nApps");
        layout.addView(heading);
        apps = new LinearLayout(this);
        apps.setOrientation(LinearLayout.VERTICAL);
        layout.addView(apps);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
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
            status.setText(runtime.installed() ? "The runtime is installed." : "The runtime is not installed yet. Push "
                    + Runtime.ARCHIVE_NAME + " to " + Runtime.pushedArchive(this) + " or pick it.");
        apps.removeAllViews();
        File[] bundles = runtime.applications().listFiles((dir, name) -> name.endsWith(".app"));
        if (bundles == null || bundles.length == 0) {
            TextView none = new TextView(this);
            none.setText("None yet.");
            apps.addView(none);
            return;
        }
        Arrays.sort(bundles);
        for (File bundle : bundles)
            apps.addView(button(bundle.getName().replaceFirst("\\.app$", ""), v -> open(bundle)));
    }

    private void open(File bundle) {
        File executable;
        try {
            executable = Ipa.executable(bundle);
        } catch (IOException e) {
            executable = null;
        }
        if (executable == null) {
            status.setText("No executable in " + bundle.getName());
            return;
        }
        String guest = "/Applications/" + bundle.getName() + "/" + executable.getName();
        startActivity(new Intent(this, ScreenActivity.class).putExtra(PocketService.EXTRA_EXECUTABLE, guest));
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
            pick(PICK_RUNTIME);
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
                    show("Installed " + bundle.getName());
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
