package eu.siacs.conversations.entities;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.utils.JidHelper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.forms.Field;
import eu.siacs.conversations.xmpp.pep.Avatar;

public class MucOptions {

    public static final String STATUS_CODE_SELF_PRESENCE = "110";
    public static final String STATUS_CODE_ROOM_CREATED = "201";
    public static final String STATUS_CODE_BANNED = "301";
    public static final String STATUS_CODE_CHANGED_NICK = "303";
    public static final String STATUS_CODE_KICKED = "307";
    public static final String STATUS_CODE_AFFILIATION_CHANGE = "321";
    public static final String STATUS_CODE_LOST_MEMBERSHIP = "322";
    public static final String STATUS_CODE_SHUTDOWN = "332";
    public static final String STATUS_CODE_TECHNICAL_REASONS = "333";
    private final Set<User> users = new HashSet<>();
    private final Conversation conversation;
    public OnRenameListener onRenameListener = null;
    private boolean mAutoPushConfiguration = true;
    private final Account account;
    private ServiceDiscoveryResult serviceDiscoveryResult;
    private boolean isOnline = false;
    private Error error = Error.NONE;
    private User self;
    private String password = null;

    private boolean tookProposedNickFromBookmark = false;

    public MucOptions(Conversation conversation) {
        this.account = conversation.getAccount();
        this.conversation = conversation;
        this.self = new User(this, createJoinJid(getProposedNick()));
        this.self.affiliation = Affiliation.of(conversation.getAttribute("affiliation"));
        this.self.role = Role.of(conversation.getAttribute("role"));
    }

    public Account getAccount() {
        return this.conversation.getAccount();
    }

    public boolean setSelf(User user) {
        this.self = user;
        final boolean roleChanged = this.conversation.setAttribute("role", user.role.toString());
        final boolean affiliationChanged = this.conversation.setAttribute("affiliation", user.affiliation.toString());
        return roleChanged || affiliationChanged;
    }

    public void changeAffiliation(Jid jid, Affiliation affiliation) {
        User user = findUserByRealJid(jid);
        synchronized (users) {
            if (user != null && user.getRole() == Role.NONE) {
                users.remove(user);
                if (affiliation.ranks(Affiliation.MEMBER)) {
                    user.affiliation = affiliation;
                    users.add(user);
                }
            }
        }
    }

    public void flagNoAutoPushConfiguration() {
        mAutoPushConfiguration = false;
    }

    public boolean autoPushConfiguration() {
        return mAutoPushConfiguration;
    }

    public boolean isSelf(Jid counterpart) {
        return counterpart.equals(self.getFullJid());
    }

    public void resetChatState() {
        synchronized (users) {
            for (User user : users) {
                user.chatState = Config.DEFAULT_CHAT_STATE;
            }
        }
    }

    public boolean isTookProposedNickFromBookmark() {
        return tookProposedNickFromBookmark;
    }

    void notifyOfBookmarkNick(final String nick) {
        final String normalized = normalize(account.getJid(),nick);
        if (normalized != null && normalized.equals(getSelf().getFullJid().getResource())) {
            this.tookProposedNickFromBookmark = true;
        }
    }

    public boolean mamSupport() {
        return MessageArchiveService.Version.has(getFeatures());
    }

    public boolean updateConfiguration(ServiceDiscoveryResult serviceDiscoveryResult) {
        this.serviceDiscoveryResult = serviceDiscoveryResult;
        String name;
        Field roomConfigName = getRoomInfoForm().getFieldByName("muc#roomconfig_roomname");
        if (roomConfigName != null) {
            name = roomConfigName.getValue();
        } else {
            List<ServiceDiscoveryResult.Identity> identities = serviceDiscoveryResult.getIdentities();
            String identityName = identities.size() > 0 ? identities.get(0).getName() : null;
            final Jid jid = conversation.getJid();
            if (identityName != null && !identityName.equals(jid == null ? null : jid.getEscapedLocal())) {
                name = identityName;
            } else {
                name = null;
            }
        }
        boolean changed = conversation.setAttribute("muc_name", name);
        changed |= conversation.setAttribute(Conversation.ATTRIBUTE_MEMBERS_ONLY, this.hasFeature("muc_membersonly"));
        changed |= conversation.setAttribute(Conversation.ATTRIBUTE_MODERATED, this.hasFeature("muc_moderated"));
        changed |= conversation.setAttribute(Conversation.ATTRIBUTE_NON_ANONYMOUS, this.hasFeature("muc_nonanonymous"));
        return changed;
    }

