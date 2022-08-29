package eu.siacs.conversations.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.databinding.DataBindingUtil;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityChannelDiscoveryBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Room;
import eu.siacs.conversations.services.ChannelDiscoveryService;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.ui.adapter.ChannelSearchResultAdapter;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.SoftKeyboardUtils;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.xmpp.Jid;

public class ChannelDiscoveryActivity extends XmppActivity implements MenuItem.OnActionExpandListener, TextView.OnEditorActionListener, ChannelDiscoveryService.OnChannelSearchResultsFound, ChannelSearchResultAdapter.OnChannelSearchResultSelected {

    private static final String CHANNEL_DISCOVERY_OPT_IN = "channel_discovery_opt_in";

    private final ChannelSearchResultAdapter adapter = new ChannelSearchResultAdapter();
    private final PendingItem<String> mInitialSearchValue = new PendingItem<>();
    private ActivityChannelDiscoveryBinding binding;
    private MenuItem mMenuSearchView;
    private EditText mSearchEditText;

    private ChannelDiscoveryService.Method method = ChannelDiscoveryService.Method.LOCAL_SERVER;

    private boolean optedIn = false;

    @Override
    protected void refreshUiReal() {

    }

    @Override
    void onBackendConnected() {
        if (optedIn || method == ChannelDiscoveryService.Method.LOCAL_SERVER) {
            final String query;
            if (mMenuSearchView != null && mMenuSearchView.isActionViewExpanded()) {
                query = mSearchEditText.getText().toString();
            } else {
                query = mInitialSearchValue.peek();
            }
            toggleLoadingScreen();
            xmppConnectionService.discoverChannels(query, this.method, this);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_channel_discovery);
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar(), true);
        binding.list.setAdapter(this.adapter);
        this.adapter.setOnChannelSearchResultSelectedListener(this);
        this.optedIn = getPreferences().getBoolean(CHANNEL_DISCOVERY_OPT_IN, false);

