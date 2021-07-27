package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.view.Window;
import android.view.WindowManager;

public class SettingsUtils {
    public static void applyScreenshotPreventionSetting(Activity activity){
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        boolean preventScreenshots = preferences.getBoolean("prevent_screenshots", false);
        Window activityWindow = activity.getWindow();
        if(preventScreenshots){
            activityWindow.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activityWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