    private Data getRoomInfoForm() {
        final List<Data> forms = serviceDiscoveryResult == null ? Collections.emptyList() : serviceDiscoveryResult.forms;
        return forms.size() == 0 ? new Data() : forms.get(0);
    }

    public String getAvatar() {
        return account.getRoster().getContact(conversation.getJid()).getAvatarFilename();
    }

    public boolean hasFeature(String feature) {
        return this.serviceDiscoveryResult != null && this.serviceDiscoveryResult.features.contains(feature);
    }

    public boolean hasVCards() {
        return hasFeature("vcard-temp");
    }

    public boolean canInvite() {
        return !membersOnly() || self.getRole().ranks(Role.MODERATOR) || allowInvites();
    }

    public boolean allowInvites() {
        final Field field = getRoomInfoForm().getFieldByName("muc#roomconfig_allowinvites");
        return field != null && "1".equals(field.getValue());
    }

    public boolean canChangeSubject() {
        return self.getRole().ranks(Role.MODERATOR) || participantsCanChangeSubject();
    }

    public boolean participantsCanChangeSubject() {
        final Field field = getRoomInfoForm().getFieldByName("muc#roominfo_changesubject");
        return field != null && "1".equals(field.getValue());
    }

    public boolean allowPm() {
        final Field field = getRoomInfoForm().getFieldByName("muc#roomconfig_allowpm");
        if (field == null) {
            return true; //fall back if field does not exists
        }
        if ("anyone".equals(field.getValue())) {
            return true;
        } else if ("participants".equals(field.getValue())) {
            return self.getRole().ranks(Role.PARTICIPANT);
        } else if ("moderators".equals(field.getValue())) {
            return self.getRole().ranks(Role.MODERATOR);
        } else {
            return false;
        }
    }

    public boolean participating() {
        return self.getRole().ranks(Role.PARTICIPANT) || !moderated();
    }

    public boolean membersOnly() {
        return conversation.getBooleanAttribute(Conversation.ATTRIBUTE_MEMBERS_ONLY, false);
    }

    public List<String> getFeatures() {
        return this.serviceDiscoveryResult != null ? this.serviceDiscoveryResult.features : Collections.emptyList();
    }

    public boolean nonanonymous() {
        return conversation.getBooleanAttribute(Conversation.ATTRIBUTE_NON_ANONYMOUS, false);
    }

    public boolean isPrivateAndNonAnonymous() {
        return membersOnly() && nonanonymous();
    }

    public boolean moderated() {
        return conversation.getBooleanAttribute(Conversation.ATTRIBUTE_MODERATED, false);
    }

    public boolean stableId() {
        return getFeatures().contains("http://jabber.org/protocol/muc#stable_id");
    }

    public User deleteUser(Jid jid) {
        User user = findUserByFullJid(jid);
        if (user != null) {
            synchronized (users) {
                users.remove(user);
                boolean realJidInMuc = false;
                for (User u : users) {
                    if (user.realJid != null && user.realJid.equals(u.realJid)) {
                        realJidInMuc = true;
                        break;
                    }
                }
                boolean self = user.realJid != null && user.realJid.equals(account.getJid().asBareJid());
                if (membersOnly()
                        && nonanonymous()
                        && user.affiliation.ranks(Affiliation.MEMBER)
                        && user.realJid != null
                        && !realJidInMuc
                        && !self) {
                    user.role = Role.NONE;
                    user.avatar = null;
                    user.fullJid = null;
                    users.add(user);
                }
            }
        }
        return user;
    }

