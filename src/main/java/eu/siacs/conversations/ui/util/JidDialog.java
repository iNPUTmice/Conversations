package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TypefaceSpan;

import androidx.annotation.StringRes;


public class JidDialog {

    public static SpannableString style(Context context, @StringRes int res, String... args) {
        final SpannableString spannable = new SpannableString(context.getString(res, (Object[]) args));
        if (args.length >= 1) {
            final String value = args[0];
            int start = spannable.toString().indexOf(value);
            if (start >= 0) {
                spannable.setSpan(new TypefaceSpan("monospace"), start, start + value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return spannable;
    }
}
