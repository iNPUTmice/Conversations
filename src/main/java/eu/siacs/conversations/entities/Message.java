package eu.siacs.conversations.entities;

import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.util.Log;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Longs;

import org.json.JSONException;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.http.URL;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.ui.util.PresenceSelector;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;

public class Message extends AbstractEntity implements AvatarService.Avatarable {

    public static final String TABLENAME = "messages";

    public static final int STATUS_RECEIVED = 0;
    public static final int STATUS_UNSEND = 1;
    public static final int STATUS_SEND = 2;
    public static final int STATUS_SEND_FAILED = 3;
    public static final int STATUS_WAITING = 5;
    public static final int STATUS_OFFERED = 6;
    public static final int STATUS_SEND_RECEIVED = 7;
    public static final int STATUS_SEND_DISPLAYED = 8;

    public static final int ENCRYPTION_NONE = 0;
    public static final int ENCRYPTION_PGP = 1;
    public static final int ENCRYPTION_OTR = 2;
    public static final int ENCRYPTION_DECRYPTED = 3;
    public static final int ENCRYPTION_DECRYPTION_FAILED = 4;
    public static final int ENCRYPTION_AXOLOTL = 5;
    public static final int ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE = 6;
    public static final int ENCRYPTION_AXOLOTL_FAILED = 7;

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_FILE = 2;
    public static final int TYPE_STATUS = 3;
    public static final int TYPE_PRIVATE = 4;
    public static final int TYPE_PRIVATE_FILE = 5;
    public static final int TYPE_RTP_SESSION = 6;

    public static final String CONVERSATION = "conversationUuid";
    public static final String COUNTERPART = "counterpart";
    public static final String TRUE_COUNTERPART = "trueCounterpart";
    public static final String BODY = "body";
    public static final String BODY_LANGUAGE = "bodyLanguage";
    public static final String TIME_SENT = "timeSent";
    public static final String ENCRYPTION = "encryption";
    public static final String STATUS = "status";
    public static final String TYPE = "type";
    public static final String CARBON = "carbon";
    public static final String OOB = "oob";
    public static final String EDITED = "edited";
    public static final String REMOTE_MSG_ID = "remoteMsgId";
    public static final String SERVER_MSG_ID = "serverMsgId";
    public static final String RELATIVE_FILE_PATH = "relativeFilePath";
    public static final String FINGERPRINT = "axolotl_fingerprint";
    public static final String READ = "read";
    public static final String ERROR_MESSAGE = "errorMsg";
    public static final String READ_BY_MARKERS = "readByMarkers";
    public static final String MARKABLE = "markable";
    public static final String DELETED = "deleted";
    public static final String ME_COMMAND = "/me ";

    public static final String ERROR_MESSAGE_CANCELLED = "eu.siacs.conversations.cancelled";


    public boolean markable = false;
    protected String conversationUuid;
    protected Jid counterpart;
    protected Jid trueCounterpart;
    protected String body;
    protected String encryptedBody;
    protected long timeSent;
    protected int encryption;
    protected int status;
    protected int type;
    protected boolean deleted = false;
    protected boolean carbon = false;
    protected boolean oob = false;
    protected List<Edit> edits = new ArrayList<>();
    protected String relativeFilePath;
    protected boolean read = true;
    protected String remoteMsgId = null;
    private String bodyLanguage = null;
    protected String serverMsgId = null;
    private final Conversational conversation;
    protected Transferable transferable = null;
    private Message mNextMessage = null;
    private Message mPreviousMessage = null;
    private String axolotlFingerprint = null;
    private String errorMessage = null;
    private Set<ReadByMarker> readByMarkers = new CopyOnWriteArraySet<>();

    private Boolean isGeoUri = null;
    private Boolean isEmojisOnly = null;
    private Boolean treatAsDownloadable = null;
    private FileParams fileParams = null;
    private List<MucOptions.User> counterparts;
    private WeakReference<MucOptions.User> user;

