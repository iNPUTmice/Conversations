package eu.siacs.conversations.services;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.security.KeyChain;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.Pair;

import androidx.annotation.BoolRes;
import androidx.annotation.IntegerRes;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;

import com.google.common.base.Objects;
import com.google.common.base.Strings;

import org.conscrypt.Conscrypt;
import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;

import java.io.File;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.android.JabberIdContact;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.crypto.PgpDecryptionService;
import eu.siacs.conversations.crypto.PgpEngine;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlMessage;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Blockable;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.OnRenameListener;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.PresenceTemplate;
import eu.siacs.conversations.entities.Roster;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.generator.AbstractGenerator;
import eu.siacs.conversations.generator.IqGenerator;
import eu.siacs.conversations.generator.MessageGenerator;
import eu.siacs.conversations.generator.PresenceGenerator;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.parser.AbstractParser;
import eu.siacs.conversations.parser.IqParser;
import eu.siacs.conversations.parser.MessageParser;
import eu.siacs.conversations.parser.PresenceParser;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.ChooseAccountForProfilePictureActivity;
import eu.siacs.conversations.ui.RtpSessionActivity;
import eu.siacs.conversations.ui.SettingsActivity;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.ui.interfaces.OnAvatarPublication;
import eu.siacs.conversations.ui.interfaces.OnMediaLoaded;
import eu.siacs.conversations.ui.interfaces.OnSearchResultsAvailable;
import eu.siacs.conversations.utils.Compatibility;
import eu.siacs.conversations.utils.ConversationsFileObserver;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.EasyOnboardingInvite;
import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.PhoneHelper;
import eu.siacs.conversations.utils.QuickLoader;
import eu.siacs.conversations.utils.ReplacingSerialSingleThreadExecutor;
import eu.siacs.conversations.utils.ReplacingTaskManager;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.utils.TorServiceUtils;
import eu.siacs.conversations.utils.WakeLockHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.LocalizedContent;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnBindListener;
import eu.siacs.conversations.xmpp.OnContactStatusChanged;
import eu.siacs.conversations.xmpp.OnIqPacketReceived;
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated;
import eu.siacs.conversations.xmpp.OnMessageAcknowledged;
import eu.siacs.conversations.xmpp.OnMessagePacketReceived;
import eu.siacs.conversations.xmpp.OnPresencePacketReceived;
import eu.siacs.conversations.xmpp.OnStatusChanged;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.Patches;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.chatstate.ChatState;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import eu.siacs.conversations.xmpp.mam.MamReference;
import eu.siacs.conversations.xmpp.pep.Avatar;
import eu.siacs.conversations.xmpp.pep.PublishOptions;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;
import eu.siacs.conversations.xmpp.stanzas.PresencePacket;
import me.leolin.shortcutbadger.ShortcutBadger;

public class XmppConnectionService extends Service {

    public static final String ACTION_REPLY_TO_CONVERSATION = "reply_to_conversations";
    public static final String ACTION_MARK_AS_READ = "mark_as_read";
    public static final String ACTION_SNOOZE = "snooze";
    public static final String ACTION_CLEAR_NOTIFICATION = "clear_notification";
    public static final String ACTION_DISMISS_ERROR_NOTIFICATIONS = "dismiss_error";
    public static final String ACTION_TRY_AGAIN = "try_again";
    public static final String ACTION_IDLE_PING = "idle_ping";
    public static final String ACTION_FCM_TOKEN_REFRESH = "fcm_token_refresh";
    public static final String ACTION_FCM_MESSAGE_RECEIVED = "fcm_message_received";
    public static final String ACTION_DISMISS_CALL = "dismiss_call";
    public static final String ACTION_END_CALL = "end_call";
    public static final String ACTION_PROVISION_ACCOUNT = "provision_account";
    private static final String ACTION_POST_CONNECTIVITY_CHANGE = "eu.siacs.conversations.POST_CONNECTIVITY_CHANGE";

    private static final String SETTING_LAST_ACTIVITY_TS = "last_activity_timestamp";

