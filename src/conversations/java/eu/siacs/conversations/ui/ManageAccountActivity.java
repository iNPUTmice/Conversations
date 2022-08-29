package eu.siacs.conversations.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.security.KeyChain;
import android.security.KeyChainAliasCallback;
import android.util.Pair;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;

import org.openintents.openpgp.util.OpenPgpApi;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.services.XmppConnectionService.OnAccountUpdate;
import eu.siacs.conversations.ui.adapter.AccountAdapter;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;

import static eu.siacs.conversations.utils.PermissionUtils.allGranted;
import static eu.siacs.conversations.utils.PermissionUtils.writeGranted;

public class ManageAccountActivity extends XmppActivity implements OnAccountUpdate, KeyChainAliasCallback, XmppConnectionService.OnAccountCreated, AccountAdapter.OnTglAccountState {

    private final String STATE_SELECTED_ACCOUNT = "selected_account";

    private static final int REQUEST_IMPORT_BACKUP = 0x63fb;

    protected Account selectedAccount = null;
    protected Jid selectedAccountJid = null;

    protected final List<Account> accountList = new ArrayList<>();
    protected ListView accountListView;
    protected AccountAdapter mAccountAdapter;
    protected AtomicBoolean mInvokedAddAccount = new AtomicBoolean(false);

