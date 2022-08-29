package eu.siacs.conversations.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView.MultiChoiceModeListener;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.util.ActivityResult;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;

public class ChooseContactActivity extends AbstractSearchableListItemActivity implements MultiChoiceModeListener, AdapterView.OnItemClickListener {
    public static final String EXTRA_TITLE_RES_ID = "extra_title_res_id";
    public static final String EXTRA_GROUP_CHAT_NAME = "extra_group_chat_name";
    public static final String EXTRA_SELECT_MULTIPLE = "extra_select_multiple";
    public static final String EXTRA_SHOW_ENTER_JID = "extra_show_enter_jid";
    public static final String EXTRA_CONVERSATION = "extra_conversation";
    private static final String EXTRA_FILTERED_CONTACTS = "extra_filtered_contacts";
    private final List<String> mActivatedAccounts = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private Set<String> filterContacts;

    private boolean showEnterJid = false;
    private boolean startSearching = false;
    private boolean multiple = false;

    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();

    public static Intent create(Activity activity, Conversation conversation) {
        final Intent intent = new Intent(activity, ChooseContactActivity.class);
        List<String> contacts = new ArrayList<>();
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            for (MucOptions.User user : conversation.getMucOptions().getUsers(false)) {
                Jid jid = user.getRealJid();
                if (jid != null) {
                    contacts.add(jid.asBareJid().toString());
                }
            }
        } else {
            contacts.add(conversation.getJid().asBareJid().toString());
        }
        intent.putExtra(EXTRA_FILTERED_CONTACTS, contacts.toArray(new String[contacts.size()]));
        intent.putExtra(EXTRA_CONVERSATION, conversation.getUuid());
        intent.putExtra(EXTRA_SELECT_MULTIPLE, true);
        intent.putExtra(EXTRA_SHOW_ENTER_JID, true);
        intent.putExtra(EXTRA_ACCOUNT, conversation.getAccount().getJid().asBareJid().toEscapedString());
        return intent;
    }

    public static List<Jid> extractJabberIds(Intent result) {
        List<Jid> jabberIds = new ArrayList<>();
        try {
            if (result.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false)) {
                String[] toAdd = result.getStringArrayExtra("contacts");
                for (String item : toAdd) {
                    jabberIds.add(Jid.of(item));
                }
            } else {
                jabberIds.add(Jid.of(result.getStringExtra("contact")));
            }
            return jabberIds;
        } catch (IllegalArgumentException e) {
            return jabberIds;
        }
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        filterContacts = new HashSet<>();
        if (savedInstanceState != null) {
            String[] selectedContacts = savedInstanceState.getStringArray("selected_contacts");
            if (selectedContacts != null) {
                selected.clear();
                selected.addAll(Arrays.asList(selectedContacts));
            }
        }

        String[] contacts = getIntent().getStringArrayExtra(EXTRA_FILTERED_CONTACTS);
        if (contacts != null) {
            Collections.addAll(filterContacts, contacts);
        }

        Intent intent = getIntent();

        multiple = intent.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false);
        if (multiple) {
            getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
            getListView().setMultiChoiceModeListener(this);
        }

        getListView().setOnItemClickListener(this);
        this.showEnterJid = intent.getBooleanExtra(EXTRA_SHOW_ENTER_JID, false);
        this.binding.fab.setOnClickListener(this::onFabClicked);
        if (this.showEnterJid) {
            this.binding.fab.show();
        } else {
            binding.fab.setImageResource(R.drawable.ic_forward_white_24dp);
        }

        final SharedPreferences preferences = getPreferences();
        this.startSearching = intent.getBooleanExtra("direct_search", false) && preferences.getBoolean("start_searching", getResources().getBoolean(R.bool.start_searching));

    }

    private void onFabClicked(View v) {
        if (selected.size() == 0) {
            showEnterJidDialog(null);
        } else {
            submitSelection();
        }
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.setTitle(getTitleFromIntent());
        binding.fab.setImageResource(R.drawable.ic_forward_white_24dp);
        binding.fab.show();
        final View view = getSearchEditText();
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (view != null && imm != null) {
            imm.hideSoftInputFromWindow(getSearchEditText().getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        this.binding.fab.setImageResource(R.drawable.ic_person_add_white_24dp);
        if (this.showEnterJid) {
            this.binding.fab.show();
        } else {
            this.binding.fab.hide();
        }
        selected.clear();
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        return false;
    }

    private void submitSelection() {
        final Intent request = getIntent();
        final Intent data = new Intent();
        data.putExtra("contacts", getSelectedContactJids());
        data.putExtra(EXTRA_SELECT_MULTIPLE, true);
        data.putExtra(EXTRA_ACCOUNT, request.getStringExtra(EXTRA_ACCOUNT));
        copy(request, data);
        setResult(RESULT_OK, data);
        finish();
    }

    private static void copy(Intent from, Intent to) {
        to.putExtra(EXTRA_CONVERSATION, from.getStringExtra(EXTRA_CONVERSATION));
        to.putExtra(EXTRA_GROUP_CHAT_NAME, from.getStringExtra(EXTRA_GROUP_CHAT_NAME));
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        if (selected.size() != 0) {
            getListView().playSoundEffect(0);
        }
        Contact item = (Contact) getListItems().get(position);
        if (checked) {
            selected.add(item.getJid().toString());
        } else {
            selected.remove(item.getJid().toString());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            try {
                bar.setTitle(getTitleFromIntent());
            } catch (Exception e) {
                bar.setTitle(R.string.title_activity_choose_contact);
            }
        }
    }

    public @StringRes
    int getTitleFromIntent() {
        final Intent intent = getIntent();
        boolean multiple = intent != null && intent.getBooleanExtra(EXTRA_SELECT_MULTIPLE, false);
        @StringRes int fallback = multiple ? R.string.title_activity_choose_contacts : R.string.title_activity_choose_contact;
        return intent != null ? intent.getIntExtra(EXTRA_TITLE_RES_ID, fallback) : fallback;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        final Intent i = getIntent();
        boolean showEnterJid = i != null && i.getBooleanExtra(EXTRA_SHOW_ENTER_JID, false);
        menu.findItem(R.id.action_scan_qr_code).setVisible(isCameraFeatureAvailable() && showEnterJid);
        MenuItem mMenuSearchView = menu.findItem(R.id.action_search);
        if (startSearching) {
            mMenuSearchView.expandActionView();
        }
        return true;
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putStringArray("selected_contacts", getSelectedContactJids());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (multiple) {
            return false;
        } else {
            List<ListItem> items = getListItems();
            if (items.size() == 1) {
                onListItemClicked(items.get(0));
                return true;
            }
            return false;
        }
    }

    protected void filterContacts(final String needle) {
        getListItems().clear();
        if (xmppConnectionService == null) {
            getListItemAdapter().notifyDataSetChanged();
            return;
        }
        for (final Account account : xmppConnectionService.getAccounts()) {
            if (account.getStatus() != Account.State.DISABLED) {
                for (final Contact contact : account.getRoster().getContacts()) {
                    if (contact.showInContactList() &&
                            !filterContacts.contains(contact.getJid().asBareJid().toString())
                            && contact.match(this, needle)) {
                        getListItems().add(contact);
                    }
                }
            }
        }
        Collections.sort(getListItems());
        getListItemAdapter().notifyDataSetChanged();
    }

    private String[] getSelectedContactJids() {
        return selected.toArray(new String[0]);
    }

    public void refreshUiReal() {
        //nothing to do. This Activity doesn't implement any listeners
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_scan_qr_code:
                ScanActivity.scan(this);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void showEnterJidDialog(XmppUri uri) {
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        Fragment prev = getSupportFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }
        ft.addToBackStack(null);
        Jid jid = uri == null ? null : uri.getJid();
        EnterJidDialog dialog = EnterJidDialog.newInstance(
                mActivatedAccounts,
                getString(R.string.enter_contact),
                getString(R.string.select),
                jid == null ? null : jid.asBareJid().toString(),
                getIntent().getStringExtra(EXTRA_ACCOUNT),
                true,
                false
        );

        dialog.setOnEnterJidDialogPositiveListener((accountJid, contactJid) -> {
            final Intent request = getIntent();
            final Intent data = new Intent();
            data.putExtra("contact", contactJid.toString());
            data.putExtra(EXTRA_ACCOUNT, accountJid.toEscapedString());
            data.putExtra(EXTRA_SELECT_MULTIPLE, false);
            copy(request, data);
            setResult(RESULT_OK, data);
            finish();

            return true;
        });

        dialog.show(ft, "dialog");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, requestCode, intent);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, intent);
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    private void handleActivityResult(ActivityResult activityResult) {
        if (activityResult.resultCode == RESULT_OK && activityResult.requestCode == ScanActivity.REQUEST_SCAN_QR_CODE) {
            String result = activityResult.data.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);
            XmppUri uri = new XmppUri(Strings.nullToEmpty(result));
            if (uri.isValidJid()) {
                showEnterJidDialog(uri);
            }
        }
    }

    @Override
    void onBackendConnected() {
        filterContacts();
        this.mActivatedAccounts.clear();
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.getStatus() != Account.State.DISABLED) {
                if (Config.DOMAIN_LOCK != null) {
                    this.mActivatedAccounts.add(account.getJid().getEscapedLocal());
                } else {
                    this.mActivatedAccounts.add(account.getJid().asBareJid().toEscapedString());
                }
            }
        }
        ActivityResult activityResult = this.postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }
        final Fragment fragment = getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG_DIALOG);
        if (fragment instanceof OnBackendConnected) {
            ((OnBackendConnected) fragment).onBackendConnected();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        ScanActivity.onRequestPermissionResult(this, requestCode, grantResults);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (multiple) {
            startActionMode(this);
            getListView().setItemChecked(position, true);
            return;
        }
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getSearchEditText().getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        final ListItem mListItem = getListItems().get(position);
        onListItemClicked(mListItem);
    }

    private void onListItemClicked(ListItem item) {
        final Intent request = getIntent();
        final Intent data = new Intent();
        data.putExtra("contact", item.getJid().toString());
        String account = request.getStringExtra(EXTRA_ACCOUNT);
        if (account == null && item instanceof Contact) {
            account = ((Contact) item).getAccount().getJid().asBareJid().toEscapedString();
        }
        data.putExtra(EXTRA_ACCOUNT, account);
        data.putExtra(EXTRA_SELECT_MULTIPLE, false);
        copy(request, data);
        setResult(RESULT_OK, data);
        finish();
    }
}