    //returns true if real jid was new;
    public boolean updateUser(User user) {
        User old;
        boolean realJidFound = false;
        if (user.fullJid == null && user.realJid != null) {
            old = findUserByRealJid(user.realJid);
            realJidFound = old != null;
            if (old != null) {
                if (old.fullJid != null) {
                    return false; //don't add. user already exists
                } else {
                    synchronized (users) {
                        users.remove(old);
                    }
                }
            }
        } else if (user.realJid != null) {
            old = findUserByRealJid(user.realJid);
            realJidFound = old != null;
            synchronized (users) {
                if (old != null && (old.fullJid == null || old.role == Role.NONE)) {
                    users.remove(old);
                }
            }
        }
        old = findUserByFullJid(user.getFullJid());

        synchronized (this.users) {
            if (old != null) {
                users.remove(old);
            }
            boolean fullJidIsSelf = isOnline && user.getFullJid() != null && user.getFullJid().equals(self.getFullJid());
            if ((!membersOnly() || user.getAffiliation().ranks(Affiliation.MEMBER))
                    && user.getAffiliation().outranks(Affiliation.OUTCAST)
                    && !fullJidIsSelf) {
                this.users.add(user);
                return !realJidFound && user.realJid != null;
            }
        }
        return false;
    }

    public User findUserByFullJid(Jid jid) {
        if (jid == null) {
            return null;
        }
        synchronized (users) {
            for (User user : users) {
                if (jid.equals(user.getFullJid())) {
                    return user;
                }
            }
        }
        return null;
    }

    public User findUserByRealJid(Jid jid) {
        if (jid == null) {
            return null;
        }
        synchronized (users) {
            for (User user : users) {
                if (jid.equals(user.realJid)) {
                    return user;
                }
            }
        }
        return null;
    }

    public User findOrCreateUserByRealJid(Jid jid, Jid fullJid) {
        User user = findUserByRealJid(jid);
        if (user == null) {
            user = new User(this, fullJid);
            user.setRealJid(jid);
        }
        return user;
    }

    public User findUser(ReadByMarker readByMarker) {
        if (readByMarker.getRealJid() != null) {
            return findOrCreateUserByRealJid(readByMarker.getRealJid().asBareJid(), readByMarker.getFullJid());
        } else if (readByMarker.getFullJid() != null) {
            return findUserByFullJid(readByMarker.getFullJid());
        } else {
            return null;
        }
    }

    public boolean isContactInRoom(Contact contact) {
        return contact != null && findUserByRealJid(contact.getJid().asBareJid()) != null;
    }

    public boolean isUserInRoom(Jid jid) {
        return findUserByFullJid(jid) != null;
    }

    public boolean setOnline() {
        boolean before = this.isOnline;
        this.isOnline = true;
        return !before;
    }

    public ArrayList<User> getUsers() {
        return getUsers(true);
    }

    public ArrayList<User> getUsers(boolean includeOffline) {
        synchronized (users) {
            ArrayList<User> users = new ArrayList<>();
            for (User user : this.users) {
                if (!user.isDomain() && (includeOffline || user.getRole().ranks(Role.PARTICIPANT))) {
                    users.add(user);
                }
            }
            return users;
        }
    }

    public ArrayList<User> getUsersWithChatState(ChatState state, int max) {
        synchronized (users) {
            ArrayList<User> list = new ArrayList<>();
            for (User user : users) {
                if (user.chatState == state) {
                    list.add(user);
                    if (list.size() >= max) {
                        break;
                    }
                }
            }
            return list;
        }
    }

    public List<User> getUsers(int max) {
        ArrayList<User> subset = new ArrayList<>();
        HashSet<Jid> jids = new HashSet<>();
        jids.add(account.getJid().asBareJid());
        synchronized (users) {
            for (User user : users) {
                if (user.getRealJid() == null || (user.getRealJid().getLocal() != null && jids.add(user.getRealJid()))) {
                    subset.add(user);
                }
                if (subset.size() >= max) {
                    break;
                }
            }
        }
        return subset;
    }