    protected Pair<Integer, Intent> mPostponedActivityResult = null;

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        synchronized (this.accountList) {
            accountList.clear();
            accountList.addAll(xmppConnectionService.getAccounts());
        }
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setHomeButtonEnabled(this.accountList.size() > 0);
            actionBar.setDisplayHomeAsUpEnabled(this.accountList.size() > 0);
        }
        invalidateOptionsMenu();
        mAccountAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_manage_accounts);
        setSupportActionBar(findViewById(R.id.toolbar));
        configureActionBar(getSupportActionBar());
        if (savedInstanceState != null) {
            String jid = savedInstanceState.getString(STATE_SELECTED_ACCOUNT);
            if (jid != null) {
                try {
                    this.selectedAccountJid = Jid.ofEscaped(jid);
                } catch (IllegalArgumentException e) {
                    this.selectedAccountJid = null;
                }
            }
        }

        accountListView = findViewById(R.id.account_list);
        this.mAccountAdapter = new AccountAdapter(this, accountList);
        accountListView.setAdapter(this.mAccountAdapter);
        accountListView.setOnItemClickListener((arg0, view, position, arg3) -> switchToAccount(accountList.get(position)));
        registerForContextMenu(accountListView);
    }

    @Override
    protected void onStart() {
        super.onStart();
        final int theme = findTheme();
        if (this.mTheme != theme) {
            recreate();
        }
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        if (selectedAccount != null) {
            savedInstanceState.putString(STATE_SELECTED_ACCOUNT, selectedAccount.getJid().asBareJid().toEscapedString());
        }
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        ManageAccountActivity.this.getMenuInflater().inflate(
                R.menu.manageaccounts_context, menu);
        AdapterContextMenuInfo acmi = (AdapterContextMenuInfo) menuInfo;
        this.selectedAccount = accountList.get(acmi.position);
        if (this.selectedAccount.isEnabled()) {
            menu.findItem(R.id.mgmt_account_enable).setVisible(false);
            menu.findItem(R.id.mgmt_account_announce_pgp).setVisible(Config.supportOpenPgp());
        } else {
            menu.findItem(R.id.mgmt_account_disable).setVisible(false);
            menu.findItem(R.id.mgmt_account_announce_pgp).setVisible(false);
            menu.findItem(R.id.mgmt_account_publish_avatar).setVisible(false);
        }
        menu.setHeaderTitle(this.selectedAccount.getJid().asBareJid().toEscapedString());
    }

    @Override
    void onBackendConnected() {
        if (selectedAccountJid != null) {
            this.selectedAccount = xmppConnectionService.findAccountByJid(selectedAccountJid);
        }
        refreshUiReal();
        if (this.mPostponedActivityResult != null) {
            this.onActivityResult(mPostponedActivityResult.first, RESULT_OK, mPostponedActivityResult.second);
        }
        if (Config.X509_VERIFICATION && this.accountList.size() == 0) {
            if (mInvokedAddAccount.compareAndSet(false, true)) {
                addAccountFromKey();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.manageaccounts, menu);
        MenuItem enableAll = menu.findItem(R.id.action_enable_all);
        MenuItem addAccount = menu.findItem(R.id.action_add_account);
        MenuItem addAccountWithCertificate = menu.findItem(R.id.action_add_account_with_cert);

        if (Config.X509_VERIFICATION) {
            addAccount.setVisible(false);
            addAccountWithCertificate.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }

        if (!accountsLeftToEnable()) {
            enableAll.setVisible(false);
        }
        MenuItem disableAll = menu.findItem(R.id.action_disable_all);
        if (!accountsLeftToDisable()) {
            disableAll.setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.mgmt_account_publish_avatar:
                publishAvatar(selectedAccount);
                return true;
            case R.id.mgmt_account_disable:
                disableAccount(selectedAccount);
                return true;
            case R.id.mgmt_account_enable:
                enableAccount(selectedAccount);
                return true;
            case R.id.mgmt_account_delete:
                deleteAccount(selectedAccount);
                return true;
            case R.id.mgmt_account_announce_pgp:
                publishOpenPGPPublicKey(selectedAccount);
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case R.id.action_add_account:
                startActivity(new Intent(this, EditAccountActivity.class));
                break;
            case R.id.action_import_backup:
                if (hasStoragePermission(REQUEST_IMPORT_BACKUP)) {
                    startActivity(new Intent(this, ImportBackupActivity.class));
                }
                break;
            case R.id.action_disable_all:
                disableAllAccounts();
                break;
            case R.id.action_enable_all:
                enableAllAccounts();
                break;
            case R.id.action_add_account_with_cert:
                addAccountFromKey();
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0) {
            if (allGranted(grantResults)) {
                switch (requestCode) {
                    case REQUEST_IMPORT_BACKUP:
                        startActivity(new Intent(this, ImportBackupActivity.class));
                        break;
                }
            } else {
                Toast.makeText(this, R.string.no_storage_permission, Toast.LENGTH_SHORT).show();
            }
        }
        if (writeGranted(grantResults, permissions)) {
            if (xmppConnectionService != null) {
                xmppConnectionService.restartFileObserver();
            }
        }
    }

    @Override
    public boolean onNavigateUp() {
        if (xmppConnectionService.getConversations().size() == 0) {
            Intent contactsIntent = new Intent(this,
                    StartConversationActivity.class);
            contactsIntent.setFlags(
                    // if activity exists in stack, pop the stack and go back to it
                    Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            // otherwise, make a new task for it
                            Intent.FLAG_ACTIVITY_NEW_TASK |
                            // don't use the new activity animation; finish
                            // animation runs instead
                            Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(contactsIntent);
            finish();
            return true;
        } else {
            return super.onNavigateUp();
        }
    }

    @Override
    public void onClickTglAccountState(Account account, boolean enable) {
        if (enable) {
            enableAccount(account);
        } else {
            disableAccount(account);
        }
    }

    private void addAccountFromKey() {
        try {
            KeyChain.choosePrivateKeyAlias(this, this, null, null, null, -1, null);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.device_does_not_support_certificates, Toast.LENGTH_LONG).show();
        }
    }

    private void publishAvatar(Account account) {
        Intent intent = new Intent(getApplicationContext(),
                PublishProfilePictureActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toEscapedString());
        startActivity(intent);
    }

    private void disableAllAccounts() {
        List<Account> list = new ArrayList<>();
        synchronized (this.accountList) {
            for (Account account : this.accountList) {
                if (account.isEnabled()) {
                    list.add(account);
                }
            }
        }
        for (Account account : list) {
            disableAccount(account);
        }
    }

    private boolean accountsLeftToDisable() {
        synchronized (this.accountList) {
            for (Account account : this.accountList) {
                if (account.isEnabled()) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean accountsLeftToEnable() {
        synchronized (this.accountList) {
            for (Account account : this.accountList) {
                if (!account.isEnabled()) {
                    return true;
                }
            }
            return false;
        }
    }

    private void enableAllAccounts() {
        List<Account> list = new ArrayList<>();
        synchronized (this.accountList) {
            for (Account account : this.accountList) {
                if (!account.isEnabled()) {
                    list.add(account);
                }
            }
        }
        for (Account account : list) {
            enableAccount(account);
        }
    }

    private void disableAccount(Account account) {
        account.setOption(Account.OPTION_DISABLED, true);
        if (!xmppConnectionService.updateAccount(account)) {
            Toast.makeText(this, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show();
        }
    }

    private void enableAccount(Account account) {
        account.setOption(Account.OPTION_DISABLED, false);
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.resetEverything();
        }
        if (!xmppConnectionService.updateAccount(account)) {
            Toast.makeText(this, R.string.unable_to_update_account, Toast.LENGTH_SHORT).show();
        }
    }

    private void publishOpenPGPPublicKey(Account account) {
        if (ManageAccountActivity.this.hasPgp()) {
            announcePgp(selectedAccount, null, null, onOpenPGPKeyPublished);
        } else {
            this.showInstallPgpDialog();
        }
    }

    private void deleteAccount(final Account account) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.mgmt_account_are_you_sure));
        builder.setIconAttribute(android.R.attr.alertDialogIcon);
        builder.setMessage(getString(R.string.mgmt_account_delete_confirm_text));
        builder.setPositiveButton(getString(R.string.delete),
                (dialog, which) -> {
                    xmppConnectionService.deleteAccount(account);
                    selectedAccount = null;
                    if (xmppConnectionService.getAccounts().size() == 0 && Config.MAGIC_CREATE_DOMAIN != null) {
                        WelcomeActivity.launch(this);
                    }
                });
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.create().show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK) {
            if (xmppConnectionServiceBound) {
                if (requestCode == REQUEST_CHOOSE_PGP_ID) {
                    if (data.getExtras().containsKey(OpenPgpApi.EXTRA_SIGN_KEY_ID)) {
                        selectedAccount.setPgpSignId(data.getExtras().getLong(OpenPgpApi.EXTRA_SIGN_KEY_ID));
                        announcePgp(selectedAccount, null, null, onOpenPGPKeyPublished);
                    } else {
                        choosePgpSignId(selectedAccount);
                    }
                } else if (requestCode == REQUEST_ANNOUNCE_PGP) {
                    announcePgp(selectedAccount, null, data, onOpenPGPKeyPublished);
                }
                this.mPostponedActivityResult = null;
            } else {
                this.mPostponedActivityResult = new Pair<>(requestCode, data);
            }
        }
    }

    @Override
    public void alias(final String alias) {
        if (alias != null) {
            xmppConnectionService.createAccountFromKey(alias, this);
        }
    }

    @Override
    public void onAccountCreated(final Account account) {
        final Intent intent = new Intent(this, EditAccountActivity.class);
        intent.putExtra("jid", account.getJid().asBareJid().toString());
        intent.putExtra("init", true);
        startActivity(intent);
    }

    @Override
    public void informUser(final int r) {
        runOnUiThread(() -> Toast.makeText(ManageAccountActivity.this, r, Toast.LENGTH_LONG).show());
    }
}
