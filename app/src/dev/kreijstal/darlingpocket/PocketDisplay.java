package dev.kreijstal.darlingpocket;

import android.view.Surface;

/** The phone's display for iOS apps: native/pocket-display.c. */
final class PocketDisplay {
    static {
        System.loadLibrary("pocketdisplay");
    }

    // PocketProtocol.h's touch phases.
    static final int TOUCH_BEGAN = 0, TOUCH_MOVED = 1, TOUCH_ENDED = 3, TOUCH_CANCELLED = 4;

    /** Called on the display's thread when an app asks for the soft keyboard or lets it go. */
    interface KeyboardListener {
        void onKeyboard(boolean shown);
    }

    private static volatile KeyboardListener keyboardListener;

    private PocketDisplay() {
    }

    static void setKeyboardListener(KeyboardListener listener) {
        keyboardListener = listener;
    }

    // Called from native code.
    static void onKeyboard(boolean shown) {
        KeyboardListener listener = keyboardListener;
        if (listener != null)
            listener.onKeyboard(shown);
    }

    /** Listens on {@code socket}; sizes and insets are pixels, the scale is pixels per point. */
    static native boolean start(String socket, int width, int height, int scaleMilli, int top, int left,
            int bottom, int right);

    static native void stop();

    static native void setScreen(int width, int height, int scaleMilli, int top, int left, int bottom, int right);

    /** Where frames go, or null. */
    static native void setSurface(Surface surface);

    /** {@code finger} is the same while it is down; coordinates are the screen's pixels. */
    static native void touch(int phase, int finger, float x, float y);

    /** A Mac virtual key code, NSEvent modifier flags, and the key's characters (UTF-8) or null. */
    static native void key(boolean down, int keycode, int modifiers, byte[] characters);

    /** Text an input method commits, UTF-8. */
    static native void text(byte[] text);
}