    protected Message(Conversational conversation) {
        this.conversation = conversation;
    }

    public Message(Conversational conversation, String body, int encryption) {
        this(conversation, body, encryption, STATUS_UNSEND);
    }

    public Message(Conversational conversation, String body, int encryption, int status) {
        this(conversation, java.util.UUID.randomUUID().toString(),
                conversation.getUuid(),
                conversation.getJid() == null ? null : conversation.getJid().asBareJid(),
                null,
                body,
                System.currentTimeMillis(),
                encryption,
                status,
                TYPE_TEXT,
                false,
                null,
                null,
                null,
                null,
                true,
                null,
                false,
                null,
                null,
                false,
                false,
                null);
    }

    public Message(Conversation conversation, int status, int type, final String remoteMsgId) {
        this(conversation, java.util.UUID.randomUUID().toString(),
                conversation.getUuid(),
                conversation.getJid() == null ? null : conversation.getJid().asBareJid(),
                null,
                null,
                System.currentTimeMillis(),
                Message.ENCRYPTION_NONE,
                status,
                type,
                false,
                remoteMsgId,
                null,
                null,
                null,
                true,
                null,
                false,
                null,
                null,
                false,
                false,
                null);
    }

    protected Message(final Conversational conversation, final String uuid, final String conversationUUid, final Jid counterpart,
                      final Jid trueCounterpart, final String body, final long timeSent,
                      final int encryption, final int status, final int type, final boolean carbon,
                      final String remoteMsgId, final String relativeFilePath,
                      final String serverMsgId, final String fingerprint, final boolean read,
                      final String edited, final boolean oob, final String errorMessage, final Set<ReadByMarker> readByMarkers,
                      final boolean markable, final boolean deleted, final String bodyLanguage) {
        this.conversation = conversation;
        this.uuid = uuid;
        this.conversationUuid = conversationUUid;
        this.counterpart = counterpart;
        this.trueCounterpart = trueCounterpart;
        this.body = body == null ? "" : body;
        this.timeSent = timeSent;
        this.encryption = encryption;
        this.status = status;
        this.type = type;
        this.carbon = carbon;
        this.remoteMsgId = remoteMsgId;
        this.relativeFilePath = relativeFilePath;
        this.serverMsgId = serverMsgId;
        this.axolotlFingerprint = fingerprint;
        this.read = read;
        this.edits = Edit.fromJson(edited);
        this.oob = oob;
        this.errorMessage = errorMessage;
        this.readByMarkers = readByMarkers == null ? new CopyOnWriteArraySet<>() : readByMarkers;
        this.markable = markable;
        this.deleted = deleted;
        this.bodyLanguage = bodyLanguage;
    }

    public static Message fromCursor(Cursor cursor, Conversation conversation) {
        return new Message(conversation,
                cursor.getString(cursor.getColumnIndex(UUID)),
                cursor.getString(cursor.getColumnIndex(CONVERSATION)),
                fromString(cursor.getString(cursor.getColumnIndex(COUNTERPART))),
                fromString(cursor.getString(cursor.getColumnIndex(TRUE_COUNTERPART))),
                cursor.getString(cursor.getColumnIndex(BODY)),
                cursor.getLong(cursor.getColumnIndex(TIME_SENT)),
                cursor.getInt(cursor.getColumnIndex(ENCRYPTION)),
                cursor.getInt(cursor.getColumnIndex(STATUS)),
                cursor.getInt(cursor.getColumnIndex(TYPE)),
                cursor.getInt(cursor.getColumnIndex(CARBON)) > 0,
                cursor.getString(cursor.getColumnIndex(REMOTE_MSG_ID)),
                cursor.getString(cursor.getColumnIndex(RELATIVE_FILE_PATH)),
                cursor.getString(cursor.getColumnIndex(SERVER_MSG_ID)),
                cursor.getString(cursor.getColumnIndex(FINGERPRINT)),
                cursor.getInt(cursor.getColumnIndex(READ)) > 0,
                cursor.getString(cursor.getColumnIndex(EDITED)),
                cursor.getInt(cursor.getColumnIndex(OOB)) > 0,
                cursor.getString(cursor.getColumnIndex(ERROR_MESSAGE)),
                ReadByMarker.fromJsonString(cursor.getString(cursor.getColumnIndex(READ_BY_MARKERS))),
                cursor.getInt(cursor.getColumnIndex(MARKABLE)) > 0,
                cursor.getInt(cursor.getColumnIndex(DELETED)) > 0,
                cursor.getString(cursor.getColumnIndex(BODY_LANGUAGE))
        );
    }

