package eu.siacs.conversations.xmpp;

import static eu.siacs.conversations.utils.Random.SECURE_RANDOM;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.SystemClock;
import android.security.KeyChain;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Strings;

import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.XmppDomainVerifier;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.sasl.ChannelBinding;
import eu.siacs.conversations.crypto.sasl.HashedToken;
import eu.siacs.conversations.crypto.sasl.SaslMechanism;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.generator.IqGenerator;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.MemorizingTrustManager;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Patterns;
import eu.siacs.conversations.utils.PhoneHelper;
import eu.siacs.conversations.utils.Resolver;
import eu.siacs.conversations.utils.SSLSockets;
import eu.siacs.conversations.utils.SocksSocketFactory;
import eu.siacs.conversations.utils.XmlHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.LocalizedContent;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xml.Tag;
import eu.siacs.conversations.xml.TagWriter;
import eu.siacs.conversations.xml.XmlReader;
import eu.siacs.conversations.xmpp.bind.Bind2;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.jingle.OnJinglePacketReceived;
import eu.siacs.conversations.xmpp.jingle.stanzas.JinglePacket;
import eu.siacs.conversations.xmpp.stanzas.AbstractAcknowledgeableStanza;
import eu.siacs.conversations.xmpp.stanzas.AbstractStanza;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;
import eu.siacs.conversations.xmpp.stanzas.PresencePacket;
import eu.siacs.conversations.xmpp.stanzas.csi.ActivePacket;
import eu.siacs.conversations.xmpp.stanzas.csi.InactivePacket;
import eu.siacs.conversations.xmpp.stanzas.streammgmt.AckPacket;
import eu.siacs.conversations.xmpp.stanzas.streammgmt.EnablePacket;
import eu.siacs.conversations.xmpp.stanzas.streammgmt.RequestPacket;
import eu.siacs.conversations.xmpp.stanzas.streammgmt.ResumePacket;
import okhttp3.HttpUrl;

public class XmppConnection implements Runnable {

