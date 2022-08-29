package eu.siacs.conversations.entities;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.common.base.Strings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.android.AbstractPhoneContact;
import eu.siacs.conversations.android.JabberIdContact;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.utils.JidHelper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.RtpCapability;
import eu.siacs.conversations.xmpp.pep.Avatar;

public class Contact implements ListItem, Blockable {
    public static final String TABLENAME = "contacts";

    public static final String SYSTEMNAME = "systemname";
    public static final String SERVERNAME = "servername";
    public static final String PRESENCE_NAME = "presence_name";
    public static final String JID = "jid";
    public static final String OPTIONS = "options";
    public static final String SYSTEMACCOUNT = "systemaccount";
    public static final String PHOTOURI = "photouri";
    public static final String KEYS = "pgpkey";
    public static final String ACCOUNT = "accountUuid";
    public static final String AVATAR = "avatar";
    public static final String LAST_PRESENCE = "last_presence";
    public static final String LAST_TIME = "last_time";
    public static final String GROUPS = "groups";
    public static final String RTP_CAPABILITY = "rtpCapability";
    private String accountUuid;
    private String systemName;
    private String serverName;
    private String presenceName;
    private String commonName;
    protected Jid jid;
    private int subscription = 0;
    private Uri systemAccount;
    private String photoUri;
    private final JSONObject keys;
    private JSONArray groups = new JSONArray();
    private final Presences presences = new Presences();
    protected Account account;
    protected Avatar avatar;

    private boolean mActive = false;
    private long mLastseen = 0;
    private String mLastPresence = null;
    private RtpCapability.Capability rtpCapability;

    public Contact(final String account, final String systemName, final String serverName, final String presenceName,
                   final Jid jid, final int subscription, final String photoUri,
                   final Uri systemAccount, final String keys, final String avatar, final long lastseen,
                   final String presence, final String groups, final RtpCapability.Capability rtpCapability) {
        this.accountUuid = account;
        this.systemName = systemName;
        this.serverName = serverName;
        this.presenceName = presenceName;
        this.jid = jid;
        this.subscription = subscription;
        this.photoUri = photoUri;
        this.systemAccount = systemAccount;
        JSONObject tmpJsonObject;
        try {
            tmpJsonObject = (keys == null ? new JSONObject("") : new JSONObject(keys));
        } catch (JSONException e) {
            tmpJsonObject = new JSONObject();
        }
        this.keys = tmpJsonObject;
        if (avatar != null) {
            this.avatar = new Avatar();
            this.avatar.sha1sum = avatar;
            this.avatar.origin = Avatar.Origin.VCARD; //always assume worst
        }
        try {
            this.groups = (groups == null ? new JSONArray() : new JSONArray(groups));
        } catch (JSONException e) {
            this.groups = new JSONArray();
        }
        this.mLastseen = lastseen;
        this.mLastPresence = presence;
        this.rtpCapability = rtpCapability;
    }

    public Contact(final Jid jid) {
        this.jid = jid;
        this.keys = new JSONObject();
    }

    public static Contact fromCursor(final Cursor cursor) {
        final Jid jid;
        try {
            jid = Jid.of(cursor.getString(cursor.getColumnIndex(JID)));
        } catch (final IllegalArgumentException e) {
            // TODO: Borked DB... handle this somehow?
            return null;
        }
        Uri systemAccount;
        try {
            systemAccount = Uri.parse(cursor.getString(cursor.getColumnIndex(SYSTEMACCOUNT)));
        } catch (Exception e) {
            systemAccount = null;
        }
        return new Contact(cursor.getString(cursor.getColumnIndex(ACCOUNT)),
                cursor.getString(cursor.getColumnIndex(SYSTEMNAME)),
                cursor.getString(cursor.getColumnIndex(SERVERNAME)),
                cursor.getString(cursor.getColumnIndex(PRESENCE_NAME)),
                jid,
                cursor.getInt(cursor.getColumnIndex(OPTIONS)),
                cursor.getString(cursor.getColumnIndex(PHOTOURI)),
                systemAccount,
                cursor.getString(cursor.getColumnIndex(KEYS)),
                cursor.getString(cursor.getColumnIndex(AVATAR)),
                cursor.getLong(cursor.getColumnIndex(LAST_TIME)),
                cursor.getString(cursor.getColumnIndex(LAST_PRESENCE)),
                cursor.getString(cursor.getColumnIndex(GROUPS)),
                RtpCapability.Capability.of(cursor.getString(cursor.getColumnIndex(RTP_CAPABILITY))));
    }