    public static List<User> sub(List<User> users, int max) {
        ArrayList<User> subset = new ArrayList<>();
        HashSet<Jid> jids = new HashSet<>();
        for (User user : users) {
            jids.add(user.getAccount().getJid().asBareJid());
            if (user.getRealJid() == null || (user.getRealJid().getLocal() != null && jids.add(user.getRealJid()))) {
                subset.add(user);
            }
            if (subset.size() >= max) {
                break;
            }
        }
        return subset;
    }

    public int getUserCount() {
        synchronized (users) {
            return users.size();
        }
    }

    public String getProposedNick() {
        final Bookmark bookmark = this.conversation.getBookmark();
        final String bookmarkedNick = normalize(account.getJid(), bookmark == null ? null : bookmark.getNick());
        if (bookmarkedNick != null) {
            this.tookProposedNickFromBookmark = true;
            return bookmarkedNick;
        } else if (!conversation.getJid().isBareJid()) {
            return conversation.getJid().getResource();
        } else {
            return defaultNick(account);
        }
    }

    public static String defaultNick(final Account account) {
        final String displayName = normalize(account.getJid(), account.getDisplayName());
        if (displayName == null) {
            return JidHelper.localPartOrFallback(account.getJid());
        } else {
            return displayName;
        }
    }

    private static String normalize(Jid account, String nick) {
        if (account == null || TextUtils.isEmpty(nick)) {
            return null;
        }
        try {
            return account.withResource(nick).getResource();
        } catch (IllegalArgumentException e) {
            return null;
        }

    }

    public String getActualNick() {
        if (this.self.getName() != null) {
            return this.self.getName();
        } else {
            return this.getProposedNick();
        }
    }

    public boolean online() {
        return this.isOnline;
    }

    public Error getError() {
        return this.error;
    }

    public void setError(Error error) {
        this.isOnline = isOnline && error == Error.NONE;
        this.error = error;
    }

    public void setOnRenameListener(OnRenameListener listener) {
        this.onRenameListener = listener;
    }

    public void setOffline() {
        synchronized (users) {
            this.users.clear();
        }
        this.error = Error.NO_RESPONSE;
        this.isOnline = false;
    }

    public User getSelf() {
        return self;
    }

    public boolean setSubject(String subject) {
        return this.conversation.setAttribute("subject", subject);
    }

    public String getSubject() {
        return this.conversation.getAttribute("subject");
    }

    public String getName() {
        return this.conversation.getAttribute("muc_name");
    }

    private List<User> getFallbackUsersFromCryptoTargets() {
        List<User> users = new ArrayList<>();
        for (Jid jid : conversation.getAcceptedCryptoTargets()) {
            User user = new User(this, null);
            user.setRealJid(jid);
            users.add(user);
        }
        return users;
    }

    public List<User> getUsersRelevantForNameAndAvatar() {
        final List<User> users;
        if (isOnline) {
            users = getUsers(5);
        } else {
            users = getFallbackUsersFromCryptoTargets();
        }
        return users;
    }

    String createNameFromParticipants() {
        List<User> users = getUsersRelevantForNameAndAvatar();
        if (users.size() >= 2) {
            StringBuilder builder = new StringBuilder();
            for (User user : users) {
                if (builder.length() != 0) {
                    builder.append(", ");
                }
                String name = UIHelper.getDisplayName(user);
                if (name != null) {
                    builder.append(name.split("\\s+")[0]);
                }
            }
            return builder.toString();
        } else {
            return null;
        }
    }

    public long[] getPgpKeyIds() {
        List<Long> ids = new ArrayList<>();
        for (User user : this.users) {
            if (user.getPgpKeyId() != 0) {
                ids.add(user.getPgpKeyId());
            }
        }
        ids.add(account.getPgpId());
        long[] primitiveLongArray = new long[ids.size()];
        for (int i = 0; i < ids.size(); ++i) {
            primitiveLongArray[i] = ids.get(i);
        }
        return primitiveLongArray;
    }