    private static final int PACKET_IQ = 0;
    private static final int PACKET_MESSAGE = 1;
    private static final int PACKET_PRESENCE = 2;
    public final OnIqPacketReceived registrationResponseListener =
            (account, packet) -> {
                if (packet.getType() == IqPacket.TYPE.RESULT) {
                    account.setOption(Account.OPTION_REGISTER, false);
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": successfully registered new account on server");
                    throw new StateChangingError(Account.State.REGISTRATION_SUCCESSFUL);
                } else {
                    final List<String> PASSWORD_TOO_WEAK_MSGS =
                            Arrays.asList(
                                    "The password is too weak", "Please use a longer password.");
                    Element error = packet.findChild("error");
                    Account.State state = Account.State.REGISTRATION_FAILED;
                    if (error != null) {
                        if (error.hasChild("conflict")) {
                            state = Account.State.REGISTRATION_CONFLICT;
                        } else if (error.hasChild("resource-constraint")
                                && "wait".equals(error.getAttribute("type"))) {
                            state = Account.State.REGISTRATION_PLEASE_WAIT;
                        } else if (error.hasChild("not-acceptable")
                                && PASSWORD_TOO_WEAK_MSGS.contains(
                                        error.findChildContent("text"))) {
                            state = Account.State.REGISTRATION_PASSWORD_TOO_WEAK;
                        }
                    }
                    throw new StateChangingError(state);
                }
            };
    protected final Account account;
    private final Features features = new Features(this);
    private final HashMap<Jid, ServiceDiscoveryResult> disco = new HashMap<>();
    private final HashMap<String, Jid> commands = new HashMap<>();
    private final SparseArray<AbstractAcknowledgeableStanza> mStanzaQueue = new SparseArray<>();
    private final Hashtable<String, Pair<IqPacket, OnIqPacketReceived>> packetCallbacks =
            new Hashtable<>();
    private final Set<OnAdvancedStreamFeaturesLoaded> advancedStreamFeaturesLoadedListeners =
            new HashSet<>();
    private final XmppConnectionService mXmppConnectionService;
    private Socket socket;
    private XmlReader tagReader;
    private TagWriter tagWriter = new TagWriter();
    private boolean shouldAuthenticate = true;
    private boolean inSmacksSession = false;
    private boolean quickStartInProgress = false;
    private boolean isBound = false;
    private Element streamFeatures;
    private String streamId = null;
    private int stanzasReceived = 0;
    private int stanzasSent = 0;
    private long lastPacketReceived = 0;
    private long lastPingSent = 0;
    private long lastConnect = 0;
    private long lastSessionStarted = 0;
    private long lastDiscoStarted = 0;
    private boolean isMamPreferenceAlways = false;
    private final AtomicInteger mPendingServiceDiscoveries = new AtomicInteger(0);
    private final AtomicBoolean mWaitForDisco = new AtomicBoolean(true);
    private final AtomicBoolean mWaitingForSmCatchup = new AtomicBoolean(false);
    private final AtomicInteger mSmCatchupMessageCounter = new AtomicInteger(0);
    private boolean mInteractive = false;
    private int attempt = 0;
    private OnPresencePacketReceived presenceListener = null;
    private OnJinglePacketReceived jingleListener = null;
    private OnIqPacketReceived unregisteredIqListener = null;
    private OnMessagePacketReceived messageListener = null;
    private OnStatusChanged statusListener = null;
    private OnBindListener bindListener = null;
    private OnMessageAcknowledged acknowledgedListener = null;
    private SaslMechanism saslMechanism;
    private HashedToken.Mechanism hashTokenRequest;
    private HttpUrl redirectionUrl = null;
    private String verifiedHostname = null;
    private volatile Thread mThread;
    private CountDownLatch mStreamCountDownLatch;

    public XmppConnection(final Account account, final XmppConnectionService service) {
        this.account = account;
        this.mXmppConnectionService = service;
    }

    private static void fixResource(Context context, Account account) {
        String resource = account.getResource();
        int fixedPartLength =
                context.getString(R.string.app_name).length() + 1; // include the trailing dot
        int randomPartLength = 4; // 3 bytes
        if (resource != null && resource.length() > fixedPartLength + randomPartLength) {
            if (validBase64(
                    resource.substring(fixedPartLength, fixedPartLength + randomPartLength))) {
                account.setResource(resource.substring(0, fixedPartLength + randomPartLength));
            }
        }
    }

    private static boolean validBase64(String input) {
        try {
            return Base64.decode(input, Base64.URL_SAFE).length == 3;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void changeStatus(final Account.State nextStatus) {
        synchronized (this) {
            if (Thread.currentThread().isInterrupted()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": not changing status to "
                                + nextStatus
                                + " because thread was interrupted");
                return;
            }
            if (account.getStatus() != nextStatus) {
                if ((nextStatus == Account.State.OFFLINE)
                        && (account.getStatus() != Account.State.CONNECTING)
                        && (account.getStatus() != Account.State.ONLINE)
                        && (account.getStatus() != Account.State.DISABLED)) {
                    return;
                }
                if (nextStatus == Account.State.ONLINE) {
                    this.attempt = 0;
                }
                account.setStatus(nextStatus);
            } else {
                return;
            }
        }
        if (statusListener != null) {
            statusListener.onStatusChanged(account);
        }
    }

    public Jid getJidForCommand(final String node) {
        synchronized (this.commands) {
            return this.commands.get(node);
        }
    }

    public void prepareNewConnection() {
        this.lastConnect = SystemClock.elapsedRealtime();
        this.lastPingSent = SystemClock.elapsedRealtime();
        this.lastDiscoStarted = Long.MAX_VALUE;
        this.mWaitingForSmCatchup.set(false);
        this.changeStatus(Account.State.CONNECTING);
    }

    public boolean isWaitingForSmCatchup() {
        return mWaitingForSmCatchup.get();
    }

    public void incrementSmCatchupMessageCounter() {
        this.mSmCatchupMessageCounter.incrementAndGet();
    }

    protected void connect() {
        if (mXmppConnectionService.areMessagesInitialized()) {
            mXmppConnectionService.resetSendingToWaiting(account);
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": connecting");
        features.encryptionEnabled = false;
        this.inSmacksSession = false;
        this.quickStartInProgress = false;
        this.isBound = false;
        this.attempt++;
        this.verifiedHostname = null; // will be set if user entered hostname is being used or hostname was verified
        // with dnssec
        try {
            Socket localSocket;
            shouldAuthenticate = !account.isOptionSet(Account.OPTION_REGISTER);
            this.changeStatus(Account.State.CONNECTING);
            final boolean useTor = mXmppConnectionService.useTorToConnect() || account.isOnion();
            final boolean extended = mXmppConnectionService.showExtendedConnectionOptions();
            if (useTor) {
                String destination;
                if (account.getHostname().isEmpty() || account.isOnion()) {
                    destination = account.getServer();
                } else {
                    destination = account.getHostname();
                    this.verifiedHostname = destination;
                }

                final int port = account.getPort();
                final boolean directTls = Resolver.useDirectTls(port);

                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": connect to "
                                + destination
                                + " via Tor. directTls="
                                + directTls);
                localSocket = SocksSocketFactory.createSocketOverTor(destination, port);

                if (directTls) {
                    localSocket = upgradeSocketToTls(localSocket);
                    features.encryptionEnabled = true;
                }

                try {
                    startXmpp(localSocket);
                } catch (final InterruptedException e) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": thread was interrupted before beginning stream");
                    return;
                } catch (final Exception e) {
                    throw new IOException("Could not start stream", e);
                }
            } else {
                final String domain = account.getServer();
                final List<Resolver.Result> results;
                final boolean hardcoded = extended && !account.getHostname().isEmpty();
                if (hardcoded) {
                    results = Resolver.fromHardCoded(account.getHostname(), account.getPort());
                } else {
                    results = Resolver.resolve(domain);
                }
                if (Thread.currentThread().isInterrupted()) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": Thread was interrupted");
                    return;
                }
                if (results.size() == 0) {
                    Log.e(
                            Config.LOGTAG,
                            account.getJid().asBareJid() + ": Resolver results were empty");
                    return;
                }
                final Resolver.Result storedBackupResult;
                if (hardcoded) {
                    storedBackupResult = null;
                } else {
                    storedBackupResult =
                            mXmppConnectionService.databaseBackend.findResolverResult(domain);
                    if (storedBackupResult != null && !results.contains(storedBackupResult)) {
                        results.add(storedBackupResult);
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": loaded backup resolver result from db: "
                                        + storedBackupResult);
                    }
                }
                for (Iterator<Resolver.Result> iterator = results.iterator();
                        iterator.hasNext(); ) {
                    final Resolver.Result result = iterator.next();
                    if (Thread.currentThread().isInterrupted()) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": Thread was interrupted");
                        return;
                    }
                    try {
                        // if tls is true, encryption is implied and must not be started
                        features.encryptionEnabled = result.isDirectTls();
                        verifiedHostname =
                                result.isAuthenticated() ? result.getHostname().toString() : null;
                        Log.d(Config.LOGTAG, "verified hostname " + verifiedHostname);
                        final InetSocketAddress addr;
                        if (result.getIp() != null) {
                            addr = new InetSocketAddress(result.getIp(), result.getPort());
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString()
                                            + ": using values from resolver "
                                            + (result.getHostname() == null
                                                    ? ""
                                                    : result.getHostname().toString() + "/")
                                            + result.getIp().getHostAddress()
                                            + ":"
                                            + result.getPort()
                                            + " tls: "
                                            + features.encryptionEnabled);
                        } else {
                            addr =
                                    new InetSocketAddress(
                                            IDN.toASCII(result.getHostname().toString()),
                                            result.getPort());
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString()
                                            + ": using values from resolver "
                                            + result.getHostname().toString()
                                            + ":"
                                            + result.getPort()
                                            + " tls: "
                                            + features.encryptionEnabled);
                        }

                        localSocket = new Socket();
                        localSocket.connect(addr, Config.SOCKET_TIMEOUT * 1000);

                        if (features.encryptionEnabled) {
                            localSocket = upgradeSocketToTls(localSocket);
                        }

                        localSocket.setSoTimeout(Config.SOCKET_TIMEOUT * 1000);
                        if (startXmpp(localSocket)) {
                            localSocket.setSoTimeout(
                                    0); // reset to 0; once the connection is established we don’t
                            // want this
                            if (!hardcoded && !result.equals(storedBackupResult)) {
                                mXmppConnectionService.databaseBackend.saveResolverResult(
                                        domain, result);
                            }
                            break; // successfully connected to server that speaks xmpp
                        } else {
                            FileBackend.close(localSocket);
                            throw new StateChangingException(Account.State.STREAM_OPENING_ERROR);
                        }
                    } catch (final StateChangingException e) {
                        if (!iterator.hasNext()) {
                            throw e;
                        }
                    } catch (InterruptedException e) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": thread was interrupted before beginning stream");
                        return;
                    } catch (final Throwable e) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid().toString()
                                        + ": "
                                        + e.getMessage()
                                        + "("
                                        + e.getClass().getName()
                                        + ")");
                        if (!iterator.hasNext()) {
                            throw new UnknownHostException();
                        }
                    }
                }
            }
            processStream();
        } catch (final SecurityException e) {
            this.changeStatus(Account.State.MISSING_INTERNET_PERMISSION);
        } catch (final StateChangingException e) {
            this.changeStatus(e.state);
        } catch (final UnknownHostException
                | ConnectException
                | SocksSocketFactory.HostNotFoundException e) {
            this.changeStatus(Account.State.SERVER_NOT_FOUND);
        } catch (final SocksSocketFactory.SocksProxyNotFoundException e) {
            this.changeStatus(Account.State.TOR_NOT_AVAILABLE);
        } catch (final IOException | XmlPullParserException e) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": " + e.getMessage());
            this.changeStatus(Account.State.OFFLINE);
            this.attempt = Math.max(0, this.attempt - 1);
        } finally {
            if (!Thread.currentThread().isInterrupted()) {
                forceCloseSocket();
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": not force closing socket because thread was interrupted");
            }
        }
    }

    /**
     * Starts xmpp protocol, call after connecting to socket
     *
     * @return true if server returns with valid xmpp, false otherwise
     */
    private boolean startXmpp(final Socket socket) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        this.socket = socket;
        tagReader = new XmlReader();
        if (tagWriter != null) {
            tagWriter.forceClose();
        }
        tagWriter = new TagWriter();
        tagWriter.setOutputStream(socket.getOutputStream());
        tagReader.setInputStream(socket.getInputStream());
        tagWriter.beginDocument();
        final boolean quickStart;
        if (socket instanceof SSLSocket) {
            final SSLSocket sslSocket = (SSLSocket) socket;
            SSLSockets.log(account, sslSocket);
            quickStart = establishStream(SSLSockets.version(sslSocket));
        } else {
            quickStart = establishStream(SSLSockets.Version.NONE);
        }
        final Tag tag = tagReader.readTag();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        final boolean success = tag != null && tag.isStart("stream", Namespace.STREAMS);
        if (success && quickStart) {
            this.quickStartInProgress = true;
        }
        return success;
    }

    private SSLSocketFactory getSSLSocketFactory()
            throws NoSuchAlgorithmException, KeyManagementException {
        final SSLContext sc = SSLSockets.getSSLContext();
        final MemorizingTrustManager trustManager =
                this.mXmppConnectionService.getMemorizingTrustManager();
        final KeyManager[] keyManager;
        if (account.getPrivateKeyAlias() != null) {
            keyManager = new KeyManager[] {new MyKeyManager()};
        } else {
            keyManager = null;
        }
        final String domain = account.getServer();
        sc.init(
                keyManager,
                new X509TrustManager[] {
                    mInteractive
                            ? trustManager.getInteractive(domain)
                            : trustManager.getNonInteractive(domain)
                },
                SECURE_RANDOM);
        return sc.getSocketFactory();
    }

    @Override
    public void run() {
        synchronized (this) {
            this.mThread = Thread.currentThread();
            if (this.mThread.isInterrupted()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": aborting connect because thread was interrupted");
                return;
            }
            forceCloseSocket();
        }
        connect();
    }

    private void processStream() throws XmlPullParserException, IOException {
        final CountDownLatch streamCountDownLatch = new CountDownLatch(1);
        this.mStreamCountDownLatch = streamCountDownLatch;
        Tag nextTag = tagReader.readTag();
        while (nextTag != null && !nextTag.isEnd("stream")) {
            if (nextTag.isStart("error")) {
                processStreamError(nextTag);
            } else if (nextTag.isStart("features", Namespace.STREAMS)) {
                processStreamFeatures(nextTag);
            } else if (nextTag.isStart("proceed", Namespace.TLS)) {
                switchOverToTls();
            } else if (nextTag.isStart("success")) {
                final Element success = tagReader.readElement(nextTag);
                if (processSuccess(success)) {
                    break;
                }

            } else if (nextTag.isStart("failure", Namespace.TLS)) {
                throw new StateChangingException(Account.State.TLS_ERROR);
            } else if (nextTag.isStart("failure")) {
                final Element failure = tagReader.readElement(nextTag);
                processFailure(failure);
            } else if (nextTag.isStart("continue", Namespace.SASL_2)) {
                // two step sasl2 - we don’t support this yet
                throw new StateChangingException(Account.State.INCOMPATIBLE_CLIENT);
            } else if (nextTag.isStart("challenge")) {
                if (isSecure() && this.saslMechanism != null) {
                    final Element challenge = tagReader.readElement(nextTag);
                    processChallenge(challenge);
                } else {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": received 'challenge on an unsecure connection");
                    throw new StateChangingException(Account.State.INCOMPATIBLE_CLIENT);
                }
            } else if (nextTag.isStart("enabled", Namespace.STREAM_MANAGEMENT)) {
                final Element enabled = tagReader.readElement(nextTag);
                processEnabled(enabled);
            } else if (nextTag.isStart("resumed")) {
                final Element resumed = tagReader.readElement(nextTag);
                processResumed(resumed);
            } else if (nextTag.isStart("r")) {
                tagReader.readElement(nextTag);
                if (Config.EXTENDED_SM_LOGGING) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": acknowledging stanza #"
                                    + this.stanzasReceived);
                }
                final AckPacket ack = new AckPacket(this.stanzasReceived);
                tagWriter.writeStanzaAsync(ack);
            } else if (nextTag.isStart("a")) {
                boolean accountUiNeedsRefresh = false;
                synchronized (NotificationService.CATCHUP_LOCK) {
                    if (mWaitingForSmCatchup.compareAndSet(true, false)) {
                        final int messageCount = mSmCatchupMessageCounter.get();
                        final int pendingIQs = packetCallbacks.size();
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": SM catchup complete (messages="
                                        + messageCount
                                        + ", pending IQs="
                                        + pendingIQs
                                        + ")");
                        accountUiNeedsRefresh = true;
                        if (messageCount > 0) {
                            mXmppConnectionService
                                    .getNotificationService()
                                    .finishBacklog(true, account);
                        }
                    }
                }
                if (accountUiNeedsRefresh) {
                    mXmppConnectionService.updateAccountUi();
                }
                final Element ack = tagReader.readElement(nextTag);
                lastPacketReceived = SystemClock.elapsedRealtime();
                try {
                    final boolean acknowledgedMessages;
                    synchronized (this.mStanzaQueue) {
                        final int serverSequence = Integer.parseInt(ack.getAttribute("h"));
                        acknowledgedMessages = acknowledgeStanzaUpTo(serverSequence);
                    }
                    if (acknowledgedMessages) {
                        mXmppConnectionService.updateConversationUi();
                    }
                } catch (NumberFormatException | NullPointerException e) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": server send ack without sequence number");
                }
            } else if (nextTag.isStart("failed")) {
                final Element failed = tagReader.readElement(nextTag);
                processFailed(failed, true);
            } else if (nextTag.isStart("iq")) {
                processIq(nextTag);
            } else if (nextTag.isStart("message")) {
                processMessage(nextTag);
            } else if (nextTag.isStart("presence")) {
                processPresence(nextTag);
            }
            nextTag = tagReader.readTag();
        }
        if (nextTag != null && nextTag.isEnd("stream")) {
            streamCountDownLatch.countDown();
        }
    }

    private void processChallenge(final Element challenge) throws IOException {
        final SaslMechanism.Version version;
        try {
            version = SaslMechanism.Version.of(challenge);
        } catch (final IllegalArgumentException e) {
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
        final Element response;
        if (version == SaslMechanism.Version.SASL) {
            response = new Element("response", Namespace.SASL);
        } else if (version == SaslMechanism.Version.SASL_2) {
            response = new Element("response", Namespace.SASL_2);
        } else {
            throw new AssertionError("Missing implementation for " + version);
        }
        try {
            response.setContent(saslMechanism.getResponse(challenge.getContent(), sslSocketOrNull(socket)));
        } catch (final SaslMechanism.AuthenticationException e) {
            // TODO: Send auth abort tag.
            Log.e(Config.LOGTAG, e.toString());
            throw new StateChangingException(Account.State.UNAUTHORIZED);
        }
        tagWriter.writeElement(response);
    }

    private boolean processSuccess(final Element success)
            throws IOException, XmlPullParserException {
        final SaslMechanism.Version version;
        try {
            version = SaslMechanism.Version.of(success);
        } catch (final IllegalArgumentException e) {
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
        final SaslMechanism currentSaslMechanism = this.saslMechanism;
        if (currentSaslMechanism == null) {
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
        final String challenge;
        if (version == SaslMechanism.Version.SASL) {
            challenge = success.getContent();
        } else if (version == SaslMechanism.Version.SASL_2) {
            challenge = success.findChildContent("additional-data");
        } else {
            throw new AssertionError("Missing implementation for " + version);
        }
        try {
            currentSaslMechanism.getResponse(challenge, sslSocketOrNull(socket));
        } catch (final SaslMechanism.AuthenticationException e) {
            Log.e(Config.LOGTAG, String.valueOf(e));
            throw new StateChangingException(Account.State.UNAUTHORIZED);
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString() + ": logged in (using " + version + ")");
        if (SaslMechanism.pin(currentSaslMechanism)) {
            account.setPinnedMechanism(currentSaslMechanism);
        }
        if (version == SaslMechanism.Version.SASL_2) {
            final String authorizationIdentifier =
                    success.findChildContent("authorization-identifier");
            final Jid authorizationJid;
            try {
                authorizationJid =
                        Strings.isNullOrEmpty(authorizationIdentifier)
                                ? null
                                : Jid.ofEscaped(authorizationIdentifier);
            } catch (final IllegalArgumentException e) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": SASL 2.0 authorization identifier was not a valid jid");
                throw new StateChangingException(Account.State.BIND_FAILURE);
            }
            if (authorizationJid == null) {
                throw new StateChangingException(Account.State.BIND_FAILURE);
            }
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": SASL 2.0 authorization identifier was "
                            + authorizationJid);
            if (!account.getJid().getDomain().equals(authorizationJid.getDomain())) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": server tried to re-assign domain to "
                                + authorizationJid.getDomain());
                throw new StateChangingError(Account.State.BIND_FAILURE);
            }
            if (authorizationJid.isFullJid() && account.setJid(authorizationJid)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": jid changed during SASL 2.0. updating database");
            }
            final boolean nopStreamFeatures;
            final Element bound = success.findChild("bound", Namespace.BIND2);
            final Element resumed = success.findChild("resumed", "urn:xmpp:sm:3");
            final Element failed = success.findChild("failed", "urn:xmpp:sm:3");
            final Element tokenWrapper = success.findChild("token", Namespace.FAST);
            final String token = tokenWrapper == null ? null : tokenWrapper.getAttribute("token");
            if (bound != null && resumed != null) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": server sent bound and resumed in SASL2 success");
                throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
            }
            final boolean processNopStreamFeatures;
            if (resumed != null && streamId != null) {
                processResumed(resumed);
            } else if (failed != null) {
                processFailed(failed, false); // wait for new stream features
            }
            if (bound != null) {
                clearIqCallbacks();
                this.isBound = true;
                final Element streamManagementEnabled =
                        bound.findChild("enabled", Namespace.STREAM_MANAGEMENT);
                final Element carbonsEnabled = bound.findChild("enabled", Namespace.CARBONS);
                final boolean waitForDisco;
                if (streamManagementEnabled != null) {
                    processEnabled(streamManagementEnabled);
                    waitForDisco = true;
                } else {
                    //if we did not enable stream management in bind do it now
                    waitForDisco = enableStreamManagement();
                }
                if (carbonsEnabled != null) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid() + ": successfully enabled carbons");
                    features.carbonsEnabled = true;
                }
                sendPostBindInitialization(waitForDisco, carbonsEnabled != null);
                processNopStreamFeatures = true;
            } else {
                processNopStreamFeatures = false;
            }
            final HashedToken.Mechanism tokenMechanism;
            if (SaslMechanism.hashedToken(currentSaslMechanism)) {
                tokenMechanism = ((HashedToken) currentSaslMechanism).getTokenMechanism();
            } else if (this.hashTokenRequest != null) {
                tokenMechanism = this.hashTokenRequest;
            } else {
                tokenMechanism = null;
            }
            if (tokenMechanism != null && !Strings.isNullOrEmpty(token)) {
                this.account.setFastToken(tokenMechanism,token);
                Log.d(Config.LOGTAG,account.getJid().asBareJid()+": storing hashed token "+tokenMechanism);
            }
            // a successful resume will not send stream features
            if (processNopStreamFeatures) {
                processNopStreamFeatures();
            }
        }
        mXmppConnectionService.databaseBackend.updateAccount(account);
        this.quickStartInProgress = false;
        if (version == SaslMechanism.Version.SASL) {
            tagReader.reset();
            sendStartStream(false, true);
            final Tag tag = tagReader.readTag();
            if (tag != null && tag.isStart("stream", Namespace.STREAMS)) {
                processStream();
                return true;
            } else {
                throw new StateChangingException(Account.State.STREAM_OPENING_ERROR);
            }
        } else {
            return false;
        }
    }

    private void processNopStreamFeatures() throws IOException {
        final Tag tag = tagReader.readTag();
        if (tag != null && tag.isStart("features", Namespace.STREAMS)) {
            this.streamFeatures = tagReader.readElement(tag);
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": processed NOP stream features after success: "
                            + XmlHelper.printElementNames(this.streamFeatures));
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": received " + tag);
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": server did not send stream features after SASL2 success");
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
    }

    private void processFailure(final Element failure) throws IOException {
        final SaslMechanism.Version version;
        try {
            version = SaslMechanism.Version.of(failure);
        } catch (final IllegalArgumentException e) {
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
        Log.d(Config.LOGTAG, failure.toString());
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": login failure " + version);
        if (SaslMechanism.hashedToken(this.saslMechanism)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": resetting token");
            account.resetFastToken();
            mXmppConnectionService.databaseBackend.updateAccount(account);
        }
        if (failure.hasChild("temporary-auth-failure")) {
            throw new StateChangingException(Account.State.TEMPORARY_AUTH_FAILURE);
        } else if (failure.hasChild("account-disabled")) {
            final String text = failure.findChildContent("text");
            if (Strings.isNullOrEmpty(text)) {
                throw new StateChangingException(Account.State.UNAUTHORIZED);
            }
            final Matcher matcher = Patterns.AUTOLINK_WEB_URL.matcher(text);
            if (matcher.find()) {
                final HttpUrl url;
                try {
                    url = HttpUrl.get(text.substring(matcher.start(), matcher.end()));
                } catch (final IllegalArgumentException e) {
                    throw new StateChangingException(Account.State.UNAUTHORIZED);
                }
                if (url.isHttps()) {
                    this.redirectionUrl = url;
                    throw new StateChangingException(Account.State.PAYMENT_REQUIRED);
                }
            }
        }
        if (SaslMechanism.hashedToken(this.saslMechanism)) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": fast authentication failed. falling back to regular authentication");
            authenticate();
        } else {
            throw new StateChangingException(Account.State.UNAUTHORIZED);
        }
    }

    private static SSLSocket sslSocketOrNull(final Socket socket) {
        if (socket instanceof SSLSocket) {
            return (SSLSocket) socket;
        } else {
            return null;
        }
    }

    private void processEnabled(final Element enabled) {
        final String streamId;
        if (enabled.getAttributeAsBoolean("resume")) {
            streamId = enabled.getAttribute("id");
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString()
                            + ": stream management enabled (resumable)");
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid().toString() + ": stream management enabled");
            streamId = null;
        }
        this.streamId = streamId;
        this.stanzasReceived = 0;
        this.inSmacksSession = true;
        final RequestPacket r = new RequestPacket();
        tagWriter.writeStanzaAsync(r);
    }

    private void processResumed(final Element resumed) throws StateChangingException {
        this.inSmacksSession = true;
        this.isBound = true;
        this.tagWriter.writeStanzaAsync(new RequestPacket());
        lastPacketReceived = SystemClock.elapsedRealtime();
        final String h = resumed.getAttribute("h");
        if (h == null) {
            resetStreamId();
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
        final int serverCount;
        try {
            serverCount = Integer.parseInt(h);
        } catch (final NumberFormatException e) {
            resetStreamId();
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
        final ArrayList<AbstractAcknowledgeableStanza> failedStanzas = new ArrayList<>();
        final boolean acknowledgedMessages;
        synchronized (this.mStanzaQueue) {
            if (serverCount < stanzasSent) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid() + ": session resumed with lost packages");
                stanzasSent = serverCount;
            } else {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": session resumed");
            }
            acknowledgedMessages = acknowledgeStanzaUpTo(serverCount);
            for (int i = 0; i < this.mStanzaQueue.size(); ++i) {
                failedStanzas.add(mStanzaQueue.valueAt(i));
            }
            mStanzaQueue.clear();
        }
        if (acknowledgedMessages) {
            mXmppConnectionService.updateConversationUi();
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": resending " + failedStanzas.size() + " stanzas");
        for (final AbstractAcknowledgeableStanza packet : failedStanzas) {
            if (packet instanceof MessagePacket) {
                MessagePacket message = (MessagePacket) packet;
                mXmppConnectionService.markMessage(
                        account,
                        message.getTo().asBareJid(),
                        message.getId(),
                        Message.STATUS_UNSEND);
            }
            sendPacket(packet);
        }
        changeStatusToOnline();
    }

    private void changeStatusToOnline() {
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": online with resource " + account.getResource());
        changeStatus(Account.State.ONLINE);
    }

    private void processFailed(final Element failed, final boolean sendBindRequest) {
        final int serverCount;
        try {
            serverCount = Integer.parseInt(failed.getAttribute("h"));
        } catch (final NumberFormatException | NullPointerException e) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": resumption failed");
            resetStreamId();
            if (sendBindRequest) {
                sendBindRequest();
            }
            return;
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": resumption failed but server acknowledged stanza #"
                        + serverCount);
        final boolean acknowledgedMessages;
        synchronized (this.mStanzaQueue) {
            acknowledgedMessages = acknowledgeStanzaUpTo(serverCount);
        }
        if (acknowledgedMessages) {
            mXmppConnectionService.updateConversationUi();
        }
        resetStreamId();
        if (sendBindRequest) {
            sendBindRequest();
        }
    }

    private boolean acknowledgeStanzaUpTo(int serverCount) {
        if (serverCount > stanzasSent) {
            Log.e(
                    Config.LOGTAG,
                    "server acknowledged more stanzas than we sent. serverCount="
                            + serverCount
                            + ", ourCount="
                            + stanzasSent);
        }
        boolean acknowledgedMessages = false;
        for (int i = 0; i < mStanzaQueue.size(); ++i) {
            if (serverCount >= mStanzaQueue.keyAt(i)) {
                if (Config.EXTENDED_SM_LOGGING) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": server acknowledged stanza #"
                                    + mStanzaQueue.keyAt(i));
                }
                final AbstractAcknowledgeableStanza stanza = mStanzaQueue.valueAt(i);
                if (stanza instanceof MessagePacket && acknowledgedListener != null) {
                    final MessagePacket packet = (MessagePacket) stanza;
                    final String id = packet.getId();
                    final Jid to = packet.getTo();
                    if (id != null && to != null) {
                        acknowledgedMessages |=
                                acknowledgedListener.onMessageAcknowledged(account, to, id);
                    }
                }
                mStanzaQueue.removeAt(i);
                i--;
            }
        }
        return acknowledgedMessages;
    }

    private @NonNull Element processPacket(final Tag currentTag, final int packetType)
            throws IOException {
        final Element element;
        switch (packetType) {
            case PACKET_IQ:
                element = new IqPacket();
                break;
            case PACKET_MESSAGE:
                element = new MessagePacket();
                break;
            case PACKET_PRESENCE:
                element = new PresencePacket();
                break;
            default:
                throw new AssertionError("Should never encounter invalid type");
        }
        element.setAttributes(currentTag.getAttributes());
        Tag nextTag = tagReader.readTag();
        if (nextTag == null) {
            throw new IOException("interrupted mid tag");
        }
        while (!nextTag.isEnd(element.getName())) {
            if (!nextTag.isNo()) {
                element.addChild(tagReader.readElement(nextTag));
            }
            nextTag = tagReader.readTag();
            if (nextTag == null) {
                throw new IOException("interrupted mid tag");
            }
        }
        if (stanzasReceived == Integer.MAX_VALUE) {
            resetStreamId();
            throw new IOException("time to restart the session. cant handle >2 billion pcks");
        }
        if (inSmacksSession) {
            ++stanzasReceived;
        } else if (features.sm()) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": not counting stanza("
                            + element.getClass().getSimpleName()
                            + "). Not in smacks session.");
        }
        lastPacketReceived = SystemClock.elapsedRealtime();
        if (Config.BACKGROUND_STANZA_LOGGING && mXmppConnectionService.checkListeners()) {
            Log.d(Config.LOGTAG, "[background stanza] " + element);
        }
        if (element instanceof IqPacket
                && (((IqPacket) element).getType() == IqPacket.TYPE.SET)
                && element.hasChild("jingle", Namespace.JINGLE)) {
            return JinglePacket.upgrade((IqPacket) element);
        } else {
            return element;
        }
    }

    private void processIq(final Tag currentTag) throws IOException {
        final IqPacket packet = (IqPacket) processPacket(currentTag, PACKET_IQ);
        if (!packet.valid()) {
            Log.e(
                    Config.LOGTAG,
                    "encountered invalid iq from='"
                            + packet.getFrom()
                            + "' to='"
                            + packet.getTo()
                            + "'");
            return;
        }
        if (packet instanceof JinglePacket) {
            if (this.jingleListener != null) {
                this.jingleListener.onJinglePacketReceived(account, (JinglePacket) packet);
            }
        } else {
            OnIqPacketReceived callback = null;
            synchronized (this.packetCallbacks) {
                final Pair<IqPacket, OnIqPacketReceived> packetCallbackDuple =
                        packetCallbacks.get(packet.getId());
                if (packetCallbackDuple != null) {
                    // Packets to the server should have responses from the server
                    if (packetCallbackDuple.first.toServer(account)) {
                        if (packet.fromServer(account)) {
                            callback = packetCallbackDuple.second;
                            packetCallbacks.remove(packet.getId());
                        } else {
                            Log.e(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString()
                                            + ": ignoring spoofed iq packet");
                        }
                    } else {
                        if (packet.getFrom() != null
                                && packet.getFrom().equals(packetCallbackDuple.first.getTo())) {
                            callback = packetCallbackDuple.second;
                            packetCallbacks.remove(packet.getId());
                        } else {
                            Log.e(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid().toString()
                                            + ": ignoring spoofed iq packet");
                        }
                    }
                } else if (packet.getType() == IqPacket.TYPE.GET
                        || packet.getType() == IqPacket.TYPE.SET) {
                    callback = this.unregisteredIqListener;
                }
            }
            if (callback != null) {
                try {
                    callback.onIqPacketReceived(account, packet);
                } catch (StateChangingError error) {
                    throw new StateChangingException(error.state);
                }
            }
        }
    }

    private void processMessage(final Tag currentTag) throws IOException {
        final MessagePacket packet = (MessagePacket) processPacket(currentTag, PACKET_MESSAGE);
        if (!packet.valid()) {
            Log.e(
                    Config.LOGTAG,
                    "encountered invalid message from='"
                            + packet.getFrom()
                            + "' to='"
                            + packet.getTo()
                            + "'");
            return;
        }
        this.messageListener.onMessagePacketReceived(account, packet);
    }

    private void processPresence(final Tag currentTag) throws IOException {
        PresencePacket packet = (PresencePacket) processPacket(currentTag, PACKET_PRESENCE);
        if (!packet.valid()) {
            Log.e(
                    Config.LOGTAG,
                    "encountered invalid presence from='"
                            + packet.getFrom()
                            + "' to='"
                            + packet.getTo()
                            + "'");
            return;
        }
        this.presenceListener.onPresencePacketReceived(account, packet);
    }

    private void sendStartTLS() throws IOException {
        final Tag startTLS = Tag.empty("starttls");
        startTLS.setAttribute("xmlns", Namespace.TLS);
        tagWriter.writeTag(startTLS);
    }

    private void switchOverToTls() throws XmlPullParserException, IOException {
        tagReader.readTag();
        final Socket socket = this.socket;
        final SSLSocket sslSocket = upgradeSocketToTls(socket);
        tagReader.setInputStream(sslSocket.getInputStream());
        tagWriter.setOutputStream(sslSocket.getOutputStream());
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": TLS connection established");
        final boolean quickStart;
        try {
            quickStart = establishStream(SSLSockets.version(sslSocket));
        } catch (final InterruptedException e) {
            return;
        }
        if (quickStart) {
            this.quickStartInProgress = true;
        }
        features.encryptionEnabled = true;
        final Tag tag = tagReader.readTag();
        if (tag != null && tag.isStart("stream", Namespace.STREAMS)) {
            SSLSockets.log(account, sslSocket);
            processStream();
        } else {
            throw new StateChangingException(Account.State.STREAM_OPENING_ERROR);
        }
        sslSocket.close();
    }

    private SSLSocket upgradeSocketToTls(final Socket socket) throws IOException {
        final SSLSocketFactory sslSocketFactory;
        try {
            sslSocketFactory = getSSLSocketFactory();
        } catch (final NoSuchAlgorithmException | KeyManagementException e) {
            throw new StateChangingException(Account.State.TLS_ERROR);
        }
        final InetAddress address = socket.getInetAddress();
        final SSLSocket sslSocket =
                (SSLSocket)
                        sslSocketFactory.createSocket(
                                socket, address.getHostAddress(), socket.getPort(), true);
        SSLSockets.setSecurity(sslSocket);
        SSLSockets.setHostname(sslSocket, IDN.toASCII(account.getServer()));
        SSLSockets.setApplicationProtocol(sslSocket, "xmpp-client");
        final XmppDomainVerifier xmppDomainVerifier = new XmppDomainVerifier();
        try {
            if (!xmppDomainVerifier.verify(
                    account.getServer(), this.verifiedHostname, sslSocket.getSession())) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": TLS certificate domain verification failed");
                FileBackend.close(sslSocket);
                throw new StateChangingException(Account.State.TLS_ERROR_DOMAIN);
            }
        } catch (final SSLPeerUnverifiedException e) {
            FileBackend.close(sslSocket);
            throw new StateChangingException(Account.State.TLS_ERROR);
        }
        return sslSocket;
    }

    private void processStreamFeatures(final Tag currentTag) throws IOException {
        this.streamFeatures = tagReader.readElement(currentTag);
        final boolean isSecure = isSecure();
        final boolean needsBinding = !isBound && !account.isOptionSet(Account.OPTION_REGISTER);
        if (this.quickStartInProgress) {
            if (this.streamFeatures.hasChild("authentication", Namespace.SASL_2)) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": quick start in progress. ignoring features: "
                                + XmlHelper.printElementNames(this.streamFeatures));
                if (SaslMechanism.hashedToken(this.saslMechanism)) {
                    return;
                }
                if (isFastTokenAvailable(
                        this.streamFeatures.findChild("authentication", Namespace.SASL_2))) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": fast token available; resetting quick start");
                    account.setOption(Account.OPTION_QUICKSTART_AVAILABLE, false);
                    mXmppConnectionService.databaseBackend.updateAccount(account);
                }
                return;
            }
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": server lost support for SASL 2. quick start not possible");
            this.account.setOption(Account.OPTION_QUICKSTART_AVAILABLE, false);
            mXmppConnectionService.databaseBackend.updateAccount(account);
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
        if (this.streamFeatures.hasChild("starttls", Namespace.TLS)
                && !features.encryptionEnabled) {
            sendStartTLS();
        } else if (this.streamFeatures.hasChild("register", Namespace.REGISTER_STREAM_FEATURE)
                && account.isOptionSet(Account.OPTION_REGISTER)) {
            if (isSecure) {
                register();
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": unable to find STARTTLS for registration process "
                                + XmlHelper.printElementNames(this.streamFeatures));
                throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
            }
        } else if (!this.streamFeatures.hasChild("register", Namespace.REGISTER_STREAM_FEATURE)
                && account.isOptionSet(Account.OPTION_REGISTER)) {
            throw new StateChangingException(Account.State.REGISTRATION_NOT_SUPPORTED);
        } else if (this.streamFeatures.hasChild("authentication", Namespace.SASL_2)
                && shouldAuthenticate
                && isSecure) {
            authenticate(SaslMechanism.Version.SASL_2);
        } else if (this.streamFeatures.hasChild("mechanisms", Namespace.SASL)
                && shouldAuthenticate
                && isSecure) {
            authenticate(SaslMechanism.Version.SASL);
        } else if (this.streamFeatures.hasChild("sm", Namespace.STREAM_MANAGEMENT)
                && streamId != null
                && !inSmacksSession) {
            if (Config.EXTENDED_SM_LOGGING) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": resuming after stanza #"
                                + stanzasReceived);
            }
            final ResumePacket resume = new ResumePacket(this.streamId, stanzasReceived);
            this.mSmCatchupMessageCounter.set(0);
            this.mWaitingForSmCatchup.set(true);
            this.tagWriter.writeStanzaAsync(resume);
        } else if (needsBinding) {
            if (this.streamFeatures.hasChild("bind", Namespace.BIND) && isSecure) {
                sendBindRequest();
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": unable to find bind feature "
                                + XmlHelper.printElementNames(this.streamFeatures));
                throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
            }
        } else {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": received NOP stream features: "
                            + XmlHelper.printElementNames(this.streamFeatures));
        }
    }

    private void authenticate() throws IOException {
        final boolean isSecure = isSecure();
        if (isSecure && this.streamFeatures.hasChild("authentication", Namespace.SASL_2)) {authenticate(SaslMechanism.Version.SASL_2);
        } else if (isSecure && this.streamFeatures.hasChild("mechanisms", Namespace.SASL)) {
            authenticate(SaslMechanism.Version.SASL);
        } else {
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
    }

    private boolean isSecure() {
        return features.encryptionEnabled || Config.ALLOW_NON_TLS_CONNECTIONS || account.isOnion();
    }

    private void authenticate(final SaslMechanism.Version version) throws IOException {
        final Element authElement;
        if (version == SaslMechanism.Version.SASL) {
            authElement = this.streamFeatures.findChild("mechanisms", Namespace.SASL);
        } else {
            authElement = this.streamFeatures.findChild("authentication", Namespace.SASL_2);
        }
        final Collection<String> mechanisms = SaslMechanism.mechanisms(authElement);
        final Element cbElement =
                this.streamFeatures.findChild("sasl-channel-binding", Namespace.CHANNEL_BINDING);
        final Collection<ChannelBinding> channelBindings = ChannelBinding.of(cbElement);
        final SaslMechanism.Factory factory = new SaslMechanism.Factory(account);
        final SaslMechanism saslMechanism = factory.of(mechanisms, channelBindings, version, SSLSockets.version(this.socket));
        this.saslMechanism = validate(saslMechanism, mechanisms);
        final boolean quickStartAvailable;
        final String firstMessage = this.saslMechanism.getClientFirstMessage(sslSocketOrNull(this.socket));
        final boolean usingFast = SaslMechanism.hashedToken(this.saslMechanism);
        final Element authenticate;
        if (version == SaslMechanism.Version.SASL) {
            authenticate = new Element("auth", Namespace.SASL);
            if (!Strings.isNullOrEmpty(firstMessage)) {
                authenticate.setContent(firstMessage);
            }
            quickStartAvailable = false;
        } else if (version == SaslMechanism.Version.SASL_2) {
            final Element inline = authElement.findChild("inline", Namespace.SASL_2);
            final boolean sm = inline != null && inline.hasChild("sm", "urn:xmpp:sm:3");
            final HashedToken.Mechanism hashTokenRequest;
            if (usingFast) {
                hashTokenRequest = null;
            } else {
                final Element fast = inline == null ? null : inline.findChild("fast", Namespace.FAST);
                final Collection<String> fastMechanisms = SaslMechanism.mechanisms(fast);
                hashTokenRequest =
                        HashedToken.Mechanism.best(fastMechanisms, SSLSockets.version(this.socket));
            }
            final Collection<String> bindFeatures = Bind2.features(inline);
            quickStartAvailable =
                    sm
                            && bindFeatures != null
                            && bindFeatures.containsAll(Bind2.QUICKSTART_FEATURES);
            if (bindFeatures != null) {
                try {
                    mXmppConnectionService.restoredFromDatabaseLatch.await();
                } catch (final InterruptedException e) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": interrupted while waiting for DB restore during SASL2 bind");
                    return;
                }
            }
            this.hashTokenRequest = hashTokenRequest;
            authenticate = generateAuthenticationRequest(firstMessage, usingFast, hashTokenRequest, bindFeatures, sm);
        } else {
            throw new AssertionError("Missing implementation for " + version);
        }

        if (account.setOption(Account.OPTION_QUICKSTART_AVAILABLE, quickStartAvailable)) {
            mXmppConnectionService.databaseBackend.updateAccount(account);
        }

        Log.d(
                Config.LOGTAG,
                account.getJid().toString()
                        + ": Authenticating with "
                        + version
                        + "/"
                        + this.saslMechanism.getMechanism());
        authenticate.setAttribute("mechanism", this.saslMechanism.getMechanism());
        tagWriter.writeElement(authenticate);
    }

    private static boolean isFastTokenAvailable(final Element authentication) {
        final Element inline = authentication == null ? null : authentication.findChild("inline");
        return inline != null && inline.hasChild("fast", Namespace.FAST);
    }

    @NonNull
    private SaslMechanism validate(final @Nullable SaslMechanism saslMechanism, Collection<String> mechanisms) throws StateChangingException {
        if (saslMechanism == null) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": unable to find supported SASL mechanism in "
                            + mechanisms);
            throw new StateChangingException(Account.State.INCOMPATIBLE_SERVER);
        }
        if (SaslMechanism.hashedToken(saslMechanism)) {
            return saslMechanism;
        }
        final int pinnedMechanism = account.getPinnedMechanismPriority();
        if (pinnedMechanism > saslMechanism.getPriority()) {
            Log.e(
                    Config.LOGTAG,
                    "Auth failed. Authentication mechanism "
                            + saslMechanism.getMechanism()
                            + " has lower priority ("
                            + saslMechanism.getPriority()
                            + ") than pinned priority ("
                            + pinnedMechanism
                            + "). Possible downgrade attack?");
            throw new StateChangingException(Account.State.DOWNGRADE_ATTACK);
        }
        return saslMechanism;
    }

    private Element generateAuthenticationRequest(final String firstMessage, final boolean usingFast) {
        return generateAuthenticationRequest(firstMessage, usingFast, null, Bind2.QUICKSTART_FEATURES, true);
    }

    private Element generateAuthenticationRequest(
            final String firstMessage,
            final boolean usingFast,
            final HashedToken.Mechanism hashedTokenRequest,
            final Collection<String> bind,
            final boolean inlineStreamManagement) {
        final Element authenticate = new Element("authenticate", Namespace.SASL_2);
        if (!Strings.isNullOrEmpty(firstMessage)) {
            authenticate.addChild("initial-response").setContent(firstMessage);
        }
        final Element userAgent = authenticate.addChild("user-agent");
        userAgent.setAttribute("id", account.getUuid());
        userAgent
                .addChild("software")
                .setContent(mXmppConnectionService.getString(R.string.app_name));
        if (!PhoneHelper.isEmulator()) {
            userAgent
                    .addChild("device")
                    .setContent(String.format("%s %s", Build.MANUFACTURER, Build.MODEL));
        }
        if (bind != null) {
            authenticate.addChild(generateBindRequest(bind));
        }
        if (inlineStreamManagement && streamId != null) {
            final ResumePacket resume = new ResumePacket(this.streamId, stanzasReceived);
            this.mSmCatchupMessageCounter.set(0);
            this.mWaitingForSmCatchup.set(true);
            authenticate.addChild(resume);
        }
        if (hashedTokenRequest != null) {
            authenticate
                    .addChild("request-token", Namespace.FAST)
                    .setAttribute("mechanism", hashedTokenRequest.name());
        }
        if (usingFast) {
            authenticate.addChild("fast", Namespace.FAST);
        }
        return authenticate;
    }

    private Element generateBindRequest(final Collection<String> bindFeatures) {
        Log.d(Config.LOGTAG, "inline bind features: " + bindFeatures);
        final Element bind = new Element("bind", Namespace.BIND2);
        bind.addChild("tag").setContent(mXmppConnectionService.getString(R.string.app_name));
        if (bindFeatures.contains(Namespace.CARBONS)) {
            bind.addChild("enable", Namespace.CARBONS);
        }
        if (bindFeatures.contains(Namespace.STREAM_MANAGEMENT)) {
            bind.addChild(new EnablePacket());
        }
        return bind;
    }

    private void register() {
        final String preAuth = account.getKey(Account.KEY_PRE_AUTH_REGISTRATION_TOKEN);
        if (preAuth != null && features.invite()) {
            final IqPacket preAuthRequest = new IqPacket(IqPacket.TYPE.SET);
            preAuthRequest.addChild("preauth", Namespace.PARS).setAttribute("token", preAuth);
            sendUnmodifiedIqPacket(
                    preAuthRequest,
                    (account, response) -> {
                        if (response.getType() == IqPacket.TYPE.RESULT) {
                            sendRegistryRequest();
                        } else {
                            final String error = response.getErrorCondition();
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": failed to pre auth. "
                                            + error);
                            throw new StateChangingError(Account.State.REGISTRATION_INVALID_TOKEN);
                        }
                    },
                    true);
        } else {
            sendRegistryRequest();
        }
    }

    private void sendRegistryRequest() {
        final IqPacket register = new IqPacket(IqPacket.TYPE.GET);
        register.query(Namespace.REGISTER);
        register.setTo(account.getDomain());
        sendUnmodifiedIqPacket(
                register,
                (account, packet) -> {
                    if (packet.getType() == IqPacket.TYPE.TIMEOUT) {
                        return;
                    }
                    if (packet.getType() == IqPacket.TYPE.ERROR) {
                        throw new StateChangingError(Account.State.REGISTRATION_FAILED);
                    }
                    final Element query = packet.query(Namespace.REGISTER);
                    if (query.hasChild("username") && (query.hasChild("password"))) {
                        final IqPacket register1 = new IqPacket(IqPacket.TYPE.SET);
                        final Element username =
                                new Element("username").setContent(account.getUsername());
                        final Element password =
                                new Element("password").setContent(account.getPassword());
                        register1.query(Namespace.REGISTER).addChild(username);
                        register1.query().addChild(password);
                        register1.setFrom(account.getJid().asBareJid());
                        sendUnmodifiedIqPacket(register1, registrationResponseListener, true);
                    } else if (query.hasChild("x", Namespace.DATA)) {
                        final Data data = Data.parse(query.findChild("x", Namespace.DATA));
                        final Element blob = query.findChild("data", "urn:xmpp:bob");
                        final String id = packet.getId();
                        InputStream is;
                        if (blob != null) {
                            try {
                                final String base64Blob = blob.getContent();
                                final byte[] strBlob = Base64.decode(base64Blob, Base64.DEFAULT);
                                is = new ByteArrayInputStream(strBlob);
                            } catch (Exception e) {
                                is = null;
                            }
                        } else {
                            final boolean useTor =
                                    mXmppConnectionService.useTorToConnect() || account.isOnion();
                            try {
                                final String url = data.getValue("url");
                                final String fallbackUrl = data.getValue("captcha-fallback-url");
                                if (url != null) {
                                    is = HttpConnectionManager.open(url, useTor);
                                } else if (fallbackUrl != null) {
                                    is = HttpConnectionManager.open(fallbackUrl, useTor);
                                } else {
                                    is = null;
                                }
                            } catch (final IOException e) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid() + ": unable to fetch captcha",
                                        e);
                                is = null;
                            }
                        }

                        if (is != null) {
                            Bitmap captcha = BitmapFactory.decodeStream(is);
                            try {
                                if (mXmppConnectionService.displayCaptchaRequest(
                                        account, id, data, captcha)) {
                                    return;
                                }
                            } catch (Exception e) {
                                throw new StateChangingError(Account.State.REGISTRATION_FAILED);
                            }
                        }
                        throw new StateChangingError(Account.State.REGISTRATION_FAILED);
                    } else if (query.hasChild("instructions")
                            || query.hasChild("x", Namespace.OOB)) {
                        final String instructions = query.findChildContent("instructions");
                        final Element oob = query.findChild("x", Namespace.OOB);
                        final String url = oob == null ? null : oob.findChildContent("url");
                        if (url != null) {
                            setAccountCreationFailed(url);
                        } else if (instructions != null) {
                            final Matcher matcher = Patterns.AUTOLINK_WEB_URL.matcher(instructions);
                            if (matcher.find()) {
                                setAccountCreationFailed(
                                        instructions.substring(matcher.start(), matcher.end()));
                            }
                        }
                        throw new StateChangingError(Account.State.REGISTRATION_FAILED);
                    }
                },
                true);
    }

    private void setAccountCreationFailed(final String url) {
        final HttpUrl httpUrl = url == null ? null : HttpUrl.parse(url);
        if (httpUrl != null && httpUrl.isHttps()) {
            this.redirectionUrl = httpUrl;
            throw new StateChangingError(Account.State.REGISTRATION_WEB);
        }
        throw new StateChangingError(Account.State.REGISTRATION_FAILED);
    }

    public HttpUrl getRedirectionUrl() {
        return this.redirectionUrl;
    }

    public void resetEverything() {
        resetAttemptCount(true);
        resetStreamId();
        clearIqCallbacks();
        this.stanzasSent = 0;
        mStanzaQueue.clear();
        this.redirectionUrl = null;
        synchronized (this.disco) {
            disco.clear();
        }
        synchronized (this.commands) {
            this.commands.clear();
        }
        this.saslMechanism = null;
    }

    private void sendBindRequest() {
        try {
            mXmppConnectionService.restoredFromDatabaseLatch.await();
        } catch (InterruptedException e) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": interrupted while waiting for DB restore during bind");
            return;
        }
        clearIqCallbacks();
        if (account.getJid().isBareJid()) {
            account.setResource(this.createNewResource());
        } else {
            fixResource(mXmppConnectionService, account);
        }
        final IqPacket iq = new IqPacket(IqPacket.TYPE.SET);
        final String resource =
                Config.USE_RANDOM_RESOURCE_ON_EVERY_BIND ? nextRandomId() : account.getResource();
        iq.addChild("bind", Namespace.BIND).addChild("resource").setContent(resource);
        this.sendUnmodifiedIqPacket(
                iq,
                (account, packet) -> {
                    if (packet.getType() == IqPacket.TYPE.TIMEOUT) {
                        return;
                    }
                    final Element bind = packet.findChild("bind");
                    if (bind != null && packet.getType() == IqPacket.TYPE.RESULT) {
                        isBound = true;
                        final Element jid = bind.findChild("jid");
                        if (jid != null && jid.getContent() != null) {
                            try {
                                Jid assignedJid = Jid.ofEscaped(jid.getContent());
                                if (!account.getJid().getDomain().equals(assignedJid.getDomain())) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": server tried to re-assign domain to "
                                                    + assignedJid.getDomain());
                                    throw new StateChangingError(Account.State.BIND_FAILURE);
                                }
                                if (account.setJid(assignedJid)) {
                                    Log.d(
                                            Config.LOGTAG,
                                            account.getJid().asBareJid()
                                                    + ": jid changed during bind. updating database");
                                    mXmppConnectionService.databaseBackend.updateAccount(account);
                                }
                                if (streamFeatures.hasChild("session")
                                        && !streamFeatures
                                                .findChild("session")
                                                .hasChild("optional")) {
                                    sendStartSession();
                                } else {
                                    final boolean waitForDisco = enableStreamManagement();
                                    sendPostBindInitialization(waitForDisco, false);
                                }
                                return;
                            } catch (final IllegalArgumentException e) {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": server reported invalid jid ("
                                                + jid.getContent()
                                                + ") on bind");
                            }
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid()
                                            + ": disconnecting because of bind failure. (no jid)");
                        }
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid()
                                        + ": disconnecting because of bind failure ("
                                        + packet);
                    }
                    final Element error = packet.findChild("error");
                    if (packet.getType() == IqPacket.TYPE.ERROR
                            && error != null
                            && error.hasChild("conflict")) {
                        account.setResource(createNewResource());
                    }
                    throw new StateChangingError(Account.State.BIND_FAILURE);
                },
                true);
    }

    private void clearIqCallbacks() {
        final IqPacket failurePacket = new IqPacket(IqPacket.TYPE.TIMEOUT);
        final ArrayList<OnIqPacketReceived> callbacks = new ArrayList<>();
        synchronized (this.packetCallbacks) {
            if (this.packetCallbacks.size() == 0) {
                return;
            }
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": clearing "
                            + this.packetCallbacks.size()
                            + " iq callbacks");
            final Iterator<Pair<IqPacket, OnIqPacketReceived>> iterator =
                    this.packetCallbacks.values().iterator();
            while (iterator.hasNext()) {
                Pair<IqPacket, OnIqPacketReceived> entry = iterator.next();
                callbacks.add(entry.second);
                iterator.remove();
            }
        }
        for (OnIqPacketReceived callback : callbacks) {
            try {
                callback.onIqPacketReceived(account, failurePacket);
            } catch (StateChangingError error) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": caught StateChangingError("
                                + error.state.toString()
                                + ") while clearing callbacks");
                // ignore
            }
        }
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid()
                        + ": done clearing iq callbacks. "
                        + this.packetCallbacks.size()
                        + " left");
    }

    public void sendDiscoTimeout() {
        if (mWaitForDisco.compareAndSet(true, false)) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": finalizing bind after disco timeout");
            finalizeBind();
        }
    }

    private void sendStartSession() {
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid() + ": sending legacy session to outdated server");
        final IqPacket startSession = new IqPacket(IqPacket.TYPE.SET);
        startSession.addChild("session", "urn:ietf:params:xml:ns:xmpp-session");
        this.sendUnmodifiedIqPacket(
                startSession,
                (account, packet) -> {
                    if (packet.getType() == IqPacket.TYPE.RESULT) {
                        final boolean waitForDisco = enableStreamManagement();
                        sendPostBindInitialization(waitForDisco, false);
                    } else if (packet.getType() != IqPacket.TYPE.TIMEOUT) {
                        throw new StateChangingError(Account.State.SESSION_FAILURE);
                    }
                },
                true);
    }

    private boolean enableStreamManagement() {
        final boolean streamManagement =
                this.streamFeatures.hasChild("sm", Namespace.STREAM_MANAGEMENT);
        if (streamManagement) {
            synchronized (this.mStanzaQueue) {
                final EnablePacket enable = new EnablePacket();
                tagWriter.writeStanzaAsync(enable);
                stanzasSent = 0;
                mStanzaQueue.clear();
            }
            return true;
        } else {
            return false;
        }
    }

    private void sendPostBindInitialization(
            final boolean waitForDisco, final boolean carbonsEnabled) {
        features.carbonsEnabled = carbonsEnabled;
        features.blockListRequested = false;
        synchronized (this.disco) {
            this.disco.clear();
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": starting service discovery");
        mPendingServiceDiscoveries.set(0);
        if (!waitForDisco
                || Patches.DISCO_EXCEPTIONS.contains(
                        account.getJid().getDomain().toEscapedString())) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid() + ": do not wait for service discovery");
            mWaitForDisco.set(false);
        } else {
            mWaitForDisco.set(true);
        }
        lastDiscoStarted = SystemClock.elapsedRealtime();
        mXmppConnectionService.scheduleWakeUpCall(
                Config.CONNECT_DISCO_TIMEOUT, account.getUuid().hashCode());
        final Element caps = streamFeatures.findChild("c");
        final String hash = caps == null ? null : caps.getAttribute("hash");
        final String ver = caps == null ? null : caps.getAttribute("ver");
        ServiceDiscoveryResult discoveryResult = null;
        if (hash != null && ver != null) {
            discoveryResult =
                    mXmppConnectionService.getCachedServiceDiscoveryResult(new Pair<>(hash, ver));
        }
        final boolean requestDiscoItemsFirst =
                !account.isOptionSet(Account.OPTION_LOGGED_IN_SUCCESSFULLY);
        if (requestDiscoItemsFirst) {
            sendServiceDiscoveryItems(account.getDomain());
        }
        if (discoveryResult == null) {
            sendServiceDiscoveryInfo(account.getDomain());
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": server caps came from cache");
            disco.put(account.getDomain(), discoveryResult);
        }
        discoverMamPreferences();
        sendServiceDiscoveryInfo(account.getJid().asBareJid());
        if (!requestDiscoItemsFirst) {
            sendServiceDiscoveryItems(account.getDomain());
        }

        if (!mWaitForDisco.get()) {
            finalizeBind();
        }
        this.lastSessionStarted = SystemClock.elapsedRealtime();
    }

    private void sendServiceDiscoveryInfo(final Jid jid) {
        mPendingServiceDiscoveries.incrementAndGet();
        final IqPacket iq = new IqPacket(IqPacket.TYPE.GET);
        iq.setTo(jid);
        iq.query("http://jabber.org/protocol/disco#info");
        this.sendIqPacket(
                iq,
                (account, packet) -> {
                    if (packet.getType() == IqPacket.TYPE.RESULT) {
                        boolean advancedStreamFeaturesLoaded;
                        synchronized (XmppConnection.this.disco) {
                            ServiceDiscoveryResult result = new ServiceDiscoveryResult(packet);
                            if (jid.equals(account.getDomain())) {
                                mXmppConnectionService.databaseBackend.insertDiscoveryResult(
                                        result);
                            }
                            disco.put(jid, result);
                            advancedStreamFeaturesLoaded =
                                    disco.containsKey(account.getDomain())
                                            && disco.containsKey(account.getJid().asBareJid());
                        }
                        if (advancedStreamFeaturesLoaded
                                && (jid.equals(account.getDomain())
                                        || jid.equals(account.getJid().asBareJid()))) {
                            enableAdvancedStreamFeatures();
                        }
                    } else if (packet.getType() == IqPacket.TYPE.ERROR) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": could not query disco info for "
                                        + jid.toString());
                        final boolean serverOrAccount =
                                jid.equals(account.getDomain())
                                        || jid.equals(account.getJid().asBareJid());
                        final boolean advancedStreamFeaturesLoaded;
                        if (serverOrAccount) {
                            synchronized (XmppConnection.this.disco) {
                                disco.put(jid, ServiceDiscoveryResult.empty());
                                advancedStreamFeaturesLoaded =
                                        disco.containsKey(account.getDomain())
                                                && disco.containsKey(account.getJid().asBareJid());
                            }
                        } else {
                            advancedStreamFeaturesLoaded = false;
                        }
                        if (advancedStreamFeaturesLoaded) {
                            enableAdvancedStreamFeatures();
                        }
                    }
                    if (packet.getType() != IqPacket.TYPE.TIMEOUT) {
                        if (mPendingServiceDiscoveries.decrementAndGet() == 0
                                && mWaitForDisco.compareAndSet(true, false)) {
                            finalizeBind();
                        }
                    }
                });
    }

    private void discoverMamPreferences() {
        IqPacket request = new IqPacket(IqPacket.TYPE.GET);
        request.addChild("prefs", MessageArchiveService.Version.MAM_2.namespace);
        sendIqPacket(
                request,
                (account, response) -> {
                    if (response.getType() == IqPacket.TYPE.RESULT) {
                        Element prefs =
                                response.findChild(
                                        "prefs", MessageArchiveService.Version.MAM_2.namespace);
                        isMamPreferenceAlways =
                                "always"
                                        .equals(
                                                prefs == null
                                                        ? null
                                                        : prefs.getAttribute("default"));
                    }
                });
    }

    private void discoverCommands() {
        final IqPacket request = new IqPacket(IqPacket.TYPE.GET);
        request.setTo(account.getDomain());
        request.addChild("query", Namespace.DISCO_ITEMS).setAttribute("node", Namespace.COMMANDS);
        sendIqPacket(
                request,
                (account, response) -> {
                    if (response.getType() == IqPacket.TYPE.RESULT) {
                        final Element query = response.findChild("query", Namespace.DISCO_ITEMS);
                        if (query == null) {
                            return;
                        }
                        final HashMap<String, Jid> commands = new HashMap<>();
                        for (final Element child : query.getChildren()) {
                            if ("item".equals(child.getName())) {
                                final String node = child.getAttribute("node");
                                final Jid jid = child.getAttributeAsJid("jid");
                                if (node != null && jid != null) {
                                    commands.put(node, jid);
                                }
                            }
                        }
                        synchronized (this.commands) {
                            this.commands.clear();
                            this.commands.putAll(commands);
                        }
                    }
                });
    }

    public boolean isMamPreferenceAlways() {
        return isMamPreferenceAlways;
    }

    private void finalizeBind() {
        if (bindListener != null) {
            bindListener.onBind(account);
        }
        changeStatusToOnline();
    }

    private void enableAdvancedStreamFeatures() {
        if (getFeatures().blocking() && !features.blockListRequested) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": Requesting block list");
            this.sendIqPacket(
                    getIqGenerator().generateGetBlockList(), mXmppConnectionService.getIqParser());
        }
        for (final OnAdvancedStreamFeaturesLoaded listener :
                advancedStreamFeaturesLoadedListeners) {
            listener.onAdvancedStreamFeaturesAvailable(account);
        }
        if (getFeatures().carbons() && !features.carbonsEnabled) {
            sendEnableCarbons();
        }
        if (getFeatures().commands()) {
            discoverCommands();
        }
    }

    private void sendServiceDiscoveryItems(final Jid server) {
        mPendingServiceDiscoveries.incrementAndGet();
        final IqPacket iq = new IqPacket(IqPacket.TYPE.GET);
        iq.setTo(server.getDomain());
        iq.query("http://jabber.org/protocol/disco#items");
        this.sendIqPacket(
                iq,
                (account, packet) -> {
                    if (packet.getType() == IqPacket.TYPE.RESULT) {
                        final HashSet<Jid> items = new HashSet<>();
                        final List<Element> elements = packet.query().getChildren();
                        for (final Element element : elements) {
                            if (element.getName().equals("item")) {
                                final Jid jid =
                                        InvalidJid.getNullForInvalid(
                                                element.getAttributeAsJid("jid"));
                                if (jid != null && !jid.equals(account.getDomain())) {
                                    items.add(jid);
                                }
                            }
                        }
                        for (Jid jid : items) {
                            sendServiceDiscoveryInfo(jid);
                        }
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": could not query disco items of "
                                        + server);
                    }
                    if (packet.getType() != IqPacket.TYPE.TIMEOUT) {
                        if (mPendingServiceDiscoveries.decrementAndGet() == 0
                                && mWaitForDisco.compareAndSet(true, false)) {
                            finalizeBind();
                        }
                    }
                });
    }

    private void sendEnableCarbons() {
        final IqPacket iq = new IqPacket(IqPacket.TYPE.SET);
        iq.addChild("enable", Namespace.CARBONS);
        this.sendIqPacket(
                iq,
                (account, packet) -> {
                    if (packet.getType() == IqPacket.TYPE.RESULT) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid() + ": successfully enabled carbons");
                        features.carbonsEnabled = true;
                    } else {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": could not enable carbons "
                                        + packet);
                    }
                });
    }

    private void processStreamError(final Tag currentTag) throws IOException {
        final Element streamError = tagReader.readElement(currentTag);
        if (streamError == null) {
            return;
        }
        if (streamError.hasChild("conflict")) {
            account.setResource(createNewResource());
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": switching resource due to conflict ("
                            + account.getResource()
                            + ")");
            throw new IOException();
        } else if (streamError.hasChild("host-unknown")) {
            throw new StateChangingException(Account.State.HOST_UNKNOWN);
        } else if (streamError.hasChild("policy-violation")) {
            this.lastConnect = SystemClock.elapsedRealtime();
            final String text = streamError.findChildContent("text");
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": policy violation. " + text);
            failPendingMessages(text);
            throw new StateChangingException(Account.State.POLICY_VIOLATION);
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": stream error " + streamError);
            throw new StateChangingException(Account.State.STREAM_ERROR);
        }
    }

    private void failPendingMessages(final String error) {
        synchronized (this.mStanzaQueue) {
            for (int i = 0; i < mStanzaQueue.size(); ++i) {
                final AbstractAcknowledgeableStanza stanza = mStanzaQueue.valueAt(i);
                if (stanza instanceof MessagePacket) {
                    final MessagePacket packet = (MessagePacket) stanza;
                    final String id = packet.getId();
                    final Jid to = packet.getTo();
                    mXmppConnectionService.markMessage(
                            account, to.asBareJid(), id, Message.STATUS_SEND_FAILED, error);
                }
            }
        }
    }

    private boolean establishStream(final SSLSockets.Version sslVersion)
            throws IOException, InterruptedException {
        final SaslMechanism quickStartMechanism =
                SaslMechanism.ensureAvailable(account.getQuickStartMechanism(), sslVersion);
        final boolean secureConnection = sslVersion != SSLSockets.Version.NONE;
        if (secureConnection
                && Config.QUICKSTART_ENABLED
                && quickStartMechanism != null
                && account.isOptionSet(Account.OPTION_QUICKSTART_AVAILABLE)) {
            mXmppConnectionService.restoredFromDatabaseLatch.await();
            this.saslMechanism = quickStartMechanism;
            final boolean usingFast = quickStartMechanism instanceof HashedToken;
            final Element authenticate =
                    generateAuthenticationRequest(quickStartMechanism.getClientFirstMessage(sslSocketOrNull(this.socket)), usingFast);
            authenticate.setAttribute("mechanism", quickStartMechanism.getMechanism());
            sendStartStream(true, false);
            tagWriter.writeElement(authenticate);
            Log.d(
                    Config.LOGTAG,
                    account.getJid().toString()
                            + ": quick start with "
                            + quickStartMechanism.getMechanism());
            return true;
        } else {
            sendStartStream(secureConnection, true);
            return false;
        }
    }

    private void sendStartStream(final boolean from, final boolean flush) throws IOException {
        final Tag stream = Tag.start("stream:stream");
        stream.setAttribute("to", account.getServer());
        if (from) {
            stream.setAttribute("from", account.getJid().asBareJid().toEscapedString());
        }
        stream.setAttribute("version", "1.0");
        stream.setAttribute("xml:lang", LocalizedContent.STREAM_LANGUAGE);
        stream.setAttribute("xmlns", "jabber:client");
        stream.setAttribute("xmlns:stream", Namespace.STREAMS);
        tagWriter.writeTag(stream, flush);
    }

    private String createNewResource() {
        return mXmppConnectionService.getString(R.string.app_name) + '.' + nextRandomId(true);
    }

    private String nextRandomId() {
        return nextRandomId(false);
    }

    private String nextRandomId(final boolean s) {
        return CryptoHelper.random(s ? 3 : 9);
    }

    public String sendIqPacket(final IqPacket packet, final OnIqPacketReceived callback) {
        packet.setFrom(account.getJid());
        return this.sendUnmodifiedIqPacket(packet, callback, false);
    }

    public synchronized String sendUnmodifiedIqPacket(
            final IqPacket packet, final OnIqPacketReceived callback, boolean force) {
        if (packet.getId() == null) {
            packet.setAttribute("id", nextRandomId());
        }
        if (callback != null) {
            synchronized (this.packetCallbacks) {
                packetCallbacks.put(packet.getId(), new Pair<>(packet, callback));
            }
        }
        this.sendPacket(packet, force);
        return packet.getId();
    }

    public void sendMessagePacket(final MessagePacket packet) {
        this.sendPacket(packet);
    }

    public void sendPresencePacket(final PresencePacket packet) {
        this.sendPacket(packet);
    }

    private synchronized void sendPacket(final AbstractStanza packet) {
        sendPacket(packet, false);
    }

    private synchronized void sendPacket(final AbstractStanza packet, final boolean force) {
        if (stanzasSent == Integer.MAX_VALUE) {
            resetStreamId();
            disconnect(true);
            return;
        }
        synchronized (this.mStanzaQueue) {
            if (force || isBound) {
                tagWriter.writeStanzaAsync(packet);
            } else {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + " do not write stanza to unbound stream "
                                + packet.toString());
            }
            if (packet instanceof AbstractAcknowledgeableStanza) {
                AbstractAcknowledgeableStanza stanza = (AbstractAcknowledgeableStanza) packet;

                if (this.mStanzaQueue.size() != 0) {
                    int currentHighestKey = this.mStanzaQueue.keyAt(this.mStanzaQueue.size() - 1);
                    if (currentHighestKey != stanzasSent) {
                        throw new AssertionError("Stanza count messed up");
                    }
                }

                ++stanzasSent;
                this.mStanzaQueue.append(stanzasSent, stanza);
                if (stanza instanceof MessagePacket && stanza.getId() != null && inSmacksSession) {
                    if (Config.EXTENDED_SM_LOGGING) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": requesting ack for message stanza #"
                                        + stanzasSent);
                    }
                    tagWriter.writeStanzaAsync(new RequestPacket());
                }
            }
        }
    }

    public void sendPing() {
        if (!r()) {
            final IqPacket iq = new IqPacket(IqPacket.TYPE.GET);
            iq.setFrom(account.getJid());
            iq.addChild("ping", Namespace.PING);
            this.sendIqPacket(iq, null);
        }
        this.lastPingSent = SystemClock.elapsedRealtime();
    }

    public void setOnMessagePacketReceivedListener(final OnMessagePacketReceived listener) {
        this.messageListener = listener;
    }

    public void setOnUnregisteredIqPacketReceivedListener(final OnIqPacketReceived listener) {
        this.unregisteredIqListener = listener;
    }

    public void setOnPresencePacketReceivedListener(final OnPresencePacketReceived listener) {
        this.presenceListener = listener;
    }

    public void setOnJinglePacketReceivedListener(final OnJinglePacketReceived listener) {
        this.jingleListener = listener;
    }

    public void setOnStatusChangedListener(final OnStatusChanged listener) {
        this.statusListener = listener;
    }

    public void setOnBindListener(final OnBindListener listener) {
        this.bindListener = listener;
    }

    public void setOnMessageAcknowledgeListener(final OnMessageAcknowledged listener) {
        this.acknowledgedListener = listener;
    }

    public void addOnAdvancedStreamFeaturesAvailableListener(
            final OnAdvancedStreamFeaturesLoaded listener) {
        this.advancedStreamFeaturesLoadedListeners.add(listener);
    }

    private void forceCloseSocket() {
        FileBackend.close(this.socket);
        FileBackend.close(this.tagReader);
    }

    public void interrupt() {
        if (this.mThread != null) {
            this.mThread.interrupt();
        }
    }

    public void disconnect(final boolean force) {
        interrupt();
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": disconnecting force=" + force);
        if (force) {
            forceCloseSocket();
        } else {
            final TagWriter currentTagWriter = this.tagWriter;
            if (currentTagWriter.isActive()) {
                currentTagWriter.finish();
                final Socket currentSocket = this.socket;
                final CountDownLatch streamCountDownLatch = this.mStreamCountDownLatch;
                try {
                    currentTagWriter.await(1, TimeUnit.SECONDS);
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": closing stream");
                    currentTagWriter.writeTag(Tag.end("stream:stream"));
                    if (streamCountDownLatch != null) {
                        if (streamCountDownLatch.await(1, TimeUnit.SECONDS)) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid() + ": remote ended stream");
                        } else {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": remote has not closed socket. force closing");
                        }
                    }
                } catch (InterruptedException e) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": interrupted while gracefully closing stream");
                } catch (final IOException e) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": io exception during disconnect ("
                                    + e.getMessage()
                                    + ")");
                } finally {
                    FileBackend.close(currentSocket);
                }
            } else {
                forceCloseSocket();
            }
        }
    }

    private void resetStreamId() {
        this.streamId = null;
    }

    private List<Entry<Jid, ServiceDiscoveryResult>> findDiscoItemsByFeature(final String feature) {
        synchronized (this.disco) {
            final List<Entry<Jid, ServiceDiscoveryResult>> items = new ArrayList<>();
            for (final Entry<Jid, ServiceDiscoveryResult> cursor : this.disco.entrySet()) {
                if (cursor.getValue().getFeatures().contains(feature)) {
                    items.add(cursor);
                }
            }
            return items;
        }
    }

    public Jid findDiscoItemByFeature(final String feature) {
        final List<Entry<Jid, ServiceDiscoveryResult>> items = findDiscoItemsByFeature(feature);
        if (items.size() >= 1) {
            return items.get(0).getKey();
        }
        return null;
    }

    public boolean r() {
        if (getFeatures().sm()) {
            this.tagWriter.writeStanzaAsync(new RequestPacket());
            return true;
        } else {
            return false;
        }
    }

    public List<String> getMucServersWithholdAccount() {
        final List<String> servers = getMucServers();
        servers.remove(account.getDomain().toEscapedString());
        return servers;
    }

    public List<String> getMucServers() {
        List<String> servers = new ArrayList<>();
        synchronized (this.disco) {
            for (final Entry<Jid, ServiceDiscoveryResult> cursor : disco.entrySet()) {
                final ServiceDiscoveryResult value = cursor.getValue();
                if (value.getFeatures().contains("http://jabber.org/protocol/muc")
                        && value.hasIdentity("conference", "text")
                        && !value.getFeatures().contains("jabber:iq:gateway")
                        && !value.hasIdentity("conference", "irc")) {
                    servers.add(cursor.getKey().toString());
                }
            }
        }
        return servers;
    }

    public String getMucServer() {
        List<String> servers = getMucServers();
        return servers.size() > 0 ? servers.get(0) : null;
    }

    public int getTimeToNextAttempt() {
        final int additionalTime =
                account.getLastErrorStatus() == Account.State.POLICY_VIOLATION ? 3 : 0;
        final int interval = Math.min((int) (25 * Math.pow(1.3, (additionalTime + attempt))), 300);
        final int secondsSinceLast =
                (int) ((SystemClock.elapsedRealtime() - this.lastConnect) / 1000);
        return interval - secondsSinceLast;
    }

    public int getAttempt() {
        return this.attempt;
    }

    public Features getFeatures() {
        return this.features;
    }

    public long getLastSessionEstablished() {
        final long diff = SystemClock.elapsedRealtime() - this.lastSessionStarted;
        return System.currentTimeMillis() - diff;
    }

    public long getLastConnect() {
        return this.lastConnect;
    }

    public long getLastPingSent() {
        return this.lastPingSent;
    }

    public long getLastDiscoStarted() {
        return this.lastDiscoStarted;
    }

    public long getLastPacketReceived() {
        return this.lastPacketReceived;
    }

    public void sendActive() {
        this.sendPacket(new ActivePacket());
    }

    public void sendInactive() {
        this.sendPacket(new InactivePacket());
    }

    public void resetAttemptCount(boolean resetConnectTime) {
        this.attempt = 0;
        if (resetConnectTime) {
            this.lastConnect = 0;
        }
    }

    public void setInteractive(boolean interactive) {
        this.mInteractive = interactive;
    }

    public Identity getServerIdentity() {
        synchronized (this.disco) {
            ServiceDiscoveryResult result = disco.get(account.getJid().getDomain());
            if (result == null) {
                return Identity.UNKNOWN;
            }
            for (final ServiceDiscoveryResult.Identity id : result.getIdentities()) {
                if (id.getType().equals("im")
                        && id.getCategory().equals("server")
                        && id.getName() != null) {
                    switch (id.getName()) {
                        case "Prosody":
                            return Identity.PROSODY;
                        case "ejabberd":
                            return Identity.EJABBERD;
                        case "Slack-XMPP":
                            return Identity.SLACK;
                    }
                }
            }
        }
        return Identity.UNKNOWN;
    }

    private IqGenerator getIqGenerator() {
        return mXmppConnectionService.getIqGenerator();
    }

    public enum Identity {
        FACEBOOK,
        SLACK,
        EJABBERD,
        PROSODY,
        NIMBUZZ,
        UNKNOWN
    }

    private class MyKeyManager implements X509KeyManager {
        @Override
        public String chooseClientAlias(String[] strings, Principal[] principals, Socket socket) {
            return account.getPrivateKeyAlias();
        }

        @Override
        public String chooseServerAlias(String s, Principal[] principals, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            Log.d(Config.LOGTAG, "getting certificate chain");
            try {
                return KeyChain.getCertificateChain(mXmppConnectionService, alias);
            } catch (final Exception e) {
                Log.d(Config.LOGTAG, "could not get certificate chain", e);
                return new X509Certificate[0];
            }
        }

        @Override
        public String[] getClientAliases(String s, Principal[] principals) {
            final String alias = account.getPrivateKeyAlias();
            return alias != null ? new String[] {alias} : new String[0];
        }

        @Override
        public String[] getServerAliases(String s, Principal[] principals) {
            return new String[0];
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            try {
                return KeyChain.getPrivateKey(mXmppConnectionService, alias);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static class StateChangingError extends Error {
        private final Account.State state;

        public StateChangingError(Account.State state) {
            this.state = state;
        }
    }

    private static class StateChangingException extends IOException {
        private final Account.State state;

        public StateChangingException(Account.State state) {
            this.state = state;
        }
    }

    public class Features {
        XmppConnection connection;
        private boolean carbonsEnabled = false;
        private boolean encryptionEnabled = false;
        private boolean blockListRequested = false;

        public Features(final XmppConnection connection) {
            this.connection = connection;
        }

        private boolean hasDiscoFeature(final Jid server, final String feature) {
            synchronized (XmppConnection.this.disco) {
                final ServiceDiscoveryResult sdr = connection.disco.get(server);
                return sdr != null && sdr.getFeatures().contains(feature);
            }
        }

        public boolean carbons() {
            return hasDiscoFeature(account.getDomain(), Namespace.CARBONS);
        }

        public boolean commands() {
            return hasDiscoFeature(account.getDomain(), Namespace.COMMANDS);
        }

        public boolean easyOnboardingInvites() {
            synchronized (commands) {
                return commands.containsKey(Namespace.EASY_ONBOARDING_INVITE);
            }
        }

        public boolean bookmarksConversion() {
            return hasDiscoFeature(account.getJid().asBareJid(), Namespace.BOOKMARKS_CONVERSION)
                    && pepPublishOptions();
        }

        public boolean avatarConversion() {
            return hasDiscoFeature(account.getJid().asBareJid(), Namespace.AVATAR_CONVERSION)
                    && pepPublishOptions();
        }

        public boolean blocking() {
            return hasDiscoFeature(account.getDomain(), Namespace.BLOCKING);
        }

        public boolean spamReporting() {
            return hasDiscoFeature(account.getDomain(), "urn:xmpp:reporting:reason:spam:0");
        }

        public boolean flexibleOfflineMessageRetrieval() {
            return hasDiscoFeature(
                    account.getDomain(), Namespace.FLEXIBLE_OFFLINE_MESSAGE_RETRIEVAL);
        }

        public boolean register() {
            return hasDiscoFeature(account.getDomain(), Namespace.REGISTER);
        }

        public boolean invite() {
            return connection.streamFeatures != null
                    && connection.streamFeatures.hasChild("register", Namespace.INVITE);
        }

        public boolean sm() {
            return streamId != null
                    || (connection.streamFeatures != null
                            && connection.streamFeatures.hasChild("sm"));
        }

        public boolean csi() {
            return connection.streamFeatures != null
                    && connection.streamFeatures.hasChild("csi", Namespace.CSI);
        }

        public boolean pep() {
            synchronized (XmppConnection.this.disco) {
                ServiceDiscoveryResult info = disco.get(account.getJid().asBareJid());
                return info != null && info.hasIdentity("pubsub", "pep");
            }
        }

        public boolean pepPersistent() {
            synchronized (XmppConnection.this.disco) {
                ServiceDiscoveryResult info = disco.get(account.getJid().asBareJid());
                return info != null
                        && info.getFeatures()
                                .contains("http://jabber.org/protocol/pubsub#persistent-items");
            }
        }

        public boolean pepPublishOptions() {
            return hasDiscoFeature(account.getJid().asBareJid(), Namespace.PUBSUB_PUBLISH_OPTIONS);
        }

        public boolean pepOmemoWhitelisted() {
            return hasDiscoFeature(
                    account.getJid().asBareJid(), AxolotlService.PEP_OMEMO_WHITELISTED);
        }

        public boolean mam() {
            return MessageArchiveService.Version.has(getAccountFeatures());
        }

        public List<String> getAccountFeatures() {
            ServiceDiscoveryResult result = connection.disco.get(account.getJid().asBareJid());
            return result == null ? Collections.emptyList() : result.getFeatures();
        }

        public boolean push() {
            return hasDiscoFeature(account.getJid().asBareJid(), Namespace.PUSH)
                    || hasDiscoFeature(account.getDomain(), Namespace.PUSH);
        }

        public boolean rosterVersioning() {
            return connection.streamFeatures != null && connection.streamFeatures.hasChild("ver");
        }

        public void setBlockListRequested(boolean value) {
            this.blockListRequested = value;
        }

        public boolean httpUpload(long filesize) {
            if (Config.DISABLE_HTTP_UPLOAD) {
                return false;
            } else {
                for (String namespace :
                        new String[] {Namespace.HTTP_UPLOAD, Namespace.HTTP_UPLOAD_LEGACY}) {
                    List<Entry<Jid, ServiceDiscoveryResult>> items =
                            findDiscoItemsByFeature(namespace);
                    if (items.size() > 0) {
                        try {
                            long maxsize =
                                    Long.parseLong(
                                            items.get(0)
                                                    .getValue()
                                                    .getExtendedDiscoInformation(
                                                            namespace, "max-file-size"));
                            if (filesize <= maxsize) {
                                return true;
                            } else {
                                Log.d(
                                        Config.LOGTAG,
                                        account.getJid().asBareJid()
                                                + ": http upload is not available for files with size "
                                                + filesize
                                                + " (max is "
                                                + maxsize
                                                + ")");
                                return false;
                            }
                        } catch (Exception e) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }

        public boolean useLegacyHttpUpload() {
            return findDiscoItemByFeature(Namespace.HTTP_UPLOAD) == null
                    && findDiscoItemByFeature(Namespace.HTTP_UPLOAD_LEGACY) != null;
        }

        public long getMaxHttpUploadSize() {
            for (String namespace :
                    new String[] {Namespace.HTTP_UPLOAD, Namespace.HTTP_UPLOAD_LEGACY}) {
                List<Entry<Jid, ServiceDiscoveryResult>> items = findDiscoItemsByFeature(namespace);
                if (items.size() > 0) {
                    try {
                        return Long.parseLong(
                                items.get(0)
                                        .getValue()
                                        .getExtendedDiscoInformation(namespace, "max-file-size"));
                    } catch (Exception e) {
                        // ignored
                    }
                }
            }
            return -1;
        }

        public boolean stanzaIds() {
            return hasDiscoFeature(account.getJid().asBareJid(), Namespace.STANZA_IDS);
        }

        public boolean bookmarks2() {
            return Config
                    .USE_BOOKMARKS2 /* || hasDiscoFeature(account.getJid().asBareJid(), Namespace.BOOKMARKS2_COMPAT)*/;
        }

        public boolean externalServiceDiscovery() {
            return hasDiscoFeature(account.getDomain(), Namespace.EXTERNAL_SERVICE_DISCOVERY);
        }
    }
}
