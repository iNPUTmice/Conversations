package eu.siacs.conversations.utils;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.auth.api.phone.SmsRetrieverClient;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.Task;
import com.google.common.base.Strings;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.Config;

public class SmsRetrieverWrapper {

    public static void start(final Context context) {
        final SmsRetrieverClient client = SmsRetriever.getClient(context);
        final Task<Void> task = client.startSmsRetriever();
        task.addOnSuccessListener(aVoid -> Log.d(Config.LOGTAG, "successfully started SMS retriever"));
        task.addOnFailureListener(e -> Log.d(Config.LOGTAG, "unable to start SMS retriever", e));
    }

    public static String extractPin(Bundle extras) {
        final Status status = extras == null ? null : (Status) extras.get(SmsRetriever.EXTRA_STATUS);
        if (status != null && status.getStatusCode() == CommonStatusCodes.SUCCESS) {
            Log.d(Config.LOGTAG, "Verification SMS received with status success");
            final String message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE);
            final Matcher m = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)").matcher(Strings.nullToEmpty(message));
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }
}