    public String getDisplayName() {
        if (isSelf()) {
            final String displayName = account.getDisplayName();
            if (!Strings.isNullOrEmpty(displayName)) {
                return displayName;
            }
        }
        if (Config.X509_VERIFICATION && !TextUtils.isEmpty(this.commonName)) {
            return this.commonName;
        } else if (!TextUtils.isEmpty(this.systemName)) {
            return this.systemName;
        } else if (!TextUtils.isEmpty(this.serverName)) {
            return this.serverName;
        } else if (!TextUtils.isEmpty(this.presenceName) && ((QuickConversationsService.isQuicksy() && JidHelper.isQuicksyDomain(jid.getDomain())) || mutualPresenceSubscription())) {
            return this.presenceName;
        } else if (jid.getLocal() != null) {
            return JidHelper.localPartOrFallback(jid);
        } else {
            return jid.getDomain().toEscapedString();
        }
    }

    public String getPublicDisplayName() {
        if (!TextUtils.isEmpty(this.presenceName)) {
            return this.presenceName;
        } else if (jid.getLocal() != null) {
            return JidHelper.localPartOrFallback(jid);
        } else {
            return jid.getDomain().toEscapedString();
        }
    }

    public String getProfilePhoto() {
        return this.photoUri;
    }

    public Jid getJid() {
        return jid;
    }

    @Override
    public List<Tag> getTags(Context context) {
        final ArrayList<Tag> tags = new ArrayList<>();
        for (final String group : getGroups(true)) {
            tags.add(new Tag(group, UIHelper.getColorForName(group)));
        }
        Presence.Status status = getShownStatus();
        if (status != Presence.Status.OFFLINE) {
            tags.add(UIHelper.getTagForStatus(context, status));
        }
        if (isBlocked()) {
            tags.add(new Tag(context.getString(R.string.blocked), 0xff2e2f3b));
        }
        return tags;
    }

    public boolean match(Context context, String needle) {
        if (TextUtils.isEmpty(needle)) {
            return true;
        }
        needle = needle.toLowerCase(Locale.US).trim();
        String[] parts = needle.split("\\s+");
        if (parts.length > 1) {
            for (String part : parts) {
                if (!match(context, part)) {
                    return false;
                }
            }
            return true;
        } else {
            return jid.toString().contains(needle) ||
                    getDisplayName().toLowerCase(Locale.US).contains(needle) ||
                    matchInTag(context, needle);
        }
    }

