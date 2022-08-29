package eu.siacs.conversations.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.ConversationAdapter;
import eu.siacs.conversations.xmpp.Jid;

public class ShareWithActivity extends XmppActivity implements XmppConnectionService.OnConversationUpdate {

    private static final int REQUEST_STORAGE_PERMISSION = 0x733f32;
    private Conversation mPendingConversation = null;

    @Override
    public void onConversationUpdate() {
        refreshUi();
    }

    private static class Share {
        public String type;
        ArrayList<Uri> uris = new ArrayList<>();
        public String account;
        public String contact;
        public String text;
        public boolean asQuote = false;
    }

    private Share share;

    private static final int REQUEST_START_NEW_CONVERSATION = 0x0501;
    private ConversationAdapter mAdapter;
    private final List<Conversation> mConversations = new ArrayList<>();


    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_START_NEW_CONVERSATION
                && resultCode == RESULT_OK) {
            share.contact = data.getStringExtra("contact");
            share.account = data.getStringExtra(EXTRA_ACCOUNT);
        }
        if (xmppConnectionServiceBound
                && share != null
                && share.contact != null
                && share.account != null) {
            share();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0)
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (requestCode == REQUEST_STORAGE_PERMISSION) {
                    if (this.mPendingConversation != null) {
                        share(this.mPendingConversation);
                    } else {
                        Log.d(Config.LOGTAG, "unable to find stored conversation");
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.no_storage_permission, getString(R.string.app_name)), Toast.LENGTH_SHORT).show();
            }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share_with);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setHomeButtonEnabled(false);
        }

        setTitle(getString(R.string.title_activity_sharewith));

        RecyclerView mListView = findViewById(R.id.choose_conversation_list);
        mAdapter = new ConversationAdapter(this, this.mConversations);
        mListView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        mListView.setAdapter(mAdapter);
        mAdapter.setConversationClickListener((view, conversation) -> share(conversation));
        this.share = new Share();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.share_with, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_add:
                final Intent intent = new Intent(getApplicationContext(), ChooseContactActivity.class);
                intent.putExtra("direct_search",true);
                startActivityForResult(intent, REQUEST_START_NEW_CONVERSATION);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        final String type = intent.getType();
        final String action = intent.getAction();
        final Uri data = intent.getData();
        if (Intent.ACTION_SEND.equals(action)) {
            final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            final boolean asQuote = intent.getBooleanExtra(ConversationsActivity.EXTRA_AS_QUOTE, false);

            if (data != null && "geo".equals(data.getScheme())) {
                this.share.uris.clear();
                this.share.uris.add(data);
            } else if (type != null && uri != null) {
                this.share.uris.clear();
                this.share.uris.add(uri);
                this.share.type = type;
            } else {
                this.share.text = text;
                this.share.asQuote = asQuote;
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            final ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            this.share.uris = uris == null ? new ArrayList<>() : uris;
        }
        if (xmppConnectionServiceBound) {
            xmppConnectionService.populateWithOrderedConversations(mConversations, this.share.uris.size() == 0, false);
        }

    }

    @Override
    void onBackendConnected() {
        if (xmppConnectionServiceBound && share != null && ((share.contact != null && share.account != null))) {
            share();
            return;
        }
        refreshUiReal();
    }

    private void share() {
        final Conversation conversation;
            Account account;
            try {
                account = xmppConnectionService.findAccountByJid(Jid.ofEscaped(share.account));
            } catch (final IllegalArgumentException e) {
                account = null;
            }
            if (account == null) {
                return;
            }

            try {
                conversation = xmppConnectionService.findOrCreateConversation(account, Jid.of(share.contact), false, true);
            } catch (final IllegalArgumentException e) {
                return;
            }
        share(conversation);
    }

    private void share(final Conversation conversation) {
        if (share.uris.size() != 0 && !hasStoragePermission(REQUEST_STORAGE_PERMISSION)) {
            mPendingConversation = conversation;
            return;
        }
        Intent intent = new Intent(this, ConversationsActivity.class);
        intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        if (share.uris.size() > 0) {
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, share.uris);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (share.type != null) {
                intent.putExtra(ConversationsActivity.EXTRA_TYPE, share.type);
            }
        } else if (share.text != null) {
            intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
            intent.putExtra(Intent.EXTRA_TEXT, share.text);
            intent.putExtra(ConversationsActivity.EXTRA_AS_QUOTE, share.asQuote);
        }
        try {
            startActivity(intent);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.sharing_application_not_grant_permission, Toast.LENGTH_SHORT).show();
            return;
        }
        finish();
    }

    public void refreshUiReal() {
        //TODO inject desired order to not resort on refresh
        xmppConnectionService.populateWithOrderedConversations(mConversations, this.share != null && this.share.uris.size() == 0, false);
        mAdapter.notifyDataSetChanged();
    }
}