        final String search = savedInstanceState == null ? null : savedInstanceState.getString("search");
        if (search != null) {
            mInitialSearchValue.push(search);
        }
    }

    private static ChannelDiscoveryService.Method getMethod(final Context c) {
        if (QuickConversationsService.isQuicksy()) {
            return ChannelDiscoveryService.Method.JABBER_NETWORK;
        }
        final SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);
        final String m = p.getString("channel_discovery_method", c.getString(R.string.default_channel_discovery));
        try {
            return ChannelDiscoveryService.Method.valueOf(m);
        } catch (IllegalArgumentException e) {
            return ChannelDiscoveryService.Method.JABBER_NETWORK;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.channel_discovery_activity, menu);
        AccountUtils.showHideMenuItems(menu);
        mMenuSearchView = menu.findItem(R.id.action_search);
        final View mSearchView = mMenuSearchView.getActionView();
        mSearchEditText = mSearchView.findViewById(R.id.search_field);
        mSearchEditText.setHint(R.string.search_channels);
        final String initialSearchValue = mInitialSearchValue.pop();
        if (initialSearchValue != null) {
            mMenuSearchView.expandActionView();
            mSearchEditText.append(initialSearchValue);
            mSearchEditText.requestFocus();
            if ((optedIn || method == ChannelDiscoveryService.Method.LOCAL_SERVER) && xmppConnectionService != null) {
                xmppConnectionService.discoverChannels(initialSearchValue, this.method, this);
            }
        }
        mSearchEditText.setOnEditorActionListener(this);
        mMenuSearchView.setOnActionExpandListener(this);
        return true;
    }

    @Override
    public boolean onMenuItemActionExpand(MenuItem item) {
        mSearchEditText.post(() -> {
            mSearchEditText.requestFocus();
            final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(mSearchEditText, InputMethodManager.SHOW_IMPLICIT);
        });
        return true;
    }

    @Override
    public boolean onMenuItemActionCollapse(MenuItem item) {
        final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(mSearchEditText.getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
        mSearchEditText.setText("");
        toggleLoadingScreen();
        if (optedIn || method == ChannelDiscoveryService.Method.LOCAL_SERVER) {
            xmppConnectionService.discoverChannels(null, this.method, this);
        }
        return true;
    }

    private void toggleLoadingScreen() {
        adapter.submitList(Collections.emptyList());
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.list.setBackgroundColor(StyledAttributes.getColor(this, R.attr.color_background_primary));
    }

    @Override
    public void onStart() {
        super.onStart();
        this.method = getMethod(this);
        if (!optedIn && method == ChannelDiscoveryService.Method.JABBER_NETWORK) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.channel_discovery_opt_in_title);
            builder.setMessage(Html.fromHtml(getString(R.string.channel_discover_opt_in_message)));
            builder.setNegativeButton(R.string.cancel, (dialog, which) -> finish());
            builder.setPositiveButton(R.string.confirm, (dialog, which) -> optIn());
            builder.setOnCancelListener(dialog -> finish());
            final AlertDialog dialog = builder.create();
            dialog.setOnShowListener(d -> {
                final TextView textView = dialog.findViewById(android.R.id.message);
                if (textView == null) {
                    return;
                }
                textView.setMovementMethod(LinkMovementMethod.getInstance());
            });
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
            holdLoading();
        }
    }

    private void holdLoading() {
        adapter.submitList(Collections.emptyList());
        binding.progressBar.setVisibility(View.GONE);
        binding.list.setBackgroundColor(StyledAttributes.getColor(this, R.attr.color_background_primary));
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        if (mMenuSearchView != null && mMenuSearchView.isActionViewExpanded()) {
            savedInstanceState.putString("search", mSearchEditText != null ? mSearchEditText.getText().toString() : null);
        }
        super.onSaveInstanceState(savedInstanceState);
    }

    private void optIn() {
        SharedPreferences preferences = getPreferences();
        preferences.edit().putBoolean(CHANNEL_DISCOVERY_OPT_IN, true).apply();
        optedIn = true;
        toggleLoadingScreen();
        xmppConnectionService.discoverChannels(null, this.method, this);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (optedIn || method == ChannelDiscoveryService.Method.LOCAL_SERVER) {
            toggleLoadingScreen();
            SoftKeyboardUtils.hideSoftKeyboard(this);
            xmppConnectionService.discoverChannels(v.getText().toString(), this.method, this);
        }
        return true;
    }

    @Override
    public void onChannelSearchResultsFound(final List<Room> results) {
        runOnUiThread(() -> {
            adapter.submitList(results);
            binding.progressBar.setVisibility(View.GONE);
            if (results.size() == 0) {
                binding.list.setBackground(StyledAttributes.getDrawable(this, R.attr.activity_primary_background_no_results));
            } else {
                binding.list.setBackgroundColor(StyledAttributes.getColor(this, R.attr.color_background_primary));
            }
        });

    }

    @Override
    public void onChannelSearchResult(final Room result) {
        final List<String> accounts = AccountUtils.getEnabledAccounts(xmppConnectionService);
        if (accounts.size() == 1) {
            joinChannelSearchResult(accounts.get(0), result);
        } else if (accounts.size() == 0) {
            Toast.makeText(this, R.string.please_enable_an_account, Toast.LENGTH_LONG).show();
        } else {
            final AtomicReference<String> account = new AtomicReference<>(accounts.get(0));
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.choose_account);
            builder.setSingleChoiceItems(accounts.toArray(new CharSequence[0]), 0, (dialog, which) -> account.set(accounts.get(which)));
            builder.setPositiveButton(R.string.join, (dialog, which) -> joinChannelSearchResult(account.get(), result));
            builder.setNegativeButton(R.string.cancel, null);
            builder.create().show();
        }

    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        final Room room = adapter.getCurrent();
        if (room != null) {
            switch (item.getItemId()) {
                case R.id.share_with:
                    StartConversationActivity.shareAsChannel(this, room.address);
                    return true;
                case R.id.open_join_dialog:
                    final Intent intent = new Intent(this, StartConversationActivity.class);
                    intent.setAction(Intent.ACTION_VIEW);
                    intent.putExtra("force_dialog", true);
                    intent.setData(Uri.parse(String.format("xmpp:%s?join", room.address)));
                    startActivity(intent);
                    return true;
            }
        }
        return false;
    }

    public void joinChannelSearchResult(String selectedAccount, Room result) {
        final Jid jid = Config.DOMAIN_LOCK == null ? Jid.ofEscaped(selectedAccount) : Jid.ofLocalAndDomainEscaped(selectedAccount, Config.DOMAIN_LOCK);
        final boolean syncAutoJoin = getBooleanPreference("autojoin", R.bool.autojoin);
        final Account account = xmppConnectionService.findAccountByJid(jid);
        final Conversation conversation = xmppConnectionService.findOrCreateConversation(account, result.getRoom(), true, true, true);
        Bookmark bookmark = conversation.getBookmark();
        if (bookmark != null) {
            if (!bookmark.autojoin() && syncAutoJoin) {
                bookmark.setAutojoin(true);
                xmppConnectionService.createBookmark(account, bookmark);
            }
        } else {
            bookmark = new Bookmark(account, conversation.getJid().asBareJid());
            bookmark.setAutojoin(syncAutoJoin);
            xmppConnectionService.createBookmark(account, bookmark);
        }
        switchToConversation(conversation);
    }
}
