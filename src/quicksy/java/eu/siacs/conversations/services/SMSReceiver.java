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

public class SMSReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, final Intent intent) {
        intent.setClass(context, XmppConnectionService.class);
        Compatibility.startService(context, intent);
    }
}
