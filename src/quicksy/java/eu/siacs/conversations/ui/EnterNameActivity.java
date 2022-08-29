package eu.siacs.conversations.ui;

import android.content.Intent;
import androidx.databinding.DataBindingUtil;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.view.View;

import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityEnterNameBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.AccountUtils;

public class EnterNameActivity extends XmppActivity implements XmppConnectionService.OnAccountUpdate {

    private ActivityEnterNameBinding binding;

    private Account account;

    private final AtomicBoolean setNick = new AtomicBoolean(false);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_enter_name);
        setSupportActionBar((Toolbar) this.binding.toolbar);
        this.binding.next.setOnClickListener(this::next);
        this.setNick.set(savedInstanceState != null && savedInstanceState.getBoolean("set_nick",false));
    }

    private void next(View view) {
        if (account != null) {

            String name = this.binding.name.getText().toString().trim();

            account.setDisplayName(name);

            xmppConnectionService.publishDisplayName(account);

            Intent intent = new Intent(this, PublishProfilePictureActivity.class);
            intent.putExtra(PublishProfilePictureActivity.EXTRA_ACCOUNT, account.getJid().asBareJid().toEscapedString());
            intent.putExtra("setup", true);
            startActivity(intent);
        }
        finish();
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putBoolean("set_nick", this.setNick.get());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void refreshUiReal() {
        checkSuggestPreviousNick();
    }

    @Override
    void onBackendConnected() {
        this.account = AccountUtils.getFirst(xmppConnectionService);
        checkSuggestPreviousNick();
    }

    private void checkSuggestPreviousNick() {
        String displayName = this.account == null ? null : this.account.getDisplayName();
        if (displayName != null) {
            if (setNick.compareAndSet(false, true) && this.binding.name.getText().length() == 0) {
                this.binding.name.getText().append(displayName);
            }
        }
    }

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }
}
