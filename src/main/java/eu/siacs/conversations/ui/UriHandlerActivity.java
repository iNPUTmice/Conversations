package eu.siacs.conversations.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;

import com.google.common.base.Strings;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityUriHandlerBinding;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.utils.ProvisioningUtils;
import eu.siacs.conversations.utils.SignupUtils;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

public class UriHandlerActivity extends AppCompatActivity {

    public static final String ACTION_SCAN_QR_CODE = "scan_qr_code";
    private static final String EXTRA_ALLOW_PROVISIONING = "extra_allow_provisioning";
    private static final int REQUEST_SCAN_QR_CODE = 0x1234;
    private static final int REQUEST_CAMERA_PERMISSIONS_TO_SCAN = 0x6789;
    private static final int REQUEST_CAMERA_PERMISSIONS_TO_SCAN_AND_PROVISION = 0x6790;
    private static final Pattern V_CARD_XMPP_PATTERN = Pattern.compile("\nIMPP([^:]*):(xmpp:.+)\n");
    private static final Pattern LINK_HEADER_PATTERN = Pattern.compile("<(.*?)>");
    private ActivityUriHandlerBinding binding;
    private Call call;

    public static void scan(final Activity activity) {
        scan(activity, false);
    }

    public static void scan(final Activity activity, final boolean provisioning) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            final Intent intent = new Intent(activity, UriHandlerActivity.class);
            intent.setAction(UriHandlerActivity.ACTION_SCAN_QR_CODE);
            if (provisioning) {
                intent.putExtra(EXTRA_ALLOW_PROVISIONING, true);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            activity.startActivity(intent);
        } else {
            activity.requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    provisioning ? REQUEST_CAMERA_PERMISSIONS_TO_SCAN_AND_PROVISION : REQUEST_CAMERA_PERMISSIONS_TO_SCAN
            );
        }
    }

    public static void onRequestPermissionResult(Activity activity, int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CAMERA_PERMISSIONS_TO_SCAN && requestCode != REQUEST_CAMERA_PERMISSIONS_TO_SCAN_AND_PROVISION) {
            return;
        }
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_CAMERA_PERMISSIONS_TO_SCAN_AND_PROVISION) {
                    scan(activity, true);
                } else {
                    scan(activity);
                }
            } else {
                Toast.makeText(activity, R.string.qr_code_scanner_needs_access_to_camera, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_uri_handler);
    }

    @Override
    public void onStart() {
        super.onStart();
        handleIntent(getIntent());
    }

    @Override
    public void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private boolean handleUri(final Uri uri) {
        return handleUri(uri, false);
    }

    private boolean handleUri(final Uri uri, final boolean scanned) {
        final Intent intent;
        final XmppUri xmppUri = new XmppUri(uri);
        final List<Jid> accounts = DatabaseBackend.getInstance(this).getAccountJids(true);

        if (SignupUtils.isSupportTokenRegistry() && xmppUri.isValidJid()) {
            final String preAuth = xmppUri.getParameter(XmppUri.PARAMETER_PRE_AUTH);
            final Jid jid = xmppUri.getJid();
            if (xmppUri.isAction(XmppUri.ACTION_REGISTER)) {
                if (jid.getEscapedLocal() != null && accounts.contains(jid.asBareJid())) {
                    showError(R.string.account_already_exists);
                    return false;
                }
                intent = SignupUtils.getTokenRegistrationIntent(this, jid, preAuth);
                startActivity(intent);
                return true;
            }
            if (accounts.size() == 0 && xmppUri.isAction(XmppUri.ACTION_ROSTER) && "y".equals(xmppUri.getParameter(XmppUri.PARAMETER_IBR))) {
                intent = SignupUtils.getTokenRegistrationIntent(this, jid.getDomain(), preAuth);
                intent.putExtra(StartConversationActivity.EXTRA_INVITE_URI, xmppUri.toString());
                startActivity(intent);
                return true;
            }
        } else if (xmppUri.isAction(XmppUri.ACTION_REGISTER)) {
            showError(R.string.account_registrations_are_not_supported);
            return false;
        }

        if (accounts.size() == 0) {
            if (xmppUri.isValidJid()) {
                intent = SignupUtils.getSignUpIntent(this);
                intent.putExtra(StartConversationActivity.EXTRA_INVITE_URI, xmppUri.toString());
                startActivity(intent);
                return true;
            } else {
                showError(R.string.invalid_jid);
                return false;
            }
        }

        if (xmppUri.isAction(XmppUri.ACTION_MESSAGE)) {
            final Jid jid = xmppUri.getJid();
            final String body = xmppUri.getBody();

            if (jid != null) {
                final Class<?> clazz = findShareViaAccountClass();
                if (clazz != null) {
                    intent = new Intent(this, clazz);
                    intent.putExtra("contact", jid.toEscapedString());
                    intent.putExtra("body", body);
                } else {
                    intent = new Intent(this, StartConversationActivity.class);
                    intent.setAction(Intent.ACTION_VIEW);
                    intent.setData(uri);
                    intent.putExtra("account", accounts.get(0).toEscapedString());
                }
            } else {
                intent = new Intent(this, ShareWithActivity.class);
                intent.setAction(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, body);
            }
        } else if (accounts.contains(xmppUri.getJid())) {
            intent = new Intent(getApplicationContext(), EditAccountActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("jid", xmppUri.getJid().asBareJid().toString());
            intent.setData(uri);
            intent.putExtra("scanned", scanned);
        } else if (xmppUri.isValidJid()) {
            intent = new Intent(getApplicationContext(), StartConversationActivity.class);
            intent.setAction(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            intent.putExtra("scanned", scanned);
            intent.setData(uri);
        } else {
            showError(R.string.invalid_jid);
            return false;
        }
        startActivity(intent);
        return true;
    }

    private void checkForLinkHeader(final HttpUrl url) {
        Log.d(Config.LOGTAG, "checking for link header on " + url);
        this.call = HttpConnectionManager.OK_HTTP_CLIENT.newCall(new Request.Builder()
                .url(url)
                .head()
                .build());
        this.call.enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                Log.d(Config.LOGTAG, "unable to check HTTP url", e);
                showError(R.string.no_xmpp_adddress_found);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                if (response.isSuccessful()) {
                    final String linkHeader = response.header("Link");
                    if (linkHeader != null && processLinkHeader(linkHeader)) {
                        return;
                    }
                }
                showError(R.string.no_xmpp_adddress_found);
            }
        });

    }

    private boolean processLinkHeader(final String header) {
        final Matcher matcher = LINK_HEADER_PATTERN.matcher(header);
        if (matcher.find()) {
            final String group = matcher.group();
            final String link = group.substring(1, group.length() - 1);
            if (handleUri(Uri.parse(link))) {
                finish();
                return true;
            }
        }
        return false;
    }

    private void showError(@StringRes int error) {
        this.binding.progress.setVisibility(View.INVISIBLE);
        this.binding.error.setText(error);
        this.binding.error.setVisibility(View.VISIBLE);
    }

    private static Class<?> findShareViaAccountClass() {
        try {
            return Class.forName("eu.siacs.conversations.ui.ShareViaAccountActivity");
        } catch (final ClassNotFoundException e) {
            return null;
        }
    }

    private void handleIntent(final Intent data) {
        final String action = data == null ? null : data.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case Intent.ACTION_MAIN:
                binding.progress.setVisibility(call != null && !call.isCanceled() ? View.VISIBLE : View.INVISIBLE);
                break;
            case Intent.ACTION_VIEW:
            case Intent.ACTION_SENDTO:
                if (handleUri(data.getData())) {
                    finish();
                }
                break;
            case ACTION_SCAN_QR_CODE:
                Log.d(Config.LOGTAG, "scan. allow=" + allowProvisioning());
                setIntent(createMainIntent());
                startActivityForResult(new Intent(this, ScanActivity.class), REQUEST_SCAN_QR_CODE);
                break;
        }
    }

    private Intent createMainIntent() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.putExtra(EXTRA_ALLOW_PROVISIONING, allowProvisioning());
        return intent;
    }

    private boolean allowProvisioning() {
        final Intent launchIntent = getIntent();
        return launchIntent != null && launchIntent.getBooleanExtra(EXTRA_ALLOW_PROVISIONING, false);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, requestCode, intent);
        if (requestCode == REQUEST_SCAN_QR_CODE && resultCode == RESULT_OK) {
            final boolean allowProvisioning = allowProvisioning();
            final String result = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
            if (Strings.isNullOrEmpty(result)) {
                finish();
                return;
            }
            if (result.startsWith("BEGIN:VCARD\n")) {
                final Matcher matcher = V_CARD_XMPP_PATTERN.matcher(result);
                if (matcher.find()) {
                    if (handleUri(Uri.parse(matcher.group(2)), true)) {
                        finish();
                    }
                } else {
                    showError(R.string.no_xmpp_adddress_found);
                }
                return;
            } else if (QuickConversationsService.isConversations() && looksLikeJsonObject(result) && allowProvisioning) {
                ProvisioningUtils.provision(this, result);
                finish();
                return;
            }
            final Uri uri = Uri.parse(result.trim());
            if (allowProvisioning && "https".equalsIgnoreCase(uri.getScheme()) && !XmppUri.INVITE_DOMAIN.equalsIgnoreCase(uri.getHost())) {
                final HttpUrl httpUrl = HttpUrl.parse(uri.toString());
                if (httpUrl != null) {
                    checkForLinkHeader(httpUrl);
                } else {
                    finish();
                }
            } else if (handleUri(uri, true)) {
                finish();
            } else {
                setIntent(new Intent(Intent.ACTION_VIEW, uri));
            }
        } else {
            finish();
        }
    }

    private static boolean looksLikeJsonObject(final String input) {
        final String trimmed = Strings.nullToEmpty(input).trim();
        return trimmed.charAt(0) == '{' && trimmed.charAt(trimmed.length() - 1) == '}';
    }
}