    public boolean pgpKeysInUse() {
        synchronized (users) {
            for (User user : users) {
                if (user.getPgpKeyId() != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean everybodyHasKeys() {
        synchronized (users) {
            for (User user : users) {
                if (user.getPgpKeyId() == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    public Jid createJoinJid(String nick) {
        try {
            return conversation.getJid().withResource(nick);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public Jid getTrueCounterpart(Jid jid) {
        if (jid.equals(getSelf().getFullJid())) {
            return account.getJid().asBareJid();
        }
        User user = findUserByFullJid(jid);
        return user == null ? null : user.realJid;
    }

    public String getPassword() {
        this.password = conversation.getAttribute(Conversation.ATTRIBUTE_MUC_PASSWORD);
        if (this.password == null && conversation.getBookmark() != null
                && conversation.getBookmark().getPassword() != null) {
            return conversation.getBookmark().getPassword();
        } else {
            return this.password;
        }
    }

    public void setPassword(String password) {
        if (conversation.getBookmark() != null) {
            conversation.getBookmark().setPassword(password);
        } else {
            this.password = password;
        }
        conversation.setAttribute(Conversation.ATTRIBUTE_MUC_PASSWORD, password);
    }

    public Conversation getConversation() {
        return this.conversation;
    }

    public List<Jid> getMembers(final boolean includeDomains) {
        ArrayList<Jid> members = new ArrayList<>();
        synchronized (users) {
            for (User user : users) {
                if (user.affiliation.ranks(Affiliation.MEMBER) && user.realJid != null && !user.realJid.asBareJid().equals(conversation.account.getJid().asBareJid()) && (!user.isDomain() || includeDomains)) {
                    members.add(user.realJid);
                }
            }
        }
        return members;
    }

    public enum Affiliation {
        OWNER(4, R.string.owner),
        ADMIN(3, R.string.admin),
        MEMBER(2, R.string.member),
        OUTCAST(0, R.string.outcast),
        NONE(1, R.string.no_affiliation);

        private final int resId;
        private final int rank;

        Affiliation(int rank, int resId) {
            this.resId = resId;
            this.rank = rank;
        }

        public static Affiliation of(@Nullable String value) {
            if (value == null) {
                return NONE;
            }
            try {
                return Affiliation.valueOf(value.toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }

        public int getResId() {
            return resId;
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.US);
        }

        public boolean outranks(Affiliation affiliation) {
            return rank > affiliation.rank;
        }

        public boolean ranks(Affiliation affiliation) {
            return rank >= affiliation.rank;
        }
    }

    public enum Role {
        MODERATOR(R.string.moderator, 3),
        VISITOR(R.string.visitor, 1),
        PARTICIPANT(R.string.participant, 2),
        NONE(R.string.no_role, 0);

        private final int resId;
        private final int rank;

        Role(int resId, int rank) {
            this.resId = resId;
            this.rank = rank;
        }

        public static Role of(@Nullable String value) {
            if (value == null) {
                return NONE;
            }
            try {
                return Role.valueOf(value.toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }

        public int getResId() {
            return resId;
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.US);
        }

        public boolean ranks(Role role) {
            return rank >= role.rank;
        }
    }

    public enum Error {
        NO_RESPONSE,
        SERVER_NOT_FOUND,
        REMOTE_SERVER_TIMEOUT,
        NONE,
        NICK_IN_USE,
        PASSWORD_REQUIRED,
        BANNED,
        MEMBERS_ONLY,
        RESOURCE_CONSTRAINT,
        KICKED,
        SHUTDOWN,
        DESTROYED,
        INVALID_NICK,
        TECHNICAL_PROBLEMS,
        UNKNOWN,
        NON_ANONYMOUS
    }

    private interface OnEventListener {
        void onSuccess();

        void onFailure();
    }

    public interface OnRenameListener extends OnEventListener {

    }

    public static class User implements Comparable<User>, AvatarService.Avatarable {
        private Role role = Role.NONE;
        private Affiliation affiliation = Affiliation.NONE;
        private Jid realJid;
        private Jid fullJid;
        private long pgpKeyId = 0;
        private Avatar avatar;
        private final MucOptions options;
        private ChatState chatState = Config.DEFAULT_CHAT_STATE;

        public User(MucOptions options, Jid fullJid) {
            this.options = options;
            this.fullJid = fullJid;
        }

        public String getName() {
            return fullJid == null ? null : fullJid.getResource();
        }

        public Role getRole() {
            return this.role;
        }

        public void setRole(String role) {
            this.role = Role.of(role);
        }

        public Affiliation getAffiliation() {
            return this.affiliation;
        }

        public void setAffiliation(String affiliation) {
            this.affiliation = Affiliation.of(affiliation);
        }

        public long getPgpKeyId() {
            if (this.pgpKeyId != 0) {
                return this.pgpKeyId;
            } else if (realJid != null) {
                return getAccount().getRoster().getContact(realJid).getPgpKeyId();
            } else {
                return 0;
            }
        }

        public void setPgpKeyId(long id) {
            this.pgpKeyId = id;
        }

        public Contact getContact() {
            if (fullJid != null) {
                return getAccount().getRoster().getContactFromContactList(realJid);
            } else if (realJid != null) {
                return getAccount().getRoster().getContact(realJid);
            } else {
                return null;
            }
        }

        public boolean setAvatar(Avatar avatar) {
            if (this.avatar != null && this.avatar.equals(avatar)) {
                return false;
            } else {
                this.avatar = avatar;
                return true;
            }
        }

        public String getAvatar() {
            if (avatar != null) {
                return avatar.getFilename();
            }
            Avatar avatar = realJid != null ? getAccount().getRoster().getContact(realJid).getAvatar() : null;
            return avatar == null ? null : avatar.getFilename();
        }

        public Account getAccount() {
            return options.getAccount();
        }

        public Conversation getConversation() {
            return options.getConversation();
        }

        public Jid getFullJid() {
            return fullJid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            User user = (User) o;

            if (role != user.role) return false;
            if (affiliation != user.affiliation) return false;
            if (realJid != null ? !realJid.equals(user.realJid) : user.realJid != null)
                return false;
            return fullJid != null ? fullJid.equals(user.fullJid) : user.fullJid == null;

        }

        public boolean isDomain() {
            return realJid != null && realJid.getLocal() == null && role == Role.NONE;
        }

        @Override
        public int hashCode() {
            int result = role != null ? role.hashCode() : 0;
            result = 31 * result + (affiliation != null ? affiliation.hashCode() : 0);
            result = 31 * result + (realJid != null ? realJid.hashCode() : 0);
            result = 31 * result + (fullJid != null ? fullJid.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "[fulljid:" + fullJid + ",realjid:" + realJid + ",affiliation" + affiliation.toString() + "]";
        }

        public boolean realJidMatchesAccount() {
            return realJid != null && realJid.equals(options.account.getJid().asBareJid());
        }

        @Override
        public int compareTo(@NonNull User another) {
            if (another.getAffiliation().outranks(getAffiliation())) {
                return 1;
            } else if (getAffiliation().outranks(another.getAffiliation())) {
                return -1;
            } else {
                return getComparableName().compareToIgnoreCase(another.getComparableName());
            }
        }

        public String getComparableName() {
            Contact contact = getContact();
            if (contact != null) {
                return contact.getDisplayName();
            } else {
                String name = getName();
                return name == null ? "" : name;
            }
        }

        public Jid getRealJid() {
            return realJid;
        }

        public void setRealJid(Jid jid) {
            this.realJid = jid != null ? jid.asBareJid() : null;
        }

        public boolean setChatState(ChatState chatState) {
            if (this.chatState == chatState) {
                return false;
            }
            this.chatState = chatState;
            return true;
        }

        @Override
        public int getAvatarBackgroundColor() {
            final String seed = realJid != null ? realJid.asBareJid().toString() : null;
            return UIHelper.getColorForName(seed == null ? getName() : seed);
        }

        @Override
        public String getAvatarName() {
            return getConversation().getName().toString();
        }
    }
}
