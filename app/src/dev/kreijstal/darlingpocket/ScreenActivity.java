package dev.kreijstal.darlingpocket;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.SurfaceHolder;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

/**
 * The phone's screen for iOS apps: the whole window is the display, drawn
 * under the status and navigation bars, whose size the apps get as their
 * safe area.
 */
public final class ScreenActivity extends Activity implements SurfaceHolder.Callback {
    private PocketView view;
    private int width, height;
    private int top, left, bottom, right;
    private String sent = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        // iOS apps move their own views out of the keyboard's way (or don't).
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }

        // The service owns the display; this only shows it.
        startService(new Intent(this, PocketService.class));
        PocketService.startDisplay(this);

        view = new PocketView(this);
        view.getHolder().addCallback(this);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            readInsets(insets);
            updateScreen();
            return insets;
        });
        setContentView(view);
        PocketDisplay.setKeyboardListener(shown -> runOnUiThread(() -> view.setKeyboardWanted(shown)));

        launch(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        launch(intent);
    }

    private void launch(Intent intent) {
        String executable = intent.getStringExtra(PocketService.EXTRA_EXECUTABLE);
        if (executable == null)
            return;
        Intent launch = new Intent(this, PocketService.class).setAction(PocketService.ACTION_LAUNCH)
                .putExtra(PocketService.EXTRA_EXECUTABLE, executable);
        startService(launch);
    }

    @SuppressWarnings("deprecation")
    private void readInsets(WindowInsets insets) {
        top = insets.getSystemWindowInsetTop();
        left = insets.getSystemWindowInsetLeft();
        bottom = insets.getSystemWindowInsetBottom();
        right = insets.getSystemWindowInsetRight();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                top = Math.max(top, cutout.getSafeInsetTop());
                left = Math.max(left, cutout.getSafeInsetLeft());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                right = Math.max(right, cutout.getSafeInsetRight());
            }
        }
    }

    private void updateScreen() {
        if (width <= 0 || height <= 0)
            return;
        int scale = PocketService.scaleMilli(getResources().getDisplayMetrics().density);
        // Apps lay themselves out again on every change, so only real ones are sent.
        String screen = width + "x" + height + "@" + scale + " " + top + "," + left + "," + bottom + "," + right;
        if (screen.equals(sent))
            return;
        sent = screen;
        PocketDisplay.setScreen(width, height, scale, top, left, bottom, right);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        width = w;
        height = h;
        if (view.getRootWindowInsets() != null)
            readInsets(view.getRootWindowInsets());
        updateScreen();
        PocketDisplay.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        PocketDisplay.setSurface(null);
    }

    /** Back is the home button: the app keeps running behind the manager. */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        PocketDisplay.setKeyboardListener(null);
        super.onDestroy();
    }
}
