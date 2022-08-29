package eu.siacs.conversations.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.databinding.DataBindingUtil;

import com.google.common.base.Strings;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityEasyInviteBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.BarcodeProvider;
import eu.siacs.conversations.utils.EasyOnboardingInvite;
import eu.siacs.conversations.xmpp.Jid;

public class EasyOnboardingInviteActivity extends XmppActivity implements EasyOnboardingInvite.OnInviteRequested {

    private ActivityEasyInviteBinding binding;

    private EasyOnboardingInvite easyOnboardingInvite;


    @Override
    public void onCreate(final Bundle bundle) {
        super.onCreate(bundle);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_easy_invite);
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar(), true);
        this.binding.shareButton.setOnClickListener(v -> share());
        if (bundle != null && bundle.containsKey("invite")) {
            this.easyOnboardingInvite = bundle.getParcelable("invite");
            if (this.easyOnboardingInvite != null) {
                showInvite(this.easyOnboardingInvite);
                return;
            }
        }
        this.showLoading();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.easy_onboarding_invite, menu);
        final MenuItem share = menu.findItem(R.id.action_share);
        share.setVisible(easyOnboardingInvite != null);
        return super.onCreateOptionsMenu(menu);
    }

    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.action_share) {
            share();
            return true;
        } else {
            return super.onOptionsItemSelected(menuItem);
        }
    }

    private void share() {
        final String shareText = getString(
                R.string.easy_invite_share_text,
                easyOnboardingInvite.getDomain(),
                easyOnboardingInvite.getShareableLink()
        );
        final Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        sendIntent.setType("text/plain");
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_invite_with)));
    }

    @Override
    protected void refreshUiReal() {
        invalidateOptionsMenu();
        if (easyOnboardingInvite != null) {
            showInvite(easyOnboardingInvite);
        } else {
            showLoading();
        }
    }

    private void showLoading() {
        this.binding.inProgress.setVisibility(View.VISIBLE);
        this.binding.invite.setVisibility(View.GONE);
    }

    private void showInvite(final EasyOnboardingInvite invite) {
        this.binding.inProgress.setVisibility(View.GONE);
        this.binding.invite.setVisibility(View.VISIBLE);
        this.binding.tapToShare.setText(getString(R.string.tap_share_button_send_invite, invite.getDomain()));
        final Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        final int width = Math.min(size.x, size.y);
        final Bitmap bitmap = BarcodeProvider.create2dBarcodeBitmap(invite.getShareableLink(), width);
        binding.qrCode.setImageBitmap(bitmap);
    }

    @Override
    public void onSaveInstanceState(Bundle bundle) {
        super.onSaveInstanceState(bundle);
        if (easyOnboardingInvite != null) {
            bundle.putParcelable("invite", easyOnboardingInvite);
        }
    }

    @Override
    void onBackendConnected() {
        if (easyOnboardingInvite != null) {
            return;
        }
        final Intent launchIntent = getIntent();
        final String accountExtra = launchIntent.getStringExtra(EXTRA_ACCOUNT);
        final Jid jid = accountExtra == null ? null : Jid.ofEscaped(accountExtra);
        if (jid == null) {
            return;
        }
        final Account account = xmppConnectionService.findAccountByJid(jid);
        xmppConnectionService.requestEasyOnboardingInvite(account, this);
    }

    public static void launch(final Account account, final Activity context) {
        final Intent intent = new Intent(context, EasyOnboardingInviteActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toEscapedString());
        context.startActivity(intent);
    }

    @Override
    public void inviteRequested(EasyOnboardingInvite invite) {
        this.easyOnboardingInvite = invite;
        Log.d(Config.LOGTAG, "invite requested");
        refreshUi();
    }

    @Override
    public void inviteRequestFailed(final String message) {
        runOnUiThread(() -> {
            if (!Strings.isNullOrEmpty(message)) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
            finish();
        });
    }
}