    private boolean matchInTag(Context context, String needle) {
        needle = needle.toLowerCase(Locale.US);
        for (Tag tag : getTags(context)) {
            if (tag.getName().toLowerCase(Locale.US).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public ContentValues getContentValues() {
        synchronized (this.keys) {
            final ContentValues values = new ContentValues();
            values.put(ACCOUNT, accountUuid);
            values.put(SYSTEMNAME, systemName);
            values.put(SERVERNAME, serverName);
            values.put(PRESENCE_NAME, presenceName);
            values.put(JID, jid.toString());
            values.put(OPTIONS, subscription);
            values.put(SYSTEMACCOUNT, systemAccount != null ? systemAccount.toString() : null);
            values.put(PHOTOURI, photoUri);
            values.put(KEYS, keys.toString());
            values.put(AVATAR, avatar == null ? null : avatar.getFilename());
            values.put(LAST_PRESENCE, mLastPresence);
            values.put(LAST_TIME, mLastseen);
            values.put(GROUPS, groups.toString());
            values.put(RTP_CAPABILITY, rtpCapability == null ? null : rtpCapability.toString());
            return values;
        }
    }

    public Account getAccount() {
        return this.account;
    }

    public void setAccount(Account account) {
        this.account = account;
        this.accountUuid = account.getUuid();
    }

    public Presences getPresences() {
        return this.presences;
    }

    public void updatePresence(final String resource, final Presence presence) {
        this.presences.updatePresence(resource, presence);
    }

    public void removePresence(final String resource) {
        this.presences.removePresence(resource);
    }

    public void clearPresences() {
        this.presences.clearPresences();
        this.resetOption(Options.PENDING_SUBSCRIPTION_REQUEST);
    }

    public Presence.Status getShownStatus() {
        return this.presences.getShownStatus();
    }

    public boolean setPhotoUri(String uri) {
        if (uri != null && !uri.equals(this.photoUri)) {
            this.photoUri = uri;
            return true;
        } else if (this.photoUri != null && uri == null) {
            this.photoUri = null;
            return true;
        } else {
            return false;
        }
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public boolean setSystemName(String systemName) {
        final String old = getDisplayName();
        this.systemName = systemName;
        return !old.equals(getDisplayName());
    }

    public boolean setPresenceName(String presenceName) {
        final String old = getDisplayName();
        this.presenceName = presenceName;
        return !old.equals(getDisplayName());
    }

    public Uri getSystemAccount() {
        return systemAccount;
    }

    public void setSystemAccount(Uri lookupUri) {
        this.systemAccount = lookupUri;
    }

    private Collection<String> getGroups(final boolean unique) {
        final Collection<String> groups = unique ? new HashSet<>() : new ArrayList<>();
        for (int i = 0; i < this.groups.length(); ++i) {
            try {
                groups.add(this.groups.getString(i));
            } catch (final JSONException ignored) {
            }
        }
        return groups;
    }

    public long getPgpKeyId() {
        synchronized (this.keys) {
            if (this.keys.has("pgp_keyid")) {
                try {
                    return this.keys.getLong("pgp_keyid");
                } catch (JSONException e) {
                    return 0;
                }
            } else {
                return 0;
            }
        }
    }

    public boolean setPgpKeyId(long keyId) {
        final long previousKeyId = getPgpKeyId();
        synchronized (this.keys) {
            try {
                this.keys.put("pgp_keyid", keyId);
                return previousKeyId != keyId;
            } catch (final JSONException ignored) {
            }
        }
        return false;
    }

    public void setOption(int option) {
        this.subscription |= 1 << option;
    }

    public void resetOption(int option) {
        this.subscription &= ~(1 << option);
    }

    public boolean getOption(int option) {
        return ((this.subscription & (1 << option)) != 0);
    }

    public boolean showInRoster() {
        return (this.getOption(Contact.Options.IN_ROSTER) && (!this
                .getOption(Contact.Options.DIRTY_DELETE)))
                || (this.getOption(Contact.Options.DIRTY_PUSH));
    }

    public boolean showInContactList() {
        return showInRoster()
                || getOption(Options.SYNCED_VIA_OTHER)
                || (QuickConversationsService.isQuicksy() && systemAccount != null);
    }

    public void parseSubscriptionFromElement(Element item) {
        String ask = item.getAttribute("ask");
        String subscription = item.getAttribute("subscription");

        if (subscription == null) {
            this.resetOption(Options.FROM);
            this.resetOption(Options.TO);
        } else {
            switch (subscription) {
                case "to":
                    this.resetOption(Options.FROM);
                    this.setOption(Options.TO);
                    break;
                case "from":
                    this.resetOption(Options.TO);
                    this.setOption(Options.FROM);
                    this.resetOption(Options.PREEMPTIVE_GRANT);
                    this.resetOption(Options.PENDING_SUBSCRIPTION_REQUEST);
                    break;
                case "both":
                    this.setOption(Options.TO);
                    this.setOption(Options.FROM);
                    this.resetOption(Options.PREEMPTIVE_GRANT);
                    this.resetOption(Options.PENDING_SUBSCRIPTION_REQUEST);
                    break;
                case "none":
                    this.resetOption(Options.FROM);
                    this.resetOption(Options.TO);
                    break;
            }
        }

        // do NOT override asking if pending push request
        if (!this.getOption(Contact.Options.DIRTY_PUSH)) {
            if ((ask != null) && (ask.equals("subscribe"))) {
                this.setOption(Contact.Options.ASKING);
            } else {
                this.resetOption(Contact.Options.ASKING);
            }
        }
    }

    public void parseGroupsFromElement(Element item) {
        this.groups = new JSONArray();
        for (Element element : item.getChildren()) {
            if (element.getName().equals("group") && element.getContent() != null) {
                this.groups.put(element.getContent());
            }
        }
    }

    public Element asElement() {
        final Element item = new Element("item");
        item.setAttribute("jid", this.jid);
        if (this.serverName != null) {
            item.setAttribute("name", this.serverName);
        }
        for (String group : getGroups(false)) {
            item.addChild("group").setContent(group);
        }
        return item;
    }

    @Override
    public int compareTo(@NonNull final ListItem another) {
        return this.getDisplayName().compareToIgnoreCase(
                another.getDisplayName());
    }

    public String getServer() {
        return getJid().getDomain().toEscapedString();
    }

    public void setAvatar(Avatar avatar) {
        setAvatar(avatar, false);
    }

    public void setAvatar(Avatar avatar, boolean previouslyOmittedPepFetch) {
        if (this.avatar != null && this.avatar.equals(avatar)) {
            return;
        }
        if (!previouslyOmittedPepFetch && this.avatar != null && this.avatar.origin == Avatar.Origin.PEP && avatar.origin == Avatar.Origin.VCARD) {
            return;
        }
        this.avatar = avatar;
    }

    public String getAvatarFilename() {
        return avatar == null ? null : avatar.getFilename();
    }

    public Avatar getAvatar() {
        return avatar;
    }

    public boolean mutualPresenceSubscription() {
        return getOption(Options.FROM) && getOption(Options.TO);
    }

    @Override
    public boolean isBlocked() {
        return getAccount().isBlocked(this);
    }

    @Override
    public boolean isDomainBlocked() {
        return getAccount().isBlocked(this.getJid().getDomain());
    }

    @Override
    public Jid getBlockedJid() {
        if (isDomainBlocked()) {
            return getJid().getDomain();
        } else {
            return getJid();
        }
    }

    public boolean isSelf() {
        return account.getJid().asBareJid().equals(jid.asBareJid());
    }

    boolean isOwnServer() {
        return account.getJid().getDomain().equals(jid.asBareJid());
    }

    public void setCommonName(String cn) {
        this.commonName = cn;
    }

    public void flagActive() {
        this.mActive = true;
    }

    public void flagInactive() {
        this.mActive = false;
    }

    public boolean isActive() {
        return this.mActive;
    }

    public boolean setLastseen(long timestamp) {
        if (timestamp > this.mLastseen) {
            this.mLastseen = timestamp;
            return true;
        } else {
            return false;
        }
    }

    public long getLastseen() {
        return this.mLastseen;
    }

    public void setLastResource(String resource) {
        this.mLastPresence = resource;
    }

    public String getLastResource() {
        return this.mLastPresence;
    }

    public String getServerName() {
        return serverName;
    }

    public synchronized boolean setPhoneContact(AbstractPhoneContact phoneContact) {
        setOption(getOption(phoneContact.getClass()));
        setSystemAccount(phoneContact.getLookupUri());
        boolean changed = setSystemName(phoneContact.getDisplayName());
        changed |= setPhotoUri(phoneContact.getPhotoUri());
        return changed;
    }

    public synchronized boolean unsetPhoneContact(Class<? extends AbstractPhoneContact> clazz) {
        resetOption(getOption(clazz));
        boolean changed = false;
        if (!getOption(Options.SYNCED_VIA_ADDRESSBOOK) && !getOption(Options.SYNCED_VIA_OTHER)) {
            setSystemAccount(null);
            changed |= setPhotoUri(null);
            changed |= setSystemName(null);
        }
        return changed;
    }

    public static int getOption(Class<? extends AbstractPhoneContact> clazz) {
        if (clazz == JabberIdContact.class) {
            return Options.SYNCED_VIA_ADDRESSBOOK;
        } else {
            return Options.SYNCED_VIA_OTHER;
        }
    }

    @Override
    public int getAvatarBackgroundColor() {
        return UIHelper.getColorForName(jid != null ? jid.asBareJid().toString() : getDisplayName());
    }

    @Override
    public String getAvatarName() {
        return getDisplayName();
    }

    public boolean hasAvatarOrPresenceName() {
        return (avatar != null && avatar.getFilename() != null) || presenceName != null;
    }

    public boolean refreshRtpCapability() {
        final RtpCapability.Capability previous = this.rtpCapability;
        this.rtpCapability = RtpCapability.check(this, false);
        return !Objects.equals(previous, this.rtpCapability);
    }

    public RtpCapability.Capability getRtpCapability() {
        return this.rtpCapability == null ? RtpCapability.Capability.NONE : this.rtpCapability;
    }

    public static final class Options {
        public static final int TO = 0;
        public static final int FROM = 1;
        public static final int ASKING = 2;
        public static final int PREEMPTIVE_GRANT = 3;
        public static final int IN_ROSTER = 4;
        public static final int PENDING_SUBSCRIPTION_REQUEST = 5;
        public static final int DIRTY_PUSH = 6;
        public static final int DIRTY_DELETE = 7;
        private static final int SYNCED_VIA_ADDRESSBOOK = 8;
        public static final int SYNCED_VIA_OTHER = 9;
    }
}
