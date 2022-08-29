package eu.siacs.conversations.ui.widget;


import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.TextView;

import java.lang.reflect.Field;

/**
 * A wrapper class to fix some weird fuck ups on Meizu devices
 * credit goes to the people in this thread https://github.com/android-in-china/Compatibility/issues/11
 */
public class TextInputEditText extends com.google.android.material.textfield.TextInputEditText {

    public TextInputEditText(Context context) {
        super(context);
    }

    public TextInputEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TextInputEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public CharSequence getHint() {
        String manufacturer = Build.MANUFACTURER.toUpperCase();
        if (!manufacturer.contains("MEIZU") || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return super.getHint();
        }else{
            try {
                return getSuperHintHack();
            }catch (Exception e){
                return super.getHint();
            }
        }
    }

    private CharSequence getSuperHintHack() throws NoSuchFieldException, IllegalAccessException {
        Field hintField = TextView.class.getDeclaredField("mHint");
        hintField.setAccessible(true);
        return (CharSequence) hintField.get(this);
    }
}

