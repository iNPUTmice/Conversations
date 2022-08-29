package eu.siacs.conversations.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.common.base.Strings;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.utils.Compatibility;

public class EventReceiver extends BroadcastReceiver {

    public static final String SETTING_ENABLED_ACCOUNTS = "enabled_accounts";
    public static final String EXTRA_NEEDS_FOREGROUND_SERVICE = "needs_foreground_service";

    @Override
    public void onReceive(final Context context, final Intent originalIntent) {
        final Intent intentForService = new Intent(context, XmppConnectionService.class);
        final String action = originalIntent.getAction();
        intentForService.setAction(Strings.isNullOrEmpty(action) ? "other" : action);
        final Bundle extras = originalIntent.getExtras();
        if (extras != null) {
            intentForService.putExtras(extras);
        }
        if ("ui".equals(action) || hasEnabledAccounts(context)) {
            Compatibility.startService(context, intentForService);
        } else {
            Log.d(Config.LOGTAG, "EventReceiver ignored action " + intentForService.getAction());
        }
    }

    public static boolean hasEnabledAccounts(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SETTING_ENABLED_ACCOUNTS, true);
    }

}
