package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Field;

public class ActionBarUtil {

    public static void resetActionBarOnClickListeners(@NonNull View view) {
        final View title = findActionBarTitle(view);
        final View subtitle = findActionBarSubTitle(view);
        if (title != null) {
            title.setOnClickListener(null);
        }
        if (subtitle != null) {
            subtitle.setOnClickListener(null);
        }
    }

    public static void setActionBarOnClickListener(@NonNull View view,
                                                   @NonNull final View.OnClickListener onClickListener) {
        final View title = findActionBarTitle(view);
        final View subtitle = findActionBarSubTitle(view);
        if (title != null) {
            title.setOnClickListener(onClickListener);
        }
        if (subtitle != null) {
            subtitle.setOnClickListener(onClickListener);
        }
    }

    private static @Nullable View findActionBarTitle(@NonNull View root) {
        return findActionBarItem(root, "action_bar_title", "mTitleTextView");
    }

    private static @Nullable
    View findActionBarSubTitle(@NonNull View root) {
        return findActionBarItem(root, "action_bar_subtitle", "mSubtitleTextView");
    }

    private static @Nullable View findActionBarItem(@NonNull View root,
                                                    @NonNull String resourceName,
                                                    @NonNull String toolbarFieldName) {
        View result = findViewSupportOrAndroid(root, resourceName);

        if (result == null) {
            View actionBar = findViewSupportOrAndroid(root, "action_bar");
            if (actionBar != null) {
                result = reflectiveRead(actionBar, toolbarFieldName);
            }
        }
        if (result == null && root.getClass().getName().endsWith("widget.Toolbar")) {
            result = reflectiveRead(root, toolbarFieldName);
        }
        return result;
    }

    @SuppressWarnings("ConstantConditions")
    private static @Nullable View findViewSupportOrAndroid(@NonNull View root,
                                                           @NonNull String resourceName) {
        Context context = root.getContext();
        View result = null;
        if (result == null) {
            int supportID = context.getResources().getIdentifier(resourceName, "id", context.getPackageName());
            result = root.findViewById(supportID);
        }
        if (result == null) {
            int androidID = context.getResources().getIdentifier(resourceName, "id", "android");
            result = root.findViewById(androidID);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T reflectiveRead(@NonNull Object object, @NonNull String fieldName) {
        try {
            Field field = object.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(object);
        } catch (final Exception ex) {
            return null;
        }
    }
}
