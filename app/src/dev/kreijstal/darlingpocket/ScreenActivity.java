package dev.kreijstal.darlingpocket;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The phone's screen for iOS apps: the whole window is the display, drawn
 * under the status bar, whose size the apps get as their safe area. The
 * navigation bar is hidden (iOS has none): a swipe from the edge shows it for
 * a moment, over the apps.
 */
public final class ScreenActivity extends Activity implements SurfaceHolder.Callback {
    private PocketView view;
    private FrameLayout root;
    private int width, height;
    private int top, left, bottom, right;
    private int keyboard = -1;
    private String sent = "";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        // iOS apps move their own views out of the keyboard's way (or don't),
        // when UIKit tells them its height: the window keeps its size, and the
        // keyboard comes as an inset.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30)
            window.setDecorFitsSystemWindows(false);
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
            updateKeyboard(insets);
            return insets;
        });
        // The pages of apps' UIWebViews lie over the screen (WebHost).
        root = new FrameLayout(this);
        root.addView(view);
        View pages = WebHost.start(this).overlay();
        if (pages.getParent() != null)
            ((ViewGroup) pages.getParent()).removeView(pages);
        root.addView(pages, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        PocketDisplay.setKeyboardListener(keyboard -> runOnUiThread(() -> view.setKeyboard(keyboard)));

        hideNavigation();
        launch(getIntent());
    }

    @SuppressWarnings("deprecation")
    private void hideNavigation() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** The keyboard, a dialog or another app can bring the bar back. */
    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus)
            hideNavigation();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        launch(intent);
    }

    private void launch(Intent intent) {
        String executable = intent.getStringExtra(PocketService.EXTRA_EXECUTABLE);
        if (executable == null)
            executable = PocketService.HOME_SCREEN;
        Intent launch = new Intent(this, PocketService.class).setAction(PocketService.ACTION_LAUNCH)
                .putExtra(PocketService.EXTRA_EXECUTABLE, executable);
        startService(launch);
    }

    /** The bars' and the cutout's insets: the keyboard's are not the safe area. */
    @SuppressWarnings("deprecation")
    private void readInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = bars.top;
            left = bars.left;
            bottom = bars.bottom;
            right = bars.right;
            return;
        }
        // The stable insets count the hidden navigation bar; only the status bar is kept.
        top = insets.getStableInsetTop();
        left = 0;
        bottom = 0;
        right = 0;
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

    /** Tells the apps how much of the screen the input method covers. */
    @SuppressWarnings("deprecation")
    private void updateKeyboard(WindowInsets insets) {
        int height;
        if (Build.VERSION.SDK_INT >= 30)
            height = insets.getInsets(WindowInsets.Type.ime()).bottom;
        else
            height = insets.getSystemWindowInsetBottom();
        if (height == keyboard)
            return;
        keyboard = height;
        PocketDisplay.keyboardFrame(height);
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

    /** Back is the Home button: SpringBoard comes back, and the app keeps running. */
    @Override
    public void onBackPressed() {
        PocketDisplay.key(true, PocketView.MAC_HOME, 0, null);
        PocketDisplay.key(false, PocketView.MAC_HOME, 0, null);
    }

    @Override
    protected void onDestroy() {
        View pages = WebHost.start(this).overlay();
        if (pages.getParent() == root)
            root.removeView(pages);
        PocketDisplay.setKeyboardListener(null);
        super.onDestroy();
    }
}
