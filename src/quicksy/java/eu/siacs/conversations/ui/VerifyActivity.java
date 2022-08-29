package eu.siacs.conversations.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import androidx.databinding.DataBindingUtil;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.widget.Toolbar;
import android.text.Html;
import android.view.View;

import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityVerifyBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.ui.util.ApiDialogHelper;
import eu.siacs.conversations.ui.util.PinEntryWrapper;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.utils.PhoneNumberUtilWrapper;
import eu.siacs.conversations.utils.TimeFrameUtils;
import io.michaelrocks.libphonenumber.android.NumberParseException;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

public class VerifyActivity extends XmppActivity implements ClipboardManager.OnPrimaryClipChangedListener, QuickConversationsService.OnVerification, QuickConversationsService.OnVerificationRequested {

    public static final String EXTRA_RETRY_SMS_AFTER = "retry_sms_after";
    private static final String EXTRA_RETRY_VERIFICATION_AFTER = "retry_verification_after";
    private final Handler mHandler = new Handler();
    private ActivityVerifyBinding binding;
    private Account account;
    private PinEntryWrapper pinEntryWrapper;
    private ClipboardManager clipboardManager;
    private String pasted = null;
    private boolean verifying = false;
    private boolean requestingVerification = false;
    private long retrySmsAfter = 0;
    private final Runnable SMS_TIMEOUT_UPDATER = new Runnable() {
        @Override
        public void run() {
            if (setTimeoutLabelInResendButton()) {
                mHandler.postDelayed(this, 300);
            }
        }
    };
    private long retryVerificationAfter = 0;
    private final Runnable VERIFICATION_TIMEOUT_UPDATER = new Runnable() {
        @Override
        public void run() {
            if (setTimeoutLabelInNextButton()) {
                mHandler.postDelayed(this, 300);
            }
        }
    };
    private final AtomicBoolean redirectInProgress = new AtomicBoolean(false);

    private boolean setTimeoutLabelInResendButton() {
        if (retrySmsAfter != 0) {
            long remaining = retrySmsAfter - SystemClock.elapsedRealtime();
            if (remaining >= 0) {
                binding.resendSms.setEnabled(false);
                binding.resendSms.setText(getString(R.string.resend_sms_in, TimeFrameUtils.resolve(VerifyActivity.this, remaining)));
                return true;
            }
        }
        binding.resendSms.setEnabled(true);
        binding.resendSms.setText(R.string.resend_sms);
        return false;
    }

