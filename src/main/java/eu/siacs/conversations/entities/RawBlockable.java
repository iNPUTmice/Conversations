package eu.siacs.conversations.entities;

import android.content.Context;
import android.text.TextUtils;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;

public class RawBlockable implements ListItem, Blockable {

    private final Account account;
    private final Jid jid;

    public RawBlockable(Account account, Jid jid) {
        this.account = account;
        this.jid = jid;
    }

    @Override
    public boolean isBlocked() {
        return true;
    }

    @Override
    public boolean isDomainBlocked() {
        throw new AssertionError("not implemented");
    }

    @Override
    public Jid getBlockedJid() {
        return this.jid;
    }

    @Override
    public String getDisplayName() {
        if (jid.isFullJid()) {
            return jid.getResource();
        } else {
            return jid.toEscapedString();
        }
    }

    @Override
    public Jid getJid() {
        return this.jid;
    }

    @Override
    public List<Tag> getTags(Context context) {
        return Collections.emptyList();
    }

    @Override
    public boolean match(Context context, String needle) {
        if (TextUtils.isEmpty(needle)) {
            return true;
        }
        needle = needle.toLowerCase(Locale.US).trim();
        String[] parts = needle.split("\\s+");
        for (String part : parts) {
            if (!jid.toEscapedString().contains(part)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Account getAccount() {
        return account;
    }

    @Override
    public int getAvatarBackgroundColor() {
        return  UIHelper.getColorForName(jid.toEscapedString());
    }

    @Override
    public String getAvatarName() {
        return getDisplayName();
    }

    @Override
    public int compareTo(ListItem o) {
        return this.getDisplayName().compareToIgnoreCase(
				o.getDisplayName());
    }
}