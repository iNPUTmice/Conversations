package eu.siacs.conversations.utils;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import eu.siacs.conversations.R;
import me.drakeet.support.toast.ToastCompat;

public class TorServiceUtils {

    private static final String URI_ORBOT = "org.torproject.android";
    private static final Uri ORBOT_PLAYSTORE_URI = Uri.parse("market://details?id=" + URI_ORBOT);
    private static final String ACTION_START_TOR = "org.torproject.android.START_TOR";

    public static final Intent INSTALL_INTENT = new Intent(Intent.ACTION_VIEW, ORBOT_PLAYSTORE_URI);
    public static final Intent LAUNCH_INTENT = new Intent(ACTION_START_TOR);

    public static final String ACTION_STATUS = "org.torproject.android.intent.action.STATUS";
    public static final String EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS";

    public static boolean isOrbotInstalled(final Context context) {
        try {
            context.getPackageManager().getPackageInfo(URI_ORBOT, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (final PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void downloadOrbot(Activity activity, int requestCode) {
        try {
            activity.startActivityForResult(INSTALL_INTENT, requestCode);
        } catch (final ActivityNotFoundException e) {
            ToastCompat.makeText(
                            activity, R.string.no_market_app_installed, ToastCompat.LENGTH_SHORT)
                    .show();
        }
    }

    public static void startOrbot(final Activity activity, final int requestCode) {
        try {
            activity.startActivityForResult(LAUNCH_INTENT, requestCode);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.install_orbot, Toast.LENGTH_LONG).show();
        }
    }
}