    public final CountDownLatch restoredFromDatabaseLatch = new CountDownLatch(1);
    private final static Executor FILE_OBSERVER_EXECUTOR = Executors.newSingleThreadExecutor();
    private final static Executor FILE_ATTACHMENT_EXECUTOR = Executors.newSingleThreadExecutor();
    private final static SerialSingleThreadExecutor VIDEO_COMPRESSION_EXECUTOR = new SerialSingleThreadExecutor("VideoCompression");
    private final SerialSingleThreadExecutor mDatabaseWriterExecutor = new SerialSingleThreadExecutor("DatabaseWriter");
    private final SerialSingleThreadExecutor mDatabaseReaderExecutor = new SerialSingleThreadExecutor("DatabaseReader");
    private final SerialSingleThreadExecutor mNotificationExecutor = new SerialSingleThreadExecutor("NotificationExecutor");
    private final ReplacingTaskManager mRosterSyncTaskManager = new ReplacingTaskManager();
    private final IBinder mBinder = new XmppConnectionBinder();
    private final List<Conversation> conversations = new CopyOnWriteArrayList<>();
    private final IqGenerator mIqGenerator = new IqGenerator(this);
    private final Set<String> mInProgressAvatarFetches = new HashSet<>();
    private final Set<String> mOmittedPepAvatarFetches = new HashSet<>();
    private final HashSet<Jid> mLowPingTimeoutMode = new HashSet<>();
    private final OnIqPacketReceived mDefaultIqHandler = (account, packet) -> {
        if (packet.getType() != IqPacket.TYPE.RESULT) {
            Element error = packet.findChild("error");
            String text = error != null ? error.findChildContent("text") : null;
            if (text != null) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": received iq error - " + text);
            }
        }
    };
    public DatabaseBackend databaseBackend;
    private final ReplacingSerialSingleThreadExecutor mContactMergerExecutor = new ReplacingSerialSingleThreadExecutor("ContactMerger");
    private long mLastActivity = 0;
    private final FileBackend fileBackend = new FileBackend(this);
    private MemorizingTrustManager mMemorizingTrustManager;
    private final NotificationService mNotificationService = new NotificationService(this);
    private final ChannelDiscoveryService mChannelDiscoveryService = new ChannelDiscoveryService(this);
    private final ShortcutService mShortcutService = new ShortcutService(this);
    private final AtomicBoolean mInitialAddressbookSyncCompleted = new AtomicBoolean(false);
    private final AtomicBoolean mForceForegroundService = new AtomicBoolean(false);
    private final AtomicBoolean mForceDuringOnCreate = new AtomicBoolean(false);
    private final AtomicReference<OngoingCall> ongoingCall = new AtomicReference<>();
    private final OnMessagePacketReceived mMessageParser = new MessageParser(this);
    private final OnPresencePacketReceived mPresenceParser = new PresenceParser(this);
    private final IqParser mIqParser = new IqParser(this);
    private final MessageGenerator mMessageGenerator = new MessageGenerator(this);
    public OnContactStatusChanged onContactStatusChanged = (contact, online) -> {
        Conversation conversation = find(getConversations(), contact);
        if (conversation != null) {
            if (online) {
                if (contact.getPresences().size() == 1) {
                    sendUnsentMessages(conversation);
                }
            }
        }
    };
    private final PresenceGenerator mPresenceGenerator = new PresenceGenerator(this);
    private List<Account> accounts;
    private final JingleConnectionManager mJingleConnectionManager = new JingleConnectionManager(this);
    private final HttpConnectionManager mHttpConnectionManager = new HttpConnectionManager(this);
    private final AvatarService mAvatarService = new AvatarService(this);
    private final MessageArchiveService mMessageArchiveService = new MessageArchiveService(this);
    private final PushManagementService mPushManagementService = new PushManagementService(this);
    private final QuickConversationsService mQuickConversationsService = new QuickConversationsService(this);
    private final ConversationsFileObserver fileObserver = new ConversationsFileObserver(
            Environment.getExternalStorageDirectory().getAbsolutePath()
    ) {
        @Override
        public void onEvent(final int event, final File file) {
            markFileDeleted(file);
        }
    };
    private final OnMessageAcknowledged mOnMessageAcknowledgedListener = new OnMessageAcknowledged() {

        @Override
        public boolean onMessageAcknowledged(final Account account, final Jid to, final String id) {
            if (id.startsWith(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX)) {
                final String sessionId = id.substring(JingleRtpConnection.JINGLE_MESSAGE_PROPOSE_ID_PREFIX.length());
                mJingleConnectionManager.updateProposedSessionDiscovered(
                        account,
                        to,
                        sessionId,
                        JingleConnectionManager.DeviceDiscoveryState.SEARCHING_ACKNOWLEDGED
                );
            }


            final Jid bare = to.asBareJid();

            for (final Conversation conversation : getConversations()) {
                if (conversation.getAccount() == account && conversation.getJid().asBareJid().equals(bare)) {
                    final Message message = conversation.findUnsentMessageWithUuid(id);
                    if (message != null) {
                        message.setStatus(Message.STATUS_SEND);
                        message.setErrorMessage(null);
                        databaseBackend.updateMessage(message, false);
                        return true;
                    }
                }
            }
            return false;
        }
    };
    private final AtomicBoolean isPhoneInCall = new AtomicBoolean(false);
    private final PhoneStateListener phoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(final int state, final String phoneNumber) {
            isPhoneInCall.set(state != TelephonyManager.CALL_STATE_IDLE);
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                mJingleConnectionManager.notifyPhoneCallStarted();
            }
        }
    };

    private boolean destroyed = false;

    private int unreadCount = -1;

    //Ui callback listeners
    private final Set<OnConversationUpdate> mOnConversationUpdates = Collections.newSetFromMap(new WeakHashMap<OnConversationUpdate, Boolean>());
    private final Set<OnShowErrorToast> mOnShowErrorToasts = Collections.newSetFromMap(new WeakHashMap<OnShowErrorToast, Boolean>());
    private final Set<OnAccountUpdate> mOnAccountUpdates = Collections.newSetFromMap(new WeakHashMap<OnAccountUpdate, Boolean>());
    private final Set<OnCaptchaRequested> mOnCaptchaRequested = Collections.newSetFromMap(new WeakHashMap<OnCaptchaRequested, Boolean>());
    private final Set<OnRosterUpdate> mOnRosterUpdates = Collections.newSetFromMap(new WeakHashMap<OnRosterUpdate, Boolean>());
    private final Set<OnUpdateBlocklist> mOnUpdateBlocklist = Collections.newSetFromMap(new WeakHashMap<OnUpdateBlocklist, Boolean>());
    private final Set<OnMucRosterUpdate> mOnMucRosterUpdate = Collections.newSetFromMap(new WeakHashMap<OnMucRosterUpdate, Boolean>());
    private final Set<OnKeyStatusUpdated> mOnKeyStatusUpdated = Collections.newSetFromMap(new WeakHashMap<OnKeyStatusUpdated, Boolean>());
    private final Set<OnJingleRtpConnectionUpdate> onJingleRtpConnectionUpdate = Collections.newSetFromMap(new WeakHashMap<OnJingleRtpConnectionUpdate, Boolean>());

    private final Object LISTENER_LOCK = new Object();


    public final Set<String> FILENAMES_TO_IGNORE_DELETION = new HashSet<>();


    private final OnBindListener mOnBindListener = new OnBindListener() {

        @Override
        public void onBind(final Account account) {
            synchronized (mInProgressAvatarFetches) {
                for (Iterator<String> iterator = mInProgressAvatarFetches.iterator(); iterator.hasNext(); ) {
                    final String KEY = iterator.next();
                    if (KEY.startsWith(account.getJid().asBareJid() + "_")) {
                        iterator.remove();
                    }
                }
            }
            boolean loggedInSuccessfully = account.setOption(Account.OPTION_LOGGED_IN_SUCCESSFULLY, true);
            boolean gainedFeature = account.setOption(Account.OPTION_HTTP_UPLOAD_AVAILABLE, account.getXmppConnection().getFeatures().httpUpload(0));
            if (loggedInSuccessfully || gainedFeature) {
                databaseBackend.updateAccount(account);
            }

            if (loggedInSuccessfully) {
                if (!TextUtils.isEmpty(account.getDisplayName())) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": display name wasn't empty on first log in. publishing");
                    publishDisplayName(account);
                }
            }

            account.getRoster().clearPresences();
            synchronized (account.inProgressConferenceJoins) {
                account.inProgressConferenceJoins.clear();
            }
            synchronized (account.inProgressConferencePings) {
                account.inProgressConferencePings.clear();
            }
            mJingleConnectionManager.notifyRebound(account);
            mQuickConversationsService.considerSyncBackground(false);
            fetchRosterFromServer(account);

            final XmppConnection connection = account.getXmppConnection();

            if (connection.getFeatures().bookmarks2()) {
                fetchBookmarks2(account);
            } else if (!account.getXmppConnection().getFeatures().bookmarksConversion()) {
                fetchBookmarks(account);
            }
            final boolean flexible = account.getXmppConnection().getFeatures().flexibleOfflineMessageRetrieval();
            final boolean catchup = getMessageArchiveService().inCatchup(account);
            if (flexible && catchup && account.getXmppConnection().isMamPreferenceAlways()) {
                sendIqPacket(account, mIqGenerator.purgeOfflineMessages(), (acc, packet) -> {
                    if (packet.getType() == IqPacket.TYPE.RESULT) {
                        Log.d(Config.LOGTAG, acc.getJid().asBareJid() + ": successfully purged offline messages");
                    }
                });
            }
            sendPresence(account);
            if (mPushManagementService.available(account)) {
                mPushManagementService.registerPushTokenOnServer(account);
            }
            connectMultiModeConversations(account);
            syncDirtyContacts(account);

        }
    };
    private final AtomicLong mLastExpiryRun = new AtomicLong(0);
    private SecureRandom mRandom;
    private final LruCache<Pair<String, String>, ServiceDiscoveryResult> discoCache = new LruCache<>(20);
    private final OnStatusChanged statusListener = new OnStatusChanged() {

        @Override
        public void onStatusChanged(final Account account) {
            XmppConnection connection = account.getXmppConnection();
            updateAccountUi();

            if (account.getStatus() == Account.State.ONLINE || account.getStatus().isError()) {
                mQuickConversationsService.signalAccountStateChange();
            }

            if (account.getStatus() == Account.State.ONLINE) {
                synchronized (mLowPingTimeoutMode) {
                    if (mLowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": leaving low ping timeout mode");
                    }
                }
                if (account.setShowErrorNotification(true)) {
                    databaseBackend.updateAccount(account);
                }
                mMessageArchiveService.executePendingQueries(account);
                if (connection != null && connection.getFeatures().csi()) {
                    if (checkListeners()) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + " sending csi//inactive");
                        connection.sendInactive();
                    } else {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + " sending csi//active");
                        connection.sendActive();
                    }
                }
                List<Conversation> conversations = getConversations();
                for (Conversation conversation : conversations) {
                    final boolean inProgressJoin;
                    synchronized (account.inProgressConferenceJoins) {
                        inProgressJoin = account.inProgressConferenceJoins.contains(conversation);
                    }
                    final boolean pendingJoin;
                    synchronized (account.pendingConferenceJoins) {
                        pendingJoin = account.pendingConferenceJoins.contains(conversation);
                    }
                    if (conversation.getAccount() == account
                            && !pendingJoin
                            && !inProgressJoin) {
                        sendUnsentMessages(conversation);
                    }
                }
                final List<Conversation> pendingLeaves;
                synchronized (account.pendingConferenceLeaves) {
                    pendingLeaves = new ArrayList<>(account.pendingConferenceLeaves);
                    account.pendingConferenceLeaves.clear();

                }
                for (Conversation conversation : pendingLeaves) {
                    leaveMuc(conversation);
                }
                final List<Conversation> pendingJoins;
                synchronized (account.pendingConferenceJoins) {
                    pendingJoins = new ArrayList<>(account.pendingConferenceJoins);
                    account.pendingConferenceJoins.clear();
                }
                for (Conversation conversation : pendingJoins) {
                    joinMuc(conversation);
                }
                scheduleWakeUpCall(Config.PING_MAX_INTERVAL, account.getUuid().hashCode());
            } else if (account.getStatus() == Account.State.OFFLINE || account.getStatus() == Account.State.DISABLED) {
                resetSendingToWaiting(account);
                if (account.isEnabled() && isInLowPingTimeoutMode(account)) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": went into offline state during low ping mode. reconnecting now");
                    reconnectAccount(account, true, false);
                } else {
                    int timeToReconnect = mRandom.nextInt(10) + 2;
                    scheduleWakeUpCall(timeToReconnect, account.getUuid().hashCode());
                }
            } else if (account.getStatus() == Account.State.REGISTRATION_SUCCESSFUL) {
                databaseBackend.updateAccount(account);
                reconnectAccount(account, true, false);
            } else if (account.getStatus() != Account.State.CONNECTING && account.getStatus() != Account.State.NO_INTERNET) {
                resetSendingToWaiting(account);
                if (connection != null && account.getStatus().isAttemptReconnect()) {
                    final int next = connection.getTimeToNextAttempt();
                    final boolean lowPingTimeoutMode = isInLowPingTimeoutMode(account);
                    if (next <= 0) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": error connecting account. reconnecting now. lowPingTimeout=" + lowPingTimeoutMode);
                        reconnectAccount(account, true, false);
                    } else {
                        final int attempt = connection.getAttempt() + 1;
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": error connecting account. try again in " + next + "s for the " + attempt + " time. lowPingTimeout=" + lowPingTimeoutMode);
                        scheduleWakeUpCall(next, account.getUuid().hashCode());
                    }
                }
            }
            getNotificationService().updateErrorNotification();
        }
    };
    private OpenPgpServiceConnection pgpServiceConnection;
    private PgpEngine mPgpEngine = null;
    private WakeLock wakeLock;
    private LruCache<String, Bitmap> mBitmapCache;
    private final BroadcastReceiver mInternalEventReceiver = new InternalEventReceiver();
    private final BroadcastReceiver mInternalScreenEventReceiver = new InternalEventReceiver();

    private static String generateFetchKey(Account account, final Avatar avatar) {
        return account.getJid().asBareJid() + "_" + avatar.owner + "_" + avatar.sha1sum;
    }

    private boolean isInLowPingTimeoutMode(Account account) {
        synchronized (mLowPingTimeoutMode) {
            return mLowPingTimeoutMode.contains(account.getJid().asBareJid());
        }
    }

    public void startForcingForegroundNotification() {
        mForceForegroundService.set(true);
        toggleForegroundService();
    }

    public void stopForcingForegroundNotification() {
        mForceForegroundService.set(false);
        toggleForegroundService();
    }

    public boolean areMessagesInitialized() {
        return this.restoredFromDatabaseLatch.getCount() == 0;
    }

    public PgpEngine getPgpEngine() {
        if (!Config.supportOpenPgp()) {
            return null;
        } else if (pgpServiceConnection != null && pgpServiceConnection.isBound()) {
            if (this.mPgpEngine == null) {
                this.mPgpEngine = new PgpEngine(new OpenPgpApi(
                        getApplicationContext(),
                        pgpServiceConnection.getService()), this);
            }
            return mPgpEngine;
        } else {
            return null;
        }

    }

    public OpenPgpApi getOpenPgpApi() {
        if (!Config.supportOpenPgp()) {
            return null;
        } else if (pgpServiceConnection != null && pgpServiceConnection.isBound()) {
            return new OpenPgpApi(this, pgpServiceConnection.getService());
        } else {
            return null;
        }
    }

    public FileBackend getFileBackend() {
        return this.fileBackend;
    }

    public AvatarService getAvatarService() {
        return this.mAvatarService;
    }

    public void attachLocationToConversation(final Conversation conversation, final Uri uri, final UiCallback<Message> callback) {
        int encryption = conversation.getNextEncryption();
        if (encryption == Message.ENCRYPTION_PGP) {
            encryption = Message.ENCRYPTION_DECRYPTED;
        }
        Message message = new Message(conversation, uri.toString(), encryption);
        Message.configurePrivateMessage(message);
        if (encryption == Message.ENCRYPTION_DECRYPTED) {
            getPgpEngine().encrypt(message, callback);
        } else {
            sendMessage(message);
            callback.success(message);
        }
    }

    public void attachFileToConversation(final Conversation conversation, final Uri uri, final String type, final UiCallback<Message> callback) {
        final Message message;
        if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
            message = new Message(conversation, "", Message.ENCRYPTION_DECRYPTED);
        } else {
            message = new Message(conversation, "", conversation.getNextEncryption());
        }
        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart());
            message.setType(Message.TYPE_FILE);
        }
        Log.d(Config.LOGTAG, "attachFile: type=" + message.getType());
        Log.d(Config.LOGTAG, "counterpart=" + message.getCounterpart());
        final AttachFileToConversationRunnable runnable = new AttachFileToConversationRunnable(this, uri, type, message, callback);
        if (runnable.isVideoMessage()) {
            VIDEO_COMPRESSION_EXECUTOR.execute(runnable);
        } else {
            FILE_ATTACHMENT_EXECUTOR.execute(runnable);
        }
    }

    public void attachImageToConversation(final Conversation conversation, final Uri uri,  final String type, final UiCallback<Message> callback) {
        final String mimeType = MimeUtils.guessMimeTypeFromUriAndMime(this, uri, type);
        final String compressPictures = getCompressPicturesPreference();

        if ("never".equals(compressPictures)
                || ("auto".equals(compressPictures) && getFileBackend().useImageAsIs(uri))
                || (mimeType != null && mimeType.endsWith("/gif"))
                || getFileBackend().unusualBounds(uri)) {
            Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": not compressing picture. sending as file");
            attachFileToConversation(conversation, uri, mimeType, callback);
            return;
        }
        final Message message;
        if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
            message = new Message(conversation, "", Message.ENCRYPTION_DECRYPTED);
        } else {
            message = new Message(conversation, "", conversation.getNextEncryption());
        }
        if (!Message.configurePrivateFileMessage(message)) {
            message.setCounterpart(conversation.getNextCounterpart());
            message.setType(Message.TYPE_IMAGE);
        }
        Log.d(Config.LOGTAG, "attachImage: type=" + message.getType());
        FILE_ATTACHMENT_EXECUTOR.execute(() -> {
            try {
                getFileBackend().copyImageToPrivateStorage(message, uri);
            } catch (FileBackend.ImageCompressionException e) {
                Log.d(Config.LOGTAG, "unable to compress image. fall back to file transfer", e);
                attachFileToConversation(conversation, uri, mimeType, callback);
                return;
            } catch (final FileBackend.FileCopyException e) {
                callback.error(e.getResId(), message);
                return;
            }
            if (conversation.getNextEncryption() == Message.ENCRYPTION_PGP) {
                final PgpEngine pgpEngine = getPgpEngine();
                if (pgpEngine != null) {
                    pgpEngine.encrypt(message, callback);
                } else if (callback != null) {
                    callback.error(R.string.unable_to_connect_to_keychain, null);
                }
            } else {
                sendMessage(message);
                callback.success(message);
            }
        });
    }

    public Conversation find(Bookmark bookmark) {
        return find(bookmark.getAccount(), bookmark.getJid());
    }

    public Conversation find(final Account account, final Jid jid) {
        return find(getConversations(), account, jid);
    }

    public boolean isMuc(final Account account, final Jid jid) {
        final Conversation c = find(account, jid);
        return c != null && c.getMode() == Conversational.MODE_MULTI;
    }

    public void search(final List<String> term, final String uuid, final OnSearchResultsAvailable onSearchResultsAvailable) {
        MessageSearchTask.search(this, term, uuid, onSearchResultsAvailable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        final boolean needsForegroundService = intent != null && intent.getBooleanExtra(EventReceiver.EXTRA_NEEDS_FOREGROUND_SERVICE, false);
        if (needsForegroundService) {
            Log.d(Config.LOGTAG, "toggle forced foreground service after receiving event (action=" + action + ")");
            toggleForegroundService(true);
        }
        String pushedAccountHash = null;
        boolean interactive = false;
        if (action != null) {
            final String uuid = intent.getStringExtra("uuid");
            switch (action) {
                case QuickConversationsService.SMS_RETRIEVED_ACTION:
                    mQuickConversationsService.handleSmsReceived(intent);
                    break;
                case ConnectivityManager.CONNECTIVITY_ACTION:
                    if (hasInternetConnection()) {
                        if (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0) {
                            schedulePostConnectivityChange();
                        }
                        if (Config.RESET_ATTEMPT_COUNT_ON_NETWORK_CHANGE) {
                            resetAllAttemptCounts(true, false);
                        }
                        Resolver.clearCache();
                    }
                    break;
                case Intent.ACTION_SHUTDOWN:
                    logoutAndSave(true);
                    return START_NOT_STICKY;
                case ACTION_CLEAR_NOTIFICATION:
                    mNotificationExecutor.execute(() -> {
                        try {
                            final Conversation c = findConversationByUuid(uuid);
                            if (c != null) {
                                mNotificationService.clear(c);
                            } else {
                                mNotificationService.clear();
                            }
                            restoredFromDatabaseLatch.await();

                        } catch (InterruptedException e) {
                            Log.d(Config.LOGTAG, "unable to process clear notification");
                        }
                    });
                    break;
                case ACTION_DISMISS_CALL: {
                    final String sessionId = intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID);
                    Log.d(Config.LOGTAG, "received intent to dismiss call with session id " + sessionId);
                    mJingleConnectionManager.rejectRtpSession(sessionId);
                    break;
                }
                case TorServiceUtils.ACTION_STATUS:
                    final String status = intent.getStringExtra(TorServiceUtils.EXTRA_STATUS);
                    //TODO port and host are in 'extras' - but this may not be a reliable source?
                    if ("ON".equals(status)) {
                        handleOrbotStartedEvent();
                        return START_STICKY;
                    }
                    break;
                case ACTION_END_CALL: {
                    final String sessionId = intent.getStringExtra(RtpSessionActivity.EXTRA_SESSION_ID);
                    Log.d(Config.LOGTAG, "received intent to end call with session id " + sessionId);
                    mJingleConnectionManager.endRtpSession(sessionId);
                }
                break;
                case ACTION_PROVISION_ACCOUNT: {
                    final String address = intent.getStringExtra("address");
                    final String password = intent.getStringExtra("password");
                    if (QuickConversationsService.isQuicksy() || Strings.isNullOrEmpty(address) || Strings.isNullOrEmpty(password)) {
                        break;
                    }
                    provisionAccount(address, password);
                    break;
                }
                case ACTION_DISMISS_ERROR_NOTIFICATIONS:
                    dismissErrorNotifications();
                    break;
                case ACTION_TRY_AGAIN:
                    resetAllAttemptCounts(false, true);
                    interactive = true;
                    break;
                case ACTION_REPLY_TO_CONVERSATION:
                    Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
                    if (remoteInput == null) {
                        break;
                    }
                    final CharSequence body = remoteInput.getCharSequence("text_reply");
                    final boolean dismissNotification = intent.getBooleanExtra("dismiss_notification", false);
                    final String lastMessageUuid = intent.getStringExtra("last_message_uuid");
                    if (body == null || body.length() <= 0) {
                        break;
                    }
                    mNotificationExecutor.execute(() -> {
                        try {
                            restoredFromDatabaseLatch.await();
                            final Conversation c = findConversationByUuid(uuid);
                            if (c != null) {
                                directReply(c, body.toString(), lastMessageUuid, dismissNotification);
                            }
                        } catch (InterruptedException e) {
                            Log.d(Config.LOGTAG, "unable to process direct reply");
                        }
                    });
                    break;
                case ACTION_MARK_AS_READ:
                    mNotificationExecutor.execute(() -> {
                        final Conversation c = findConversationByUuid(uuid);
                        if (c == null) {
                            Log.d(Config.LOGTAG, "received mark read intent for unknown conversation (" + uuid + ")");
                            return;
                        }
                        try {
                            restoredFromDatabaseLatch.await();
                            sendReadMarker(c, null);
                        } catch (InterruptedException e) {
                            Log.d(Config.LOGTAG, "unable to process notification read marker for conversation " + c.getName());
                        }

                    });
                    break;
                case ACTION_SNOOZE:
                    mNotificationExecutor.execute(() -> {
                        final Conversation c = findConversationByUuid(uuid);
                        if (c == null) {
                            Log.d(Config.LOGTAG, "received snooze intent for unknown conversation (" + uuid + ")");
                            return;
                        }
                        c.setMutedTill(System.currentTimeMillis() + 30 * 60 * 1000);
                        mNotificationService.clear(c);
                        updateConversation(c);
                    });
                case AudioManager.RINGER_MODE_CHANGED_ACTION:
                case NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED:
                    if (dndOnSilentMode()) {
                        refreshAllPresences();
                    }
                    break;
                case Intent.ACTION_SCREEN_ON:
                    deactivateGracePeriod();
                case Intent.ACTION_USER_PRESENT:
                case Intent.ACTION_SCREEN_OFF:
                    if (awayWhenScreenLocked()) {
                        refreshAllPresences();
                    }
                    break;
                case ACTION_FCM_TOKEN_REFRESH:
                    refreshAllFcmTokens();
                    break;
                case ACTION_IDLE_PING:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        scheduleNextIdlePing();
                    }
                    break;
                case ACTION_FCM_MESSAGE_RECEIVED:
                    pushedAccountHash = intent.getStringExtra("account");
                    Log.d(Config.LOGTAG, "push message arrived in service. account=" + pushedAccountHash);
                    break;
                case Intent.ACTION_SEND:
                    Uri uri = intent.getData();
                    if (uri != null) {
                        Log.d(Config.LOGTAG, "received uri permission for " + uri.toString());
                    }
                    return START_STICKY;
            }
        }
        synchronized (this) {
            WakeLockHelper.acquire(wakeLock);
            boolean pingNow = ConnectivityManager.CONNECTIVITY_ACTION.equals(action) || (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL > 0 && ACTION_POST_CONNECTIVITY_CHANGE.equals(action));
            final HashSet<Account> pingCandidates = new HashSet<>();
            final String androidId = PhoneHelper.getAndroidId(this);
            for (Account account : accounts) {
                final boolean pushWasMeantForThisAccount = CryptoHelper.getAccountFingerprint(account, androidId).equals(pushedAccountHash);
                pingNow |= processAccountState(account,
                        interactive,
                        "ui".equals(action),
                        pushWasMeantForThisAccount,
                        pingCandidates);
            }
            if (pingNow) {
                for (Account account : pingCandidates) {
                    final boolean lowTimeout = isInLowPingTimeoutMode(account);
                    account.getXmppConnection().sendPing();
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + " send ping (action=" + action + ",lowTimeout=" + lowTimeout + ")");
                    scheduleWakeUpCall(lowTimeout ? Config.LOW_PING_TIMEOUT : Config.PING_TIMEOUT, account.getUuid().hashCode());
                }
            }
            WakeLockHelper.release(wakeLock);
        }
        if (SystemClock.elapsedRealtime() - mLastExpiryRun.get() >= Config.EXPIRY_INTERVAL) {
            expireOldMessages();
        }
        return START_STICKY;
    }

    private void handleOrbotStartedEvent() {
        for (final Account account : accounts) {
            if (account.getStatus() == Account.State.TOR_NOT_AVAILABLE) {
                reconnectAccount(account, true, false);
            }
        }
    }

    private boolean processAccountState(Account account, boolean interactive, boolean isUiAction, boolean isAccountPushed, HashSet<Account> pingCandidates) {
        boolean pingNow = false;
        if (account.getStatus().isAttemptReconnect()) {
            if (!hasInternetConnection()) {
                account.setStatus(Account.State.NO_INTERNET);
                if (statusListener != null) {
                    statusListener.onStatusChanged(account);
                }
            } else {
                if (account.getStatus() == Account.State.NO_INTERNET) {
                    account.setStatus(Account.State.OFFLINE);
                    if (statusListener != null) {
                        statusListener.onStatusChanged(account);
                    }
                }
                if (account.getStatus() == Account.State.ONLINE) {
                    synchronized (mLowPingTimeoutMode) {
                        long lastReceived = account.getXmppConnection().getLastPacketReceived();
                        long lastSent = account.getXmppConnection().getLastPingSent();
                        long pingInterval = isUiAction ? Config.PING_MIN_INTERVAL * 1000 : Config.PING_MAX_INTERVAL * 1000;
                        long msToNextPing = (Math.max(lastReceived, lastSent) + pingInterval) - SystemClock.elapsedRealtime();
                        int pingTimeout = mLowPingTimeoutMode.contains(account.getJid().asBareJid()) ? Config.LOW_PING_TIMEOUT * 1000 : Config.PING_TIMEOUT * 1000;
                        long pingTimeoutIn = (lastSent + pingTimeout) - SystemClock.elapsedRealtime();
                        if (lastSent > lastReceived) {
                            if (pingTimeoutIn < 0) {
                                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ping timeout");
                                this.reconnectAccount(account, true, interactive);
                            } else {
                                int secs = (int) (pingTimeoutIn / 1000);
                                this.scheduleWakeUpCall(secs, account.getUuid().hashCode());
                            }
                        } else {
                            pingCandidates.add(account);
                            if (isAccountPushed) {
                                pingNow = true;
                                if (mLowPingTimeoutMode.add(account.getJid().asBareJid())) {
                                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": entering low ping timeout mode");
                                }
                            } else if (msToNextPing <= 0) {
                                pingNow = true;
                            } else {
                                this.scheduleWakeUpCall((int) (msToNextPing / 1000), account.getUuid().hashCode());
                                if (mLowPingTimeoutMode.remove(account.getJid().asBareJid())) {
                                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": leaving low ping timeout mode");
                                }
                            }
                        }
                    }
                } else if (account.getStatus() == Account.State.OFFLINE) {
                    reconnectAccount(account, true, interactive);
                } else if (account.getStatus() == Account.State.CONNECTING) {
                    long secondsSinceLastConnect = (SystemClock.elapsedRealtime() - account.getXmppConnection().getLastConnect()) / 1000;
                    long secondsSinceLastDisco = (SystemClock.elapsedRealtime() - account.getXmppConnection().getLastDiscoStarted()) / 1000;
                    long discoTimeout = Config.CONNECT_DISCO_TIMEOUT - secondsSinceLastDisco;
                    long timeout = Config.CONNECT_TIMEOUT - secondsSinceLastConnect;
                    if (timeout < 0) {
                        Log.d(Config.LOGTAG, account.getJid() + ": time out during connect reconnecting (secondsSinceLast=" + secondsSinceLastConnect + ")");
                        account.getXmppConnection().resetAttemptCount(false);
                        reconnectAccount(account, true, interactive);
                    } else if (discoTimeout < 0) {
                        account.getXmppConnection().sendDiscoTimeout();
                        scheduleWakeUpCall((int) Math.min(timeout, discoTimeout), account.getUuid().hashCode());
                    } else {
                        scheduleWakeUpCall((int) Math.min(timeout, discoTimeout), account.getUuid().hashCode());
                    }
                } else {
                    if (account.getXmppConnection().getTimeToNextAttempt() <= 0) {
                        reconnectAccount(account, true, interactive);
                    }
                }
            }
        }
        return pingNow;
    }

    public void reinitializeMuclumbusService() {
        mChannelDiscoveryService.initializeMuclumbusService();
    }

    public void discoverChannels(String query, ChannelDiscoveryService.Method method, ChannelDiscoveryService.OnChannelSearchResultsFound onChannelSearchResultsFound) {
        mChannelDiscoveryService.discover(Strings.nullToEmpty(query).trim(), method, onChannelSearchResultsFound);
    }

    public boolean isDataSaverDisabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            return !connectivityManager.isActiveNetworkMetered()
                    || connectivityManager.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
        } else {
            return true;
        }
    }

    private void directReply(final Conversation conversation, final String body, final String lastMessageUuid, final boolean dismissAfterReply) {
        final Message inReplyTo = lastMessageUuid == null ? null : conversation.findMessageWithUuid(lastMessageUuid);
        final Message message = new Message(conversation, body, conversation.getNextEncryption());
        if (inReplyTo != null && inReplyTo.isPrivateMessage()) {
            Message.configurePrivateMessage(message, inReplyTo.getCounterpart());
        }
        message.markUnread();
        if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            getPgpEngine().encrypt(message, new UiCallback<Message>() {
                @Override
                public void success(Message message) {
                    if (dismissAfterReply) {
                        markRead((Conversation) message.getConversation(), true);
                    } else {
                        mNotificationService.pushFromDirectReply(message);
                    }
                }

                @Override
                public void error(int errorCode, Message object) {

                }

                @Override
                public void userInputRequired(PendingIntent pi, Message object) {

                }
            });
        } else {
            sendMessage(message);
            if (dismissAfterReply) {
                markRead(conversation, true);
            } else {
                mNotificationService.pushFromDirectReply(message);
            }
        }
    }

    private boolean dndOnSilentMode() {
        return getBooleanPreference(SettingsActivity.DND_ON_SILENT_MODE, R.bool.dnd_on_silent_mode);
    }

    private boolean manuallyChangePresence() {
        return getBooleanPreference(SettingsActivity.MANUALLY_CHANGE_PRESENCE, R.bool.manually_change_presence);
    }

    private boolean treatVibrateAsSilent() {
        return getBooleanPreference(SettingsActivity.TREAT_VIBRATE_AS_SILENT, R.bool.treat_vibrate_as_silent);
    }

    private boolean awayWhenScreenLocked() {
        return getBooleanPreference(SettingsActivity.AWAY_WHEN_SCREEN_IS_OFF, R.bool.away_when_screen_off);
    }

    private String getCompressPicturesPreference() {
        return getPreferences().getString("picture_compression", getResources().getString(R.string.picture_compression));
    }

    private Presence.Status getTargetPresence() {
        if (dndOnSilentMode() && isPhoneSilenced()) {
            return Presence.Status.DND;
        } else if (awayWhenScreenLocked() && isScreenLocked()) {
            return Presence.Status.AWAY;
        } else {
            return Presence.Status.ONLINE;
        }
    }

    public boolean isScreenLocked() {
        final KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final boolean locked = keyguardManager != null && keyguardManager.isKeyguardLocked();
        final boolean interactive = powerManager != null && powerManager.isInteractive();
        return locked || !interactive;
    }

    private boolean isPhoneSilenced() {
        final boolean notificationDnd;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final NotificationManager notificationManager = getSystemService(NotificationManager.class);
            final int filter = notificationManager == null ? NotificationManager.INTERRUPTION_FILTER_UNKNOWN : notificationManager.getCurrentInterruptionFilter();
            notificationDnd = filter >= NotificationManager.INTERRUPTION_FILTER_PRIORITY;
        } else {
            notificationDnd = false;
        }
        final AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        final int ringerMode = audioManager == null ? AudioManager.RINGER_MODE_NORMAL : audioManager.getRingerMode();
        try {
            if (treatVibrateAsSilent()) {
                return notificationDnd || ringerMode != AudioManager.RINGER_MODE_NORMAL;
            } else {
                return notificationDnd || ringerMode == AudioManager.RINGER_MODE_SILENT;
            }
        } catch (Throwable throwable) {
            Log.d(Config.LOGTAG, "platform bug in isPhoneSilenced (" + throwable.getMessage() + ")");
            return notificationDnd;
        }
    }

    private void resetAllAttemptCounts(boolean reallyAll, boolean retryImmediately) {
        Log.d(Config.LOGTAG, "resetting all attempt counts");
        for (Account account : accounts) {
            if (account.hasErrorStatus() || reallyAll) {
                final XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    connection.resetAttemptCount(retryImmediately);
                }
            }
            if (account.setShowErrorNotification(true)) {
                mDatabaseWriterExecutor.execute(() -> databaseBackend.updateAccount(account));
            }
        }
        mNotificationService.updateErrorNotification();
    }

    private void dismissErrorNotifications() {
        for (final Account account : this.accounts) {
            if (account.hasErrorStatus()) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": dismissing error notification");
                if (account.setShowErrorNotification(false)) {
                    mDatabaseWriterExecutor.execute(() -> databaseBackend.updateAccount(account));
                }
            }
        }
    }

    private void expireOldMessages() {
        expireOldMessages(false);
    }

    public void expireOldMessages(final boolean resetHasMessagesLeftOnServer) {
        mLastExpiryRun.set(SystemClock.elapsedRealtime());
        mDatabaseWriterExecutor.execute(() -> {
            long timestamp = getAutomaticMessageDeletionDate();
            if (timestamp > 0) {
                databaseBackend.expireOldMessages(timestamp);
                synchronized (XmppConnectionService.this.conversations) {
                    for (Conversation conversation : XmppConnectionService.this.conversations) {
                        conversation.expireOldMessages(timestamp);
                        if (resetHasMessagesLeftOnServer) {
                            conversation.messagesLoaded.set(true);
                            conversation.setHasMessagesLeftOnServer(true);
                        }
                    }
                }
                updateConversationUi();
            }
        });
    }

    public boolean hasInternetConnection() {
        final ConnectivityManager cm = ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (cm == null) {
            return true; //if internet connection can not be checked it is probably best to just try
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                final Network activeNetwork = cm.getActiveNetwork();
                final NetworkCapabilities capabilities = activeNetwork == null ? null : cm.getNetworkCapabilities(activeNetwork);
                return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                final NetworkInfo networkInfo = cm.getActiveNetworkInfo();
                return networkInfo != null && (networkInfo.isConnected() || networkInfo.getType() == ConnectivityManager.TYPE_ETHERNET);
            }
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to check for internet connection", e);
            return true; //if internet connection can not be checked it is probably best to just try
        }
    }

    @SuppressLint("TrulyRandom")
    @Override
    public void onCreate() {
        if (Compatibility.runsTwentySix()) {
            mNotificationService.initializeChannels();
        }
        mChannelDiscoveryService.initializeMuclumbusService();
        mForceDuringOnCreate.set(Compatibility.runsAndTargetsTwentySix(this));
        toggleForegroundService();
        this.destroyed = false;
        OmemoSetting.load(this);
        ExceptionHelper.init(getApplicationContext());
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable throwable) {
            Log.e(Config.LOGTAG, "unable to initialize security provider", throwable);
        }
        Resolver.init(this);
        this.mRandom = new SecureRandom();
        updateMemorizingTrustmanager();
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;
        this.mBitmapCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(final String key, final Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        if (mLastActivity == 0) {
            mLastActivity = getPreferences().getLong(SETTING_LAST_ACTIVITY_TS, System.currentTimeMillis());
        }

        Log.d(Config.LOGTAG, "initializing database...");
        this.databaseBackend = DatabaseBackend.getInstance(getApplicationContext());
        Log.d(Config.LOGTAG, "restoring accounts...");
        this.accounts = databaseBackend.getAccounts();
        final SharedPreferences.Editor editor = getPreferences().edit();
        if (this.accounts.size() == 0 && Arrays.asList("Sony", "Sony Ericsson").contains(Build.MANUFACTURER)) {
            editor.putBoolean(SettingsActivity.KEEP_FOREGROUND_SERVICE, true);
            Log.d(Config.LOGTAG, Build.MANUFACTURER + " is on blacklist. enabling foreground service");
        }
        final boolean hasEnabledAccounts = hasEnabledAccounts();
        editor.putBoolean(EventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts).apply();
        editor.apply();
        toggleSetProfilePictureActivity(hasEnabledAccounts);

        restoreFromDatabase();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            startContactObserver();
        }
        FILE_OBSERVER_EXECUTOR.execute(fileBackend::deleteHistoricAvatarPath);
        if (Compatibility.hasStoragePermission(this)) {
            Log.d(Config.LOGTAG, "starting file observer");
            FILE_OBSERVER_EXECUTOR.execute(this.fileObserver::startWatching);
            FILE_OBSERVER_EXECUTOR.execute(this::checkForDeletedFiles);
        }
        if (Config.supportOpenPgp()) {
            this.pgpServiceConnection = new OpenPgpServiceConnection(this, "org.sufficientlysecure.keychain", new OpenPgpServiceConnection.OnBound() {
                @Override
                public void onBound(IOpenPgpService2 service) {
                    for (Account account : accounts) {
                        final PgpDecryptionService pgp = account.getPgpDecryptionService();
                        if (pgp != null) {
                            pgp.continueDecryption(true);
                        }
                    }
                }

                @Override
                public void onError(Exception e) {
                }
            });
            this.pgpServiceConnection.bindToService();
        }

        final PowerManager pm = ContextCompat.getSystemService(this, PowerManager.class);
        this.wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Conversations:Service");

        toggleForegroundService();
        updateUnreadCountBadge();
        toggleScreenEventReceiver();
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TorServiceUtils.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scheduleNextIdlePing();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            }
            intentFilter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        }
        registerReceiver(this.mInternalEventReceiver, intentFilter);
        mForceDuringOnCreate.set(false);
        toggleForegroundService();
        setupPhoneStateListener();
    }


    private void setupPhoneStateListener() {
        final TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        }
    }

    public boolean isPhoneInCall() {
        return isPhoneInCall.get();
    }

    private void checkForDeletedFiles() {
        if (destroyed) {
            Log.d(Config.LOGTAG, "Do not check for deleted files because service has been destroyed");
            return;
        }
        final long start = SystemClock.elapsedRealtime();
        final List<DatabaseBackend.FilePathInfo> relativeFilePaths = databaseBackend.getFilePathInfo();
        final List<DatabaseBackend.FilePathInfo> changed = new ArrayList<>();
        for (final DatabaseBackend.FilePathInfo filePath : relativeFilePaths) {
            if (destroyed) {
                Log.d(Config.LOGTAG, "Stop checking for deleted files because service has been destroyed");
                return;
            }
            final File file = fileBackend.getFileForPath(filePath.path);
            if (filePath.setDeleted(!file.exists())) {
                changed.add(filePath);
            }
        }
        final long duration = SystemClock.elapsedRealtime() - start;
        Log.d(Config.LOGTAG, "found " + changed.size() + " changed files on start up. total=" + relativeFilePaths.size() + ". (" + duration + "ms)");
        if (changed.size() > 0) {
            databaseBackend.markFilesAsChanged(changed);
            markChangedFiles(changed);
        }
    }

    public void startContactObserver() {
        getContentResolver().registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                if (restoredFromDatabaseLatch.getCount() == 0) {
                    loadPhoneContacts();
                }
            }
        });
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.d(Config.LOGTAG, "clear cache due to low memory");
            getBitmapCache().evictAll();
        }
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(this.mInternalEventReceiver);
            unregisterReceiver(this.mInternalScreenEventReceiver);
        } catch (final IllegalArgumentException e) {
            //ignored
        }
        destroyed = false;
        fileObserver.stopWatching();
        super.onDestroy();
    }

    public void restartFileObserver() {
        Log.d(Config.LOGTAG, "restarting file observer");
        FILE_OBSERVER_EXECUTOR.execute(this.fileObserver::restartWatching);
        FILE_OBSERVER_EXECUTOR.execute(this::checkForDeletedFiles);
    }

    public void toggleScreenEventReceiver() {
        if (awayWhenScreenLocked() && !manuallyChangePresence()) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(this.mInternalScreenEventReceiver, filter);
        } else {
            try {
                unregisterReceiver(this.mInternalScreenEventReceiver);
            } catch (IllegalArgumentException e) {
                //ignored
            }
        }
    }

    public void toggleForegroundService() {
        toggleForegroundService(false);
    }

    public void setOngoingCall(AbstractJingleConnection.Id id, Set<Media> media) {
        ongoingCall.set(new OngoingCall(id, media));
        toggleForegroundService(false);
    }

    public void removeOngoingCall() {
        ongoingCall.set(null);
        toggleForegroundService(false);
    }

    private void toggleForegroundService(boolean force) {
        final boolean status;
        final OngoingCall ongoing = ongoingCall.get();
        if (force || mForceDuringOnCreate.get() || mForceForegroundService.get() || ongoing != null || (Compatibility.keepForegroundService(this) && hasEnabledAccounts())) {
            final Notification notification;
            final int id;
            if (ongoing != null) {
                notification = this.mNotificationService.getOngoingCallNotification(ongoing.id, ongoing.media);
                id = NotificationService.ONGOING_CALL_NOTIFICATION_ID;
                startForeground(id, notification);
                mNotificationService.cancel(NotificationService.FOREGROUND_NOTIFICATION_ID);
            } else {
                notification = this.mNotificationService.createForegroundNotification();
                id = NotificationService.FOREGROUND_NOTIFICATION_ID;
                startForeground(id, notification);
            }

            if (!mForceForegroundService.get()) {
                mNotificationService.notify(id, notification);
            }
            status = true;
        } else {
            stopForeground(true);
            status = false;
        }
        if (!mForceForegroundService.get()) {
            mNotificationService.cancel(NotificationService.FOREGROUND_NOTIFICATION_ID);
        }
        if (ongoing == null) {
            mNotificationService.cancel(NotificationService.ONGOING_CALL_NOTIFICATION_ID);
        }
        Log.d(Config.LOGTAG, "ForegroundService: " + (status ? "on" : "off"));
    }

    public boolean foregroundNotificationNeedsUpdatingWhenErrorStateChanges() {
        return !mForceForegroundService.get() && ongoingCall.get() == null && Compatibility.keepForegroundService(this) && hasEnabledAccounts();
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if ((Compatibility.keepForegroundService(this) && hasEnabledAccounts()) || mForceForegroundService.get() || ongoingCall.get() != null) {
            Log.d(Config.LOGTAG, "ignoring onTaskRemoved because foreground service is activated");
        } else {
            this.logoutAndSave(false);
        }
    }

    private void logoutAndSave(boolean stop) {
        int activeAccounts = 0;
        for (final Account account : accounts) {
            if (account.getStatus() != Account.State.DISABLED) {
                databaseBackend.writeRoster(account.getRoster());
                activeAccounts++;
            }
            if (account.getXmppConnection() != null) {
                new Thread(() -> disconnect(account, false)).start();
            }
        }
        if (stop || activeAccounts == 0) {
            Log.d(Config.LOGTAG, "good bye");
            stopSelf();
        }
    }

    private void schedulePostConnectivityChange() {
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final long triggerAtMillis = SystemClock.elapsedRealtime() + (Config.POST_CONNECTIVITY_CHANGE_PING_INTERVAL * 1000);
        final Intent intent = new Intent(this, EventReceiver.class);
        intent.setAction(ACTION_POST_CONNECTIVITY_CHANGE);
        try {
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 1, intent, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent);
            }
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for post connectivity change", e);
        }
    }

    public void scheduleWakeUpCall(int seconds, int requestCode) {
        final long timeToWake = SystemClock.elapsedRealtime() + (seconds < 0 ? 1 : seconds + 1) * 1000;
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final Intent intent = new Intent(this, EventReceiver.class);
        intent.setAction("ping");
        try {
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, requestCode, intent, 0);
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent);
        } catch (RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to schedule alarm for ping", e);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void scheduleNextIdlePing() {
        final long timeToWake = SystemClock.elapsedRealtime() + (Config.IDLE_PING_INTERVAL * 1000);
        final AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        final Intent intent = new Intent(this, EventReceiver.class);
        intent.setAction(ACTION_IDLE_PING);
        try {
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, timeToWake, pendingIntent);
        } catch (RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to schedule alarm for idle ping", e);
        }
    }

    public XmppConnection createConnection(final Account account) {
        final XmppConnection connection = new XmppConnection(account, this);
        connection.setOnMessagePacketReceivedListener(this.mMessageParser);
        connection.setOnStatusChangedListener(this.statusListener);
        connection.setOnPresencePacketReceivedListener(this.mPresenceParser);
        connection.setOnUnregisteredIqPacketReceivedListener(this.mIqParser);
        connection.setOnJinglePacketReceivedListener(((a, jp) -> mJingleConnectionManager.deliverPacket(a, jp)));
        connection.setOnBindListener(this.mOnBindListener);
        connection.setOnMessageAcknowledgeListener(this.mOnMessageAcknowledgedListener);
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.mMessageArchiveService);
        connection.addOnAdvancedStreamFeaturesAvailableListener(this.mAvatarService);
        AxolotlService axolotlService = account.getAxolotlService();
        if (axolotlService != null) {
            connection.addOnAdvancedStreamFeaturesAvailableListener(axolotlService);
        }
        return connection;
    }

    public void sendChatState(Conversation conversation) {
        if (sendChatStates()) {
            MessagePacket packet = mMessageGenerator.generateChatState(conversation);
            sendMessagePacket(conversation.getAccount(), packet);
        }
    }

    private void sendFileMessage(final Message message, final boolean delay) {
        Log.d(Config.LOGTAG, "send file message");
        final Account account = message.getConversation().getAccount();
        if (account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                || message.getConversation().getMode() == Conversation.MODE_MULTI) {
            mHttpConnectionManager.createNewUploadConnection(message, delay);
        } else {
            mJingleConnectionManager.startJingleFileTransfer(message);
        }
    }

    public void sendMessage(final Message message) {
        sendMessage(message, false, false);
    }

    private void sendMessage(final Message message, final boolean resend, final boolean delay) {
        final Account account = message.getConversation().getAccount();
        if (account.setShowErrorNotification(true)) {
            databaseBackend.updateAccount(account);
            mNotificationService.updateErrorNotification();
        }
        final Conversation conversation = (Conversation) message.getConversation();
        account.deactivateGracePeriod();


        if (QuickConversationsService.isQuicksy() && conversation.getMode() == Conversation.MODE_SINGLE) {
            final Contact contact = conversation.getContact();
            if (!contact.showInRoster() && contact.getOption(Contact.Options.SYNCED_VIA_OTHER)) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": adding " + contact.getJid() + " on sending message");
                createContact(contact, true);
            }
        }

        MessagePacket packet = null;
        final boolean addToConversation = (conversation.getMode() != Conversation.MODE_MULTI
                || !Patches.BAD_MUC_REFLECTION.contains(account.getServerIdentity()))
                && !message.edited();
        boolean saveInDb = addToConversation;
        message.setStatus(Message.STATUS_WAITING);

        if (message.getEncryption() != Message.ENCRYPTION_NONE && conversation.getMode() == Conversation.MODE_MULTI && conversation.isPrivateAndNonAnonymous()) {
            if (conversation.setAttribute(Conversation.ATTRIBUTE_FORMERLY_PRIVATE_NON_ANONYMOUS, true)) {
                databaseBackend.updateConversation(conversation);
            }
        }

        final boolean inProgressJoin = isJoinInProgress(conversation);


        if (account.isOnlineAndConnected() && !inProgressJoin) {
            switch (message.getEncryption()) {
                case Message.ENCRYPTION_NONE:
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart()) {
                            this.sendFileMessage(message, delay);
                        } else {
                            break;
                        }
                    } else {
                        packet = mMessageGenerator.generateChat(message);
                    }
                    break;
                case Message.ENCRYPTION_PGP:
                case Message.ENCRYPTION_DECRYPTED:
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart()) {
                            this.sendFileMessage(message, delay);
                        } else {
                            break;
                        }
                    } else {
                        packet = mMessageGenerator.generatePgpChat(message);
                    }
                    break;
                case Message.ENCRYPTION_AXOLOTL:
                    message.setFingerprint(account.getAxolotlService().getOwnFingerprint());
                    if (message.needsUploading()) {
                        if (account.httpUploadAvailable(fileBackend.getFile(message, false).getSize())
                                || conversation.getMode() == Conversation.MODE_MULTI
                                || message.fixCounterpart()) {
                            this.sendFileMessage(message, delay);
                        } else {
                            break;
                        }
                    } else {
                        XmppAxolotlMessage axolotlMessage = account.getAxolotlService().fetchAxolotlMessageFromCache(message);
                        if (axolotlMessage == null) {
                            account.getAxolotlService().preparePayloadMessage(message, delay);
                        } else {
                            packet = mMessageGenerator.generateAxolotlChat(message, axolotlMessage);
                        }
                    }
                    break;

            }
            if (packet != null) {
                if (account.getXmppConnection().getFeatures().sm()
                        || (conversation.getMode() == Conversation.MODE_MULTI && message.getCounterpart().isBareJid())) {
                    message.setStatus(Message.STATUS_UNSEND);
                } else {
                    message.setStatus(Message.STATUS_SEND);
                }
            }
        } else {
            switch (message.getEncryption()) {
                case Message.ENCRYPTION_DECRYPTED:
                    if (!message.needsUploading()) {
                        String pgpBody = message.getEncryptedBody();
                        String decryptedBody = message.getBody();
                        message.setBody(pgpBody); //TODO might throw NPE
                        message.setEncryption(Message.ENCRYPTION_PGP);
                        if (message.edited()) {
                            message.setBody(decryptedBody);
                            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                            if (!databaseBackend.updateMessage(message, message.getEditedId())) {
                                Log.e(Config.LOGTAG, "error updated message in DB after edit");
                            }
                            updateConversationUi();
                            return;
                        } else {
                            databaseBackend.createMessage(message);
                            saveInDb = false;
                            message.setBody(decryptedBody);
                            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                        }
                    }
                    break;
                case Message.ENCRYPTION_AXOLOTL:
                    message.setFingerprint(account.getAxolotlService().getOwnFingerprint());
                    break;
            }
        }


        boolean mucMessage = conversation.getMode() == Conversation.MODE_MULTI && !message.isPrivateMessage();
        if (mucMessage) {
            message.setCounterpart(conversation.getMucOptions().getSelf().getFullJid());
        }

        if (resend) {
            if (packet != null && addToConversation) {
                if (account.getXmppConnection().getFeatures().sm() || mucMessage) {
                    markMessage(message, Message.STATUS_UNSEND);
                } else {
                    markMessage(message, Message.STATUS_SEND);
                }
            }
        } else {
            if (addToConversation) {
                conversation.add(message);
            }
            if (saveInDb) {
                databaseBackend.createMessage(message);
            } else if (message.edited()) {
                if (!databaseBackend.updateMessage(message, message.getEditedId())) {
                    Log.e(Config.LOGTAG, "error updated message in DB after edit");
                }
            }
            updateConversationUi();
        }
        if (packet != null) {
            if (delay) {
                mMessageGenerator.addDelay(packet, message.getTimeSent());
            }
            if (conversation.setOutgoingChatState(Config.DEFAULT_CHAT_STATE)) {
                if (this.sendChatStates()) {
                    packet.addChild(ChatState.toElement(conversation.getOutgoingChatState()));
                }
            }
            sendMessagePacket(account, packet);
        }
    }

    private boolean isJoinInProgress(final Conversation conversation) {
        final Account account = conversation.getAccount();
        synchronized (account.inProgressConferenceJoins) {
            if (conversation.getMode() == Conversational.MODE_MULTI) {
                final boolean inProgress = account.inProgressConferenceJoins.contains(conversation);
                final boolean pending = account.pendingConferenceJoins.contains(conversation);
                final boolean inProgressJoin = inProgress || pending;
                if (inProgressJoin) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": holding back message to group. inProgress=" + inProgress + ", pending=" + pending);
                }
                return inProgressJoin;
            } else {
                return false;
            }
        }
    }

    private void sendUnsentMessages(final Conversation conversation) {
        conversation.findWaitingMessages(message -> resendMessage(message, true));
    }

    public void resendMessage(final Message message, final boolean delay) {
        sendMessage(message, true, delay);
    }

    public void requestEasyOnboardingInvite(final Account account, final EasyOnboardingInvite.OnInviteRequested callback) {
        final XmppConnection connection = account.getXmppConnection();
        final Jid jid = connection == null ? null : connection.getJidForCommand(Namespace.EASY_ONBOARDING_INVITE);
        if (jid == null) {
            callback.inviteRequestFailed(getString(R.string.server_does_not_support_easy_onboarding_invites));
            return;
        }
        final IqPacket request = new IqPacket(IqPacket.TYPE.SET);
        request.setTo(jid);
        final Element command = request.addChild("command", Namespace.COMMANDS);
        command.setAttribute("node", Namespace.EASY_ONBOARDING_INVITE);
        command.setAttribute("action", "execute");
        sendIqPacket(account, request, (a, response) -> {
            if (response.getType() == IqPacket.TYPE.RESULT) {
                final Element resultCommand = response.findChild("command", Namespace.COMMANDS);
                final Element x = resultCommand == null ? null : resultCommand.findChild("x", Namespace.DATA);
                if (x != null) {
                    final Data data = Data.parse(x);
                    final String uri = data.getValue("uri");
                    final String landingUrl = data.getValue("landing-url");
                    if (uri != null) {
                        final EasyOnboardingInvite invite = new EasyOnboardingInvite(jid.getDomain().toEscapedString(), uri, landingUrl);
                        callback.inviteRequested(invite);
                        return;
                    }
                }
                callback.inviteRequestFailed(getString(R.string.unable_to_parse_invite));
                Log.d(Config.LOGTAG, response.toString());
            } else if (response.getType() == IqPacket.TYPE.ERROR) {
                callback.inviteRequestFailed(IqParser.errorMessage(response));
            } else {
                callback.inviteRequestFailed(getString(R.string.remote_server_timeout));
            }
        });

    }

    public void fetchRosterFromServer(final Account account) {
        final IqPacket iqPacket = new IqPacket(IqPacket.TYPE.GET);
        if (!"".equals(account.getRosterVersion())) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid()
                    + ": fetching roster version " + account.getRosterVersion());
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": fetching roster");
        }
        iqPacket.query(Namespace.ROSTER).setAttribute("ver", account.getRosterVersion());
        sendIqPacket(account, iqPacket, mIqParser);
    }

    public void fetchBookmarks(final Account account) {
        final IqPacket iqPacket = new IqPacket(IqPacket.TYPE.GET);
        final Element query = iqPacket.query("jabber:iq:private");
        query.addChild("storage", Namespace.BOOKMARKS);
        final OnIqPacketReceived callback = (a, response) -> {
            if (response.getType() == IqPacket.TYPE.RESULT) {
                final Element query1 = response.query();
                final Element storage = query1.findChild("storage", "storage:bookmarks");
                Map<Jid, Bookmark> bookmarks = Bookmark.parseFromStorage(storage, account);
                processBookmarksInitial(a, bookmarks, false);
            } else {
                Log.d(Config.LOGTAG, a.getJid().asBareJid() + ": could not fetch bookmarks");
            }
        };
        sendIqPacket(account, iqPacket, callback);
    }

    public void fetchBookmarks2(final Account account) {
        final IqPacket retrieve = mIqGenerator.retrieveBookmarks();
        sendIqPacket(account, retrieve, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(final Account account, final IqPacket response) {
                if (response.getType() == IqPacket.TYPE.RESULT) {
                    final Element pubsub = response.findChild("pubsub", Namespace.PUBSUB);
                    final Map<Jid, Bookmark> bookmarks = Bookmark.parseFromPubsub(pubsub, account);
                    processBookmarksInitial(account, bookmarks, true);
                }
            }
        });
    }

    public void processBookmarksInitial(Account account, Map<Jid, Bookmark> bookmarks, final boolean pep) {
        final Set<Jid> previousBookmarks = account.getBookmarkedJids();
        final boolean synchronizeWithBookmarks = synchronizeWithBookmarks();
        for (Bookmark bookmark : bookmarks.values()) {
            previousBookmarks.remove(bookmark.getJid().asBareJid());
            processModifiedBookmark(bookmark, pep, synchronizeWithBookmarks);
        }
        if (pep && synchronizeWithBookmarks) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": " + previousBookmarks.size() + " bookmarks have been removed");
            for (Jid jid : previousBookmarks) {
                processDeletedBookmark(account, jid);
            }
        }
        account.setBookmarks(bookmarks);
    }

    public void processDeletedBookmark(Account account, Jid jid) {
        final Conversation conversation = find(account, jid);
        if (conversation != null && conversation.getMucOptions().getError() == MucOptions.Error.DESTROYED) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": archiving destroyed conference (" + conversation.getJid() + ") after receiving pep");
            archiveConversation(conversation, false);
        }
    }

    private void processModifiedBookmark(Bookmark bookmark, final boolean pep, final boolean synchronizeWithBookmarks) {
        final Account account = bookmark.getAccount();
        Conversation conversation = find(bookmark);
        if (conversation != null) {
            if (conversation.getMode() != Conversation.MODE_MULTI) {
                return;
            }
            bookmark.setConversation(conversation);
            if (pep && synchronizeWithBookmarks && !bookmark.autojoin()) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": archiving conference (" + conversation.getJid() + ") after receiving pep");
                archiveConversation(conversation, false);
            } else {
                final MucOptions mucOptions = conversation.getMucOptions();
                if (mucOptions.getError() == MucOptions.Error.NICK_IN_USE) {
                    final String current = mucOptions.getActualNick();
                    final String proposed = mucOptions.getProposedNick();
                    if (current != null && !current.equals(proposed)) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": proposed nick changed after bookmark push " + current + "->" + proposed);
                        joinMuc(conversation);
                    }
                }
            }
        } else if (synchronizeWithBookmarks && bookmark.autojoin()) {
            conversation = findOrCreateConversation(account, bookmark.getFullJid(), true, true, false);
            bookmark.setConversation(conversation);
        }
    }

    public void processModifiedBookmark(Bookmark bookmark) {
        final boolean synchronizeWithBookmarks = synchronizeWithBookmarks();
        processModifiedBookmark(bookmark, true, synchronizeWithBookmarks);
    }

    public void createBookmark(final Account account, final Bookmark bookmark) {
        account.putBookmark(bookmark);
        final XmppConnection connection = account.getXmppConnection();
        if (connection.getFeatures().bookmarks2()) {
            final Element item = mIqGenerator.publishBookmarkItem(bookmark);
            pushNodeAndEnforcePublishOptions(account, Namespace.BOOKMARKS2, item, bookmark.getJid().asBareJid().toEscapedString(), PublishOptions.persistentWhitelistAccessMaxItems());
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(account);
        } else {
            pushBookmarksPrivateXml(account);
        }
    }

    public void deleteBookmark(final Account account, final Bookmark bookmark) {
        account.removeBookmark(bookmark);
        final XmppConnection connection = account.getXmppConnection();
        if (connection.getFeatures().bookmarks2()) {
            IqPacket request = mIqGenerator.deleteItem(Namespace.BOOKMARKS2, bookmark.getJid().asBareJid().toEscapedString());
            sendIqPacket(account, request, (a, response) -> {
                if (response.getType() == IqPacket.TYPE.ERROR) {
                    Log.d(Config.LOGTAG, a.getJid().asBareJid() + ": unable to delete bookmark " + response.getErrorCondition());
                }
            });
        } else if (connection.getFeatures().bookmarksConversion()) {
            pushBookmarksPep(account);
        } else {
            pushBookmarksPrivateXml(account);
        }
    }

    private void pushBookmarksPrivateXml(Account account) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": pushing bookmarks via private xml");
        IqPacket iqPacket = new IqPacket(IqPacket.TYPE.SET);
        Element query = iqPacket.query("jabber:iq:private");
        Element storage = query.addChild("storage", "storage:bookmarks");
        for (Bookmark bookmark : account.getBookmarks()) {
            storage.addChild(bookmark);
        }
        sendIqPacket(account, iqPacket, mDefaultIqHandler);
    }

    private void pushBookmarksPep(Account account) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": pushing bookmarks via pep");
        Element storage = new Element("storage", "storage:bookmarks");
        for (Bookmark bookmark : account.getBookmarks()) {
            storage.addChild(bookmark);
        }
        pushNodeAndEnforcePublishOptions(account, Namespace.BOOKMARKS, storage, "current", PublishOptions.persistentWhitelistAccess());

    }

    private void pushNodeAndEnforcePublishOptions(final Account account, final String node, final Element element, final String id, final Bundle options) {
        pushNodeAndEnforcePublishOptions(account, node, element, id, options, true);

    }

    private void pushNodeAndEnforcePublishOptions(final Account account, final String node, final Element element, final String id, final Bundle options, final boolean retry) {
        final IqPacket packet = mIqGenerator.publishElement(node, element, id, options);
        sendIqPacket(account, packet, (a, response) -> {
            if (response.getType() == IqPacket.TYPE.RESULT) {
                return;
            }
            if (retry && PublishOptions.preconditionNotMet(response)) {
                pushNodeConfiguration(account, node, options, new OnConfigurationPushed() {
                    @Override
                    public void onPushSucceeded() {
                        pushNodeAndEnforcePublishOptions(account, node, element, id, options, false);
                    }

                    @Override
                    public void onPushFailed() {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to push node configuration (" + node + ")");
                    }
                });
            } else {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": error publishing bookmarks (retry=" + retry + ") " + response);
            }
        });
    }

    private void restoreFromDatabase() {
        synchronized (this.conversations) {
            final Map<String, Account> accountLookupTable = new Hashtable<>();
            for (Account account : this.accounts) {
                accountLookupTable.put(account.getUuid(), account);
            }
            Log.d(Config.LOGTAG, "restoring conversations...");
            final long startTimeConversationsRestore = SystemClock.elapsedRealtime();
            this.conversations.addAll(databaseBackend.getConversations(Conversation.STATUS_AVAILABLE));
            for (Iterator<Conversation> iterator = conversations.listIterator(); iterator.hasNext(); ) {
                Conversation conversation = iterator.next();
                Account account = accountLookupTable.get(conversation.getAccountUuid());
                if (account != null) {
                    conversation.setAccount(account);
                } else {
                    Log.e(Config.LOGTAG, "unable to restore Conversations with " + conversation.getJid());
                    iterator.remove();
                }
            }
            long diffConversationsRestore = SystemClock.elapsedRealtime() - startTimeConversationsRestore;
            Log.d(Config.LOGTAG, "finished restoring conversations in " + diffConversationsRestore + "ms");
            Runnable runnable = () -> {
                if (DatabaseBackend.requiresMessageIndexRebuild()) {
                    DatabaseBackend.getInstance(this).rebuildMessagesIndex();
                }
                final long deletionDate = getAutomaticMessageDeletionDate();
                mLastExpiryRun.set(SystemClock.elapsedRealtime());
                if (deletionDate > 0) {
                    Log.d(Config.LOGTAG, "deleting messages that are older than " + AbstractGenerator.getTimestamp(deletionDate));
                    databaseBackend.expireOldMessages(deletionDate);
                }
                Log.d(Config.LOGTAG, "restoring roster...");
                for (Account account : accounts) {
                    databaseBackend.readRoster(account.getRoster());
                    account.initAccountServices(XmppConnectionService.this); //roster needs to be loaded at this stage
                }
                getBitmapCache().evictAll();
                loadPhoneContacts();
                Log.d(Config.LOGTAG, "restoring messages...");
                final long startMessageRestore = SystemClock.elapsedRealtime();
                final Conversation quickLoad = QuickLoader.get(this.conversations);
                if (quickLoad != null) {
                    restoreMessages(quickLoad);
                    updateConversationUi();
                    final long diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore;
                    Log.d(Config.LOGTAG, "quickly restored " + quickLoad.getName() + " after " + diffMessageRestore + "ms");
                }
                for (Conversation conversation : this.conversations) {
                    if (quickLoad != conversation) {
                        restoreMessages(conversation);
                    }
                }
                mNotificationService.finishBacklog(false);
                restoredFromDatabaseLatch.countDown();
                final long diffMessageRestore = SystemClock.elapsedRealtime() - startMessageRestore;
                Log.d(Config.LOGTAG, "finished restoring messages in " + diffMessageRestore + "ms");
                updateConversationUi();
            };
            mDatabaseReaderExecutor.execute(runnable); //will contain one write command (expiry) but that's fine
        }
    }

    private void restoreMessages(Conversation conversation) {
        conversation.addAll(0, databaseBackend.getMessages(conversation, Config.PAGE_SIZE));
        conversation.findUnsentTextMessages(message -> markMessage(message, Message.STATUS_WAITING));
        conversation.findUnreadMessages(mNotificationService::pushFromBacklog);
    }

    public void loadPhoneContacts() {
        mContactMergerExecutor.execute(() -> {
            Map<Jid, JabberIdContact> contacts = JabberIdContact.load(this);
            Log.d(Config.LOGTAG, "start merging phone contacts with roster");
            for (Account account : accounts) {
                List<Contact> withSystemAccounts = account.getRoster().getWithSystemAccounts(JabberIdContact.class);
                for (JabberIdContact jidContact : contacts.values()) {
                    final Contact contact = account.getRoster().getContact(jidContact.getJid());
                    boolean needsCacheClean = contact.setPhoneContact(jidContact);
                    if (needsCacheClean) {
                        getAvatarService().clear(contact);
                    }
                    withSystemAccounts.remove(contact);
                }
                for (Contact contact : withSystemAccounts) {
                    boolean needsCacheClean = contact.unsetPhoneContact(JabberIdContact.class);
                    if (needsCacheClean) {
                        getAvatarService().clear(contact);
                    }
                }
            }
            Log.d(Config.LOGTAG, "finished merging phone contacts");
            mShortcutService.refresh(mInitialAddressbookSyncCompleted.compareAndSet(false, true));
            updateRosterUi();
            mQuickConversationsService.considerSync();
        });
    }


    public void syncRoster(final Account account) {
        mRosterSyncTaskManager.execute(account, () -> databaseBackend.writeRoster(account.getRoster()));
    }

    public List<Conversation> getConversations() {
        return this.conversations;
    }

    private void markFileDeleted(final File file) {
        synchronized (FILENAMES_TO_IGNORE_DELETION) {
            if (FILENAMES_TO_IGNORE_DELETION.remove(file.getAbsolutePath())) {
                Log.d(Config.LOGTAG, "ignored deletion of " + file.getAbsolutePath());
                return;
            }
        }
        final boolean isInternalFile = fileBackend.isInternalFile(file);
        final List<String> uuids = databaseBackend.markFileAsDeleted(file, isInternalFile);
        Log.d(Config.LOGTAG, "deleted file " + file.getAbsolutePath() + " internal=" + isInternalFile + ", database hits=" + uuids.size());
        markUuidsAsDeletedFiles(uuids);
    }

    private void markUuidsAsDeletedFiles(List<String> uuids) {
        boolean deleted = false;
        for (Conversation conversation : getConversations()) {
            deleted |= conversation.markAsDeleted(uuids);
        }
        for (final String uuid : uuids) {
            evictPreview(uuid);
        }
        if (deleted) {
            updateConversationUi();
        }
    }

    private void markChangedFiles(List<DatabaseBackend.FilePathInfo> infos) {
        boolean changed = false;
        for (Conversation conversation : getConversations()) {
            changed |= conversation.markAsChanged(infos);
        }
        if (changed) {
            updateConversationUi();
        }
    }

    public void populateWithOrderedConversations(final List<Conversation> list) {
        populateWithOrderedConversations(list, true, true);
    }

    public void populateWithOrderedConversations(final List<Conversation> list, final boolean includeNoFileUpload) {
        populateWithOrderedConversations(list, includeNoFileUpload, true);
    }

    public void populateWithOrderedConversations(final List<Conversation> list, final boolean includeNoFileUpload, final boolean sort) {
        final List<String> orderedUuids;
        if (sort) {
            orderedUuids = null;
        } else {
            orderedUuids = new ArrayList<>();
            for (Conversation conversation : list) {
                orderedUuids.add(conversation.getUuid());
            }
        }
        list.clear();
        if (includeNoFileUpload) {
            list.addAll(getConversations());
        } else {
            for (Conversation conversation : getConversations()) {
                if (conversation.getMode() == Conversation.MODE_SINGLE
                        || (conversation.getAccount().httpUploadAvailable() && conversation.getMucOptions().participating())) {
                    list.add(conversation);
                }
            }
        }
        try {
            if (orderedUuids != null) {
                Collections.sort(list, (a, b) -> {
                    final int indexA = orderedUuids.indexOf(a.getUuid());
                    final int indexB = orderedUuids.indexOf(b.getUuid());
                    if (indexA == -1 || indexB == -1 || indexA == indexB) {
                        return a.compareTo(b);
                    }
                    return indexA - indexB;
                });
            } else {
                Collections.sort(list);
            }
        } catch (IllegalArgumentException e) {
            //ignore
        }
    }

    public void loadMoreMessages(final Conversation conversation, final long timestamp, final OnMoreMessagesLoaded callback) {
        if (XmppConnectionService.this.getMessageArchiveService().queryInProgress(conversation, callback)) {
            return;
        } else if (timestamp == 0) {
            return;
        }
        Log.d(Config.LOGTAG, "load more messages for " + conversation.getName() + " prior to " + MessageGenerator.getTimestamp(timestamp));
        final Runnable runnable = () -> {
            final Account account = conversation.getAccount();
            List<Message> messages = databaseBackend.getMessages(conversation, 50, timestamp);
            if (messages.size() > 0) {
                conversation.addAll(0, messages);
                callback.onMoreMessagesLoaded(messages.size(), conversation);
            } else if (conversation.hasMessagesLeftOnServer()
                    && account.isOnlineAndConnected()
                    && conversation.getLastClearHistory().getTimestamp() == 0) {
                final boolean mamAvailable;
                if (conversation.getMode() == Conversation.MODE_SINGLE) {
                    mamAvailable = account.getXmppConnection().getFeatures().mam() && !conversation.getContact().isBlocked();
                } else {
                    mamAvailable = conversation.getMucOptions().mamSupport();
                }
                if (mamAvailable) {
                    MessageArchiveService.Query query = getMessageArchiveService().query(conversation, new MamReference(0), timestamp, false);
                    if (query != null) {
                        query.setCallback(callback);
                        callback.informUser(R.string.fetching_history_from_server);
                    } else {
                        callback.informUser(R.string.not_fetching_history_retention_period);
                    }

                }
            }
        };
        mDatabaseReaderExecutor.execute(runnable);
    }

    public List<Account> getAccounts() {
        return this.accounts;
    }


    /**
     * This will find all conferences with the contact as member and also the conference that is the contact (that 'fake' contact is used to store the avatar)
     */
    public List<Conversation> findAllConferencesWith(Contact contact) {
        final ArrayList<Conversation> results = new ArrayList<>();
        for (final Conversation c : conversations) {
            if (c.getMode() != Conversation.MODE_MULTI) {
                continue;
            }
            final MucOptions mucOptions = c.getMucOptions();
            if (c.getJid().asBareJid().equals(contact.getJid().asBareJid()) || (mucOptions != null && mucOptions.isContactInRoom(contact))) {
                results.add(c);
            }
        }
        return results;
    }

    public Conversation find(final Iterable<Conversation> haystack, final Contact contact) {
        for (final Conversation conversation : haystack) {
            if (conversation.getContact() == contact) {
                return conversation;
            }
        }
        return null;
    }

    public Conversation find(final Iterable<Conversation> haystack, final Account account, final Jid jid) {
        if (jid == null) {
            return null;
        }
        for (final Conversation conversation : haystack) {
            if ((account == null || conversation.getAccount() == account)
                    && (conversation.getJid().asBareJid().equals(jid.asBareJid()))) {
                return conversation;
            }
        }
        return null;
    }

    public boolean isConversationsListEmpty(final Conversation ignore) {
        synchronized (this.conversations) {
            final int size = this.conversations.size();
            return size == 0 || size == 1 && this.conversations.get(0) == ignore;
        }
    }

    public boolean isConversationStillOpen(final Conversation conversation) {
        synchronized (this.conversations) {
            for (Conversation current : this.conversations) {
                if (current == conversation) {
                    return true;
                }
            }
        }
        return false;
    }

    public Conversation findOrCreateConversation(Account account, Jid jid, boolean muc, final boolean async) {
        return this.findOrCreateConversation(account, jid, muc, false, async);
    }

    public Conversation findOrCreateConversation(final Account account, final Jid jid, final boolean muc, final boolean joinAfterCreate, final boolean async) {
        return this.findOrCreateConversation(account, jid, muc, joinAfterCreate, null, async);
    }

    public Conversation findOrCreateConversation(final Account account, final Jid jid, final boolean muc, final boolean joinAfterCreate, final MessageArchiveService.Query query, final boolean async) {
        synchronized (this.conversations) {
            Conversation conversation = find(account, jid);
            if (conversation != null) {
                return conversation;
            }
            conversation = databaseBackend.findConversation(account, jid);
            final boolean loadMessagesFromDb;
            if (conversation != null) {
                conversation.setStatus(Conversation.STATUS_AVAILABLE);
                conversation.setAccount(account);
                if (muc) {
                    conversation.setMode(Conversation.MODE_MULTI);
                    conversation.setContactJid(jid);
                } else {
                    conversation.setMode(Conversation.MODE_SINGLE);
                    conversation.setContactJid(jid.asBareJid());
                }
                databaseBackend.updateConversation(conversation);
                loadMessagesFromDb = conversation.messagesLoaded.compareAndSet(true, false);
            } else {
                String conversationName;
                Contact contact = account.getRoster().getContact(jid);
                if (contact != null) {
                    conversationName = contact.getDisplayName();
                } else {
                    conversationName = jid.getLocal();
                }
                if (muc) {
                    conversation = new Conversation(conversationName, account, jid,
                            Conversation.MODE_MULTI);
                } else {
                    conversation = new Conversation(conversationName, account, jid.asBareJid(),
                            Conversation.MODE_SINGLE);
                }
                this.databaseBackend.createConversation(conversation);
                loadMessagesFromDb = false;
            }
            final Conversation c = conversation;
            final Runnable runnable = () -> {
                if (loadMessagesFromDb) {
                    c.addAll(0, databaseBackend.getMessages(c, Config.PAGE_SIZE));
                    updateConversationUi();
                    c.messagesLoaded.set(true);
                }
                if (account.getXmppConnection() != null
                        && !c.getContact().isBlocked()
                        && account.getXmppConnection().getFeatures().mam()
                        && !muc) {
                    if (query == null) {
                        mMessageArchiveService.query(c);
                    } else {
                        if (query.getConversation() == null) {
                            mMessageArchiveService.query(c, query.getStart(), query.isCatchup());
                        }
                    }
                }
                if (joinAfterCreate) {
                    joinMuc(c);
                }
            };
            if (async) {
                mDatabaseReaderExecutor.execute(runnable);
            } else {
                runnable.run();
            }
            this.conversations.add(conversation);
            updateConversationUi();
            return conversation;
        }
    }

    public void archiveConversation(Conversation conversation) {
        archiveConversation(conversation, true);
    }

    private void archiveConversation(Conversation conversation, final boolean maySynchronizeWithBookmarks) {
        getNotificationService().clear(conversation);
        conversation.setStatus(Conversation.STATUS_ARCHIVED);
        conversation.setNextMessage(null);
        synchronized (this.conversations) {
            getMessageArchiveService().kill(conversation);
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                if (conversation.getAccount().getStatus() == Account.State.ONLINE) {
                    final Bookmark bookmark = conversation.getBookmark();
                    if (maySynchronizeWithBookmarks && bookmark != null && synchronizeWithBookmarks()) {
                        if (conversation.getMucOptions().getError() == MucOptions.Error.DESTROYED) {
                            Account account = bookmark.getAccount();
                            bookmark.setConversation(null);
                            deleteBookmark(account, bookmark);
                        } else if (bookmark.autojoin()) {
                            bookmark.setAutojoin(false);
                            createBookmark(bookmark.getAccount(), bookmark);
                        }
                    }
                }
                leaveMuc(conversation);
            } else {
                if (conversation.getContact().getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)) {
                    stopPresenceUpdatesTo(conversation.getContact());
                }
            }
            updateConversation(conversation);
            this.conversations.remove(conversation);
            updateConversationUi();
        }
    }

    public void stopPresenceUpdatesTo(Contact contact) {
        Log.d(Config.LOGTAG, "Canceling presence request from " + contact.getJid().toString());
        sendPresencePacket(contact.getAccount(), mPresenceGenerator.stopPresenceUpdatesTo(contact));
        contact.resetOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
    }

    public void createAccount(final Account account) {
        account.initAccountServices(this);
        databaseBackend.createAccount(account);
        this.accounts.add(account);
        this.reconnectAccountInBackground(account);
        updateAccountUi();
        syncEnabledAccountSetting();
        toggleForegroundService();
    }

    private void syncEnabledAccountSetting() {
        final boolean hasEnabledAccounts = hasEnabledAccounts();
        getPreferences().edit().putBoolean(EventReceiver.SETTING_ENABLED_ACCOUNTS, hasEnabledAccounts).apply();
        toggleSetProfilePictureActivity(hasEnabledAccounts);
    }

    private void toggleSetProfilePictureActivity(final boolean enabled) {
        try {
            final ComponentName name = new ComponentName(this, ChooseAccountForProfilePictureActivity.class);
            final int targetState = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            getPackageManager().setComponentEnabledSetting(name, targetState, PackageManager.DONT_KILL_APP);
        } catch (IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle profile picture actvitiy");
        }
    }

    private void provisionAccount(final String address, final String password) {
        final Jid jid = Jid.ofEscaped(address);
        final Account account = new Account(jid, password);
        account.setOption(Account.OPTION_DISABLED, true);
        Log.d(Config.LOGTAG, jid.asBareJid().toEscapedString() + ": provisioning account");
        createAccount(account);
    }

    public void createAccountFromKey(final String alias, final OnAccountCreated callback) {
        new Thread(() -> {
            try {
                final X509Certificate[] chain = KeyChain.getCertificateChain(this, alias);
                final X509Certificate cert = chain != null && chain.length > 0 ? chain[0] : null;
                if (cert == null) {
                    callback.informUser(R.string.unable_to_parse_certificate);
                    return;
                }
                Pair<Jid, String> info = CryptoHelper.extractJidAndName(cert);
                if (info == null) {
                    callback.informUser(R.string.certificate_does_not_contain_jid);
                    return;
                }
                if (findAccountByJid(info.first) == null) {
                    final Account account = new Account(info.first, "");
                    account.setPrivateKeyAlias(alias);
                    account.setOption(Account.OPTION_DISABLED, true);
                    account.setOption(Account.OPTION_FIXED_USERNAME, true);
                    account.setDisplayName(info.second);
                    createAccount(account);
                    callback.onAccountCreated(account);
                    if (Config.X509_VERIFICATION) {
                        try {
                            getMemorizingTrustManager().getNonInteractive(account.getServer()).checkClientTrusted(chain, "RSA");
                        } catch (CertificateException e) {
                            callback.informUser(R.string.certificate_chain_is_not_trusted);
                        }
                    }
                } else {
                    callback.informUser(R.string.account_already_exists);
                }
            } catch (Exception e) {
                callback.informUser(R.string.unable_to_parse_certificate);
            }
        }).start();

    }

    public void updateKeyInAccount(final Account account, final String alias) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": update key in account " + alias);
        try {
            X509Certificate[] chain = KeyChain.getCertificateChain(XmppConnectionService.this, alias);
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + " loaded certificate chain");
            Pair<Jid, String> info = CryptoHelper.extractJidAndName(chain[0]);
            if (info == null) {
                showErrorToastInUi(R.string.certificate_does_not_contain_jid);
                return;
            }
            if (account.getJid().asBareJid().equals(info.first)) {
                account.setPrivateKeyAlias(alias);
                account.setDisplayName(info.second);
                databaseBackend.updateAccount(account);
                if (Config.X509_VERIFICATION) {
                    try {
                        getMemorizingTrustManager().getNonInteractive().checkClientTrusted(chain, "RSA");
                    } catch (CertificateException e) {
                        showErrorToastInUi(R.string.certificate_chain_is_not_trusted);
                    }
                    account.getAxolotlService().regenerateKeys(true);
                }
            } else {
                showErrorToastInUi(R.string.jid_does_not_match_certificate);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean updateAccount(final Account account) {
        if (databaseBackend.updateAccount(account)) {
            account.setShowErrorNotification(true);
            this.statusListener.onStatusChanged(account);
            databaseBackend.updateAccount(account);
            reconnectAccountInBackground(account);
            updateAccountUi();
            getNotificationService().updateErrorNotification();
            toggleForegroundService();
            syncEnabledAccountSetting();
            mChannelDiscoveryService.cleanCache();
            return true;
        } else {
            return false;
        }
    }

    public void updateAccountPasswordOnServer(final Account account, final String newPassword, final OnAccountPasswordChanged callback) {
        final IqPacket iq = getIqGenerator().generateSetPassword(account, newPassword);
        sendIqPacket(account, iq, (a, packet) -> {
            if (packet.getType() == IqPacket.TYPE.RESULT) {
                a.setPassword(newPassword);
                a.setOption(Account.OPTION_MAGIC_CREATE, false);
                databaseBackend.updateAccount(a);
                callback.onPasswordChangeSucceeded();
            } else {
                callback.onPasswordChangeFailed();
            }
        });
    }

    public void deleteAccount(final Account account) {
        final boolean connected = account.getStatus() == Account.State.ONLINE;
        synchronized (this.conversations) {
            if (connected) {
                account.getAxolotlService().deleteOmemoIdentity();
            }
            for (final Conversation conversation : conversations) {
                if (conversation.getAccount() == account) {
                    if (conversation.getMode() == Conversation.MODE_MULTI) {
                        if (connected) {
                            leaveMuc(conversation);
                        }
                    }
                    conversations.remove(conversation);
                    mNotificationService.clear(conversation);
                }
            }
            if (account.getXmppConnection() != null) {
                new Thread(() -> disconnect(account, !connected)).start();
            }
            final Runnable runnable = () -> {
                if (!databaseBackend.deleteAccount(account)) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to delete account");
                }
            };
            mDatabaseWriterExecutor.execute(runnable);
            this.accounts.remove(account);
            this.mRosterSyncTaskManager.clear(account);
            updateAccountUi();
            mNotificationService.updateErrorNotification();
            syncEnabledAccountSetting();
            toggleForegroundService();
        }
    }

    public void setOnConversationListChangedListener(OnConversationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnConversationUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as ConversationListChangedListener");
            }
            this.mNotificationService.setIsInForeground(this.mOnConversationUpdates.size() > 0);
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnConversationListChangedListener(OnConversationUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnConversationUpdates.remove(listener);
            this.mNotificationService.setIsInForeground(this.mOnConversationUpdates.size() > 0);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnShowErrorToastListener(OnShowErrorToast listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnShowErrorToasts.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnShowErrorToastListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnShowErrorToastListener(OnShowErrorToast onShowErrorToast) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnShowErrorToasts.remove(onShowErrorToast);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnAccountListChangedListener(OnAccountUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnAccountUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnAccountListChangedtListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnAccountListChangedListener(OnAccountUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnAccountUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnCaptchaRequestedListener(OnCaptchaRequested listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnCaptchaRequested.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnCaptchaRequestListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnCaptchaRequestedListener(OnCaptchaRequested listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnCaptchaRequested.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnRosterUpdateListener(final OnRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnRosterUpdates.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnRosterUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnRosterUpdateListener(final OnRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnRosterUpdates.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnUpdateBlocklistListener(final OnUpdateBlocklist listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnUpdateBlocklist.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnUpdateBlocklistListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnUpdateBlocklistListener(final OnUpdateBlocklist listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnUpdateBlocklist.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnKeyStatusUpdatedListener(final OnKeyStatusUpdated listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnKeyStatusUpdated.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnKeyStatusUpdateListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnNewKeysAvailableListener(final OnKeyStatusUpdated listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnKeyStatusUpdated.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnRtpConnectionUpdateListener(final OnJingleRtpConnectionUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.onJingleRtpConnectionUpdate.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnJingleRtpConnectionUpdate");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeRtpConnectionUpdateListener(final OnJingleRtpConnectionUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.onJingleRtpConnectionUpdate.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public void setOnMucRosterUpdateListener(OnMucRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            remainingListeners = checkListeners();
            if (!this.mOnMucRosterUpdate.add(listener)) {
                Log.w(Config.LOGTAG, listener.getClass().getName() + " is already registered as OnMucRosterListener");
            }
        }
        if (remainingListeners) {
            switchToForeground();
        }
    }

    public void removeOnMucRosterUpdateListener(final OnMucRosterUpdate listener) {
        final boolean remainingListeners;
        synchronized (LISTENER_LOCK) {
            this.mOnMucRosterUpdate.remove(listener);
            remainingListeners = checkListeners();
        }
        if (remainingListeners) {
            switchToBackground();
        }
    }

    public boolean checkListeners() {
        return (this.mOnAccountUpdates.size() == 0
                && this.mOnConversationUpdates.size() == 0
                && this.mOnRosterUpdates.size() == 0
                && this.mOnCaptchaRequested.size() == 0
                && this.mOnMucRosterUpdate.size() == 0
                && this.mOnUpdateBlocklist.size() == 0
                && this.mOnShowErrorToasts.size() == 0
                && this.onJingleRtpConnectionUpdate.size() == 0
                && this.mOnKeyStatusUpdated.size() == 0);
    }

    private void switchToForeground() {
        final boolean broadcastLastActivity = broadcastLastActivity();
        for (Conversation conversation : getConversations()) {
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                conversation.getMucOptions().resetChatState();
            } else {
                conversation.setIncomingChatState(Config.DEFAULT_CHAT_STATE);
            }
        }
        for (Account account : getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE) {
                account.deactivateGracePeriod();
                final XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    if (connection.getFeatures().csi()) {
                        connection.sendActive();
                    }
                    if (broadcastLastActivity) {
                        sendPresence(account, false); //send new presence but don't include idle because we are not
                    }
                }
            }
        }
        Log.d(Config.LOGTAG, "app switched into foreground");
    }

    private void switchToBackground() {
        final boolean broadcastLastActivity = broadcastLastActivity();
        if (broadcastLastActivity) {
            mLastActivity = System.currentTimeMillis();
            final SharedPreferences.Editor editor = getPreferences().edit();
            editor.putLong(SETTING_LAST_ACTIVITY_TS, mLastActivity);
            editor.apply();
        }
        for (Account account : getAccounts()) {
            if (account.getStatus() == Account.State.ONLINE) {
                XmppConnection connection = account.getXmppConnection();
                if (connection != null) {
                    if (broadcastLastActivity) {
                        sendPresence(account, true);
                    }
                    if (connection.getFeatures().csi()) {
                        connection.sendInactive();
                    }
                }
            }
        }
        this.mNotificationService.setIsInForeground(false);
        Log.d(Config.LOGTAG, "app switched into background");
    }

    private void connectMultiModeConversations(Account account) {
        List<Conversation> conversations = getConversations();
        for (Conversation conversation : conversations) {
            if (conversation.getMode() == Conversation.MODE_MULTI && conversation.getAccount() == account) {
                joinMuc(conversation);
            }
        }
    }

    public void mucSelfPingAndRejoin(final Conversation conversation) {
        final Account account = conversation.getAccount();
        synchronized (account.inProgressConferenceJoins) {
            if (account.inProgressConferenceJoins.contains(conversation)) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": canceling muc self ping because join is already under way");
                return;
            }
        }
        synchronized (account.inProgressConferencePings) {
            if (!account.inProgressConferencePings.add(conversation)) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": canceling muc self ping because ping is already under way");
                return;
            }
        }
        final Jid self = conversation.getMucOptions().getSelf().getFullJid();
        final IqPacket ping = new IqPacket(IqPacket.TYPE.GET);
        ping.setTo(self);
        ping.addChild("ping", Namespace.PING);
        sendIqPacket(conversation.getAccount(), ping, (a, response) -> {
            if (response.getType() == IqPacket.TYPE.ERROR) {
                Element error = response.findChild("error");
                if (error == null || error.hasChild("service-unavailable") || error.hasChild("feature-not-implemented") || error.hasChild("item-not-found")) {
                    Log.d(Config.LOGTAG, a.getJid().asBareJid() + ": ping to " + self + " came back as ignorable error");
                } else {
                    Log.d(Config.LOGTAG, a.getJid().asBareJid() + ": ping to " + self + " failed. attempting rejoin");
                    joinMuc(conversation);
                }
            } else if (response.getType() == IqPacket.TYPE.RESULT) {
                Log.d(Config.LOGTAG, a.getJid().asBareJid() + ": ping to " + self + " came back fine");
            }
            synchronized (account.inProgressConferencePings) {
                account.inProgressConferencePings.remove(conversation);
            }
        });
    }

    public void joinMuc(Conversation conversation) {
        joinMuc(conversation, null, false);
    }

    public void joinMuc(Conversation conversation, boolean followedInvite) {
        joinMuc(conversation, null, followedInvite);
    }

    private void joinMuc(Conversation conversation, final OnConferenceJoined onConferenceJoined) {
        joinMuc(conversation, onConferenceJoined, false);
    }

    private void joinMuc(Conversation conversation, final OnConferenceJoined onConferenceJoined, final boolean followedInvite) {
        final Account account = conversation.getAccount();
        synchronized (account.pendingConferenceJoins) {
            account.pendingConferenceJoins.remove(conversation);
        }
        synchronized (account.pendingConferenceLeaves) {
            account.pendingConferenceLeaves.remove(conversation);
        }
        if (account.getStatus() == Account.State.ONLINE) {
            synchronized (account.inProgressConferenceJoins) {
                account.inProgressConferenceJoins.add(conversation);
            }
            if (Config.MUC_LEAVE_BEFORE_JOIN) {
                sendPresencePacket(account, mPresenceGenerator.leave(conversation.getMucOptions()));
            }
            conversation.resetMucOptions();
            if (onConferenceJoined != null) {
                conversation.getMucOptions().flagNoAutoPushConfiguration();
            }
            conversation.setHasMessagesLeftOnServer(false);
            fetchConferenceConfiguration(conversation, new OnConferenceConfigurationFetched() {

                private void join(Conversation conversation) {
                    Account account = conversation.getAccount();
                    final MucOptions mucOptions = conversation.getMucOptions();

                    if (mucOptions.nonanonymous() && !mucOptions.membersOnly() && !conversation.getBooleanAttribute("accept_non_anonymous", false)) {
                        synchronized (account.inProgressConferenceJoins) {
                            account.inProgressConferenceJoins.remove(conversation);
                        }
                        mucOptions.setError(MucOptions.Error.NON_ANONYMOUS);
                        updateConversationUi();
                        if (onConferenceJoined != null) {
                            onConferenceJoined.onConferenceJoined(conversation);
                        }
                        return;
                    }

                    final Jid joinJid = mucOptions.getSelf().getFullJid();
                    Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": joining conversation " + joinJid.toString());
                    PresencePacket packet = mPresenceGenerator.selfPresence(account, Presence.Status.ONLINE, mucOptions.nonanonymous() || onConferenceJoined != null);
                    packet.setTo(joinJid);
                    Element x = packet.addChild("x", "http://jabber.org/protocol/muc");
                    if (conversation.getMucOptions().getPassword() != null) {
                        x.addChild("password").setContent(mucOptions.getPassword());
                    }

                    if (mucOptions.mamSupport()) {
                        // Use MAM instead of the limited muc history to get history
                        x.addChild("history").setAttribute("maxchars", "0");
                    } else {
                        // Fallback to muc history
                        x.addChild("history").setAttribute("since", PresenceGenerator.getTimestamp(conversation.getLastMessageTransmitted().getTimestamp()));
                    }
                    sendPresencePacket(account, packet);
                    if (onConferenceJoined != null) {
                        onConferenceJoined.onConferenceJoined(conversation);
                    }
                    if (!joinJid.equals(conversation.getJid())) {
                        conversation.setContactJid(joinJid);
                        databaseBackend.updateConversation(conversation);
                    }

                    if (mucOptions.mamSupport()) {
                        getMessageArchiveService().catchupMUC(conversation);
                    }
                    if (mucOptions.isPrivateAndNonAnonymous()) {
                        fetchConferenceMembers(conversation);

                        if (followedInvite) {
                            final Bookmark bookmark = conversation.getBookmark();
                            if (bookmark != null) {
                                if (!bookmark.autojoin()) {
                                    bookmark.setAutojoin(true);
                                    createBookmark(account, bookmark);
                                }
                            } else {
                                saveConversationAsBookmark(conversation, null);
                            }
                        }
                    }
                    synchronized (account.inProgressConferenceJoins) {
                        account.inProgressConferenceJoins.remove(conversation);
                        sendUnsentMessages(conversation);
                    }
                }

                @Override
                public void onConferenceConfigurationFetched(Conversation conversation) {
                    if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": conversation (" + conversation.getJid() + ") got archived before IQ result");
                        return;
                    }
                    join(conversation);
                }

                @Override
                public void onFetchFailed(final Conversation conversation, final String errorCondition) {
                    if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": conversation (" + conversation.getJid() + ") got archived before IQ result");
                        return;
                    }
                    if ("remote-server-not-found".equals(errorCondition)) {
                        synchronized (account.inProgressConferenceJoins) {
                            account.inProgressConferenceJoins.remove(conversation);
                        }
                        conversation.getMucOptions().setError(MucOptions.Error.SERVER_NOT_FOUND);
                        updateConversationUi();
                    } else {
                        join(conversation);
                        fetchConferenceConfiguration(conversation);
                    }
                }
            });
            updateConversationUi();
        } else {
            synchronized (account.pendingConferenceJoins) {
                account.pendingConferenceJoins.add(conversation);
            }
            conversation.resetMucOptions();
            conversation.setHasMessagesLeftOnServer(false);
            updateConversationUi();
        }
    }

    private void fetchConferenceMembers(final Conversation conversation) {
        final Account account = conversation.getAccount();
        final AxolotlService axolotlService = account.getAxolotlService();
        final String[] affiliations = {"member", "admin", "owner"};
        OnIqPacketReceived callback = new OnIqPacketReceived() {

            private int i = 0;
            private boolean success = true;

            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                final boolean omemoEnabled = conversation.getNextEncryption() == Message.ENCRYPTION_AXOLOTL;
                Element query = packet.query("http://jabber.org/protocol/muc#admin");
                if (packet.getType() == IqPacket.TYPE.RESULT && query != null) {
                    for (Element child : query.getChildren()) {
                        if ("item".equals(child.getName())) {
                            MucOptions.User user = AbstractParser.parseItem(conversation, child);
                            if (!user.realJidMatchesAccount()) {
                                boolean isNew = conversation.getMucOptions().updateUser(user);
                                Contact contact = user.getContact();
                                if (omemoEnabled
                                        && isNew
                                        && user.getRealJid() != null
                                        && (contact == null || !contact.mutualPresenceSubscription())
                                        && axolotlService.hasEmptyDeviceList(user.getRealJid())) {
                                    axolotlService.fetchDeviceIds(user.getRealJid());
                                }
                            }
                        }
                    }
                } else {
                    success = false;
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": could not request affiliation " + affiliations[i] + " in " + conversation.getJid().asBareJid());
                }
                ++i;
                if (i >= affiliations.length) {
                    List<Jid> members = conversation.getMucOptions().getMembers(true);
                    if (success) {
                        List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
                        boolean changed = false;
                        for (ListIterator<Jid> iterator = cryptoTargets.listIterator(); iterator.hasNext(); ) {
                            Jid jid = iterator.next();
                            if (!members.contains(jid) && !members.contains(jid.getDomain())) {
                                iterator.remove();
                                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": removed " + jid + " from crypto targets of " + conversation.getName());
                                changed = true;
                            }
                        }
                        if (changed) {
                            conversation.setAcceptedCryptoTargets(cryptoTargets);
                            updateConversation(conversation);
                        }
                    }
                    getAvatarService().clear(conversation);
                    updateMucRosterUi();
                    updateConversationUi();
                }
            }
        };
        for (String affiliation : affiliations) {
            sendIqPacket(account, mIqGenerator.queryAffiliation(conversation, affiliation), callback);
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": fetching members for " + conversation.getName());
    }

    public void providePasswordForMuc(Conversation conversation, String password) {
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            conversation.getMucOptions().setPassword(password);
            if (conversation.getBookmark() != null) {
                final Bookmark bookmark = conversation.getBookmark();
                if (synchronizeWithBookmarks()) {
                    bookmark.setAutojoin(true);
                }
                createBookmark(conversation.getAccount(), bookmark);
            }
            updateConversation(conversation);
            joinMuc(conversation);
        }
    }

    private boolean hasEnabledAccounts() {
        if (this.accounts == null) {
            return false;
        }
        for (Account account : this.accounts) {
            if (account.isEnabled()) {
                return true;
            }
        }
        return false;
    }


    public void getAttachments(final Conversation conversation, int limit, final OnMediaLoaded onMediaLoaded) {
        getAttachments(conversation.getAccount(), conversation.getJid().asBareJid(), limit, onMediaLoaded);
    }

    public void getAttachments(final Account account, final Jid jid, final int limit, final OnMediaLoaded onMediaLoaded) {
        getAttachments(account.getUuid(), jid.asBareJid(), limit, onMediaLoaded);
    }


    public void getAttachments(final String account, final Jid jid, final int limit, final OnMediaLoaded onMediaLoaded) {
        new Thread(() -> onMediaLoaded.onMediaLoaded(fileBackend.convertToAttachments(databaseBackend.getRelativeFilePaths(account, jid, limit)))).start();
    }

    public void persistSelfNick(MucOptions.User self) {
        final Conversation conversation = self.getConversation();
        final boolean tookProposedNickFromBookmark = conversation.getMucOptions().isTookProposedNickFromBookmark();
        Jid full = self.getFullJid();
        if (!full.equals(conversation.getJid())) {
            Log.d(Config.LOGTAG, "nick changed. updating");
            conversation.setContactJid(full);
            databaseBackend.updateConversation(conversation);
        }

        final Bookmark bookmark = conversation.getBookmark();
        final String bookmarkedNick = bookmark == null ? null : bookmark.getNick();
        if (bookmark != null && (tookProposedNickFromBookmark || TextUtils.isEmpty(bookmarkedNick)) && !full.getResource().equals(bookmarkedNick)) {
            final Account account = conversation.getAccount();
            final String defaultNick = MucOptions.defaultNick(account);
            if (TextUtils.isEmpty(bookmarkedNick) && full.getResource().equals(defaultNick)) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": do not overwrite empty bookmark nick with default nick for " + conversation.getJid().asBareJid());
                return;
            }
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": persist nick '" + full.getResource() + "' into bookmark for " + conversation.getJid().asBareJid());
            bookmark.setNick(full.getResource());
            createBookmark(bookmark.getAccount(), bookmark);
        }
    }

    public boolean renameInMuc(final Conversation conversation, final String nick, final UiCallback<Conversation> callback) {
        final MucOptions options = conversation.getMucOptions();
        final Jid joinJid = options.createJoinJid(nick);
        if (joinJid == null) {
            return false;
        }
        if (options.online()) {
            Account account = conversation.getAccount();
            options.setOnRenameListener(new OnRenameListener() {

                @Override
                public void onSuccess() {
                    callback.success(conversation);
                }

                @Override
                public void onFailure() {
                    callback.error(R.string.nick_in_use, conversation);
                }
            });

            final PresencePacket packet = mPresenceGenerator.selfPresence(account, Presence.Status.ONLINE, options.nonanonymous());
            packet.setTo(joinJid);
            sendPresencePacket(account, packet);
        } else {
            conversation.setContactJid(joinJid);
            databaseBackend.updateConversation(conversation);
            if (conversation.getAccount().getStatus() == Account.State.ONLINE) {
                Bookmark bookmark = conversation.getBookmark();
                if (bookmark != null) {
                    bookmark.setNick(nick);
                    createBookmark(bookmark.getAccount(), bookmark);
                }
                joinMuc(conversation);
            }
        }
        return true;
    }

    public void leaveMuc(Conversation conversation) {
        leaveMuc(conversation, false);
    }

    private void leaveMuc(Conversation conversation, boolean now) {
        final Account account = conversation.getAccount();
        synchronized (account.pendingConferenceJoins) {
            account.pendingConferenceJoins.remove(conversation);
        }
        synchronized (account.pendingConferenceLeaves) {
            account.pendingConferenceLeaves.remove(conversation);
        }
        if (account.getStatus() == Account.State.ONLINE || now) {
            sendPresencePacket(conversation.getAccount(), mPresenceGenerator.leave(conversation.getMucOptions()));
            conversation.getMucOptions().setOffline();
            Bookmark bookmark = conversation.getBookmark();
            if (bookmark != null) {
                bookmark.setConversation(null);
            }
            Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": leaving muc " + conversation.getJid());
        } else {
            synchronized (account.pendingConferenceLeaves) {
                account.pendingConferenceLeaves.add(conversation);
            }
        }
    }

    public String findConferenceServer(final Account account) {
        String server;
        if (account.getXmppConnection() != null) {
            server = account.getXmppConnection().getMucServer();
            if (server != null) {
                return server;
            }
        }
        for (Account other : getAccounts()) {
            if (other != account && other.getXmppConnection() != null) {
                server = other.getXmppConnection().getMucServer();
                if (server != null) {
                    return server;
                }
            }
        }
        return null;
    }


    public void createPublicChannel(final Account account, final String name, final Jid address, final UiCallback<Conversation> callback) {
        joinMuc(findOrCreateConversation(account, address, true, false, true), conversation -> {
            final Bundle configuration = IqGenerator.defaultChannelConfiguration();
            if (!TextUtils.isEmpty(name)) {
                configuration.putString("muc#roomconfig_roomname", name);
            }
            pushConferenceConfiguration(conversation, configuration, new OnConfigurationPushed() {
                @Override
                public void onPushSucceeded() {
                    saveConversationAsBookmark(conversation, name);
                    callback.success(conversation);
                }

                @Override
                public void onPushFailed() {
                    if (conversation.getMucOptions().getSelf().getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                        callback.error(R.string.unable_to_set_channel_configuration, conversation);
                    } else {
                        callback.error(R.string.joined_an_existing_channel, conversation);
                    }
                }
            });
        });
    }

    public boolean createAdhocConference(final Account account,
                                         final String name,
                                         final Iterable<Jid> jids,
                                         final UiCallback<Conversation> callback) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": creating adhoc conference with " + jids.toString());
        if (account.getStatus() == Account.State.ONLINE) {
            try {
                String server = findConferenceServer(account);
                if (server == null) {
                    if (callback != null) {
                        callback.error(R.string.no_conference_server_found, null);
                    }
                    return false;
                }
                final Jid jid = Jid.of(CryptoHelper.pronounceable(getRNG()), server, null);
                final Conversation conversation = findOrCreateConversation(account, jid, true, false, true);
                joinMuc(conversation, new OnConferenceJoined() {
                    @Override
                    public void onConferenceJoined(final Conversation conversation) {
                        final Bundle configuration = IqGenerator.defaultGroupChatConfiguration();
                        if (!TextUtils.isEmpty(name)) {
                            configuration.putString("muc#roomconfig_roomname", name);
                        }
                        pushConferenceConfiguration(conversation, configuration, new OnConfigurationPushed() {
                            @Override
                            public void onPushSucceeded() {
                                for (Jid invite : jids) {
                                    invite(conversation, invite);
                                }
                                for (String resource : account.getSelfContact().getPresences().toResourceArray()) {
                                    Jid other = account.getJid().withResource(resource);
                                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": sending direct invite to " + other);
                                    directInvite(conversation, other);
                                }
                                saveConversationAsBookmark(conversation, name);
                                if (callback != null) {
                                    callback.success(conversation);
                                }
                            }

                            @Override
                            public void onPushFailed() {
                                archiveConversation(conversation);
                                if (callback != null) {
                                    callback.error(R.string.conference_creation_failed, conversation);
                                }
                            }
                        });
                    }
                });
                return true;
            } catch (IllegalArgumentException e) {
                if (callback != null) {
                    callback.error(R.string.conference_creation_failed, null);
                }
                return false;
            }
        } else {
            if (callback != null) {
                callback.error(R.string.not_connected_try_again, null);
            }
            return false;
        }
    }

    public void fetchConferenceConfiguration(final Conversation conversation) {
        fetchConferenceConfiguration(conversation, null);
    }

    public void fetchConferenceConfiguration(final Conversation conversation, final OnConferenceConfigurationFetched callback) {
        IqPacket request = mIqGenerator.queryDiscoInfo(conversation.getJid().asBareJid());
        sendIqPacket(conversation.getAccount(), request, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                if (packet.getType() == IqPacket.TYPE.RESULT) {
                    final MucOptions mucOptions = conversation.getMucOptions();
                    final Bookmark bookmark = conversation.getBookmark();
                    final boolean sameBefore = StringUtils.equals(bookmark == null ? null : bookmark.getBookmarkName(), mucOptions.getName());

                    if (mucOptions.updateConfiguration(new ServiceDiscoveryResult(packet))) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": muc configuration changed for " + conversation.getJid().asBareJid());
                        updateConversation(conversation);
                    }

                    if (bookmark != null && (sameBefore || bookmark.getBookmarkName() == null)) {
                        if (bookmark.setBookmarkName(StringUtils.nullOnEmpty(mucOptions.getName()))) {
                            createBookmark(account, bookmark);
                        }
                    }


                    if (callback != null) {
                        callback.onConferenceConfigurationFetched(conversation);
                    }


                    updateConversationUi();
                } else if (packet.getType() == IqPacket.TYPE.TIMEOUT) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": received timeout waiting for conference configuration fetch");
                } else {
                    if (callback != null) {
                        callback.onFetchFailed(conversation, packet.getErrorCondition());
                    }
                }
            }
        });
    }

    public void pushNodeConfiguration(Account account, final String node, final Bundle options, final OnConfigurationPushed callback) {
        pushNodeConfiguration(account, account.getJid().asBareJid(), node, options, callback);
    }

    public void pushNodeConfiguration(Account account, final Jid jid, final String node, final Bundle options, final OnConfigurationPushed callback) {
        Log.d(Config.LOGTAG, "pushing node configuration");
        sendIqPacket(account, mIqGenerator.requestPubsubConfiguration(jid, node), new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                if (packet.getType() == IqPacket.TYPE.RESULT) {
                    Element pubsub = packet.findChild("pubsub", "http://jabber.org/protocol/pubsub#owner");
                    Element configuration = pubsub == null ? null : pubsub.findChild("configure");
                    Element x = configuration == null ? null : configuration.findChild("x", Namespace.DATA);
                    if (x != null) {
                        Data data = Data.parse(x);
                        data.submit(options);
                        sendIqPacket(account, mIqGenerator.publishPubsubConfiguration(jid, node, data), new OnIqPacketReceived() {
                            @Override
                            public void onIqPacketReceived(Account account, IqPacket packet) {
                                if (packet.getType() == IqPacket.TYPE.RESULT && callback != null) {
                                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": successfully changed node configuration for node " + node);
                                    callback.onPushSucceeded();
                                } else if (packet.getType() == IqPacket.TYPE.ERROR && callback != null) {
                                    callback.onPushFailed();
                                }
                            }
                        });
                    } else if (callback != null) {
                        callback.onPushFailed();
                    }
                } else if (packet.getType() == IqPacket.TYPE.ERROR && callback != null) {
                    callback.onPushFailed();
                }
            }
        });
    }

    public void pushConferenceConfiguration(final Conversation conversation, final Bundle options, final OnConfigurationPushed callback) {
        if (options.getString("muc#roomconfig_whois", "moderators").equals("anyone")) {
            conversation.setAttribute("accept_non_anonymous", true);
            updateConversation(conversation);
        }
        if (options.containsKey("muc#roomconfig_moderatedroom")) {
            final boolean moderated = "1".equals(options.getString("muc#roomconfig_moderatedroom"));
            options.putString("members_by_default", moderated ? "0" : "1");
        }
        final IqPacket request = new IqPacket(IqPacket.TYPE.GET);
        request.setTo(conversation.getJid().asBareJid());
        request.query("http://jabber.org/protocol/muc#owner");
        sendIqPacket(conversation.getAccount(), request, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                if (packet.getType() == IqPacket.TYPE.RESULT) {
                    final Data data = Data.parse(packet.query().findChild("x", Namespace.DATA));
                    data.submit(options);
                    final IqPacket set = new IqPacket(IqPacket.TYPE.SET);
                    set.setTo(conversation.getJid().asBareJid());
                    set.query("http://jabber.org/protocol/muc#owner").addChild(data);
                    sendIqPacket(account, set, new OnIqPacketReceived() {
                        @Override
                        public void onIqPacketReceived(Account account, IqPacket packet) {
                            if (callback != null) {
                                if (packet.getType() == IqPacket.TYPE.RESULT) {
                                    callback.onPushSucceeded();
                                } else {
                                    callback.onPushFailed();
                                }
                            }
                        }
                    });
                } else {
                    if (callback != null) {
                        callback.onPushFailed();
                    }
                }
            }
        });
    }

    public void pushSubjectToConference(final Conversation conference, final String subject) {
        MessagePacket packet = this.getMessageGenerator().conferenceSubject(conference, StringUtils.nullOnEmpty(subject));
        this.sendMessagePacket(conference.getAccount(), packet);
    }

    public void changeAffiliationInConference(final Conversation conference, Jid user, final MucOptions.Affiliation affiliation, final OnAffiliationChanged callback) {
        final Jid jid = user.asBareJid();
        final IqPacket request = this.mIqGenerator.changeAffiliation(conference, jid, affiliation.toString());
        sendIqPacket(conference.getAccount(), request, (account, response) -> {
            if (response.getType() == IqPacket.TYPE.RESULT) {
                conference.getMucOptions().changeAffiliation(jid, affiliation);
                getAvatarService().clear(conference);
                if (callback != null) {
                    callback.onAffiliationChangedSuccessful(jid);
                } else {
                    Log.d(Config.LOGTAG, "changed affiliation of " + user + " to " + affiliation);
                }
            } else if (callback != null) {
                callback.onAffiliationChangeFailed(jid, R.string.could_not_change_affiliation);
            } else {
                Log.d(Config.LOGTAG, "unable to change affiliation");
            }
        });
    }

    public void changeRoleInConference(final Conversation conference, final String nick, MucOptions.Role role) {
        IqPacket request = this.mIqGenerator.changeRole(conference, nick, role.toString());
        sendIqPacket(conference.getAccount(), request, (account, packet) -> {
            if (packet.getType() != IqPacket.TYPE.RESULT) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + " unable to change role of " + nick);
            }
        });
    }

    public void destroyRoom(final Conversation conversation, final OnRoomDestroy callback) {
        IqPacket request = new IqPacket(IqPacket.TYPE.SET);
        request.setTo(conversation.getJid().asBareJid());
        request.query("http://jabber.org/protocol/muc#owner").addChild("destroy");
        sendIqPacket(conversation.getAccount(), request, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                if (packet.getType() == IqPacket.TYPE.RESULT) {
                    if (callback != null) {
                        callback.onRoomDestroySucceeded();
                    }
                } else if (packet.getType() == IqPacket.TYPE.ERROR) {
                    if (callback != null) {
                        callback.onRoomDestroyFailed();
                    }
                }
            }
        });
    }

    private void disconnect(Account account, boolean force) {
        if ((account.getStatus() == Account.State.ONLINE)
                || (account.getStatus() == Account.State.DISABLED)) {
            final XmppConnection connection = account.getXmppConnection();
            if (!force) {
                List<Conversation> conversations = getConversations();
                for (Conversation conversation : conversations) {
                    if (conversation.getAccount() == account) {
                        if (conversation.getMode() == Conversation.MODE_MULTI) {
                            leaveMuc(conversation, true);
                        }
                    }
                }
                sendOfflinePresence(account);
            }
            connection.disconnect(force);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void updateMessage(Message message) {
        updateMessage(message, true);
    }

    public void updateMessage(Message message, boolean includeBody) {
        databaseBackend.updateMessage(message, includeBody);
        updateConversationUi();
    }

    public void createMessageAsync(final Message message) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.createMessage(message));
    }

    public void updateMessage(Message message, String uuid) {
        if (!databaseBackend.updateMessage(message, uuid)) {
            Log.e(Config.LOGTAG, "error updated message in DB after edit");
        }
        updateConversationUi();
    }

    protected void syncDirtyContacts(Account account) {
        for (Contact contact : account.getRoster().getContacts()) {
            if (contact.getOption(Contact.Options.DIRTY_PUSH)) {
                pushContactToServer(contact);
            }
            if (contact.getOption(Contact.Options.DIRTY_DELETE)) {
                deleteContactOnServer(contact);
            }
        }
    }

    public void createContact(final Contact contact, final boolean autoGrant) {
        createContact(contact, autoGrant, null);
    }

    public void createContact(final Contact contact, final boolean autoGrant, final String preAuth) {
        if (autoGrant) {
            contact.setOption(Contact.Options.PREEMPTIVE_GRANT);
            contact.setOption(Contact.Options.ASKING);
        }
        pushContactToServer(contact, preAuth);
    }

    public void pushContactToServer(final Contact contact) {
        pushContactToServer(contact, null);
    }

    private void pushContactToServer(final Contact contact, final String preAuth) {
        contact.resetOption(Contact.Options.DIRTY_DELETE);
        contact.setOption(Contact.Options.DIRTY_PUSH);
        final Account account = contact.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            final boolean ask = contact.getOption(Contact.Options.ASKING);
            final boolean sendUpdates = contact
                    .getOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST)
                    && contact.getOption(Contact.Options.PREEMPTIVE_GRANT);
            final IqPacket iq = new IqPacket(IqPacket.TYPE.SET);
            iq.query(Namespace.ROSTER).addChild(contact.asElement());
            account.getXmppConnection().sendIqPacket(iq, mDefaultIqHandler);
            if (sendUpdates) {
                sendPresencePacket(account, mPresenceGenerator.sendPresenceUpdatesTo(contact));
            }
            if (ask) {
                sendPresencePacket(account, mPresenceGenerator.requestPresenceUpdatesFrom(contact, preAuth));
            }
        } else {
            syncRoster(contact.getAccount());
        }
    }

    public void publishMucAvatar(final Conversation conversation, final Uri image, final OnAvatarPublication callback) {
        new Thread(() -> {
            final Bitmap.CompressFormat format = Config.AVATAR_FORMAT;
            final int size = Config.AVATAR_SIZE;
            final Avatar avatar = getFileBackend().getPepAvatar(image, size, format);
            if (avatar != null) {
                if (!getFileBackend().save(avatar)) {
                    callback.onAvatarPublicationFailed(R.string.error_saving_avatar);
                    return;
                }
                avatar.owner = conversation.getJid().asBareJid();
                publishMucAvatar(conversation, avatar, callback);
            } else {
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting);
            }
        }).start();
    }

    public void publishAvatar(final Account account, final Uri image, final OnAvatarPublication callback) {
        new Thread(() -> {
            final Bitmap.CompressFormat format = Config.AVATAR_FORMAT;
            final int size = Config.AVATAR_SIZE;
            final Avatar avatar = getFileBackend().getPepAvatar(image, size, format);
            if (avatar != null) {
                if (!getFileBackend().save(avatar)) {
                    Log.d(Config.LOGTAG, "unable to save vcard");
                    callback.onAvatarPublicationFailed(R.string.error_saving_avatar);
                    return;
                }
                publishAvatar(account, avatar, callback);
            } else {
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_converting);
            }
        }).start();

    }

    private void publishMucAvatar(Conversation conversation, Avatar avatar, OnAvatarPublication callback) {
        final IqPacket retrieve = mIqGenerator.retrieveVcardAvatar(avatar);
        sendIqPacket(conversation.getAccount(), retrieve, (account, response) -> {
            boolean itemNotFound = response.getType() == IqPacket.TYPE.ERROR && response.hasChild("error") && response.findChild("error").hasChild("item-not-found");
            if (response.getType() == IqPacket.TYPE.RESULT || itemNotFound) {
                Element vcard = response.findChild("vCard", "vcard-temp");
                if (vcard == null) {
                    vcard = new Element("vCard", "vcard-temp");
                }
                Element photo = vcard.findChild("PHOTO");
                if (photo == null) {
                    photo = vcard.addChild("PHOTO");
                }
                photo.clearChildren();
                photo.addChild("TYPE").setContent(avatar.type);
                photo.addChild("BINVAL").setContent(avatar.image);
                IqPacket publication = new IqPacket(IqPacket.TYPE.SET);
                publication.setTo(conversation.getJid().asBareJid());
                publication.addChild(vcard);
                sendIqPacket(account, publication, (a1, publicationResponse) -> {
                    if (publicationResponse.getType() == IqPacket.TYPE.RESULT) {
                        callback.onAvatarPublicationSucceeded();
                    } else {
                        Log.d(Config.LOGTAG, "failed to publish vcard " + publicationResponse.getErrorCondition());
                        callback.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject);
                    }
                });
            } else {
                Log.d(Config.LOGTAG, "failed to request vcard " + response.toString());
                callback.onAvatarPublicationFailed(R.string.error_publish_avatar_no_server_support);
            }
        });
    }

    public void publishAvatar(Account account, final Avatar avatar, final OnAvatarPublication callback) {
        final Bundle options;
        if (account.getXmppConnection().getFeatures().pepPublishOptions()) {
            options = PublishOptions.openAccess();
        } else {
            options = null;
        }
        publishAvatar(account, avatar, options, true, callback);
    }

    public void publishAvatar(Account account, final Avatar avatar, final Bundle options, final boolean retry, final OnAvatarPublication callback) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": publishing avatar. options=" + options);
        IqPacket packet = this.mIqGenerator.publishAvatar(avatar, options);
        this.sendIqPacket(account, packet, new OnIqPacketReceived() {

            @Override
            public void onIqPacketReceived(Account account, IqPacket result) {
                if (result.getType() == IqPacket.TYPE.RESULT) {
                    publishAvatarMetadata(account, avatar, options, true, callback);
                } else if (retry && PublishOptions.preconditionNotMet(result)) {
                    pushNodeConfiguration(account, "urn:xmpp:avatar:data", options, new OnConfigurationPushed() {
                        @Override
                        public void onPushSucceeded() {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": changed node configuration for avatar node");
                            publishAvatar(account, avatar, options, false, callback);
                        }

                        @Override
                        public void onPushFailed() {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to change node configuration for avatar node");
                            publishAvatar(account, avatar, null, false, callback);
                        }
                    });
                } else {
                    Element error = result.findChild("error");
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": server rejected avatar " + (avatar.size / 1024) + "KiB " + (error != null ? error.toString() : ""));
                    if (callback != null) {
                        callback.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject);
                    }
                }
            }
        });
    }

    public void publishAvatarMetadata(Account account, final Avatar avatar, final Bundle options, final boolean retry, final OnAvatarPublication callback) {
        final IqPacket packet = XmppConnectionService.this.mIqGenerator.publishAvatarMetadata(avatar, options);
        sendIqPacket(account, packet, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket result) {
                if (result.getType() == IqPacket.TYPE.RESULT) {
                    if (account.setAvatar(avatar.getFilename())) {
                        getAvatarService().clear(account);
                        databaseBackend.updateAccount(account);
                        notifyAccountAvatarHasChanged(account);
                    }
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": published avatar " + (avatar.size / 1024) + "KiB");
                    if (callback != null) {
                        callback.onAvatarPublicationSucceeded();
                    }
                } else if (retry && PublishOptions.preconditionNotMet(result)) {
                    pushNodeConfiguration(account, "urn:xmpp:avatar:metadata", options, new OnConfigurationPushed() {
                        @Override
                        public void onPushSucceeded() {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": changed node configuration for avatar meta data node");
                            publishAvatarMetadata(account, avatar, options, false, callback);
                        }

                        @Override
                        public void onPushFailed() {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to change node configuration for avatar meta data node");
                            publishAvatarMetadata(account, avatar, null, false, callback);
                        }
                    });
                } else {
                    if (callback != null) {
                        callback.onAvatarPublicationFailed(R.string.error_publish_avatar_server_reject);
                    }
                }
            }
        });
    }

    public void republishAvatarIfNeeded(Account account) {
        if (account.getAxolotlService().isPepBroken()) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": skipping republication of avatar because pep is broken");
            return;
        }
        IqPacket packet = this.mIqGenerator.retrieveAvatarMetaData(null);
        this.sendIqPacket(account, packet, new OnIqPacketReceived() {

            private Avatar parseAvatar(IqPacket packet) {
                Element pubsub = packet.findChild("pubsub", "http://jabber.org/protocol/pubsub");
                if (pubsub != null) {
                    Element items = pubsub.findChild("items");
                    if (items != null) {
                        return Avatar.parseMetadata(items);
                    }
                }
                return null;
            }

            private boolean errorIsItemNotFound(IqPacket packet) {
                Element error = packet.findChild("error");
                return packet.getType() == IqPacket.TYPE.ERROR
                        && error != null
                        && error.hasChild("item-not-found");
            }

            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                if (packet.getType() == IqPacket.TYPE.RESULT || errorIsItemNotFound(packet)) {
                    Avatar serverAvatar = parseAvatar(packet);
                    if (serverAvatar == null && account.getAvatar() != null) {
                        Avatar avatar = fileBackend.getStoredPepAvatar(account.getAvatar());
                        if (avatar != null) {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": avatar on server was null. republishing");
                            publishAvatar(account, fileBackend.getStoredPepAvatar(account.getAvatar()), null);
                        } else {
                            Log.e(Config.LOGTAG, account.getJid().asBareJid() + ": error rereading avatar");
                        }
                    }
                }
            }
        });
    }

    public void fetchAvatar(Account account, Avatar avatar) {
        fetchAvatar(account, avatar, null);
    }

    public void fetchAvatar(Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        final String KEY = generateFetchKey(account, avatar);
        synchronized (this.mInProgressAvatarFetches) {
            if (mInProgressAvatarFetches.add(KEY)) {
                switch (avatar.origin) {
                    case PEP:
                        this.mInProgressAvatarFetches.add(KEY);
                        fetchAvatarPep(account, avatar, callback);
                        break;
                    case VCARD:
                        this.mInProgressAvatarFetches.add(KEY);
                        fetchAvatarVcard(account, avatar, callback);
                        break;
                }
            } else if (avatar.origin == Avatar.Origin.PEP) {
                mOmittedPepAvatarFetches.add(KEY);
            } else {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": already fetching " + avatar.origin + " avatar for " + avatar.owner);
            }
        }
    }

    private void fetchAvatarPep(Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        IqPacket packet = this.mIqGenerator.retrievePepAvatar(avatar);
        sendIqPacket(account, packet, (a, result) -> {
            synchronized (mInProgressAvatarFetches) {
                mInProgressAvatarFetches.remove(generateFetchKey(a, avatar));
            }
            final String ERROR = a.getJid().asBareJid() + ": fetching avatar for " + avatar.owner + " failed ";
            if (result.getType() == IqPacket.TYPE.RESULT) {
                avatar.image = mIqParser.avatarData(result);
                if (avatar.image != null) {
                    if (getFileBackend().save(avatar)) {
                        if (a.getJid().asBareJid().equals(avatar.owner)) {
                            if (a.setAvatar(avatar.getFilename())) {
                                databaseBackend.updateAccount(a);
                            }
                            getAvatarService().clear(a);
                            updateConversationUi();
                            updateAccountUi();
                        } else {
                            final Contact contact = a.getRoster().getContact(avatar.owner);
                            contact.setAvatar(avatar);
                            syncRoster(account);
                            getAvatarService().clear(contact);
                            updateConversationUi();
                            updateRosterUi();
                        }
                        if (callback != null) {
                            callback.success(avatar);
                        }
                        Log.d(Config.LOGTAG, a.getJid().asBareJid() + ": successfully fetched pep avatar for " + avatar.owner);
                        return;
                    }
                } else {

                    Log.d(Config.LOGTAG, ERROR + "(parsing error)");
                }
            } else {
                Element error = result.findChild("error");
                if (error == null) {
                    Log.d(Config.LOGTAG, ERROR + "(server error)");
                } else {
                    Log.d(Config.LOGTAG, ERROR + error.toString());
                }
            }
            if (callback != null) {
                callback.error(0, null);
            }

        });
    }

    private void fetchAvatarVcard(final Account account, final Avatar avatar, final UiCallback<Avatar> callback) {
        IqPacket packet = this.mIqGenerator.retrieveVcardAvatar(avatar);
        this.sendIqPacket(account, packet, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                final boolean previouslyOmittedPepFetch;
                synchronized (mInProgressAvatarFetches) {
                    final String KEY = generateFetchKey(account, avatar);
                    mInProgressAvatarFetches.remove(KEY);
                    previouslyOmittedPepFetch = mOmittedPepAvatarFetches.remove(KEY);
                }
                if (packet.getType() == IqPacket.TYPE.RESULT) {
                    Element vCard = packet.findChild("vCard", "vcard-temp");
                    Element photo = vCard != null ? vCard.findChild("PHOTO") : null;
                    String image = photo != null ? photo.findChildContent("BINVAL") : null;
                    if (image != null) {
                        avatar.image = image;
                        if (getFileBackend().save(avatar)) {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid()
                                    + ": successfully fetched vCard avatar for " + avatar.owner + " omittedPep=" + previouslyOmittedPepFetch);
                            if (avatar.owner.isBareJid()) {
                                if (account.getJid().asBareJid().equals(avatar.owner) && account.getAvatar() == null) {
                                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": had no avatar. replacing with vcard");
                                    account.setAvatar(avatar.getFilename());
                                    databaseBackend.updateAccount(account);
                                    getAvatarService().clear(account);
                                    updateAccountUi();
                                } else {
                                    final Contact contact = account.getRoster().getContact(avatar.owner);
                                    contact.setAvatar(avatar, previouslyOmittedPepFetch);
                                    syncRoster(account);
                                    getAvatarService().clear(contact);
                                    updateRosterUi();
                                }
                                updateConversationUi();
                            } else {
                                Conversation conversation = find(account, avatar.owner.asBareJid());
                                if (conversation != null && conversation.getMode() == Conversation.MODE_MULTI) {
                                    MucOptions.User user = conversation.getMucOptions().findUserByFullJid(avatar.owner);
                                    if (user != null) {
                                        if (user.setAvatar(avatar)) {
                                            getAvatarService().clear(user);
                                            updateConversationUi();
                                            updateMucRosterUi();
                                        }
                                        if (user.getRealJid() != null) {
                                            Contact contact = account.getRoster().getContact(user.getRealJid());
                                            contact.setAvatar(avatar);
                                            syncRoster(account);
                                            getAvatarService().clear(contact);
                                            updateRosterUi();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    public void checkForAvatar(Account account, final UiCallback<Avatar> callback) {
        IqPacket packet = this.mIqGenerator.retrieveAvatarMetaData(null);
        this.sendIqPacket(account, packet, new OnIqPacketReceived() {

            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                if (packet.getType() == IqPacket.TYPE.RESULT) {
                    Element pubsub = packet.findChild("pubsub", "http://jabber.org/protocol/pubsub");
                    if (pubsub != null) {
                        Element items = pubsub.findChild("items");
                        if (items != null) {
                            Avatar avatar = Avatar.parseMetadata(items);
                            if (avatar != null) {
                                avatar.owner = account.getJid().asBareJid();
                                if (fileBackend.isAvatarCached(avatar)) {
                                    if (account.setAvatar(avatar.getFilename())) {
                                        databaseBackend.updateAccount(account);
                                    }
                                    getAvatarService().clear(account);
                                    callback.success(avatar);
                                } else {
                                    fetchAvatarPep(account, avatar, callback);
                                }
                                return;
                            }
                        }
                    }
                }
                callback.error(0, null);
            }
        });
    }

    public void notifyAccountAvatarHasChanged(final Account account) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null && connection.getFeatures().bookmarksConversion()) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": avatar changed. resending presence to online group chats");
            for (Conversation conversation : conversations) {
                if (conversation.getAccount() == account && conversation.getMode() == Conversational.MODE_MULTI) {
                    final MucOptions mucOptions = conversation.getMucOptions();
                    if (mucOptions.online()) {
                        PresencePacket packet = mPresenceGenerator.selfPresence(account, Presence.Status.ONLINE, mucOptions.nonanonymous());
                        packet.setTo(mucOptions.getSelf().getFullJid());
                        connection.sendPresencePacket(packet);
                    }
                }
            }
        }
    }

    public void deleteContactOnServer(Contact contact) {
        contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
        contact.resetOption(Contact.Options.DIRTY_PUSH);
        contact.setOption(Contact.Options.DIRTY_DELETE);
        Account account = contact.getAccount();
        if (account.getStatus() == Account.State.ONLINE) {
            IqPacket iq = new IqPacket(IqPacket.TYPE.SET);
            Element item = iq.query(Namespace.ROSTER).addChild("item");
            item.setAttribute("jid", contact.getJid());
            item.setAttribute("subscription", "remove");
            account.getXmppConnection().sendIqPacket(iq, mDefaultIqHandler);
        }
    }

    public void updateConversation(final Conversation conversation) {
        mDatabaseWriterExecutor.execute(() -> databaseBackend.updateConversation(conversation));
    }

    private void reconnectAccount(final Account account, final boolean force, final boolean interactive) {
        synchronized (account) {
            XmppConnection connection = account.getXmppConnection();
            if (connection == null) {
                connection = createConnection(account);
                account.setXmppConnection(connection);
            }
            boolean hasInternet = hasInternetConnection();
            if (account.isEnabled() && hasInternet) {
                if (!force) {
                    disconnect(account, false);
                }
                Thread thread = new Thread(connection);
                connection.setInteractive(interactive);
                connection.prepareNewConnection();
                connection.interrupt();
                thread.start();
                scheduleWakeUpCall(Config.CONNECT_DISCO_TIMEOUT, account.getUuid().hashCode());
            } else {
                disconnect(account, force || account.getTrueStatus().isError() || !hasInternet);
                account.getRoster().clearPresences();
                connection.resetEverything();
                final AxolotlService axolotlService = account.getAxolotlService();
                if (axolotlService != null) {
                    axolotlService.resetBrokenness();
                }
                if (!hasInternet) {
                    account.setStatus(Account.State.NO_INTERNET);
                }
            }
        }
    }

    public void reconnectAccountInBackground(final Account account) {
        new Thread(() -> reconnectAccount(account, false, true)).start();
    }

    public void invite(final Conversation conversation, final Jid contact) {
        Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": inviting " + contact + " to " + conversation.getJid().asBareJid());
        final MucOptions.User user = conversation.getMucOptions().findUserByRealJid(contact.asBareJid());
        if (user == null || user.getAffiliation() == MucOptions.Affiliation.OUTCAST) {
            changeAffiliationInConference(conversation, contact, MucOptions.Affiliation.NONE, null);
        }
        final MessagePacket packet = mMessageGenerator.invite(conversation, contact);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    public void directInvite(Conversation conversation, Jid jid) {
        MessagePacket packet = mMessageGenerator.directInvite(conversation, jid);
        sendMessagePacket(conversation.getAccount(), packet);
    }

    public void resetSendingToWaiting(Account account) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getAccount() == account) {
                conversation.findUnsentTextMessages(message -> markMessage(message, Message.STATUS_WAITING));
            }
        }
    }

    public Message markMessage(final Account account, final Jid recipient, final String uuid, final int status) {
        return markMessage(account, recipient, uuid, status, null);
    }

    public Message markMessage(final Account account, final Jid recipient, final String uuid, final int status, String errorMessage) {
        if (uuid == null) {
            return null;
        }
        for (Conversation conversation : getConversations()) {
            if (conversation.getJid().asBareJid().equals(recipient) && conversation.getAccount() == account) {
                final Message message = conversation.findSentMessageWithUuidOrRemoteId(uuid);
                if (message != null) {
                    markMessage(message, status, errorMessage);
                }
                return message;
            }
        }
        return null;
    }

    public boolean markMessage(final Conversation conversation, final String uuid, final int status, final String serverMessageId) {
        return markMessage(conversation, uuid, status, serverMessageId, null);
    }

    public boolean markMessage(final Conversation conversation, final String uuid, final int status, final String serverMessageId, final LocalizedContent body) {
        if (uuid == null) {
            return false;
        } else {
            final Message message = conversation.findSentMessageWithUuid(uuid);
            if (message != null) {
                if (message.getServerMsgId() == null) {
                    message.setServerMsgId(serverMessageId);
                }
                if (message.getEncryption() == Message.ENCRYPTION_NONE
                        && message.isTypeText()
                        && isBodyModified(message, body)) {
                    message.setBody(body.content);
                    if (body.count > 1) {
                        message.setBodyLanguage(body.language);
                    }
                    markMessage(message, status, null, true);
                } else {
                    markMessage(message, status);
                }
                return true;
            } else {
                return false;
            }
        }
    }

    private static boolean isBodyModified(final Message message, final LocalizedContent body) {
        if (body == null || body.content == null) {
            return false;
        }
        return !body.content.equals(message.getBody());
    }

    public void markMessage(Message message, int status) {
        markMessage(message, status, null);
    }


    public void markMessage(final Message message, final int status, final String errorMessage) {
        markMessage(message, status, errorMessage, false);
    }

    public void markMessage(final Message message, final int status, final String errorMessage, final boolean includeBody) {
        final int oldStatus = message.getStatus();
        if (status == Message.STATUS_SEND_FAILED && (oldStatus == Message.STATUS_SEND_RECEIVED || oldStatus == Message.STATUS_SEND_DISPLAYED)) {
            return;
        }
        if (status == Message.STATUS_SEND_RECEIVED && oldStatus == Message.STATUS_SEND_DISPLAYED) {
            return;
        }
        message.setErrorMessage(errorMessage);
        message.setStatus(status);
        databaseBackend.updateMessage(message, includeBody);
        updateConversationUi();
        if (oldStatus != status && status == Message.STATUS_SEND_FAILED) {
            mNotificationService.pushFailedDelivery(message);
        }
    }

    private SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    }

    public long getAutomaticMessageDeletionDate() {
        final long timeout = getLongPreference(SettingsActivity.AUTOMATIC_MESSAGE_DELETION, R.integer.automatic_message_deletion);
        return timeout == 0 ? timeout : (System.currentTimeMillis() - (timeout * 1000));
    }

    public long getLongPreference(String name, @IntegerRes int res) {
        long defaultValue = getResources().getInteger(res);
        try {
            return Long.parseLong(getPreferences().getString(name, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBooleanPreference(String name, @BoolRes int res) {
        return getPreferences().getBoolean(name, getResources().getBoolean(res));
    }

    public boolean confirmMessages() {
        return getBooleanPreference("confirm_messages", R.bool.confirm_messages);
    }

    public boolean allowMessageCorrection() {
        return getBooleanPreference("allow_message_correction", R.bool.allow_message_correction);
    }

    public boolean sendChatStates() {
        return getBooleanPreference("chat_states", R.bool.chat_states);
    }

    private boolean synchronizeWithBookmarks() {
        return getBooleanPreference("autojoin", R.bool.autojoin);
    }

    public boolean useTorToConnect() {
        return QuickConversationsService.isConversations() && getBooleanPreference("use_tor", R.bool.use_tor);
    }

    public boolean showExtendedConnectionOptions() {
        return QuickConversationsService.isConversations() && getBooleanPreference("show_connection_options", R.bool.show_connection_options);
    }

    public boolean broadcastLastActivity() {
        return getBooleanPreference(SettingsActivity.BROADCAST_LAST_ACTIVITY, R.bool.last_activity);
    }

    public int unreadCount() {
        int count = 0;
        for (Conversation conversation : getConversations()) {
            count += conversation.unreadCount();
        }
        return count;
    }


    private <T> List<T> threadSafeList(Set<T> set) {
        synchronized (LISTENER_LOCK) {
            return set.size() == 0 ? Collections.emptyList() : new ArrayList<>(set);
        }
    }

    public void showErrorToastInUi(int resId) {
        for (OnShowErrorToast listener : threadSafeList(this.mOnShowErrorToasts)) {
            listener.onShowErrorToast(resId);
        }
    }

    public void updateConversationUi() {
        for (OnConversationUpdate listener : threadSafeList(this.mOnConversationUpdates)) {
            listener.onConversationUpdate();
        }
    }

    public void notifyJingleRtpConnectionUpdate(final Account account, final Jid with, final String sessionId, final RtpEndUserState state) {
        for (OnJingleRtpConnectionUpdate listener : threadSafeList(this.onJingleRtpConnectionUpdate)) {
            listener.onJingleRtpConnectionUpdate(account, with, sessionId, state);
        }
    }

    public void notifyJingleRtpConnectionUpdate(AppRTCAudioManager.AudioDevice selectedAudioDevice, Set<AppRTCAudioManager.AudioDevice> availableAudioDevices) {
        for (OnJingleRtpConnectionUpdate listener : threadSafeList(this.onJingleRtpConnectionUpdate)) {
            listener.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices);
        }
    }

    public void updateAccountUi() {
        for (OnAccountUpdate listener : threadSafeList(this.mOnAccountUpdates)) {
            listener.onAccountUpdate();
        }
    }

    public void updateRosterUi() {
        for (OnRosterUpdate listener : threadSafeList(this.mOnRosterUpdates)) {
            listener.onRosterUpdate();
        }
    }

    public boolean displayCaptchaRequest(Account account, String id, Data data, Bitmap captcha) {
        if (mOnCaptchaRequested.size() > 0) {
            DisplayMetrics metrics = getApplicationContext().getResources().getDisplayMetrics();
            Bitmap scaled = Bitmap.createScaledBitmap(captcha, (int) (captcha.getWidth() * metrics.scaledDensity),
                    (int) (captcha.getHeight() * metrics.scaledDensity), false);
            for (OnCaptchaRequested listener : threadSafeList(this.mOnCaptchaRequested)) {
                listener.onCaptchaRequested(account, id, data, scaled);
            }
            return true;
        }
        return false;
    }

    public void updateBlocklistUi(final OnUpdateBlocklist.Status status) {
        for (OnUpdateBlocklist listener : threadSafeList(this.mOnUpdateBlocklist)) {
            listener.OnUpdateBlocklist(status);
        }
    }

    public void updateMucRosterUi() {
        for (OnMucRosterUpdate listener : threadSafeList(this.mOnMucRosterUpdate)) {
            listener.onMucRosterUpdate();
        }
    }

    public void keyStatusUpdated(AxolotlService.FetchStatus report) {
        for (OnKeyStatusUpdated listener : threadSafeList(this.mOnKeyStatusUpdated)) {
            listener.onKeyStatusUpdated(report);
        }
    }

    public Account findAccountByJid(final Jid jid) {
        for (final Account account : this.accounts) {
            if (account.getJid().asBareJid().equals(jid.asBareJid())) {
                return account;
            }
        }
        return null;
    }

    public Account findAccountByUuid(final String uuid) {
        for (Account account : this.accounts) {
            if (account.getUuid().equals(uuid)) {
                return account;
            }
        }
        return null;
    }

    public Conversation findConversationByUuid(String uuid) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getUuid().equals(uuid)) {
                return conversation;
            }
        }
        return null;
    }

    public Conversation findUniqueConversationByJid(XmppUri xmppUri) {
        List<Conversation> findings = new ArrayList<>();
        for (Conversation c : getConversations()) {
            if (c.getAccount().isEnabled() && c.getJid().asBareJid().equals(xmppUri.getJid()) && ((c.getMode() == Conversational.MODE_MULTI) == xmppUri.isAction(XmppUri.ACTION_JOIN))) {
                findings.add(c);
            }
        }
        return findings.size() == 1 ? findings.get(0) : null;
    }

    public boolean markRead(final Conversation conversation, boolean dismiss) {
        return markRead(conversation, null, dismiss).size() > 0;
    }

    public void markRead(final Conversation conversation) {
        markRead(conversation, null, true);
    }

    public List<Message> markRead(final Conversation conversation, String upToUuid, boolean dismiss) {
        if (dismiss) {
            mNotificationService.clear(conversation);
        }
        final List<Message> readMessages = conversation.markRead(upToUuid);
        if (readMessages.size() > 0) {
            Runnable runnable = () -> {
                for (Message message : readMessages) {
                    databaseBackend.updateMessage(message, false);
                }
            };
            mDatabaseWriterExecutor.execute(runnable);
            updateConversationUi();
            updateUnreadCountBadge();
            return readMessages;
        } else {
            return readMessages;
        }
    }

    public synchronized void updateUnreadCountBadge() {
        int count = unreadCount();
        if (unreadCount != count) {
            Log.d(Config.LOGTAG, "update unread count to " + count);
            if (count > 0) {
                ShortcutBadger.applyCount(getApplicationContext(), count);
            } else {
                ShortcutBadger.removeCount(getApplicationContext());
            }
            unreadCount = count;
        }
    }

    public void sendReadMarker(final Conversation conversation, String upToUuid) {
        final boolean isPrivateAndNonAnonymousMuc = conversation.getMode() == Conversation.MODE_MULTI && conversation.isPrivateAndNonAnonymous();
        final List<Message> readMessages = this.markRead(conversation, upToUuid, true);
        if (readMessages.size() > 0) {
            updateConversationUi();
        }
        final Message markable = Conversation.getLatestMarkableMessage(readMessages, isPrivateAndNonAnonymousMuc);
        if (confirmMessages()
                && markable != null
                && (markable.trusted() || isPrivateAndNonAnonymousMuc)
                && markable.getRemoteMsgId() != null) {
            Log.d(Config.LOGTAG, conversation.getAccount().getJid().asBareJid() + ": sending read marker to " + markable.getCounterpart().toString());
            final Account account = conversation.getAccount();
            final MessagePacket packet = mMessageGenerator.confirm(markable);
            this.sendMessagePacket(account, packet);
        }
    }

    public SecureRandom getRNG() {
        return this.mRandom;
    }

    public MemorizingTrustManager getMemorizingTrustManager() {
        return this.mMemorizingTrustManager;
    }

    public void setMemorizingTrustManager(MemorizingTrustManager trustManager) {
        this.mMemorizingTrustManager = trustManager;
    }

    public void updateMemorizingTrustmanager() {
        final MemorizingTrustManager tm;
        final boolean dontTrustSystemCAs = getBooleanPreference("dont_trust_system_cas", R.bool.dont_trust_system_cas);
        if (dontTrustSystemCAs) {
            tm = new MemorizingTrustManager(getApplicationContext(), null);
        } else {
            tm = new MemorizingTrustManager(getApplicationContext());
        }
        setMemorizingTrustManager(tm);
    }

    public LruCache<String, Bitmap> getBitmapCache() {
        return this.mBitmapCache;
    }

    public Collection<String> getKnownHosts() {
        final Set<String> hosts = new HashSet<>();
        for (final Account account : getAccounts()) {
            hosts.add(account.getServer());
            for (final Contact contact : account.getRoster().getContacts()) {
                if (contact.showInRoster()) {
                    final String server = contact.getServer();
                    if (server != null) {
                        hosts.add(server);
                    }
                }
            }
        }
        if (Config.QUICKSY_DOMAIN != null) {
            hosts.remove(Config.QUICKSY_DOMAIN.toEscapedString()); //we only want to show this when we type a e164 number
        }
        if (Config.DOMAIN_LOCK != null) {
            hosts.add(Config.DOMAIN_LOCK);
        }
        if (Config.MAGIC_CREATE_DOMAIN != null) {
            hosts.add(Config.MAGIC_CREATE_DOMAIN);
        }
        return hosts;
    }

    public Collection<String> getKnownConferenceHosts() {
        final Set<String> mucServers = new HashSet<>();
        for (final Account account : accounts) {
            if (account.getXmppConnection() != null) {
                mucServers.addAll(account.getXmppConnection().getMucServers());
                for (Bookmark bookmark : account.getBookmarks()) {
                    final Jid jid = bookmark.getJid();
                    final String s = jid == null ? null : jid.getDomain().toEscapedString();
                    if (s != null) {
                        mucServers.add(s);
                    }
                }
            }
        }
        return mucServers;
    }

    public void sendMessagePacket(Account account, MessagePacket packet) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendMessagePacket(packet);
        }
    }

    public void sendPresencePacket(Account account, PresencePacket packet) {
        XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendPresencePacket(packet);
        }
    }

    public void sendCreateAccountWithCaptchaPacket(Account account, String id, Data data) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            IqPacket request = mIqGenerator.generateCreateAccountWithCaptcha(account, id, data);
            connection.sendUnmodifiedIqPacket(request, connection.registrationResponseListener, true);
        }
    }

    public void sendIqPacket(final Account account, final IqPacket packet, final OnIqPacketReceived callback) {
        final XmppConnection connection = account.getXmppConnection();
        if (connection != null) {
            connection.sendIqPacket(packet, callback);
        } else if (callback != null) {
            callback.onIqPacketReceived(account, new IqPacket(IqPacket.TYPE.TIMEOUT));
        }
    }

    public void sendPresence(final Account account) {
        sendPresence(account, checkListeners() && broadcastLastActivity());
    }

    private void sendPresence(final Account account, final boolean includeIdleTimestamp) {
        final Presence.Status status;
        if (manuallyChangePresence()) {
            status = account.getPresenceStatus();
        } else {
            status = getTargetPresence();
        }
        final PresencePacket packet = mPresenceGenerator.selfPresence(account, status);
        if (mLastActivity > 0 && includeIdleTimestamp) {
            long since = Math.min(mLastActivity, System.currentTimeMillis()); //don't send future dates
            packet.addChild("idle", Namespace.IDLE).setAttribute("since", AbstractGenerator.getTimestamp(since));
        }
        sendPresencePacket(account, packet);
    }

    private void deactivateGracePeriod() {
        for (Account account : getAccounts()) {
            account.deactivateGracePeriod();
        }
    }

    public void refreshAllPresences() {
        boolean includeIdleTimestamp = checkListeners() && broadcastLastActivity();
        for (Account account : getAccounts()) {
            if (account.isEnabled()) {
                sendPresence(account, includeIdleTimestamp);
            }
        }
    }

    private void refreshAllFcmTokens() {
        for (Account account : getAccounts()) {
            if (account.isOnlineAndConnected() && mPushManagementService.available(account)) {
                mPushManagementService.registerPushTokenOnServer(account);
                //TODO renew mucs
            }
        }
    }

    private void sendOfflinePresence(final Account account) {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": sending offline presence");
        sendPresencePacket(account, mPresenceGenerator.sendOfflinePresence(account));
    }

    public MessageGenerator getMessageGenerator() {
        return this.mMessageGenerator;
    }

    public PresenceGenerator getPresenceGenerator() {
        return this.mPresenceGenerator;
    }

    public IqGenerator getIqGenerator() {
        return this.mIqGenerator;
    }

    public IqParser getIqParser() {
        return this.mIqParser;
    }

    public JingleConnectionManager getJingleConnectionManager() {
        return this.mJingleConnectionManager;
    }

    public MessageArchiveService getMessageArchiveService() {
        return this.mMessageArchiveService;
    }

    public QuickConversationsService getQuickConversationsService() {
        return this.mQuickConversationsService;
    }

    public List<Contact> findContacts(Jid jid, String accountJid) {
        ArrayList<Contact> contacts = new ArrayList<>();
        for (Account account : getAccounts()) {
            if ((account.isEnabled() || accountJid != null)
                    && (accountJid == null || accountJid.equals(account.getJid().asBareJid().toString()))) {
                Contact contact = account.getRoster().getContactFromContactList(jid);
                if (contact != null) {
                    contacts.add(contact);
                }
            }
        }
        return contacts;
    }

    public Conversation findFirstMuc(Jid jid) {
        for (Conversation conversation : getConversations()) {
            if (conversation.getAccount().isEnabled() && conversation.getJid().asBareJid().equals(jid.asBareJid()) && conversation.getMode() == Conversation.MODE_MULTI) {
                return conversation;
            }
        }
        return null;
    }

    public NotificationService getNotificationService() {
        return this.mNotificationService;
    }

    public HttpConnectionManager getHttpConnectionManager() {
        return this.mHttpConnectionManager;
    }

    public void resendFailedMessages(final Message message) {
        final Collection<Message> messages = new ArrayList<>();
        Message current = message;
        while (current.getStatus() == Message.STATUS_SEND_FAILED) {
            messages.add(current);
            if (current.mergeable(current.next())) {
                current = current.next();
            } else {
                break;
            }
        }
        for (final Message msg : messages) {
            msg.setTime(System.currentTimeMillis());
            markMessage(msg, Message.STATUS_WAITING);
            this.resendMessage(msg, false);
        }
        if (message.getConversation() instanceof Conversation) {
            ((Conversation) message.getConversation()).sort();
        }
        updateConversationUi();
    }

    public void clearConversationHistory(final Conversation conversation) {
        final long clearDate;
        final String reference;
        if (conversation.countMessages() > 0) {
            Message latestMessage = conversation.getLatestMessage();
            clearDate = latestMessage.getTimeSent() + 1000;
            reference = latestMessage.getServerMsgId();
        } else {
            clearDate = System.currentTimeMillis();
            reference = null;
        }
        conversation.clearMessages();
        conversation.setHasMessagesLeftOnServer(false); //avoid messages getting loaded through mam
        conversation.setLastClearHistory(clearDate, reference);
        Runnable runnable = () -> {
            databaseBackend.deleteMessagesInConversation(conversation);
            databaseBackend.updateConversation(conversation);
        };
        mDatabaseWriterExecutor.execute(runnable);
    }

    public boolean sendBlockRequest(final Blockable blockable, boolean reportSpam) {
        if (blockable != null && blockable.getBlockedJid() != null) {
            final Jid jid = blockable.getBlockedJid();
            this.sendIqPacket(blockable.getAccount(), getIqGenerator().generateSetBlockRequest(jid, reportSpam), (a, response) -> {
                if (response.getType() == IqPacket.TYPE.RESULT) {
                    a.getBlocklist().add(jid);
                    updateBlocklistUi(OnUpdateBlocklist.Status.BLOCKED);
                }
            });
            if (blockable.getBlockedJid().isFullJid()) {
                return false;
            } else if (removeBlockedConversations(blockable.getAccount(), jid)) {
                updateConversationUi();
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean removeBlockedConversations(final Account account, final Jid blockedJid) {
        boolean removed = false;
        synchronized (this.conversations) {
            boolean domainJid = blockedJid.getLocal() == null;
            for (Conversation conversation : this.conversations) {
                boolean jidMatches = (domainJid && blockedJid.getDomain().equals(conversation.getJid().getDomain()))
                        || blockedJid.equals(conversation.getJid().asBareJid());
                if (conversation.getAccount() == account
                        && conversation.getMode() == Conversation.MODE_SINGLE
                        && jidMatches) {
                    this.conversations.remove(conversation);
                    markRead(conversation);
                    conversation.setStatus(Conversation.STATUS_ARCHIVED);
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": archiving conversation " + conversation.getJid().asBareJid() + " because jid was blocked");
                    updateConversation(conversation);
                    removed = true;
                }
            }
        }
        return removed;
    }

    public void sendUnblockRequest(final Blockable blockable) {
        if (blockable != null && blockable.getJid() != null) {
            final Jid jid = blockable.getBlockedJid();
            this.sendIqPacket(blockable.getAccount(), getIqGenerator().generateSetUnblockRequest(jid), new OnIqPacketReceived() {
                @Override
                public void onIqPacketReceived(final Account account, final IqPacket packet) {
                    if (packet.getType() == IqPacket.TYPE.RESULT) {
                        account.getBlocklist().remove(jid);
                        updateBlocklistUi(OnUpdateBlocklist.Status.UNBLOCKED);
                    }
                }
            });
        }
    }

    public void publishDisplayName(Account account) {
        String displayName = account.getDisplayName();
        final IqPacket request;
        if (TextUtils.isEmpty(displayName)) {
            request = mIqGenerator.deleteNode(Namespace.NICK);
        } else {
            request = mIqGenerator.publishNick(displayName);
        }
        mAvatarService.clear(account);
        sendIqPacket(account, request, (account1, packet) -> {
            if (packet.getType() == IqPacket.TYPE.ERROR) {
                Log.d(Config.LOGTAG, account1.getJid().asBareJid() + ": unable to modify nick name " + packet.toString());
            }
        });
    }

    public ServiceDiscoveryResult getCachedServiceDiscoveryResult(Pair<String, String> key) {
        ServiceDiscoveryResult result = discoCache.get(key);
        if (result != null) {
            return result;
        } else {
            result = databaseBackend.findDiscoveryResult(key.first, key.second);
            if (result != null) {
                discoCache.put(key, result);
            }
            return result;
        }
    }

    public void fetchCaps(Account account, final Jid jid, final Presence presence) {
        final Pair<String, String> key = new Pair<>(presence.getHash(), presence.getVer());
        final ServiceDiscoveryResult disco = getCachedServiceDiscoveryResult(key);
        if (disco != null) {
            presence.setServiceDiscoveryResult(disco);
            final Contact contact = account.getRoster().getContact(jid);
            if (contact.refreshRtpCapability()) {
                syncRoster(account);
            }
        } else {
            final IqPacket request = new IqPacket(IqPacket.TYPE.GET);
            request.setTo(jid);
            final String node = presence.getNode();
            final String ver = presence.getVer();
            final Element query = request.query(Namespace.DISCO_INFO);
            if (node != null && ver != null) {
                query.setAttribute("node", node + "#" + ver);
            }
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": making disco request for " + key.second + " to " + jid);
            sendIqPacket(account, request, (a, response) -> {
                if (response.getType() == IqPacket.TYPE.RESULT) {
                    final ServiceDiscoveryResult discoveryResult = new ServiceDiscoveryResult(response);
                    if (presence.getVer().equals(discoveryResult.getVer())) {
                        databaseBackend.insertDiscoveryResult(discoveryResult);
                        injectServiceDiscoveryResult(a.getRoster(), presence.getHash(), presence.getVer(), discoveryResult);
                    } else {
                        Log.d(Config.LOGTAG, a.getJid().asBareJid() + ": mismatch in caps for contact " + jid + " " + presence.getVer() + " vs " + discoveryResult.getVer());
                    }
                } else {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to fetch caps from " + jid);
                }
            });
        }
    }

    private void injectServiceDiscoveryResult(Roster roster, String hash, String ver, ServiceDiscoveryResult disco) {
        boolean rosterNeedsSync = false;
        for (final Contact contact : roster.getContacts()) {
            boolean serviceDiscoverySet = false;
            for (final Presence presence : contact.getPresences().getPresences()) {
                if (hash.equals(presence.getHash()) && ver.equals(presence.getVer())) {
                    presence.setServiceDiscoveryResult(disco);
                    serviceDiscoverySet = true;
                }
            }
            if (serviceDiscoverySet) {
                rosterNeedsSync |= contact.refreshRtpCapability();
            }
        }
        if (rosterNeedsSync) {
            syncRoster(roster.getAccount());
        }
    }

    public void fetchMamPreferences(Account account, final OnMamPreferencesFetched callback) {
        final MessageArchiveService.Version version = MessageArchiveService.Version.get(account);
        IqPacket request = new IqPacket(IqPacket.TYPE.GET);
        request.addChild("prefs", version.namespace);
        sendIqPacket(account, request, (account1, packet) -> {
            Element prefs = packet.findChild("prefs", version.namespace);
            if (packet.getType() == IqPacket.TYPE.RESULT && prefs != null) {
                callback.onPreferencesFetched(prefs);
            } else {
                callback.onPreferencesFetchFailed();
            }
        });
    }

    public PushManagementService getPushManagementService() {
        return mPushManagementService;
    }

    public void changeStatus(Account account, PresenceTemplate template, String signature) {
        if (!template.getStatusMessage().isEmpty()) {
            databaseBackend.insertPresenceTemplate(template);
        }
        account.setPgpSignature(signature);
        account.setPresenceStatus(template.getStatus());
        account.setPresenceStatusMessage(template.getStatusMessage());
        databaseBackend.updateAccount(account);
        sendPresence(account);
    }

    public List<PresenceTemplate> getPresenceTemplates(Account account) {
        List<PresenceTemplate> templates = databaseBackend.getPresenceTemplates();
        for (PresenceTemplate template : account.getSelfContact().getPresences().asTemplates()) {
            if (!templates.contains(template)) {
                templates.add(0, template);
            }
        }
        return templates;
    }

    public void saveConversationAsBookmark(Conversation conversation, String name) {
        final Account account = conversation.getAccount();
        final Bookmark bookmark = new Bookmark(account, conversation.getJid().asBareJid());
        final String nick = conversation.getJid().getResource();
        if (nick != null && !nick.isEmpty() && !nick.equals(MucOptions.defaultNick(account))) {
            bookmark.setNick(nick);
        }
        if (!TextUtils.isEmpty(name)) {
            bookmark.setBookmarkName(name);
        }
        bookmark.setAutojoin(getPreferences().getBoolean("autojoin", getResources().getBoolean(R.bool.autojoin)));
        createBookmark(account, bookmark);
        bookmark.setConversation(conversation);
    }

    public boolean verifyFingerprints(Contact contact, List<XmppUri.Fingerprint> fingerprints) {
        boolean performedVerification = false;
        final AxolotlService axolotlService = contact.getAccount().getAxolotlService();
        for (XmppUri.Fingerprint fp : fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO) {
                String fingerprint = "05" + fp.fingerprint.replaceAll("\\s", "");
                FingerprintStatus fingerprintStatus = axolotlService.getFingerprintTrust(fingerprint);
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified()) {
                        performedVerification = true;
                        axolotlService.setFingerprintTrust(fingerprint, fingerprintStatus.toVerified());
                    }
                } else {
                    axolotlService.preVerifyFingerprint(contact, fingerprint);
                }
            }
        }
        return performedVerification;
    }

    public boolean verifyFingerprints(Account account, List<XmppUri.Fingerprint> fingerprints) {
        final AxolotlService axolotlService = account.getAxolotlService();
        boolean verifiedSomething = false;
        for (XmppUri.Fingerprint fp : fingerprints) {
            if (fp.type == XmppUri.FingerprintType.OMEMO) {
                String fingerprint = "05" + fp.fingerprint.replaceAll("\\s", "");
                Log.d(Config.LOGTAG, "trying to verify own fp=" + fingerprint);
                FingerprintStatus fingerprintStatus = axolotlService.getFingerprintTrust(fingerprint);
                if (fingerprintStatus != null) {
                    if (!fingerprintStatus.isVerified()) {
                        axolotlService.setFingerprintTrust(fingerprint, fingerprintStatus.toVerified());
                        verifiedSomething = true;
                    }
                } else {
                    axolotlService.preVerifyFingerprint(account, fingerprint);
                    verifiedSomething = true;
                }
            }
        }
        return verifiedSomething;
    }

    public boolean blindTrustBeforeVerification() {
        return getBooleanPreference(SettingsActivity.BLIND_TRUST_BEFORE_VERIFICATION, R.bool.btbv);
    }

    public ShortcutService getShortcutService() {
        return mShortcutService;
    }

    public void pushMamPreferences(Account account, Element prefs) {
        IqPacket set = new IqPacket(IqPacket.TYPE.SET);
        set.addChild(prefs);
        sendIqPacket(account, set, null);
    }

    public void evictPreview(String uuid) {
        if (mBitmapCache.remove(uuid) != null) {
            Log.d(Config.LOGTAG, "deleted cached preview");
        }
    }

    public interface OnMamPreferencesFetched {
        void onPreferencesFetched(Element prefs);

        void onPreferencesFetchFailed();
    }

    public interface OnAccountCreated {
        void onAccountCreated(Account account);

        void informUser(int r);
    }

    public interface OnMoreMessagesLoaded {
        void onMoreMessagesLoaded(int count, Conversation conversation);

        void informUser(int r);
    }

    public interface OnAccountPasswordChanged {
        void onPasswordChangeSucceeded();

        void onPasswordChangeFailed();
    }

    public interface OnRoomDestroy {
        void onRoomDestroySucceeded();

        void onRoomDestroyFailed();
    }

    public interface OnAffiliationChanged {
        void onAffiliationChangedSuccessful(Jid jid);

        void onAffiliationChangeFailed(Jid jid, int resId);
    }

    public interface OnConversationUpdate {
        void onConversationUpdate();
    }

    public interface OnJingleRtpConnectionUpdate {
        void onJingleRtpConnectionUpdate(final Account account, final Jid with, final String sessionId, final RtpEndUserState state);

        void onAudioDeviceChanged(AppRTCAudioManager.AudioDevice selectedAudioDevice, Set<AppRTCAudioManager.AudioDevice> availableAudioDevices);
    }

    public interface OnAccountUpdate {
        void onAccountUpdate();
    }

    public interface OnCaptchaRequested {
        void onCaptchaRequested(Account account, String id, Data data, Bitmap captcha);
    }

    public interface OnRosterUpdate {
        void onRosterUpdate();
    }

    public interface OnMucRosterUpdate {
        void onMucRosterUpdate();
    }

    public interface OnConferenceConfigurationFetched {
        void onConferenceConfigurationFetched(Conversation conversation);

        void onFetchFailed(Conversation conversation, String errorCondition);
    }

    public interface OnConferenceJoined {
        void onConferenceJoined(Conversation conversation);
    }

    public interface OnConfigurationPushed {
        void onPushSucceeded();

        void onPushFailed();
    }

    public interface OnShowErrorToast {
        void onShowErrorToast(int resId);
    }

    public class XmppConnectionBinder extends Binder {
        public XmppConnectionService getService() {
            return XmppConnectionService.this;
        }
    }

    private class InternalEventReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            onStartCommand(intent, 0, 0);
        }
    }

    public static class OngoingCall {
        private final AbstractJingleConnection.Id id;
        private final Set<Media> media;

        public OngoingCall(AbstractJingleConnection.Id id, Set<Media> media) {
            this.id = id;
            this.media = media;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OngoingCall that = (OngoingCall) o;
            return Objects.equal(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id);
        }
    }
}
