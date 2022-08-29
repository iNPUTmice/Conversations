package eu.siacs.conversations.ui.util;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.StringRes;

import eu.siacs.conversations.R;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.utils.TimeFrameUtils;

public class ApiDialogHelper {

    public static Dialog createError(final Context context, final int code) {
        @StringRes final int res;
        switch (code) {
            case QuickConversationsService.API_ERROR_AIRPLANE_MODE:
                res = R.string.no_network_connection;
                break;
            case QuickConversationsService.API_ERROR_OTHER:
                res = R.string.unknown_api_error_network;
                break;
            case QuickConversationsService.API_ERROR_CONNECT:
                res = R.string.unable_to_connect_to_server;
                break;
            case QuickConversationsService.API_ERROR_SSL_HANDSHAKE:
                res = R.string.unable_to_establish_secure_connection;
                break;
            case QuickConversationsService.API_ERROR_UNKNOWN_HOST:
                res = R.string.unable_to_find_server;
                break;
            case QuickConversationsService.API_ERROR_SSL_CERTIFICATE:
                res = R.string.unable_to_verify_server_identity;
                break;
            case QuickConversationsService.API_ERROR_SSL_GENERAL:
                res = R.string.unknown_security_error;
                break;
            case QuickConversationsService.API_ERROR_TIMEOUT:
                res = R.string.timeout_while_connecting_to_server;
                break;
            case 400:
                res = R.string.invalid_user_input;
                break;
            case 403:
                res = R.string.the_app_is_out_of_date;
                break;
            case 409:
                res = R.string.logged_in_with_another_device;
                break;
            case 451:
                res = R.string.not_available_in_your_country;
                break;
            case 500:
                res = R.string.something_went_wrong_processing_your_request;
                break;
            case 502:
            case 503:
            case 504:
                res = R.string.temporarily_unavailable;
                break;
            default:
                res = R.string.unknown_api_error_response;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setMessage(res);
        if (code == 403 && resolvable(context, getMarketViewIntent(context))) {
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(R.string.update, (dialog, which) -> context.startActivity(getMarketViewIntent(context)));
        } else {
            builder.setPositiveButton(R.string.ok, null);
        }
        return builder.create();
    }

    public static Dialog createRateLimited(final Context context, final long timestamp) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.rate_limited);
        builder.setMessage(context.getString(R.string.try_again_in_x, TimeFrameUtils.resolve(context, timestamp - SystemClock.elapsedRealtime())));
        builder.setPositiveButton(R.string.ok, null);
        return builder.create();
    }

    public static Dialog createTooManyAttempts(final Context context) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setMessage(R.string.too_many_attempts);
        builder.setPositiveButton(R.string.ok, null);
        return builder.create();
    }

    private static Intent getMarketViewIntent(Context context) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.getPackageName()));
    }

    private static boolean resolvable(Context context, Intent intent) {
        return context.getPackageManager().queryIntentActivities(intent, 0).size() > 0;
    }
}