    private static Jid fromString(String value) {
        try {
            if (value != null) {
                return Jid.of(value);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    public static Message createStatusMessage(Conversation conversation, String body) {
        final Message message = new Message(conversation);
        message.setType(Message.TYPE_STATUS);
        message.setStatus(Message.STATUS_RECEIVED);
        message.body = body;
        return message;
    }

    public static Message createLoadMoreMessage(Conversation conversation) {
        final Message message = new Message(conversation);
        message.setType(Message.TYPE_STATUS);
        message.body = "LOAD_MORE";
        return message;
    }

    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(UUID, uuid);
        values.put(CONVERSATION, conversationUuid);
        if (counterpart == null) {
            values.putNull(COUNTERPART);
        } else {
            values.put(COUNTERPART, counterpart.toString());
        }
        if (trueCounterpart == null) {
            values.putNull(TRUE_COUNTERPART);
        } else {
            values.put(TRUE_COUNTERPART, trueCounterpart.toString());
        }
        values.put(BODY, body.length() > Config.MAX_STORAGE_MESSAGE_CHARS ? body.substring(0, Config.MAX_STORAGE_MESSAGE_CHARS) : body);
        values.put(TIME_SENT, timeSent);
        values.put(ENCRYPTION, encryption);
        values.put(STATUS, status);
        values.put(TYPE, type);
        values.put(CARBON, carbon ? 1 : 0);
        values.put(REMOTE_MSG_ID, remoteMsgId);
        values.put(RELATIVE_FILE_PATH, relativeFilePath);
        values.put(SERVER_MSG_ID, serverMsgId);
        values.put(FINGERPRINT, axolotlFingerprint);
        values.put(READ, read ? 1 : 0);
        try {
            values.put(EDITED, Edit.toJson(edits));
        } catch (JSONException e) {
            Log.e(Config.LOGTAG, "error persisting json for edits", e);
        }
        values.put(OOB, oob ? 1 : 0);
        values.put(ERROR_MESSAGE, errorMessage);
        values.put(READ_BY_MARKERS, ReadByMarker.toJson(readByMarkers).toString());
        values.put(MARKABLE, markable ? 1 : 0);
        values.put(DELETED, deleted ? 1 : 0);
        values.put(BODY_LANGUAGE, bodyLanguage);
        return values;
    }

    public String getConversationUuid() {
        return conversationUuid;
    }

    public Conversational getConversation() {
        return this.conversation;
    }

    public Jid getCounterpart() {
        return counterpart;
    }

    public void setCounterpart(final Jid counterpart) {
        this.counterpart = counterpart;
    }

    public Contact getContact() {
        if (this.conversation.getMode() == Conversation.MODE_SINGLE) {
            return this.conversation.getContact();
        } else {
            if (this.trueCounterpart == null) {
                return null;
            } else {
                return this.conversation.getAccount().getRoster()
                        .getContactFromContactList(this.trueCounterpart);
            }
        }
    }

    public String getBody() {
        return body;
    }

    public synchronized void setBody(String body) {
        if (body == null) {
            throw new Error("You should not set the message body to null");
        }
        this.body = body;
        this.isGeoUri = null;
        this.isEmojisOnly = null;
        this.treatAsDownloadable = null;
        this.fileParams = null;
    }

    public void setMucUser(MucOptions.User user) {
        this.user = new WeakReference<>(user);
    }

    public boolean sameMucUser(Message otherMessage) {
        final MucOptions.User thisUser = this.user == null ? null : this.user.get();
        final MucOptions.User otherUser = otherMessage.user == null ? null : otherMessage.user.get();
        return thisUser != null && thisUser == otherUser;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean setErrorMessage(String message) {
        boolean changed = (message != null && !message.equals(errorMessage))
                || (message == null && errorMessage != null);
        this.errorMessage = message;
        return changed;
    }

    public long getTimeSent() {
        return timeSent;
    }

    public int getEncryption() {
        return encryption;
    }

    public void setEncryption(int encryption) {
        this.encryption = encryption;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getRelativeFilePath() {
        return this.relativeFilePath;
    }

    public void setRelativeFilePath(String path) {
        this.relativeFilePath = path;
    }

    public String getRemoteMsgId() {
        return this.remoteMsgId;
    }

    public void setRemoteMsgId(String id) {
        this.remoteMsgId = id;
    }

    public String getServerMsgId() {
        return this.serverMsgId;
    }

    public void setServerMsgId(String id) {
        this.serverMsgId = id;
    }

    public boolean isRead() {
        return this.read;
    }

    public boolean isDeleted() {
        return this.deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public void markRead() {
        this.read = true;
    }

    public void markUnread() {
        this.read = false;
    }

    public void setTime(long time) {
        this.timeSent = time;
    }

    public String getEncryptedBody() {
        return this.encryptedBody;
    }

    public void setEncryptedBody(String body) {
        this.encryptedBody = body;
    }

    public int getType() {
        return this.type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public boolean isCarbon() {
        return carbon;
    }

    public void setCarbon(boolean carbon) {
        this.carbon = carbon;
    }

    public void putEdited(String edited, String serverMsgId) {
        final Edit edit = new Edit(edited, serverMsgId);
        if (this.edits.size() < 128 && !this.edits.contains(edit)) {
            this.edits.add(edit);
        }
    }

    boolean remoteMsgIdMatchInEdit(String id) {
        for (Edit edit : this.edits) {
            if (id.equals(edit.getEditedId())) {
                return true;
            }
        }
        return false;
    }

    public String getBodyLanguage() {
        return this.bodyLanguage;
    }

    public void setBodyLanguage(String language) {
        this.bodyLanguage = language;
    }

    public boolean edited() {
        return this.edits.size() > 0;
    }

    public void setTrueCounterpart(Jid trueCounterpart) {
        this.trueCounterpart = trueCounterpart;
    }

    public Jid getTrueCounterpart() {
        return this.trueCounterpart;
    }

    public Transferable getTransferable() {
        return this.transferable;
    }

    public synchronized void setTransferable(Transferable transferable) {
        this.fileParams = null;
        this.transferable = transferable;
    }

    public boolean addReadByMarker(ReadByMarker readByMarker) {
        if (readByMarker.getRealJid() != null) {
            if (readByMarker.getRealJid().asBareJid().equals(trueCounterpart)) {
                return false;
            }
        } else if (readByMarker.getFullJid() != null) {
            if (readByMarker.getFullJid().equals(counterpart)) {
                return false;
            }
        }
        if (this.readByMarkers.add(readByMarker)) {
            if (readByMarker.getRealJid() != null && readByMarker.getFullJid() != null) {
                Iterator<ReadByMarker> iterator = this.readByMarkers.iterator();
                while (iterator.hasNext()) {
                    ReadByMarker marker = iterator.next();
                    if (marker.getRealJid() == null && readByMarker.getFullJid().equals(marker.getFullJid())) {
                        iterator.remove();
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public Set<ReadByMarker> getReadByMarkers() {
        return ImmutableSet.copyOf(this.readByMarkers);
    }

    boolean similar(Message message) {
        if (!isPrivateMessage() && this.serverMsgId != null && message.getServerMsgId() != null) {
            return this.serverMsgId.equals(message.getServerMsgId()) || Edit.wasPreviouslyEditedServerMsgId(edits, message.getServerMsgId());
        } else if (Edit.wasPreviouslyEditedServerMsgId(edits, message.getServerMsgId())) {
            return true;
        } else if (this.body == null || this.counterpart == null) {
            return false;
        } else {
            String body, otherBody;
            if (this.hasFileOnRemoteHost()) {
                body = getFileParams().url;
                otherBody = message.body == null ? null : message.body.trim();
            } else {
                body = this.body;
                otherBody = message.body;
            }
            final boolean matchingCounterpart = this.counterpart.equals(message.getCounterpart());
            if (message.getRemoteMsgId() != null) {
                final boolean hasUuid = CryptoHelper.UUID_PATTERN.matcher(message.getRemoteMsgId()).matches();
                if (hasUuid && matchingCounterpart && Edit.wasPreviouslyEditedRemoteMsgId(edits, message.getRemoteMsgId())) {
                    return true;
                }
                return (message.getRemoteMsgId().equals(this.remoteMsgId) || message.getRemoteMsgId().equals(this.uuid))
                        && matchingCounterpart
                        && (body.equals(otherBody) || (message.getEncryption() == Message.ENCRYPTION_PGP && hasUuid));
            } else {
                return this.remoteMsgId == null
                        && matchingCounterpart
                        && body.equals(otherBody)
                        && Math.abs(this.getTimeSent() - message.getTimeSent()) < Config.MESSAGE_MERGE_WINDOW * 1000;
            }
        }
    }

    public Message next() {
        if (this.conversation instanceof Conversation) {
            final Conversation conversation = (Conversation) this.conversation;
            synchronized (conversation.messages) {
                if (this.mNextMessage == null) {
                    int index = conversation.messages.indexOf(this);
                    if (index < 0 || index >= conversation.messages.size() - 1) {
                        this.mNextMessage = null;
                    } else {
                        this.mNextMessage = conversation.messages.get(index + 1);
                    }
                }
                return this.mNextMessage;
            }
        } else {
            throw new AssertionError("Calling next should be disabled for stubs");
        }
    }

    public Message prev() {
        if (this.conversation instanceof Conversation) {
            final Conversation conversation = (Conversation) this.conversation;
            synchronized (conversation.messages) {
                if (this.mPreviousMessage == null) {
                    int index = conversation.messages.indexOf(this);
                    if (index <= 0 || index > conversation.messages.size()) {
                        this.mPreviousMessage = null;
                    } else {
                        this.mPreviousMessage = conversation.messages.get(index - 1);
                    }
                }
            }
            return this.mPreviousMessage;
        } else {
            throw new AssertionError("Calling prev should be disabled for stubs");
        }
    }

    public boolean isLastCorrectableMessage() {
        Message next = next();
        while (next != null) {
            if (next.isEditable()) {
                return false;
            }
            next = next.next();
        }
        return isEditable();
    }

    public boolean isEditable() {
        return status != STATUS_RECEIVED && !isCarbon() && type != Message.TYPE_RTP_SESSION;
    }

    public boolean mergeable(final Message message) {
        return message != null &&
                (message.getType() == Message.TYPE_TEXT &&
                        this.getTransferable() == null &&
                        message.getTransferable() == null &&
                        message.getEncryption() != Message.ENCRYPTION_PGP &&
                        message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED &&
                        this.getType() == message.getType() &&
                        isStatusMergeable(this.getStatus(), message.getStatus()) &&
                        isEncryptionMergeable(this.getEncryption(),message.getEncryption()) &&
                        this.getCounterpart() != null &&
                        this.getCounterpart().equals(message.getCounterpart()) &&
                        this.edited() == message.edited() &&
                        (message.getTimeSent() - this.getTimeSent()) <= (Config.MESSAGE_MERGE_WINDOW * 1000) &&
                        this.getBody().length() + message.getBody().length() <= Config.MAX_DISPLAY_MESSAGE_CHARS &&
                        !message.isGeoUri() &&
                        !this.isGeoUri() &&
                        !message.isOOb() &&
                        !this.isOOb() &&
                        !message.treatAsDownloadable() &&
                        !this.treatAsDownloadable() &&
                        !message.hasMeCommand() &&
                        !this.hasMeCommand() &&
                        !this.bodyIsOnlyEmojis() &&
                        !message.bodyIsOnlyEmojis() &&
                        ((this.axolotlFingerprint == null && message.axolotlFingerprint == null) || this.axolotlFingerprint.equals(message.getFingerprint())) &&
                        UIHelper.sameDay(message.getTimeSent(), this.getTimeSent()) &&
                        this.getReadByMarkers().equals(message.getReadByMarkers()) &&
                        !this.conversation.getJid().asBareJid().equals(Config.BUG_REPORTS)
                );
    }

    private static boolean isStatusMergeable(int a, int b) {
        return a == b || (
                (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_UNSEND)
                        || (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_SEND)
                        || (a == Message.STATUS_SEND_RECEIVED && b == Message.STATUS_WAITING)
                        || (a == Message.STATUS_SEND && b == Message.STATUS_UNSEND)
                        || (a == Message.STATUS_SEND && b == Message.STATUS_WAITING)
        );
    }

    private static boolean isEncryptionMergeable(final int a, final int b) {
        return a == b
                && Arrays.asList(ENCRYPTION_NONE, ENCRYPTION_DECRYPTED, ENCRYPTION_AXOLOTL)
                        .contains(a);
    }

    public void setCounterparts(List<MucOptions.User> counterparts) {
        this.counterparts = counterparts;
    }

    public List<MucOptions.User> getCounterparts() {
        return this.counterparts;
    }

    @Override
    public int getAvatarBackgroundColor() {
        if (type == Message.TYPE_STATUS && getCounterparts() != null && getCounterparts().size() > 1) {
            return Color.TRANSPARENT;
        } else {
            return UIHelper.getColorForName(UIHelper.getMessageDisplayName(this));
        }
    }

    @Override
    public String getAvatarName() {
        return UIHelper.getMessageDisplayName(this);
    }

    public boolean isOOb() {
        return oob;
    }

    public static class MergeSeparator {
    }

    public SpannableStringBuilder getMergedBody() {
        SpannableStringBuilder body = new SpannableStringBuilder(MessageUtils.filterLtrRtl(this.body).trim());
        Message current = this;
        while (current.mergeable(current.next())) {
            current = current.next();
            if (current == null) {
                break;
            }
            body.append("\n\n");
            body.setSpan(new MergeSeparator(), body.length() - 2, body.length(),
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.append(MessageUtils.filterLtrRtl(current.getBody()).trim());
        }
        return body;
    }

    public boolean hasMeCommand() {
        return this.body.trim().startsWith(ME_COMMAND);
    }

    public int getMergedStatus() {
        int status = this.status;
        Message current = this;
        while (current.mergeable(current.next())) {
            current = current.next();
            if (current == null) {
                break;
            }
            status = current.status;
        }
        return status;
    }

    public long getMergedTimeSent() {
        long time = this.timeSent;
        Message current = this;
        while (current.mergeable(current.next())) {
            current = current.next();
            if (current == null) {
                break;
            }
            time = current.timeSent;
        }
        return time;
    }

    public boolean wasMergedIntoPrevious() {
        Message prev = this.prev();
        return prev != null && prev.mergeable(this);
    }

    public boolean trusted() {
        Contact contact = this.getContact();
        return status > STATUS_RECEIVED || (contact != null && (contact.showInContactList() || contact.isSelf()));
    }

    public boolean fixCounterpart() {
        final Presences presences = conversation.getContact().getPresences();
        if (counterpart != null && presences.has(Strings.nullToEmpty(counterpart.getResource()))) {
            return true;
        } else if (presences.size() >= 1) {
            counterpart = PresenceSelector.getNextCounterpart(getContact(), presences.toResourceArray()[0]);
            return true;
        } else {
            counterpart = null;
            return false;
        }
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getEditedId() {
        if (edits.size() > 0) {
            return edits.get(edits.size() - 1).getEditedId();
        } else {
            throw new IllegalStateException("Attempting to store unedited message");
        }
    }

    public String getEditedIdWireFormat() {
        if (edits.size() > 0) {
            return edits.get(Config.USE_LMC_VERSION_1_1 ? 0 : edits.size() - 1).getEditedId();
        } else {
            throw new IllegalStateException("Attempting to store unedited message");
        }
    }

    public void setOob(boolean isOob) {
        this.oob = isOob;
    }

    public String getMimeType() {
        String extension;
        if (relativeFilePath != null) {
            extension = MimeUtils.extractRelevantExtension(relativeFilePath);
        } else {
            final String url = URL.tryParse(body.split("\n")[0]);
            if (url == null) {
                return null;
            }
            extension = MimeUtils.extractRelevantExtension(url);
        }
        return MimeUtils.guessMimeTypeFromExtension(extension);
    }

    public synchronized boolean treatAsDownloadable() {
        if (treatAsDownloadable == null) {
            treatAsDownloadable = MessageUtils.treatAsDownloadable(this.body, this.oob);
        }
        return treatAsDownloadable;
    }

    public synchronized boolean bodyIsOnlyEmojis() {
        if (isEmojisOnly == null) {
            isEmojisOnly = Emoticons.isOnlyEmoji(body.replaceAll("\\s", ""));
        }
        return isEmojisOnly;
    }

    public synchronized boolean isGeoUri() {
        if (isGeoUri == null) {
            isGeoUri = GeoHelper.GEO_URI.matcher(body).matches();
        }
        return isGeoUri;
    }

    public synchronized void resetFileParams() {
        this.fileParams = null;
    }

    public synchronized FileParams getFileParams() {
        if (fileParams == null) {
            fileParams = new FileParams();
            if (this.transferable != null) {
                fileParams.size = this.transferable.getFileSize();
            }
            final String[] parts = body == null ? new String[0] : body.split("\\|");
            switch (parts.length) {
                case 1:
                    try {
                        fileParams.size = Long.parseLong(parts[0]);
                    } catch (final NumberFormatException e) {
                        fileParams.url = URL.tryParse(parts[0]);
                    }
                    break;
                case 5:
                    fileParams.runtime = parseInt(parts[4]);
                case 4:
                    fileParams.width = parseInt(parts[2]);
                    fileParams.height = parseInt(parts[3]);
                case 2:
                    fileParams.url = URL.tryParse(parts[0]);
                    fileParams.size = Longs.tryParse(parts[1]);
                    break;
                case 3:
                    fileParams.size = Longs.tryParse(parts[0]);
                    fileParams.width = parseInt(parts[1]);
                    fileParams.height = parseInt(parts[2]);
                    break;
            }
        }
        return fileParams;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void untie() {
        this.mNextMessage = null;
        this.mPreviousMessage = null;
    }

    public boolean isPrivateMessage() {
        return type == TYPE_PRIVATE || type == TYPE_PRIVATE_FILE;
    }

    public boolean isFileOrImage() {
        return type == TYPE_FILE || type == TYPE_IMAGE || type == TYPE_PRIVATE_FILE;
    }


    public boolean isTypeText() {
        return type == TYPE_TEXT || type == TYPE_PRIVATE;
    }

    public boolean hasFileOnRemoteHost() {
        return isFileOrImage() && getFileParams().url != null;
    }

    public boolean needsUploading() {
        return isFileOrImage() && getFileParams().url == null;
    }

    public static class FileParams {
        public String url;
        public Long size = null;
        public int width = 0;
        public int height = 0;
        public int runtime = 0;

        public long getSize() {
            return size == null ? 0 : size;
        }
    }

    public void setFingerprint(String fingerprint) {
        this.axolotlFingerprint = fingerprint;
    }

    public String getFingerprint() {
        return axolotlFingerprint;
    }

    public boolean isTrusted() {
        final AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
        final FingerprintStatus s = axolotlService != null ? axolotlService.getFingerprintTrust(axolotlFingerprint) : null;
        return s != null && s.isTrusted();
    }

    private int getPreviousEncryption() {
        for (Message iterator = this.prev(); iterator != null; iterator = iterator.prev()) {
            if (iterator.isCarbon() || iterator.getStatus() == STATUS_RECEIVED) {
                continue;
            }
            return iterator.getEncryption();
        }
        return ENCRYPTION_NONE;
    }

    private int getNextEncryption() {
        if (this.conversation instanceof Conversation) {
            Conversation conversation = (Conversation) this.conversation;
            for (Message iterator = this.next(); iterator != null; iterator = iterator.next()) {
                if (iterator.isCarbon() || iterator.getStatus() == STATUS_RECEIVED) {
                    continue;
                }
                return iterator.getEncryption();
            }
            return conversation.getNextEncryption();
        } else {
            throw new AssertionError("This should never be called since isInValidSession should be disabled for stubs");
        }
    }

    public boolean isValidInSession() {
        int pastEncryption = getCleanedEncryption(this.getPreviousEncryption());
        int futureEncryption = getCleanedEncryption(this.getNextEncryption());

        boolean inUnencryptedSession = pastEncryption == ENCRYPTION_NONE
                || futureEncryption == ENCRYPTION_NONE
                || pastEncryption != futureEncryption;

        return inUnencryptedSession || getCleanedEncryption(this.getEncryption()) == pastEncryption;
    }

    private static int getCleanedEncryption(int encryption) {
        if (encryption == ENCRYPTION_DECRYPTED || encryption == ENCRYPTION_DECRYPTION_FAILED) {
            return ENCRYPTION_PGP;
        }
        if (encryption == ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE || encryption == ENCRYPTION_AXOLOTL_FAILED) {
            return ENCRYPTION_AXOLOTL;
        }
        return encryption;
    }

    public static boolean configurePrivateMessage(final Message message) {
        return configurePrivateMessage(message, false);
    }

    public static boolean configurePrivateFileMessage(final Message message) {
        return configurePrivateMessage(message, true);
    }

    private static boolean configurePrivateMessage(final Message message, final boolean isFile) {
        final Conversation conversation;
        if (message.conversation instanceof Conversation) {
            conversation = (Conversation) message.conversation;
        } else {
            return false;
        }
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            final Jid nextCounterpart = conversation.getNextCounterpart();
            return configurePrivateMessage(conversation, message, nextCounterpart, isFile);
        }
        return false;
    }

    public static boolean configurePrivateMessage(final Message message, final Jid counterpart) {
        final Conversation conversation;
        if (message.conversation instanceof Conversation) {
            conversation = (Conversation) message.conversation;
        } else {
            return false;
        }
        return configurePrivateMessage(conversation, message, counterpart, false);
    }

    private static boolean configurePrivateMessage(final Conversation conversation, final Message message, final Jid counterpart, final boolean isFile) {
        if (counterpart == null) {
            return false;
        }
        message.setCounterpart(counterpart);
        message.setTrueCounterpart(conversation.getMucOptions().getTrueCounterpart(counterpart));
        message.setType(isFile ? Message.TYPE_PRIVATE_FILE : Message.TYPE_PRIVATE);
        return true;
    }
}
