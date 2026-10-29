package dev.kreijstal.darlingpocket;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import java.nio.charset.StandardCharsets;

/**
 * The screen iOS apps draw on. It sends them its touches, and while an app
 * edits text it holds the soft keyboard and sends them what the input method
 * types.
 */
final class PocketView extends SurfaceView {
    // Mac virtual key codes (kVK_*) for Android's keys whose characters the app knows.
    private static final int MAC_RETURN = 36, MAC_TAB = 48, MAC_DELETE = 51, MAC_ESCAPE = 53,
            MAC_FORWARD_DELETE = 117, MAC_HOME = 115, MAC_END = 119, MAC_PAGE_UP = 116, MAC_PAGE_DOWN = 121,
            MAC_LEFT = 123, MAC_RIGHT = 124, MAC_DOWN = 125, MAC_UP = 126;
    // NSEvent modifier flags.
    private static final int NS_SHIFT = 1 << 17, NS_CONTROL = 1 << 18, NS_OPTION = 1 << 19, NS_COMMAND = 1 << 20;

    private boolean keyboardWanted;

    PocketView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
            send(event, index, PocketDisplay.TOUCH_BEGAN);
            break;
        case MotionEvent.ACTION_MOVE:
            for (int i = 0; i < event.getPointerCount(); i++)
                send(event, i, PocketDisplay.TOUCH_MOVED);
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
            send(event, index, PocketDisplay.TOUCH_ENDED);
            break;
        case MotionEvent.ACTION_CANCEL:
            for (int i = 0; i < event.getPointerCount(); i++)
                send(event, i, PocketDisplay.TOUCH_CANCELLED);
            break;
        default:
            return false;
        }
        return true;
    }

    private static void send(MotionEvent event, int index, int phase) {
        // Finger 0 is never used by the protocol's hosts, so ids start at 1.
        PocketDisplay.touch(phase, event.getPointerId(index) + 1, event.getX(index), event.getY(index));
    }

    /** An app asked for the keyboard; called on the UI thread. */
    void setKeyboardWanted(boolean wanted) {
        keyboardWanted = wanted;
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (wanted) {
            requestFocus();
            manager.restartInput(this);
            manager.showSoftInput(this, 0);
        } else {
            manager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return keyboardWanted;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new Connection(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return key(event, true) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return key(event, false) || super.onKeyUp(keyCode, event);
    }

    /** Sends a hardware or input method key; false for keys the app is not sent. */
    static boolean key(KeyEvent event, boolean down) {
        int mac = macKeyCode(event.getKeyCode());
        if (mac >= 0) {
            PocketDisplay.key(down, mac, modifiers(event), null);
            return true;
        }
        int unicode = event.getUnicodeChar();
        if (unicode > 0 && (event.getMetaState() & (KeyEvent.META_CTRL_ON | KeyEvent.META_META_ON)) == 0) {
            // A printable key: what it types, as an input method would.
            if (down)
                text(new String(Character.toChars(unicode)));
            return true;
        }
        return false;
    }

    static void text(CharSequence text) {
        PocketDisplay.text(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void delete(int count) {
        for (int i = 0; i < count; i++) {
            PocketDisplay.key(true, MAC_DELETE, 0, null);
            PocketDisplay.key(false, MAC_DELETE, 0, null);
        }
    }

    static void returnKey() {
        PocketDisplay.key(true, MAC_RETURN, 0, null);
        PocketDisplay.key(false, MAC_RETURN, 0, null);
    }

    private static int macKeyCode(int code) {
        switch (code) {
        case KeyEvent.KEYCODE_ENTER:
        case KeyEvent.KEYCODE_NUMPAD_ENTER:
            return MAC_RETURN;
        case KeyEvent.KEYCODE_TAB:
            return MAC_TAB;
        case KeyEvent.KEYCODE_DEL:
            return MAC_DELETE;
        case KeyEvent.KEYCODE_ESCAPE:
            return MAC_ESCAPE;
        case KeyEvent.KEYCODE_FORWARD_DEL:
            return MAC_FORWARD_DELETE;
        case KeyEvent.KEYCODE_MOVE_HOME:
            return MAC_HOME;
        case KeyEvent.KEYCODE_MOVE_END:
            return MAC_END;
        case KeyEvent.KEYCODE_PAGE_UP:
            return MAC_PAGE_UP;
        case KeyEvent.KEYCODE_PAGE_DOWN:
            return MAC_PAGE_DOWN;
        case KeyEvent.KEYCODE_DPAD_LEFT:
            return MAC_LEFT;
        case KeyEvent.KEYCODE_DPAD_RIGHT:
            return MAC_RIGHT;
        case KeyEvent.KEYCODE_DPAD_DOWN:
            return MAC_DOWN;
        case KeyEvent.KEYCODE_DPAD_UP:
            return MAC_UP;
        default:
            return -1;
        }
    }

    private static int modifiers(KeyEvent event) {
        int flags = 0;
        if (event.isShiftPressed())
            flags |= NS_SHIFT;
        if (event.isCtrlPressed())
            flags |= NS_CONTROL;
        if (event.isAltPressed())
            flags |= NS_OPTION;
        if (event.isMetaPressed())
            flags |= NS_COMMAND;
        return flags;
    }

    /**
     * The input method's side. The app's text field is not ours to read, so
     * composing text (a word being typed with suggestions) is typed as it
     * changes: what was composed before is deleted, the new text typed.
     */
    private static final class Connection extends BaseInputConnection {
        private String composing = "";

        Connection(View view) {
            super(view, false);
        }

        private void replaceComposing(CharSequence text) {
            delete(composing.codePointCount(0, composing.length()));
            String typed = text.toString();
            if (!typed.isEmpty())
                PocketView.text(typed);
            composing = typed;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String typed = text.toString();
            if (typed.equals("\n")) {
                replaceComposing("");
                returnKey();
            } else {
                replaceComposing(typed);
            }
            composing = "";
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            replaceComposing(text);
            return true;
        }

        @Override
        public boolean finishComposingText() {
            composing = "";
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            delete(beforeLength);
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP)
                key(event, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }

        @Override
        public boolean performEditorAction(int action) {
            returnKey();
            return true;
        }
    }
}
