package dev.kreijstal.darlingpocket;

import android.view.Surface;

/** The phone's display for iOS apps: native/pocket-display.c. */
final class PocketDisplay {
    static {
        System.loadLibrary("pocketdisplay");
    }

    // PocketProtocol.h's touch phases.
    static final int TOUCH_BEGAN = 0, TOUCH_MOVED = 1, TOUCH_ENDED = 3, TOUCH_CANCELLED = 4;

    /** What an app asks of the soft keyboard: PocketProtocol.h's struct pocket_keyboard. */
    static final class Keyboard {
        static final int SECURE = 1, MULTILINE = 2;

        final boolean shown;
        // The text's UITextInputTraits, with iOS's values.
        final int type, returnKey, capitalization, correction, flags;

        Keyboard(boolean shown, int type, int returnKey, int capitalization, int correction, int flags) {
            this.shown = shown;
            this.type = type;
            this.returnKey = returnKey;
            this.capitalization = capitalization;
            this.correction = correction;
            this.flags = flags;
        }
    }

    /** Called on the display's thread when an app asks for the soft keyboard or lets it go. */
    interface KeyboardListener {
        void onKeyboard(Keyboard keyboard);
    }

    private static volatile KeyboardListener keyboardListener;

    private PocketDisplay() {
    }

    static void setKeyboardListener(KeyboardListener listener) {
        keyboardListener = listener;
    }

    // Called from native code.
    static void onKeyboard(boolean shown, int type, int returnKey, int capitalization, int correction, int flags) {
        KeyboardListener listener = keyboardListener;
        if (listener != null)
            listener.onKeyboard(new Keyboard(shown, type, returnKey, capitalization, correction, flags));
    }

    // Called from native code, on the display's thread, after awaitFrame.
    static void onFirstFrame() {
        PocketService.firstFrame();
    }

    /** Calls {@link #onFirstFrame} when an app next presents a frame. */
    static native void awaitFrame();

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

    /** The soft keyboard covers {@code height} pixels at the screen's bottom; 0 once it is gone. */
    static native void keyboardFrame(int height);

    /**
     * Where part of an app's window is on the screen: {@code rect} is x, y,
     * width and height in the window's pixels, and becomes the screen's.
     * Returns 1 when that part shows, 0 when it is hidden or another window
     * covers it, -1 when the app (its pid) has no such window.
     */
    static native int windowPart(int pid, int window, int[] rect);
}