    private boolean setTimeoutLabelInNextButton() {
        if (retryVerificationAfter != 0) {
            long remaining = retryVerificationAfter - SystemClock.elapsedRealtime();
            if (remaining >= 0) {
                binding.next.setEnabled(false);
                binding.next.setText(getString(R.string.wait_x, TimeFrameUtils.resolve(VerifyActivity.this, remaining)));
                return true;
            }
        }
        this.binding.next.setEnabled(!verifying);
        this.binding.next.setText(verifying ? R.string.verifying : R.string.next);
        return false;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String pin = savedInstanceState != null ? savedInstanceState.getString("pin") : null;
        boolean verifying = savedInstanceState != null && savedInstanceState.getBoolean("verifying");
        boolean requestingVerification = savedInstanceState != null && savedInstanceState.getBoolean("requesting_verification", false);
        this.pasted = savedInstanceState != null ? savedInstanceState.getString("pasted") : null;
        this.retrySmsAfter = savedInstanceState != null ? savedInstanceState.getLong(EXTRA_RETRY_SMS_AFTER, 0L) : 0L;
        this.retryVerificationAfter = savedInstanceState != null ? savedInstanceState.getLong(EXTRA_RETRY_VERIFICATION_AFTER, 0L) : 0L;
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_verify);
        setSupportActionBar((Toolbar) this.binding.toolbar);
        this.pinEntryWrapper = new PinEntryWrapper(binding.pinBox);
        if (pin != null) {
            this.pinEntryWrapper.setPin(pin);
        }
        binding.back.setOnClickListener(this::onBackButton);
        binding.next.setOnClickListener(this::onNextButton);
        binding.resendSms.setOnClickListener(this::onResendSmsButton);
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        setVerifyingState(verifying);
        setRequestingVerificationState(requestingVerification);
    }

    private void onBackButton(View view) {
        if (this.verifying) {
            setVerifyingState(false);
            return;
        }
        final Intent intent = new Intent(this, EnterPhoneNumberActivity.class);
        if (this.account != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.abort_registration_procedure);
            builder.setPositiveButton(R.string.yes, (dialog, which) -> {
                xmppConnectionService.deleteAccount(account);
                startActivity(intent);
                finish();
            });
            builder.setNegativeButton(R.string.no, null);
            builder.create().show();
        } else {
            startActivity(intent);
            finish();
        }
    }

    private void onNextButton(View view) {
        final String pin = pinEntryWrapper.getPin();
        if (PinEntryWrapper.isValidPin(pin)) {
            if (account != null && xmppConnectionService != null) {
                setVerifyingState(true);
                xmppConnectionService.getQuickConversationsService().verify(account, pin);
            }
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.please_enter_pin);
            builder.setPositiveButton(R.string.ok, null);
            builder.create().show();
        }
    }

    private void onResendSmsButton(View view) {
        try {
            xmppConnectionService.getQuickConversationsService().requestVerification(PhoneNumberUtilWrapper.toPhoneNumber(this, account.getJid()));
            setRequestingVerificationState(true);
        } catch (NumberParseException e) {

        }
    }

    private void setVerifyingState(boolean verifying) {
        this.verifying = verifying;
        this.binding.back.setText(verifying ? R.string.cancel : R.string.back);
        this.binding.next.setEnabled(!verifying);
        this.binding.next.setText(verifying ? R.string.verifying : R.string.next);
        this.binding.resendSms.setVisibility(verifying ? View.GONE : View.VISIBLE);
        pinEntryWrapper.setEnabled(!verifying);
        this.binding.progressBar.setVisibility(verifying ? View.VISIBLE : View.GONE);
        this.binding.progressBar.setIndeterminate(verifying);
    }

    private void setRequestingVerificationState(boolean requesting) {
        this.requestingVerification = requesting;
        if (requesting) {
            this.binding.resendSms.setEnabled(false);
            this.binding.resendSms.setText(R.string.requesting_sms);
        } else {
            setTimeoutLabelInResendButton();
        }

    }

    @Override
    protected void refreshUiReal() {

    }

    @Override
    void onBackendConnected() {
        xmppConnectionService.getQuickConversationsService().addOnVerificationListener(this);
        xmppConnectionService.getQuickConversationsService().addOnVerificationRequestedListener(this);
        this.account = AccountUtils.getFirst(xmppConnectionService);
        if (this.account == null) {
            return;
        }
        if (!account.isOptionSet(Account.OPTION_UNVERIFIED) && !account.isOptionSet(Account.OPTION_DISABLED)) {
            runOnUiThread(this::performPostVerificationRedirect);
            return;
        }
        this.binding.weHaveSent.setText(Html.fromHtml(getString(R.string.we_have_sent_you_an_sms_to_x, PhoneNumberUtilWrapper.toFormattedPhoneNumber(this, this.account.getJid()))));
        setVerifyingState(xmppConnectionService.getQuickConversationsService().isVerifying());
        setRequestingVerificationState(xmppConnectionService.getQuickConversationsService().isRequestingVerification());
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putString("pin", this.pinEntryWrapper.getPin());
        savedInstanceState.putBoolean("verifying", this.verifying);
        savedInstanceState.putBoolean("requesting_verification", this.requestingVerification);
        savedInstanceState.putLong(EXTRA_RETRY_SMS_AFTER, this.retrySmsAfter);
        savedInstanceState.putLong(EXTRA_RETRY_VERIFICATION_AFTER, this.retryVerificationAfter);
        if (this.pasted != null) {
            savedInstanceState.putString("pasted", this.pasted);
        }
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        clipboardManager.addPrimaryClipChangedListener(this);
        final Intent intent = getIntent();
        this.retrySmsAfter = intent != null ? intent.getLongExtra(EXTRA_RETRY_SMS_AFTER, this.retrySmsAfter) : this.retrySmsAfter;
        if (this.retrySmsAfter > 0) {
            mHandler.post(SMS_TIMEOUT_UPDATER);
        }
        if (this.retryVerificationAfter > 0) {
            mHandler.post(VERIFICATION_TIMEOUT_UPDATER);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mHandler.removeCallbacks(SMS_TIMEOUT_UPDATER);
        mHandler.removeCallbacks(VERIFICATION_TIMEOUT_UPDATER);
        clipboardManager.removePrimaryClipChangedListener(this);
        if (xmppConnectionService != null) {
            xmppConnectionService.getQuickConversationsService().removeOnVerificationListener(this);
            xmppConnectionService.getQuickConversationsService().removeOnVerificationRequestedListener(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pinEntryWrapper.isEmpty()) {
            //starting with Android P we need input focus
            pinEntryWrapper.requestFocus();
            pastePinFromClipboard();
        }
    }

    private void pastePinFromClipboard() {
        final ClipDescription description = clipboardManager != null ? clipboardManager.getPrimaryClipDescription() : null;
        if (description != null && description.hasMimeType(MIMETYPE_TEXT_PLAIN)) {
            final ClipData primaryClip = clipboardManager.getPrimaryClip();
            if (primaryClip != null && primaryClip.getItemCount() > 0) {
                final CharSequence clip = primaryClip.getItemAt(0).getText();
                if (PinEntryWrapper.isValidPin(clip) && !clip.toString().equals(this.pasted)) {
                    this.pasted = clip.toString();
                    pinEntryWrapper.setPin(clip.toString());
                    final Snackbar snackbar = Snackbar.make(binding.coordinator, R.string.possible_pin, Snackbar.LENGTH_LONG);
                    snackbar.setAction(R.string.undo, v -> pinEntryWrapper.clear());
                    snackbar.show();
                }
            }
        }
    }

    private void performPostVerificationRedirect() {
        if (redirectInProgress.compareAndSet(false, true)) {
            Intent intent = new Intent(this, EnterNameActivity.class);
            startActivity(intent);
            finish();
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        this.pasted = null;
        if (pinEntryWrapper.isEmpty()) {
            pastePinFromClipboard();
        }
    }

    @Override
    public void onVerificationFailed(final int code) {
        runOnUiThread(() -> {
            setVerifyingState(false);
            if (code == 401 || code == 404) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(code == 404 ? R.string.pin_expired : R.string.incorrect_pin);
                builder.setPositiveButton(R.string.ok, null);
                builder.create().show();
            } else {
                ApiDialogHelper.createError(this, code).show();
            }
        });
    }

    @Override
    public void onVerificationSucceeded() {
        runOnUiThread(this::performPostVerificationRedirect);
    }

    @Override
    public void onVerificationRetryAt(long timestamp) {
        this.retryVerificationAfter = timestamp;
        runOnUiThread(() -> {
            ApiDialogHelper.createTooManyAttempts(this).show();
            setVerifyingState(false);
        });
        mHandler.removeCallbacks(VERIFICATION_TIMEOUT_UPDATER);
        runOnUiThread(VERIFICATION_TIMEOUT_UPDATER);
    }

    @Override
    public void startBackgroundVerification(String pin) {
        pinEntryWrapper.setPin(pin);
        setVerifyingState(true);
    }

    //send sms again button callback
    @Override
    public void onVerificationRequestFailed(int code) {
        runOnUiThread(() -> {
            setRequestingVerificationState(false);
            ApiDialogHelper.createError(this, code).show();
        });
    }

    //send sms again button callback
    @Override
    public void onVerificationRequested() {
        runOnUiThread(() -> {
            pinEntryWrapper.clear();
            setRequestingVerificationState(false);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.we_have_sent_you_another_sms);
            builder.setPositiveButton(R.string.ok, null);
            builder.create().show();
        });
    }

    @Override
    public void onVerificationRequestedRetryAt(long timestamp) {
        this.retrySmsAfter = timestamp;
        runOnUiThread(() -> {
            ApiDialogHelper.createRateLimited(this, timestamp).show();
            setRequestingVerificationState(false);
        });
        mHandler.removeCallbacks(SMS_TIMEOUT_UPDATER);
        runOnUiThread(SMS_TIMEOUT_UPDATER);
    }
}
