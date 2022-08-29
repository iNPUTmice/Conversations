package eu.siacs.conversations.ui.util;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


public class PinEntryWrapper {

    private static final Pattern PIN_STRING_PATTERN = Pattern.compile("^[0-9]{6}$");

    private final List<EditText> digits = new ArrayList<>();

    private final TextWatcher textWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {

        }

        @Override
        public void afterTextChanged(Editable s) {
            int current = -1;
            for (int i = 0; i < digits.size(); ++i) {
                if (s.hashCode() == digits.get(i).getText().hashCode()) {
                    current = i;
                }
            }
            if (current == -1) {
                return;
            }
            if (s.length() > 0) {
                if (current < digits.size() - 1) {
                    digits.get(current + 1).requestFocus();
                }
            } else {
                int focusOn = current;
                for (int i = current - 1; i >= 0; --i) {
                    if (digits.get(i).getText().length() == 0) {
                        focusOn = i;
                    } else {
                        break;
                    }
                }
                digits.get(focusOn).requestFocus();
            }
        }
    };

    private final View.OnKeyListener keyListener = (v, keyCode, event) -> {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        if (v instanceof EditText) {
            final EditText editText = (EditText) v;
            final boolean cursorAtZero = editText.getSelectionEnd() == 0 && editText.getSelectionStart() == 0;
            if (keyCode == KeyEvent.KEYCODE_DEL && (cursorAtZero || editText.getText().length() == 0)) {
                final int current = digits.indexOf(editText);
                for (int i = current - 1; i >= 0; --i) {
                    if (digits.get(i).getText().length() > 0) {
                        digits.get(i).getText().clear();
                        return true;
                    }
                }
                if (current != 0) {
                    digits.get(0).requestFocus();
                    return true;
                }
            }
        }
        return false;
    };

    public PinEntryWrapper(LinearLayout linearLayout) {
        for (int i = 0; i < linearLayout.getChildCount(); ++i) {
            View view = linearLayout.getChildAt(i);
            if (view instanceof EditText) {
                this.digits.add((EditText) view);
            }
        }
        registerListeners();
    }

    private void registerListeners() {
        for (EditText editText : digits) {
            editText.addTextChangedListener(textWatcher);
            editText.setOnKeyListener(keyListener);
        }
    }

    public String getPin() {
        char[] chars = new char[digits.size()];
        for (int i = 0; i < chars.length; ++i) {
            final String input = digits.get(i).getText().toString();
            chars[i] = input.length() != 1 ? ' ' : input.charAt(0);
        }
        return String.valueOf(chars);
    }

    public void setPin(String pin) {
        char[] chars = pin.toCharArray();
        for (int i = 0; i < digits.size(); ++i) {
            if (i < chars.length) {
                final Editable editable = digits.get(i).getText();
                editable.clear();
                editable.append(Character.isDigit(chars[i]) ? String.valueOf(chars[i]) : "");
            }
        }
    }

    public void setEnabled(final boolean enabled) {
        for (EditText digit : digits) {
            digit.setEnabled(enabled);
            digit.setCursorVisible(enabled);
            digit.setFocusable(enabled);
            digit.setFocusableInTouchMode(enabled);
        }
        if (enabled) {
            final EditText last = digits.get(digits.size() - 1);
            if (last.getEditableText().length() > 0) {
                last.requestFocus();
            }
        }
    }

    public boolean isEmpty() {
        for (EditText digit : digits) {
            if (digit.getText().length() > 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean isValidPin(CharSequence pin) {
        return pin != null && PIN_STRING_PATTERN.matcher(pin).matches();
    }

    public void clear() {
        for (int i = digits.size() - 1; i >= 0; --i) {
            digits.get(i).getText().clear();
        }
    }

    public void requestFocus() {
        digits.get(0).requestFocus();
    }
}