package eu.siacs.conversations.crypto.axolotl;

import android.os.Bundle;
import android.security.KeyChain;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.parser.IqParser;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnAdvancedStreamFeaturesLoaded;
import eu.siacs.conversations.xmpp.OnIqPacketReceived;
import eu.siacs.conversations.xmpp.jingle.OmemoVerification;
import eu.siacs.conversations.xmpp.jingle.OmemoVerifiedRtpContentMap;
import eu.siacs.conversations.xmpp.jingle.RtpContentMap;
import eu.siacs.conversations.xmpp.jingle.stanzas.IceUdpTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.OmemoVerifiedIceUdpTransportInfo;
import eu.siacs.conversations.xmpp.pep.PublishOptions;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;

public class AxolotlService implements OnAdvancedStreamFeaturesLoaded {

    public static final String PEP_PREFIX = "eu.siacs.conversations.axolotl";
    public static final String PEP_DEVICE_LIST = PEP_PREFIX + ".devicelist";
    public static final String PEP_DEVICE_LIST_NOTIFY = PEP_DEVICE_LIST + "+notify";
    public static final String PEP_BUNDLES = PEP_PREFIX + ".bundles";
    public static final String PEP_VERIFICATION = PEP_PREFIX + ".verification";
    public static final String PEP_OMEMO_WHITELISTED = PEP_PREFIX + ".whitelisted";

    public static final String LOGPREFIX = "AxolotlService";

    private static final int NUM_KEYS_TO_PUBLISH = 100;
    private static final int publishTriesThreshold = 3;

    private final Account account;
    private final XmppConnectionService mXmppConnectionService;
    private final SQLiteAxolotlStore axolotlStore;
    private final SessionMap sessions;
    private final Map<Jid, Set<Integer>> deviceIds;
    private final Map<String, XmppAxolotlMessage> messageCache;
    private final FetchStatusMap fetchStatusMap;
    private final Map<Jid, Boolean> fetchDeviceListStatus = new HashMap<>();
    private final HashMap<Jid, List<OnDeviceIdsFetched>> fetchDeviceIdsMap = new HashMap<>();
    private final SerialSingleThreadExecutor executor;
    private final Set<SignalProtocolAddress> healingAttempts = new HashSet<>();
    private final HashSet<Integer> cleanedOwnDeviceIds = new HashSet<>();
    private final Set<Integer> PREVIOUSLY_REMOVED_FROM_ANNOUNCEMENT = new HashSet<>();
    private int numPublishTriesOnEmptyPep = 0;
    private boolean pepBroken = false;
    private int lastDeviceListNotificationHash = 0;
    private final Set<XmppAxolotlSession> postponedSessions = new HashSet<>(); //sessions stored here will receive after mam catchup treatment
    private final Set<SignalProtocolAddress> postponedHealing = new HashSet<>(); //addresses stored here will need a healing notification after mam catchup
    private final AtomicBoolean changeAccessMode = new AtomicBoolean(false);

    public AxolotlService(Account account, XmppConnectionService connectionService) {
        if (account == null || connectionService == null) {
            throw new IllegalArgumentException("account and service cannot be null");
        }
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        this.mXmppConnectionService = connectionService;
        this.account = account;
        this.axolotlStore = new SQLiteAxolotlStore(this.account, this.mXmppConnectionService);
        this.deviceIds = new HashMap<>();
        this.messageCache = new HashMap<>();
        this.sessions = new SessionMap(mXmppConnectionService, axolotlStore, account);
        this.fetchStatusMap = new FetchStatusMap();
        this.executor = new SerialSingleThreadExecutor("Axolotl");
    }

    public static String getLogprefix(Account account) {
        return LOGPREFIX + " (" + account.getJid().asBareJid().toString() + "): ";
    }

    @Override
    public void onAdvancedStreamFeaturesAvailable(Account account) {
        if (Config.supportOmemo()
                && account.getXmppConnection() != null
                && account.getXmppConnection().getFeatures().pep()) {
            publishBundlesIfNeeded(true, false);
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": skipping OMEMO initialization");
        }
    }

    private boolean hasErrorFetchingDeviceList(Jid jid) {
        Boolean status = fetchDeviceListStatus.get(jid);
        return status != null && !status;
    }

    public boolean hasErrorFetchingDeviceList(List<Jid> jids) {
        for (Jid jid : jids) {
            if (hasErrorFetchingDeviceList(jid)) {
                return true;
            }
        }
        return false;
    }

    public boolean fetchMapHasErrors(List<Jid> jids) {
        for (Jid jid : jids) {
            if (deviceIds.get(jid) != null) {
                for (Integer foreignId : this.deviceIds.get(jid)) {
                    SignalProtocolAddress address = new SignalProtocolAddress(jid.toString(), foreignId);
                    if (fetchStatusMap.getAll(address.getName()).containsValue(FetchStatus.ERROR)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void preVerifyFingerprint(Contact contact, String fingerprint) {
        axolotlStore.preVerifyFingerprint(contact.getAccount(), contact.getJid().asBareJid().toString(), fingerprint);
    }

    public void preVerifyFingerprint(Account account, String fingerprint) {
        axolotlStore.preVerifyFingerprint(account, account.getJid().asBareJid().toString(), fingerprint);
    }

    public boolean hasVerifiedKeys(String name) {
        for (XmppAxolotlSession session : this.sessions.getAll(name).values()) {
            if (session.getTrust().isVerified()) {
                return true;
            }
        }
        return false;
    }

    public String getOwnFingerprint() {
        return CryptoHelper.bytesToHex(axolotlStore.getIdentityKeyPair().getPublicKey().serialize());
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status) {
        return axolotlStore.getContactKeysWithTrust(account.getJid().asBareJid().toString(), status);
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status, Jid jid) {
        return axolotlStore.getContactKeysWithTrust(jid.asBareJid().toString(), status);
    }

    public Set<IdentityKey> getKeysWithTrust(FingerprintStatus status, List<Jid> jids) {
        Set<IdentityKey> keys = new HashSet<>();
        for (Jid jid : jids) {
            keys.addAll(axolotlStore.getContactKeysWithTrust(jid.toString(), status));
        }
        return keys;
    }

    public Set<Jid> findCounterpartsBySourceId(int sid) {
        return sessions.findCounterpartsForSourceId(sid);
    }

    public long getNumTrustedKeys(Jid jid) {
        return axolotlStore.getContactNumTrustedKeys(jid.asBareJid().toString());
    }

    public boolean anyTargetHasNoTrustedKeys(List<Jid> jids) {
        for (Jid jid : jids) {
            if (axolotlStore.getContactNumTrustedKeys(jid.asBareJid().toString()) == 0) {
                return true;
            }
        }
        return false;
    }

    private SignalProtocolAddress getAddressForJid(Jid jid) {
        return new SignalProtocolAddress(jid.toString(), 0);
    }

    public Collection<XmppAxolotlSession> findOwnSessions() {
        SignalProtocolAddress ownAddress = getAddressForJid(account.getJid().asBareJid());
        ArrayList<XmppAxolotlSession> s = new ArrayList<>(this.sessions.getAll(ownAddress.getName()).values());
        Collections.sort(s);
        return s;
    }

    public Collection<XmppAxolotlSession> findSessionsForContact(Contact contact) {
        SignalProtocolAddress contactAddress = getAddressForJid(contact.getJid());
        ArrayList<XmppAxolotlSession> s = new ArrayList<>(this.sessions.getAll(contactAddress.getName()).values());
        Collections.sort(s);
        return s;
    }

    private Set<XmppAxolotlSession> findSessionsForConversation(Conversation conversation) {
        if (conversation.getContact().isSelf()) {
            //will be added in findOwnSessions()
            return Collections.emptySet();
        }
        HashSet<XmppAxolotlSession> sessions = new HashSet<>();
        for (Jid jid : conversation.getAcceptedCryptoTargets()) {
            sessions.addAll(this.sessions.getAll(getAddressForJid(jid).getName()).values());
        }
        return sessions;
    }

    private boolean hasAny(Jid jid) {
        return sessions.hasAny(getAddressForJid(jid));
    }

    public boolean isPepBroken() {
        return this.pepBroken;
    }

    public void resetBrokenness() {
        this.pepBroken = false;
        this.numPublishTriesOnEmptyPep = 0;
        this.lastDeviceListNotificationHash = 0;
        this.healingAttempts.clear();
    }

    public void clearErrorsInFetchStatusMap(Jid jid) {
        fetchStatusMap.clearErrorFor(jid);
        fetchDeviceListStatus.remove(jid);
    }

    public void regenerateKeys(boolean wipeOther) {
        axolotlStore.regenerate();
        sessions.clear();
        fetchStatusMap.clear();
        fetchDeviceIdsMap.clear();
        fetchDeviceListStatus.clear();
        publishBundlesIfNeeded(true, wipeOther);
    }

    public void destroy() {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": destroying old axolotl service. no longer in use");
        mXmppConnectionService.databaseBackend.wipeAxolotlDb(account);
    }

    public AxolotlService makeNew() {
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": make new axolotl service");
        return new AxolotlService(this.account, this.mXmppConnectionService);
    }

    public int getOwnDeviceId() {
        return axolotlStore.getLocalRegistrationId();
    }

    public SignalProtocolAddress getOwnAxolotlAddress() {
        return new SignalProtocolAddress(account.getJid().asBareJid().toString(), getOwnDeviceId());
    }

    public Set<Integer> getOwnDeviceIds() {
        return this.deviceIds.get(account.getJid().asBareJid());
    }

    public void registerDevices(final Jid jid, @NonNull final Set<Integer> deviceIds) {
        final int hash = deviceIds.hashCode();
        final boolean me = jid.asBareJid().equals(account.getJid().asBareJid());
        if (me) {
            if (hash != 0 && hash == this.lastDeviceListNotificationHash) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": ignoring duplicate own device id list");
                return;
            }
            this.lastDeviceListNotificationHash = hash;
        }
        boolean needsPublishing = me && !deviceIds.contains(getOwnDeviceId());
        if (me) {
            deviceIds.remove(getOwnDeviceId());
        }
        Set<Integer> expiredDevices = new HashSet<>(axolotlStore.getSubDeviceSessions(jid.asBareJid().toString()));
        expiredDevices.removeAll(deviceIds);
        for (Integer deviceId : expiredDevices) {
            SignalProtocolAddress address = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
            XmppAxolotlSession session = sessions.get(address);
            if (session != null && session.getFingerprint() != null) {
                if (session.getTrust().isActive()) {
                    session.setTrust(session.getTrust().toInactive());
                }
            }
        }
        Set<Integer> newDevices = new HashSet<>(deviceIds);
        for (Integer deviceId : newDevices) {
            SignalProtocolAddress address = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
            XmppAxolotlSession session = sessions.get(address);
            if (session != null && session.getFingerprint() != null) {
                if (!session.getTrust().isActive()) {
                    Log.d(Config.LOGTAG, "reactivating device with fingerprint " + session.getFingerprint());
                    session.setTrust(session.getTrust().toActive());
                }
            }
        }
        if (me) {
            if (Config.OMEMO_AUTO_EXPIRY != 0) {
                needsPublishing |= deviceIds.removeAll(getExpiredDevices());
            }
            needsPublishing |= this.changeAccessMode.get();
            for (Integer deviceId : deviceIds) {
                SignalProtocolAddress ownDeviceAddress = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
                if (sessions.get(ownDeviceAddress) == null) {
                    FetchStatus status = fetchStatusMap.get(ownDeviceAddress);
                    if (status == null || status == FetchStatus.TIMEOUT) {
                        fetchStatusMap.put(ownDeviceAddress, FetchStatus.PENDING);
                        this.buildSessionFromPEP(ownDeviceAddress);
                    }
                }
            }
            if (needsPublishing) {
                publishOwnDeviceId(deviceIds);
            }
        }
        final Set<Integer> oldSet = this.deviceIds.get(jid);
        final boolean changed = oldSet == null || oldSet.hashCode() != hash;
        this.deviceIds.put(jid, deviceIds);
        if (changed) {
            mXmppConnectionService.updateConversationUi(); //update the lock icon
            mXmppConnectionService.keyStatusUpdated(null);
            if (me) {
                mXmppConnectionService.updateAccountUi();
            }
        } else {
            Log.d(Config.LOGTAG, "skipped device list update because it hasn't changed");
        }
    }

    public void wipeOtherPepDevices() {
        if (pepBroken) {
            Log.d(Config.LOGTAG, getLogprefix(account) + "wipeOtherPepDevices called, but PEP is broken. Ignoring... ");
            return;
        }
        Set<Integer> deviceIds = new HashSet<>();
        deviceIds.add(getOwnDeviceId());
        publishDeviceIdsAndRefineAccessModel(deviceIds);
    }

    public void distrustFingerprint(final String fingerprint) {
        final String fp = fingerprint.replaceAll("\\s", "");
        final FingerprintStatus fingerprintStatus = axolotlStore.getFingerprintStatus(fp);
        axolotlStore.setFingerprintStatus(fp, fingerprintStatus.toUntrusted());
    }

    private void publishOwnDeviceIdIfNeeded() {
        if (pepBroken) {
            Log.d(Config.LOGTAG, getLogprefix(account) + "publishOwnDeviceIdIfNeeded called, but PEP is broken. Ignoring... ");
            return;
        }
        IqPacket packet = mXmppConnectionService.getIqGenerator().retrieveDeviceIds(account.getJid().asBareJid());
        mXmppConnectionService.sendIqPacket(account, packet, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                if (packet.getType() == IqPacket.TYPE.TIMEOUT) {
                    Log.d(Config.LOGTAG, getLogprefix(account) + "Timeout received while retrieving own Device Ids.");
                } else {
                    //TODO consider calling registerDevices only after item-not-found to account for broken PEPs
                    Element item = mXmppConnectionService.getIqParser().getItem(packet);
                    Set<Integer> deviceIds = mXmppConnectionService.getIqParser().deviceIds(item);
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": retrieved own device list: " + deviceIds);
                    registerDevices(account.getJid().asBareJid(), deviceIds);
                }
            }
        });
    }

    private Set<Integer> getExpiredDevices() {
        Set<Integer> devices = new HashSet<>();
        for (XmppAxolotlSession session : findOwnSessions()) {
            if (session.getTrust().isActive()) {
                long diff = System.currentTimeMillis() - session.getTrust().getLastActivation();
                if (diff > Config.OMEMO_AUTO_EXPIRY) {
                    long lastMessageDiff = System.currentTimeMillis() - mXmppConnectionService.databaseBackend.getLastTimeFingerprintUsed(account, session.getFingerprint());
                    long hours = Math.round(lastMessageDiff / (1000 * 60.0 * 60.0));
                    if (lastMessageDiff > Config.OMEMO_AUTO_EXPIRY) {
                        devices.add(session.getRemoteAddress().getDeviceId());
                        session.setTrust(session.getTrust().toInactive());
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": added own device " + session.getFingerprint() + " to list of expired devices. Last message received " + hours + " hours ago");
                    } else {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": own device " + session.getFingerprint() + " was active " + hours + " hours ago");
                    }
                } //TODO print last activation diff
            }
        }
        return devices;
    }

    private void publishOwnDeviceId(Set<Integer> deviceIds) {
        Set<Integer> deviceIdsCopy = new HashSet<>(deviceIds);
        Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "publishing own device ids");
        if (deviceIdsCopy.isEmpty()) {
            if (numPublishTriesOnEmptyPep >= publishTriesThreshold) {
                Log.w(Config.LOGTAG, getLogprefix(account) + "Own device publish attempt threshold exceeded, aborting...");
                pepBroken = true;
                return;
            } else {
                numPublishTriesOnEmptyPep++;
                Log.w(Config.LOGTAG, getLogprefix(account) + "Own device list empty, attempting to publish (try " + numPublishTriesOnEmptyPep + ")");
            }
        } else {
            numPublishTriesOnEmptyPep = 0;
        }
        deviceIdsCopy.add(getOwnDeviceId());
        publishDeviceIdsAndRefineAccessModel(deviceIdsCopy);
    }

    private void publishDeviceIdsAndRefineAccessModel(Set<Integer> ids) {
        publishDeviceIdsAndRefineAccessModel(ids, true);
    }

    private void publishDeviceIdsAndRefineAccessModel(final Set<Integer> ids, final boolean firstAttempt) {
        final Bundle publishOptions = account.getXmppConnection().getFeatures().pepPublishOptions() ? PublishOptions.openAccess() : null;
        IqPacket publish = mXmppConnectionService.getIqGenerator().publishDeviceIds(ids, publishOptions);
        mXmppConnectionService.sendIqPacket(account, publish, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {
                final Element error = packet.getType() == IqPacket.TYPE.ERROR ? packet.findChild("error") : null;
                final boolean preConditionNotMet = PublishOptions.preconditionNotMet(packet);
                if (firstAttempt && preConditionNotMet) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": precondition wasn't met for device list. pushing node configuration");
                    mXmppConnectionService.pushNodeConfiguration(account, AxolotlService.PEP_DEVICE_LIST, publishOptions, new XmppConnectionService.OnConfigurationPushed() {
                        @Override
                        public void onPushSucceeded() {
                            publishDeviceIdsAndRefineAccessModel(ids, false);
                        }

                        @Override
                        public void onPushFailed() {
                            publishDeviceIdsAndRefineAccessModel(ids, false);
                        }
                    });
                } else {
                    if (AxolotlService.this.changeAccessMode.compareAndSet(true, false)) {
                        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": done changing access mode");
                        account.setOption(Account.OPTION_REQUIRES_ACCESS_MODE_CHANGE, false);
                        mXmppConnectionService.databaseBackend.updateAccount(account);
                    }
                    if (packet.getType() == IqPacket.TYPE.ERROR) {
                        if (preConditionNotMet) {
                            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": device list pre condition still not met on second attempt");
                        } else if (error != null) {
                            pepBroken = true;
                            Log.d(Config.LOGTAG, getLogprefix(account) + "Error received while publishing own device id" + packet.findChild("error"));
                        }

                    }
                }
            }
        });
    }

    public void publishDeviceVerificationAndBundle(final SignedPreKeyRecord signedPreKeyRecord,
                                                   final Set<PreKeyRecord> preKeyRecords,
                                                   final boolean announceAfter,
                                                   final boolean wipe) {
        try {
            IdentityKey axolotlPublicKey = axolotlStore.getIdentityKeyPair().getPublicKey();
            PrivateKey x509PrivateKey = KeyChain.getPrivateKey(mXmppConnectionService, account.getPrivateKeyAlias());
            X509Certificate[] chain = KeyChain.getCertificateChain(mXmppConnectionService, account.getPrivateKeyAlias());
            Signature verifier = Signature.getInstance("sha256WithRSA");
            verifier.initSign(x509PrivateKey, mXmppConnectionService.getRNG());
            verifier.update(axolotlPublicKey.serialize());
            byte[] signature = verifier.sign();
            IqPacket packet = mXmppConnectionService.getIqGenerator().publishVerification(signature, chain, getOwnDeviceId());
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + ": publish verification for device " + getOwnDeviceId());
            mXmppConnectionService.sendIqPacket(account, packet, new OnIqPacketReceived() {
                @Override
                public void onIqPacketReceived(final Account account, IqPacket packet) {
                    String node = AxolotlService.PEP_VERIFICATION + ":" + getOwnDeviceId();
                    mXmppConnectionService.pushNodeConfiguration(account, node, PublishOptions.openAccess(), new XmppConnectionService.OnConfigurationPushed() {
                        @Override
                        public void onPushSucceeded() {
                            Log.d(Config.LOGTAG, getLogprefix(account) + "configured verification node to be world readable");
                            publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe);
                        }

                        @Override
                        public void onPushFailed() {
                            Log.d(Config.LOGTAG, getLogprefix(account) + "unable to set access model on verification node");
                            publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe);
                        }
                    });
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void publishBundlesIfNeeded(final boolean announce, final boolean wipe) {
        if (pepBroken) {
            Log.d(Config.LOGTAG, getLogprefix(account) + "publishBundlesIfNeeded called, but PEP is broken. Ignoring... ");
            return;
        }

        if (account.getXmppConnection().getFeatures().pepPublishOptions()) {
            this.changeAccessMode.set(account.isOptionSet(Account.OPTION_REQUIRES_ACCESS_MODE_CHANGE));
        } else {
            if (account.setOption(Account.OPTION_REQUIRES_ACCESS_MODE_CHANGE, true)) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": server doesn’t support publish-options. setting for later access mode change");
                mXmppConnectionService.databaseBackend.updateAccount(account);
            }
        }
        if (this.changeAccessMode.get()) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": server gained publish-options capabilities. changing access model");
        }
        IqPacket packet = mXmppConnectionService.getIqGenerator().retrieveBundlesForDevice(account.getJid().asBareJid(), getOwnDeviceId());
        mXmppConnectionService.sendIqPacket(account, packet, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(Account account, IqPacket packet) {

                if (packet.getType() == IqPacket.TYPE.TIMEOUT) {
                    return; //ignore timeout. do nothing
                }

                if (packet.getType() == IqPacket.TYPE.ERROR) {
                    Element error = packet.findChild("error");
                    if (error == null || !error.hasChild("item-not-found")) {
                        pepBroken = true;
                        Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "request for device bundles came back with something other than item-not-found" + packet);
                        return;
                    }
                }

                PreKeyBundle bundle = mXmppConnectionService.getIqParser().bundle(packet);
                Map<Integer, ECPublicKey> keys = mXmppConnectionService.getIqParser().preKeyPublics(packet);
                boolean flush = false;
                if (bundle == null) {
                    Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received invalid bundle:" + packet);
                    bundle = new PreKeyBundle(-1, -1, -1, null, -1, null, null, null);
                    flush = true;
                }
                if (keys == null) {
                    Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received invalid prekeys:" + packet);
                }
                try {
                    boolean changed = false;
                    // Validate IdentityKey
                    IdentityKeyPair identityKeyPair = axolotlStore.getIdentityKeyPair();
                    if (flush || !identityKeyPair.getPublicKey().equals(bundle.getIdentityKey())) {
                        Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding own IdentityKey " + identityKeyPair.getPublicKey() + " to PEP.");
                        changed = true;
                    }

                    // Validate signedPreKeyRecord + ID
                    SignedPreKeyRecord signedPreKeyRecord;
                    int numSignedPreKeys = axolotlStore.getSignedPreKeysCount();
                    try {
                        signedPreKeyRecord = axolotlStore.loadSignedPreKey(bundle.getSignedPreKeyId());
                        if (flush
                                || !bundle.getSignedPreKey().equals(signedPreKeyRecord.getKeyPair().getPublicKey())
                                || !Arrays.equals(bundle.getSignedPreKeySignature(), signedPreKeyRecord.getSignature())) {
                            Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding new signedPreKey with ID " + (numSignedPreKeys + 1) + " to PEP.");
                            signedPreKeyRecord = KeyHelper.generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1);
                            axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord);
                            changed = true;
                        }
                    } catch (InvalidKeyIdException e) {
                        Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding new signedPreKey with ID " + (numSignedPreKeys + 1) + " to PEP.");
                        signedPreKeyRecord = KeyHelper.generateSignedPreKey(identityKeyPair, numSignedPreKeys + 1);
                        axolotlStore.storeSignedPreKey(signedPreKeyRecord.getId(), signedPreKeyRecord);
                        changed = true;
                    }

                    // Validate PreKeys
                    Set<PreKeyRecord> preKeyRecords = new HashSet<>();
                    if (keys != null) {
                        for (Integer id : keys.keySet()) {
                            try {
                                PreKeyRecord preKeyRecord = axolotlStore.loadPreKey(id);
                                if (preKeyRecord.getKeyPair().getPublicKey().equals(keys.get(id))) {
                                    preKeyRecords.add(preKeyRecord);
                                }
                            } catch (InvalidKeyIdException ignored) {
                            }
                        }
                    }
                    int newKeys = NUM_KEYS_TO_PUBLISH - preKeyRecords.size();
                    if (newKeys > 0) {
                        List<PreKeyRecord> newRecords = KeyHelper.generatePreKeys(
                                axolotlStore.getCurrentPreKeyId() + 1, newKeys);
                        preKeyRecords.addAll(newRecords);
                        for (PreKeyRecord record : newRecords) {
                            axolotlStore.storePreKey(record.getId(), record);
                        }
                        changed = true;
                        Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Adding " + newKeys + " new preKeys to PEP.");
                    }


                    if (changed || changeAccessMode.get()) {
                        if (account.getPrivateKeyAlias() != null && Config.X509_VERIFICATION) {
                            mXmppConnectionService.publishDisplayName(account);
                            publishDeviceVerificationAndBundle(signedPreKeyRecord, preKeyRecords, announce, wipe);
                        } else {
                            publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announce, wipe);
                        }
                    } else {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "Bundle " + getOwnDeviceId() + " in PEP was current");
                        if (wipe) {
                            wipeOtherPepDevices();
                        } else if (announce) {
                            Log.d(Config.LOGTAG, getLogprefix(account) + "Announcing device " + getOwnDeviceId());
                            publishOwnDeviceIdIfNeeded();
                        }
                    }
                } catch (InvalidKeyException e) {
                    Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to publish bundle " + getOwnDeviceId() + ", reason: " + e.getMessage());
                }
            }
        });
    }

    private void publishDeviceBundle(SignedPreKeyRecord signedPreKeyRecord,
                                     Set<PreKeyRecord> preKeyRecords,
                                     final boolean announceAfter,
                                     final boolean wipe) {
        publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe, true);
    }

    private void publishDeviceBundle(final SignedPreKeyRecord signedPreKeyRecord,
                                     final Set<PreKeyRecord> preKeyRecords,
                                     final boolean announceAfter,
                                     final boolean wipe,
                                     final boolean firstAttempt) {
        final Bundle publishOptions = account.getXmppConnection().getFeatures().pepPublishOptions() ? PublishOptions.openAccess() : null;
        final IqPacket publish = mXmppConnectionService.getIqGenerator().publishBundles(
                signedPreKeyRecord, axolotlStore.getIdentityKeyPair().getPublicKey(),
                preKeyRecords, getOwnDeviceId(), publishOptions);
        Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + ": Bundle " + getOwnDeviceId() + " in PEP not current. Publishing...");
        mXmppConnectionService.sendIqPacket(account, publish, new OnIqPacketReceived() {
            @Override
            public void onIqPacketReceived(final Account account, IqPacket packet) {
                final boolean preconditionNotMet = PublishOptions.preconditionNotMet(packet);
                if (firstAttempt && preconditionNotMet) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": precondition wasn't met for bundle. pushing node configuration");
                    final String node = AxolotlService.PEP_BUNDLES + ":" + getOwnDeviceId();
                    mXmppConnectionService.pushNodeConfiguration(account, node, publishOptions, new XmppConnectionService.OnConfigurationPushed() {
                        @Override
                        public void onPushSucceeded() {
                            publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe, false);
                        }

                        @Override
                        public void onPushFailed() {
                            publishDeviceBundle(signedPreKeyRecord, preKeyRecords, announceAfter, wipe, false);
                        }
                    });
                } else if (packet.getType() == IqPacket.TYPE.RESULT) {
                    Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Successfully published bundle. ");
                    if (wipe) {
                        wipeOtherPepDevices();
                    } else if (announceAfter) {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "Announcing device " + getOwnDeviceId());
                        publishOwnDeviceIdIfNeeded();
                    }
                } else if (packet.getType() == IqPacket.TYPE.ERROR) {
                    if (preconditionNotMet) {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "bundle precondition still not met after second attempt");
                    } else {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "Error received while publishing bundle: " + packet.toString());
                    }
                    pepBroken = true;
                }
            }
        });
    }

    public void deleteOmemoIdentity() {
        mXmppConnectionService.deletePepNode(
                account, AxolotlService.PEP_BUNDLES + ":" + getOwnDeviceId());
        final Set<Integer> ownDeviceIds = getOwnDeviceIds();
        publishDeviceIdsAndRefineAccessModel(
                ownDeviceIds == null ? Collections.emptySet() : ownDeviceIds);
    }

    public List<Jid> getCryptoTargets(Conversation conversation) {
        final List<Jid> jids;
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            jids = new ArrayList<>();
            jids.add(conversation.getJid().asBareJid());
        } else {
            jids = conversation.getMucOptions().getMembers(false);
        }
        return jids;
    }

    public FingerprintStatus getFingerprintTrust(String fingerprint) {
        return axolotlStore.getFingerprintStatus(fingerprint);
    }

    public X509Certificate getFingerprintCertificate(String fingerprint) {
        return axolotlStore.getFingerprintCertificate(fingerprint);
    }

    public void setFingerprintTrust(String fingerprint, FingerprintStatus status) {
        axolotlStore.setFingerprintStatus(fingerprint, status);
    }

    private ListenableFuture<XmppAxolotlSession> verifySessionWithPEP(final XmppAxolotlSession session) {
        Log.d(Config.LOGTAG, "trying to verify fresh session (" + session.getRemoteAddress().getName() + ") with pep");
        final SignalProtocolAddress address = session.getRemoteAddress();
        final IdentityKey identityKey = session.getIdentityKey();
        final Jid jid;
        try {
            jid = Jid.of(address.getName());
        } catch (final IllegalArgumentException e) {
            fetchStatusMap.put(address, FetchStatus.SUCCESS);
            finishBuildingSessionsFromPEP(address);
            return Futures.immediateFuture(session);
        }
        final SettableFuture<XmppAxolotlSession> future = SettableFuture.create();
        final IqPacket packet = mXmppConnectionService.getIqGenerator().retrieveVerificationForDevice(jid, address.getDeviceId());
        mXmppConnectionService.sendIqPacket(account, packet, (account, response) -> {
            Pair<X509Certificate[], byte[]> verification = mXmppConnectionService.getIqParser().verification(response);
            if (verification != null) {
                try {
                    Signature verifier = Signature.getInstance("sha256WithRSA");
                    verifier.initVerify(verification.first[0]);
                    verifier.update(identityKey.serialize());
                    if (verifier.verify(verification.second)) {
                        try {
                            mXmppConnectionService.getMemorizingTrustManager().getNonInteractive().checkClientTrusted(verification.first, "RSA");
                            String fingerprint = session.getFingerprint();
                            Log.d(Config.LOGTAG, "verified session with x.509 signature. fingerprint was: " + fingerprint);
                            setFingerprintTrust(fingerprint, FingerprintStatus.createActiveVerified(true));
                            axolotlStore.setFingerprintCertificate(fingerprint, verification.first[0]);
                            fetchStatusMap.put(address, FetchStatus.SUCCESS_VERIFIED);
                            Bundle information = CryptoHelper.extractCertificateInformation(verification.first[0]);
                            try {
                                final String cn = information.getString("subject_cn");
                                final Jid jid1 = Jid.of(address.getName());
                                Log.d(Config.LOGTAG, "setting common name for " + jid1 + " to " + cn);
                                account.getRoster().getContact(jid1).setCommonName(cn);
                            } catch (final IllegalArgumentException ignored) {
                                //ignored
                            }
                            finishBuildingSessionsFromPEP(address);
                            future.set(session);
                            return;
                        } catch (Exception e) {
                            Log.d(Config.LOGTAG, "could not verify certificate");
                        }
                    }
                } catch (Exception e) {
                    Log.d(Config.LOGTAG, "error during verification " + e.getMessage());
                }
            } else {
                Log.d(Config.LOGTAG, "no verification found");
            }
            fetchStatusMap.put(address, FetchStatus.SUCCESS);
            finishBuildingSessionsFromPEP(address);
            future.set(session);
        });
        return future;
    }

    private void finishBuildingSessionsFromPEP(final SignalProtocolAddress address) {
        SignalProtocolAddress ownAddress = new SignalProtocolAddress(account.getJid().asBareJid().toString(), 0);
        Map<Integer, FetchStatus> own = fetchStatusMap.getAll(ownAddress.getName());
        Map<Integer, FetchStatus> remote = fetchStatusMap.getAll(address.getName());
        if (!own.containsValue(FetchStatus.PENDING) && !remote.containsValue(FetchStatus.PENDING)) {
            FetchStatus report = null;
            if (own.containsValue(FetchStatus.SUCCESS) || remote.containsValue(FetchStatus.SUCCESS)) {
                report = FetchStatus.SUCCESS;
            } else if (own.containsValue(FetchStatus.SUCCESS_VERIFIED) || remote.containsValue(FetchStatus.SUCCESS_VERIFIED)) {
                report = FetchStatus.SUCCESS_VERIFIED;
            } else if (own.containsValue(FetchStatus.SUCCESS_TRUSTED) || remote.containsValue(FetchStatus.SUCCESS_TRUSTED)) {
                report = FetchStatus.SUCCESS_TRUSTED;
            } else if (own.containsValue(FetchStatus.ERROR) || remote.containsValue(FetchStatus.ERROR)) {
                report = FetchStatus.ERROR;
            }
            mXmppConnectionService.keyStatusUpdated(report);
        }
        if (Config.REMOVE_BROKEN_DEVICES) {
            Set<Integer> ownDeviceIds = new HashSet<>(getOwnDeviceIds());
            boolean publish = false;
            for (Map.Entry<Integer, FetchStatus> entry : own.entrySet()) {
                int id = entry.getKey();
                if (entry.getValue() == FetchStatus.ERROR && PREVIOUSLY_REMOVED_FROM_ANNOUNCEMENT.add(id) && ownDeviceIds.remove(id)) {
                    publish = true;
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": error fetching own device with id " + id + ". removing from announcement");
                }
            }
            if (publish) {
                publishOwnDeviceId(ownDeviceIds);
            }
        }
    }

    public boolean hasEmptyDeviceList(Jid jid) {
        return !hasAny(jid) && (!deviceIds.containsKey(jid) || deviceIds.get(jid).isEmpty());
    }

    public void fetchDeviceIds(final Jid jid) {
        fetchDeviceIds(jid, null);
    }

    private void fetchDeviceIds(final Jid jid, OnDeviceIdsFetched callback) {
        IqPacket packet;
        synchronized (this.fetchDeviceIdsMap) {
            List<OnDeviceIdsFetched> callbacks = this.fetchDeviceIdsMap.get(jid);
            if (callbacks != null) {
                if (callback != null) {
                    callbacks.add(callback);
                }
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": fetching device ids for " + jid + " already running. adding callback");
                packet = null;
            } else {
                callbacks = new ArrayList<>();
                if (callback != null) {
                    callbacks.add(callback);
                }
                this.fetchDeviceIdsMap.put(jid, callbacks);
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": fetching device ids for " + jid);
                packet = mXmppConnectionService.getIqGenerator().retrieveDeviceIds(jid);
            }
        }
        if (packet != null) {
            mXmppConnectionService.sendIqPacket(account, packet, (account, response) -> {
                if (response.getType() == IqPacket.TYPE.RESULT) {
                    fetchDeviceListStatus.put(jid, true);
                    Element item = mXmppConnectionService.getIqParser().getItem(response);
                    Set<Integer> deviceIds = mXmppConnectionService.getIqParser().deviceIds(item);
                    registerDevices(jid, deviceIds);
                    final List<OnDeviceIdsFetched> callbacks;
                    synchronized (fetchDeviceIdsMap) {
                        callbacks = fetchDeviceIdsMap.remove(jid);
                    }
                    if (callbacks != null) {
                        for (OnDeviceIdsFetched c : callbacks) {
                            c.fetched(jid, deviceIds);
                        }
                    }
                } else {
                    if (response.getType() == IqPacket.TYPE.TIMEOUT) {
                        fetchDeviceListStatus.remove(jid);
                    } else {
                        fetchDeviceListStatus.put(jid, false);
                    }
                    final List<OnDeviceIdsFetched> callbacks;
                    synchronized (fetchDeviceIdsMap) {
                        callbacks = fetchDeviceIdsMap.remove(jid);
                    }
                    if (callbacks != null) {
                        for (OnDeviceIdsFetched c : callbacks) {
                            c.fetched(jid, null);
                        }
                    }
                }
            });
        }
    }

    private void fetchDeviceIds(List<Jid> jids, final OnMultipleDeviceIdFetched callback) {
        final ArrayList<Jid> unfinishedJids = new ArrayList<>(jids);
        synchronized (unfinishedJids) {
            for (Jid jid : unfinishedJids) {
                fetchDeviceIds(jid, (j, deviceIds) -> {
                    synchronized (unfinishedJids) {
                        unfinishedJids.remove(j);
                        if (unfinishedJids.size() == 0 && callback != null) {
                            callback.fetched();
                        }
                    }
                });
            }
        }
    }

    private ListenableFuture<XmppAxolotlSession> buildSessionFromPEP(final SignalProtocolAddress address) {
        return buildSessionFromPEP(address, null);
    }

    private ListenableFuture<XmppAxolotlSession> buildSessionFromPEP(final SignalProtocolAddress address, OnSessionBuildFromPep callback) {
        final SettableFuture<XmppAxolotlSession> sessionSettableFuture = SettableFuture.create();
        Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Building new session for " + address.toString());
        if (address.equals(getOwnAxolotlAddress())) {
            throw new AssertionError("We should NEVER build a session with ourselves. What happened here?!");
        }
        final Jid jid = Jid.of(address.getName());
        final boolean oneOfOurs = jid.asBareJid().equals(account.getJid().asBareJid());
        IqPacket bundlesPacket = mXmppConnectionService.getIqGenerator().retrieveBundlesForDevice(jid, address.getDeviceId());
        mXmppConnectionService.sendIqPacket(account, bundlesPacket, (account, packet) -> {
            if (packet.getType() == IqPacket.TYPE.TIMEOUT) {
                fetchStatusMap.put(address, FetchStatus.TIMEOUT);
                sessionSettableFuture.setException(new CryptoFailedException("Unable to build session. Timeout"));
            } else if (packet.getType() == IqPacket.TYPE.RESULT) {
                Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Received preKey IQ packet, processing...");
                final IqParser parser = mXmppConnectionService.getIqParser();
                final List<PreKeyBundle> preKeyBundleList = parser.preKeys(packet);
                final PreKeyBundle bundle = parser.bundle(packet);
                if (preKeyBundleList.isEmpty() || bundle == null) {
                    Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "preKey IQ packet invalid: " + packet);
                    fetchStatusMap.put(address, FetchStatus.ERROR);
                    finishBuildingSessionsFromPEP(address);
                    if (callback != null) {
                        callback.onSessionBuildFailed();
                    }
                    sessionSettableFuture.setException(new CryptoFailedException("Unable to build session. IQ Packet Invalid"));
                    return;
                }
                Random random = new Random();
                final PreKeyBundle preKey = preKeyBundleList.get(random.nextInt(preKeyBundleList.size()));
                if (preKey == null) {
                    //should never happen
                    fetchStatusMap.put(address, FetchStatus.ERROR);
                    finishBuildingSessionsFromPEP(address);
                    if (callback != null) {
                        callback.onSessionBuildFailed();
                    }
                    sessionSettableFuture.setException(new CryptoFailedException("Unable to build session. No suitable PreKey found"));
                    return;
                }

                final PreKeyBundle preKeyBundle = new PreKeyBundle(0, address.getDeviceId(),
                        preKey.getPreKeyId(), preKey.getPreKey(),
                        bundle.getSignedPreKeyId(), bundle.getSignedPreKey(),
                        bundle.getSignedPreKeySignature(), bundle.getIdentityKey());

                try {
                    SessionBuilder builder = new SessionBuilder(axolotlStore, address);
                    builder.process(preKeyBundle);
                    XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, address, bundle.getIdentityKey());
                    sessions.put(address, session);
                    if (Config.X509_VERIFICATION) {
                        sessionSettableFuture.setFuture(verifySessionWithPEP(session)); //TODO; maybe inject callback in here too
                    } else {
                        FingerprintStatus status = getFingerprintTrust(CryptoHelper.bytesToHex(bundle.getIdentityKey().getPublicKey().serialize()));
                        FetchStatus fetchStatus;
                        if (status != null && status.isVerified()) {
                            fetchStatus = FetchStatus.SUCCESS_VERIFIED;
                        } else if (status != null && status.isTrusted()) {
                            fetchStatus = FetchStatus.SUCCESS_TRUSTED;
                        } else {
                            fetchStatus = FetchStatus.SUCCESS;
                        }
                        fetchStatusMap.put(address, fetchStatus);
                        finishBuildingSessionsFromPEP(address);
                        if (callback != null) {
                            callback.onSessionBuildSuccessful();
                        }
                        sessionSettableFuture.set(session);
                    }
                } catch (UntrustedIdentityException | InvalidKeyException e) {
                    Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Error building session for " + address + ": "
                            + e.getClass().getName() + ", " + e.getMessage());
                    fetchStatusMap.put(address, FetchStatus.ERROR);
                    finishBuildingSessionsFromPEP(address);
                    if (oneOfOurs && cleanedOwnDeviceIds.add(address.getDeviceId())) {
                        removeFromDeviceAnnouncement(address.getDeviceId());
                    }
                    if (callback != null) {
                        callback.onSessionBuildFailed();
                    }
                    sessionSettableFuture.setException(new CryptoFailedException(e));
                }
            } else {
                fetchStatusMap.put(address, FetchStatus.ERROR);
                Element error = packet.findChild("error");
                boolean itemNotFound = error != null && error.hasChild("item-not-found");
                Log.d(Config.LOGTAG, getLogprefix(account) + "Error received while building session:" + packet.findChild("error"));
                finishBuildingSessionsFromPEP(address);
                if (oneOfOurs && itemNotFound && cleanedOwnDeviceIds.add(address.getDeviceId())) {
                    removeFromDeviceAnnouncement(address.getDeviceId());
                }
                if (callback != null) {
                    callback.onSessionBuildFailed();
                }
                sessionSettableFuture.setException(new CryptoFailedException("Unable to build session. IQ Packet Error"));
            }
        });
        return sessionSettableFuture;
    }

    private void removeFromDeviceAnnouncement(Integer id) {
        HashSet<Integer> temp = new HashSet<>(getOwnDeviceIds());
        if (temp.remove(id)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + " remove own device id " + id + " from announcement. devices left:" + temp);
            publishOwnDeviceId(temp);
        }
    }

    public Set<SignalProtocolAddress> findDevicesWithoutSession(final Conversation conversation) {
        Set<SignalProtocolAddress> addresses = new HashSet<>();
        for (Jid jid : getCryptoTargets(conversation)) {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Finding devices without session for " + jid);
            final Set<Integer> ids = deviceIds.get(jid);
            if (ids != null && !ids.isEmpty()) {
                for (Integer foreignId : ids) {
                    SignalProtocolAddress address = new SignalProtocolAddress(jid.toString(), foreignId);
                    if (sessions.get(address) == null) {
                        IdentityKey identityKey = axolotlStore.loadSession(address).getSessionState().getRemoteIdentityKey();
                        if (identityKey != null) {
                            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already have session for " + address.toString() + ", adding to cache...");
                            XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, address, identityKey);
                            sessions.put(address, session);
                        } else {
                            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Found device " + jid + ":" + foreignId);
                            if (fetchStatusMap.get(address) != FetchStatus.ERROR) {
                                addresses.add(address);
                            } else {
                                Log.d(Config.LOGTAG, getLogprefix(account) + "skipping over " + address + " because it's broken");
                            }
                        }
                    }
                }
            } else {
                mXmppConnectionService.keyStatusUpdated(FetchStatus.ERROR);
                Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Have no target devices in PEP!");
            }
        }
        Set<Integer> ownIds = this.deviceIds.get(account.getJid().asBareJid());
        for (Integer ownId : (ownIds != null ? ownIds : new HashSet<Integer>())) {
            SignalProtocolAddress address = new SignalProtocolAddress(account.getJid().asBareJid().toString(), ownId);
            if (sessions.get(address) == null) {
                IdentityKey identityKey = axolotlStore.loadSession(address).getSessionState().getRemoteIdentityKey();
                if (identityKey != null) {
                    Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already have session for " + address.toString() + ", adding to cache...");
                    XmppAxolotlSession session = new XmppAxolotlSession(account, axolotlStore, address, identityKey);
                    sessions.put(address, session);
                } else {
                    Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Found device " + account.getJid().asBareJid() + ":" + ownId);
                    if (fetchStatusMap.get(address) != FetchStatus.ERROR) {
                        addresses.add(address);
                    } else {
                        Log.d(Config.LOGTAG, getLogprefix(account) + "skipping over " + address + " because it's broken");
                    }
                }
            }
        }

        return addresses;
    }

    public boolean createSessionsIfNeeded(final Conversation conversation) {
        final List<Jid> jidsWithEmptyDeviceList = getCryptoTargets(conversation);
        for (Iterator<Jid> iterator = jidsWithEmptyDeviceList.iterator(); iterator.hasNext(); ) {
            final Jid jid = iterator.next();
            if (!hasEmptyDeviceList(jid)) {
                iterator.remove();
            }
        }
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": createSessionsIfNeeded() - jids with empty device list: " + jidsWithEmptyDeviceList);
        if (jidsWithEmptyDeviceList.size() > 0) {
            fetchDeviceIds(jidsWithEmptyDeviceList, () -> createSessionsIfNeededActual(conversation));
            return true;
        } else {
            return createSessionsIfNeededActual(conversation);
        }
    }

    private boolean createSessionsIfNeededActual(final Conversation conversation) {
        Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Creating axolotl sessions if needed...");
        boolean newSessions = false;
        Set<SignalProtocolAddress> addresses = findDevicesWithoutSession(conversation);
        for (SignalProtocolAddress address : addresses) {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Processing device: " + address.toString());
            FetchStatus status = fetchStatusMap.get(address);
            if (status == null || status == FetchStatus.TIMEOUT) {
                fetchStatusMap.put(address, FetchStatus.PENDING);
                this.buildSessionFromPEP(address);
                newSessions = true;
            } else if (status == FetchStatus.PENDING) {
                newSessions = true;
            } else {
                Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Already fetching bundle for " + address.toString());
            }
        }

        return newSessions;
    }

    public boolean trustedSessionVerified(final Conversation conversation) {
        final Set<XmppAxolotlSession> sessions = new HashSet<>();
        sessions.addAll(findSessionsForConversation(conversation));
        sessions.addAll(findOwnSessions());
        boolean verified = false;
        for (XmppAxolotlSession session : sessions) {
            if (session.getTrust().isTrustedAndActive()) {
                if (session.getTrust().getTrust() == FingerprintStatus.Trust.VERIFIED_X509) {
                    verified = true;
                } else {
                    return false;
                }
            }
        }
        return verified;
    }

    public boolean hasPendingKeyFetches(List<Jid> jids) {
        SignalProtocolAddress ownAddress = new SignalProtocolAddress(account.getJid().asBareJid().toString(), 0);
        if (fetchStatusMap.getAll(ownAddress.getName()).containsValue(FetchStatus.PENDING)) {
            return true;
        }
        synchronized (this.fetchDeviceIdsMap) {
            for (Jid jid : jids) {
                SignalProtocolAddress foreignAddress = new SignalProtocolAddress(jid.asBareJid().toString(), 0);
                if (fetchStatusMap.getAll(foreignAddress.getName()).containsValue(FetchStatus.PENDING) || this.fetchDeviceIdsMap.containsKey(jid)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private boolean buildHeader(XmppAxolotlMessage axolotlMessage, Conversation c) {
        Set<XmppAxolotlSession> remoteSessions = findSessionsForConversation(c);
        final boolean acceptEmpty = (c.getMode() == Conversation.MODE_MULTI && c.getMucOptions().getUserCount() == 0) || c.getContact().isSelf();
        Collection<XmppAxolotlSession> ownSessions = findOwnSessions();
        if (remoteSessions.isEmpty() && !acceptEmpty) {
            return false;
        }
        for (XmppAxolotlSession session : remoteSessions) {
            axolotlMessage.addDevice(session);
        }
        for (XmppAxolotlSession session : ownSessions) {
            axolotlMessage.addDevice(session);
        }

        return true;
    }

    //this is being used for private muc messages only
    private boolean buildHeader(XmppAxolotlMessage axolotlMessage, Jid jid) {
        if (jid == null) {
            return false;
        }
        HashSet<XmppAxolotlSession> sessions = new HashSet<>();
        sessions.addAll(this.sessions.getAll(getAddressForJid(jid).getName()).values());
        if (sessions.isEmpty()) {
            return false;
        }
        sessions.addAll(findOwnSessions());
        for (XmppAxolotlSession session : sessions) {
            axolotlMessage.addDevice(session);
        }
        return true;
    }

    @Nullable
    public XmppAxolotlMessage encrypt(Message message) {
        final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
        final String content;
        if (message.hasFileOnRemoteHost()) {
            content = message.getFileParams().url;
        } else {
            content = message.getBody();
        }
        try {
            axolotlMessage.encrypt(content);
        } catch (CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to encrypt message: " + e.getMessage());
            return null;
        }

        final boolean success;
        if (message.isPrivateMessage()) {
            success = buildHeader(axolotlMessage, message.getTrueCounterpart());
        } else {
            success = buildHeader(axolotlMessage, (Conversation) message.getConversation());
        }
        return success ? axolotlMessage : null;
    }

    public void preparePayloadMessage(final Message message, final boolean delay) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                XmppAxolotlMessage axolotlMessage = encrypt(message);
                if (axolotlMessage == null) {
                    mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED);
                    //mXmppConnectionService.updateConversationUi();
                } else {
                    Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Generated message, caching: " + message.getUuid());
                    messageCache.put(message.getUuid(), axolotlMessage);
                    mXmppConnectionService.resendMessage(message, delay);
                }
            }
        });
    }

    private OmemoVerifiedIceUdpTransportInfo encrypt(final IceUdpTransportInfo element, final XmppAxolotlSession session) throws CryptoFailedException {
        final OmemoVerifiedIceUdpTransportInfo transportInfo = new OmemoVerifiedIceUdpTransportInfo();
        transportInfo.setAttributes(element.getAttributes());
        for (final Element child : element.getChildren()) {
            if ("fingerprint".equals(child.getName()) && Namespace.JINGLE_APPS_DTLS.equals(child.getNamespace())) {
                final Element fingerprint = new Element("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION);
                fingerprint.setAttribute("setup", child.getAttribute("setup"));
                fingerprint.setAttribute("hash", child.getAttribute("hash"));
                final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
                final String content = child.getContent();
                axolotlMessage.encrypt(content);
                axolotlMessage.addDevice(session, true);
                fingerprint.addChild(axolotlMessage.toElement());
                transportInfo.addChild(fingerprint);
            } else {
                transportInfo.addChild(child);
            }
        }
        return transportInfo;
    }


    public ListenableFuture<OmemoVerifiedPayload<OmemoVerifiedRtpContentMap>> encrypt(final RtpContentMap rtpContentMap, final Jid jid, final int deviceId) {
        return Futures.transformAsync(
                getSession(jid, deviceId),
                session -> encrypt(rtpContentMap, session),
                MoreExecutors.directExecutor()
        );
    }

    private ListenableFuture<OmemoVerifiedPayload<OmemoVerifiedRtpContentMap>> encrypt(final RtpContentMap rtpContentMap, final XmppAxolotlSession session) {
        if (Config.REQUIRE_RTP_VERIFICATION) {
            requireVerification(session);
        }
        final ImmutableMap.Builder<String, RtpContentMap.DescriptionTransport> descriptionTransportBuilder = new ImmutableMap.Builder<>();
        final OmemoVerification omemoVerification = new OmemoVerification();
        omemoVerification.setDeviceId(session.getRemoteAddress().getDeviceId());
        omemoVerification.setSessionFingerprint(session.getFingerprint());
        for (final Map.Entry<String, RtpContentMap.DescriptionTransport> content : rtpContentMap.contents.entrySet()) {
            final RtpContentMap.DescriptionTransport descriptionTransport = content.getValue();
            final OmemoVerifiedIceUdpTransportInfo encryptedTransportInfo;
            try {
                encryptedTransportInfo = encrypt(descriptionTransport.transport, session);
            } catch (final CryptoFailedException e) {
                return Futures.immediateFailedFuture(e);
            }
            descriptionTransportBuilder.put(
                    content.getKey(),
                    new RtpContentMap.DescriptionTransport(descriptionTransport.description, encryptedTransportInfo)
            );
        }
        return Futures.immediateFuture(
                new OmemoVerifiedPayload<>(
                        omemoVerification,
                        new OmemoVerifiedRtpContentMap(rtpContentMap.group, descriptionTransportBuilder.build())
                ));
    }

    private ListenableFuture<XmppAxolotlSession> getSession(final Jid jid, final int deviceId) {
        final SignalProtocolAddress address = new SignalProtocolAddress(jid.asBareJid().toString(), deviceId);
        final XmppAxolotlSession session = sessions.get(address);
        if (session == null) {
            return buildSessionFromPEP(address);
        }
        return Futures.immediateFuture(session);
    }

    public ListenableFuture<OmemoVerifiedPayload<RtpContentMap>> decrypt(OmemoVerifiedRtpContentMap omemoVerifiedRtpContentMap, final Jid from) {
        final ImmutableMap.Builder<String, RtpContentMap.DescriptionTransport> descriptionTransportBuilder = new ImmutableMap.Builder<>();
        final OmemoVerification omemoVerification = new OmemoVerification();
        final ImmutableList.Builder<ListenableFuture<XmppAxolotlSession>> pepVerificationFutures = new ImmutableList.Builder<>();
        for (final Map.Entry<String, RtpContentMap.DescriptionTransport> content : omemoVerifiedRtpContentMap.contents.entrySet()) {
            final RtpContentMap.DescriptionTransport descriptionTransport = content.getValue();
            final OmemoVerifiedPayload<IceUdpTransportInfo> decryptedTransport;
            try {
                decryptedTransport = decrypt((OmemoVerifiedIceUdpTransportInfo) descriptionTransport.transport, from, pepVerificationFutures);
            } catch (CryptoFailedException e) {
                return Futures.immediateFailedFuture(e);
            }
            omemoVerification.setOrEnsureEqual(decryptedTransport);
            descriptionTransportBuilder.put(
                    content.getKey(),
                    new RtpContentMap.DescriptionTransport(descriptionTransport.description, decryptedTransport.payload)
            );
        }
        processPostponed();
        final ImmutableList<ListenableFuture<XmppAxolotlSession>> sessionFutures = pepVerificationFutures.build();
        return Futures.transform(
                Futures.allAsList(sessionFutures),
                sessions -> {
                    if (Config.REQUIRE_RTP_VERIFICATION) {
                        for (XmppAxolotlSession session : sessions) {
                            requireVerification(session);
                        }
                    }
                    return new OmemoVerifiedPayload<>(
                            omemoVerification,
                            new RtpContentMap(omemoVerifiedRtpContentMap.group, descriptionTransportBuilder.build())
                    );

                },
                MoreExecutors.directExecutor()
        );
    }

    private OmemoVerifiedPayload<IceUdpTransportInfo> decrypt(final OmemoVerifiedIceUdpTransportInfo verifiedIceUdpTransportInfo, final Jid from, ImmutableList.Builder<ListenableFuture<XmppAxolotlSession>> pepVerificationFutures) throws CryptoFailedException {
        final IceUdpTransportInfo transportInfo = new IceUdpTransportInfo();
        transportInfo.setAttributes(verifiedIceUdpTransportInfo.getAttributes());
        final OmemoVerification omemoVerification = new OmemoVerification();
        for (final Element child : verifiedIceUdpTransportInfo.getChildren()) {
            if ("fingerprint".equals(child.getName()) && Namespace.OMEMO_DTLS_SRTP_VERIFICATION.equals(child.getNamespace())) {
                final Element fingerprint = new Element("fingerprint", Namespace.JINGLE_APPS_DTLS);
                fingerprint.setAttribute("setup", child.getAttribute("setup"));
                fingerprint.setAttribute("hash", child.getAttribute("hash"));
                final Element encrypted = child.findChildEnsureSingle(XmppAxolotlMessage.CONTAINERTAG, AxolotlService.PEP_PREFIX);
                final XmppAxolotlMessage xmppAxolotlMessage = XmppAxolotlMessage.fromElement(encrypted, from.asBareJid());
                final XmppAxolotlSession session = getReceivingSession(xmppAxolotlMessage);
                final XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintext = xmppAxolotlMessage.decrypt(session, getOwnDeviceId());
                final Integer preKeyId = session.getPreKeyIdAndReset();
                if (preKeyId != null) {
                    postponedSessions.add(session);
                }
                if (session.isFresh()) {
                    pepVerificationFutures.add(putFreshSession(session));
                } else if (Config.REQUIRE_RTP_VERIFICATION) {
                    pepVerificationFutures.add(Futures.immediateFuture(session));
                }
                fingerprint.setContent(plaintext.getPlaintext());
                omemoVerification.setDeviceId(session.getRemoteAddress().getDeviceId());
                omemoVerification.setSessionFingerprint(plaintext.getFingerprint());
                transportInfo.addChild(fingerprint);
            } else {
                transportInfo.addChild(child);
            }
        }
        return new OmemoVerifiedPayload<>(omemoVerification, transportInfo);
    }

    private static void requireVerification(final XmppAxolotlSession session) {
        if (session.getTrust().isVerified()) {
            return;
        }
        throw new NotVerifiedException(String.format(
                "session with %s was not verified",
                session.getFingerprint()
        ));
    }

    public void prepareKeyTransportMessage(final Conversation conversation, final OnMessageCreatedCallback onMessageCreatedCallback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
                if (buildHeader(axolotlMessage, conversation)) {
                    onMessageCreatedCallback.run(axolotlMessage);
                } else {
                    onMessageCreatedCallback.run(null);
                }
            }
        });
    }

    public XmppAxolotlMessage fetchAxolotlMessageFromCache(Message message) {
        XmppAxolotlMessage axolotlMessage = messageCache.get(message.getUuid());
        if (axolotlMessage != null) {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Cache hit: " + message.getUuid());
            messageCache.remove(message.getUuid());
        } else {
            Log.d(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Cache miss: " + message.getUuid());
        }
        return axolotlMessage;
    }

    private XmppAxolotlSession recreateUncachedSession(SignalProtocolAddress address) {
        IdentityKey identityKey = axolotlStore.loadSession(address).getSessionState().getRemoteIdentityKey();
        return (identityKey != null)
                ? new XmppAxolotlSession(account, axolotlStore, address, identityKey)
                : null;
    }

    private XmppAxolotlSession getReceivingSession(XmppAxolotlMessage message) {
        SignalProtocolAddress senderAddress = new SignalProtocolAddress(message.getFrom().toString(), message.getSenderDeviceId());
        return getReceivingSession(senderAddress);

    }

    private XmppAxolotlSession getReceivingSession(SignalProtocolAddress senderAddress) {
        XmppAxolotlSession session = sessions.get(senderAddress);
        if (session == null) {
            session = recreateUncachedSession(senderAddress);
            if (session == null) {
                session = new XmppAxolotlSession(account, axolotlStore, senderAddress);
            }
        }
        return session;
    }

    public XmppAxolotlMessage.XmppAxolotlPlaintextMessage processReceivingPayloadMessage(XmppAxolotlMessage message, boolean postponePreKeyMessageHandling) throws NotEncryptedForThisDeviceException, BrokenSessionException, OutdatedSenderException {
        XmppAxolotlMessage.XmppAxolotlPlaintextMessage plaintextMessage = null;

        XmppAxolotlSession session = getReceivingSession(message);
        int ownDeviceId = getOwnDeviceId();
        try {
            plaintextMessage = message.decrypt(session, ownDeviceId);
            Integer preKeyId = session.getPreKeyIdAndReset();
            if (preKeyId != null) {
                postPreKeyMessageHandling(session, postponePreKeyMessageHandling);
            }
        } catch (NotEncryptedForThisDeviceException e) {
            if (account.getJid().asBareJid().equals(message.getFrom().asBareJid()) && message.getSenderDeviceId() == ownDeviceId) {
                Log.w(Config.LOGTAG, getLogprefix(account) + "Reflected omemo message received");
            } else {
                throw e;
            }
        } catch (final BrokenSessionException e) {
            throw e;
        } catch (final OutdatedSenderException e) {
            Log.e(Config.LOGTAG, account.getJid().asBareJid() + ": " + e.getMessage());
            throw e;
        } catch (CryptoFailedException e) {
            Log.w(Config.LOGTAG, getLogprefix(account) + "Failed to decrypt message from " + message.getFrom(), e);
        }

        if (session.isFresh() && plaintextMessage != null) {
            putFreshSession(session);
        }

        return plaintextMessage;
    }

    public void reportBrokenSessionException(BrokenSessionException e, boolean postpone) {
        Log.e(Config.LOGTAG, account.getJid().asBareJid() + ": broken session with " + e.getSignalProtocolAddress().toString() + " detected", e);
        if (postpone) {
            postponedHealing.add(e.getSignalProtocolAddress());
        } else {
            notifyRequiresHealing(e.getSignalProtocolAddress());
        }
    }

    private void notifyRequiresHealing(final SignalProtocolAddress signalProtocolAddress) {
        if (healingAttempts.add(signalProtocolAddress)) {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": attempt to heal " + signalProtocolAddress);
            buildSessionFromPEP(signalProtocolAddress, new OnSessionBuildFromPep() {
                @Override
                public void onSessionBuildSuccessful() {
                    Log.d(Config.LOGTAG, "successfully build new session from pep after detecting broken session");
                    completeSession(getReceivingSession(signalProtocolAddress));
                }

                @Override
                public void onSessionBuildFailed() {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to build new session from pep after detecting broken session");
                }
            });
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": do not attempt to heal " + signalProtocolAddress + " again");
        }
    }

    private void postPreKeyMessageHandling(final XmppAxolotlSession session, final boolean postpone) {
        if (postpone) {
            postponedSessions.add(session);
        } else {
            if (axolotlStore.flushPreKeys()) {
                publishBundlesIfNeeded(false, false);
            } else {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": nothing to flush. Not republishing key");
            }
            if (trustedOrPreviouslyResponded(session) && Config.AUTOMATICALLY_COMPLETE_SESSIONS) {
                completeSession(session);
            }
        }
    }

    public void processPostponed() {
        if (postponedSessions.size() > 0) {
            if (axolotlStore.flushPreKeys()) {
                publishBundlesIfNeeded(false, false);
            }
        }
        final Iterator<XmppAxolotlSession> iterator = postponedSessions.iterator();
        while (iterator.hasNext()) {
            final XmppAxolotlSession session = iterator.next();
            if (trustedOrPreviouslyResponded(session) && Config.AUTOMATICALLY_COMPLETE_SESSIONS) {
                completeSession(session);
            }
            iterator.remove();
        }
        final Iterator<SignalProtocolAddress> postponedHealingAttemptsIterator = postponedHealing.iterator();
        while (postponedHealingAttemptsIterator.hasNext()) {
            notifyRequiresHealing(postponedHealingAttemptsIterator.next());
            postponedHealingAttemptsIterator.remove();
        }
    }

    private boolean trustedOrPreviouslyResponded(XmppAxolotlSession session) {
        try {
            return trustedOrPreviouslyResponded(Jid.of(session.getRemoteAddress().getName()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean trustedOrPreviouslyResponded(Jid jid) {
        final Contact contact = account.getRoster().getContact(jid);
        if (contact.showInRoster() || contact.isSelf()) {
            return true;
        }
        final Conversation conversation = mXmppConnectionService.find(account, jid);
        return conversation != null && conversation.sentMessagesCount() > 0;
    }

    private void completeSession(XmppAxolotlSession session) {
        final XmppAxolotlMessage axolotlMessage = new XmppAxolotlMessage(account.getJid().asBareJid(), getOwnDeviceId());
        axolotlMessage.addDevice(session, true);
        try {
            final Jid jid = Jid.of(session.getRemoteAddress().getName());
            MessagePacket packet = mXmppConnectionService.getMessageGenerator().generateKeyTransportMessage(jid, axolotlMessage);
            mXmppConnectionService.sendMessagePacket(account, packet);
        } catch (IllegalArgumentException e) {
            throw new Error("Remote addresses are created from jid and should convert back to jid", e);
        }
    }

    public XmppAxolotlMessage.XmppAxolotlKeyTransportMessage processReceivingKeyTransportMessage(XmppAxolotlMessage message, final boolean postponePreKeyMessageHandling) {
        final XmppAxolotlMessage.XmppAxolotlKeyTransportMessage keyTransportMessage;
        final XmppAxolotlSession session = getReceivingSession(message);
        try {
            keyTransportMessage = message.getParameters(session, getOwnDeviceId());
            Integer preKeyId = session.getPreKeyIdAndReset();
            if (preKeyId != null) {
                postPreKeyMessageHandling(session, postponePreKeyMessageHandling);
            }
        } catch (CryptoFailedException e) {
            Log.d(Config.LOGTAG, "could not decrypt keyTransport message " + e.getMessage());
            return null;
        }

        if (session.isFresh() && keyTransportMessage != null) {
            putFreshSession(session);
        }

        return keyTransportMessage;
    }

    private ListenableFuture<XmppAxolotlSession> putFreshSession(XmppAxolotlSession session) {
        sessions.put(session);
        if (Config.X509_VERIFICATION) {
            if (session.getIdentityKey() != null) {
                return verifySessionWithPEP(session);
            } else {
                Log.e(Config.LOGTAG, account.getJid().asBareJid() + ": identity key was empty after reloading for x509 verification");
            }
        }
        return Futures.immediateFuture(session);
    }

    public enum FetchStatus {
        PENDING,
        SUCCESS,
        SUCCESS_VERIFIED,
        TIMEOUT,
        SUCCESS_TRUSTED,
        ERROR
    }

    public interface OnDeviceIdsFetched {
        void fetched(Jid jid, Set<Integer> deviceIds);
    }


    public interface OnMultipleDeviceIdFetched {
        void fetched();
    }

    interface OnSessionBuildFromPep {
        void onSessionBuildSuccessful();

        void onSessionBuildFailed();
    }

    private static class AxolotlAddressMap<T> {
        protected final Object MAP_LOCK = new Object();
        protected Map<String, Map<Integer, T>> map;

        public AxolotlAddressMap() {
            this.map = new HashMap<>();
        }

        public void put(SignalProtocolAddress address, T value) {
            synchronized (MAP_LOCK) {
                Map<Integer, T> devices = map.get(address.getName());
                if (devices == null) {
                    devices = new HashMap<>();
                    map.put(address.getName(), devices);
                }
                devices.put(address.getDeviceId(), value);
            }
        }

        public T get(SignalProtocolAddress address) {
            synchronized (MAP_LOCK) {
                Map<Integer, T> devices = map.get(address.getName());
                if (devices == null) {
                    return null;
                }
                return devices.get(address.getDeviceId());
            }
        }

        public Map<Integer, T> getAll(String name) {
            synchronized (MAP_LOCK) {
                Map<Integer, T> devices = map.get(name);
                if (devices == null) {
                    return new HashMap<>();
                }
                return devices;
            }
        }

        public boolean hasAny(SignalProtocolAddress address) {
            synchronized (MAP_LOCK) {
                Map<Integer, T> devices = map.get(address.getName());
                return devices != null && !devices.isEmpty();
            }
        }

        public void clear() {
            map.clear();
        }

    }

    private static class SessionMap extends AxolotlAddressMap<XmppAxolotlSession> {
        private final XmppConnectionService xmppConnectionService;
        private final Account account;

        public SessionMap(XmppConnectionService service, SQLiteAxolotlStore store, Account account) {
            super();
            this.xmppConnectionService = service;
            this.account = account;
            this.fillMap(store);
        }

        public Set<Jid> findCounterpartsForSourceId(Integer sid) {
            Set<Jid> candidates = new HashSet<>();
            synchronized (MAP_LOCK) {
                for (Map.Entry<String, Map<Integer, XmppAxolotlSession>> entry : map.entrySet()) {
                    String key = entry.getKey();
                    if (entry.getValue().containsKey(sid)) {
                        candidates.add(Jid.of(key));
                    }
                }
            }
            return candidates;
        }

        private void putDevicesForJid(String bareJid, List<Integer> deviceIds, SQLiteAxolotlStore store) {
            for (Integer deviceId : deviceIds) {
                SignalProtocolAddress axolotlAddress = new SignalProtocolAddress(bareJid, deviceId);
                IdentityKey identityKey = store.loadSession(axolotlAddress).getSessionState().getRemoteIdentityKey();
                if (Config.X509_VERIFICATION) {
                    X509Certificate certificate = store.getFingerprintCertificate(CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize()));
                    if (certificate != null) {
                        Bundle information = CryptoHelper.extractCertificateInformation(certificate);
                        try {
                            final String cn = information.getString("subject_cn");
                            final Jid jid = Jid.of(bareJid);
                            Log.d(Config.LOGTAG, "setting common name for " + jid + " to " + cn);
                            account.getRoster().getContact(jid).setCommonName(cn);
                        } catch (final IllegalArgumentException ignored) {
                            //ignored
                        }
                    }
                }
                this.put(axolotlAddress, new XmppAxolotlSession(account, store, axolotlAddress, identityKey));
            }
        }

        private void fillMap(SQLiteAxolotlStore store) {
            List<Integer> deviceIds = store.getSubDeviceSessions(account.getJid().asBareJid().toString());
            putDevicesForJid(account.getJid().asBareJid().toString(), deviceIds, store);
            for (String address : store.getKnownAddresses()) {
                deviceIds = store.getSubDeviceSessions(address);
                putDevicesForJid(address, deviceIds, store);
            }
        }

        @Override
        public void put(SignalProtocolAddress address, XmppAxolotlSession value) {
            super.put(address, value);
            value.setNotFresh();
        }

        public void put(XmppAxolotlSession session) {
            this.put(session.getRemoteAddress(), session);
        }
    }

    private static class FetchStatusMap extends AxolotlAddressMap<FetchStatus> {

        public void clearErrorFor(Jid jid) {
            synchronized (MAP_LOCK) {
                Map<Integer, FetchStatus> devices = this.map.get(jid.asBareJid().toString());
                if (devices == null) {
                    return;
                }
                for (Map.Entry<Integer, FetchStatus> entry : devices.entrySet()) {
                    if (entry.getValue() == FetchStatus.ERROR) {
                        Log.d(Config.LOGTAG, "resetting error for " + jid.asBareJid() + "(" + entry.getKey() + ")");
                        entry.setValue(FetchStatus.TIMEOUT);
                    }
                }
            }
        }
    }

    public static class OmemoVerifiedPayload<T> {
        private final int deviceId;
        private final String fingerprint;
        private final T payload;

        private OmemoVerifiedPayload(OmemoVerification omemoVerification, T payload) {
            this.deviceId = omemoVerification.getDeviceId();
            this.fingerprint = omemoVerification.getFingerprint();
            this.payload = payload;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public String getFingerprint() {
            return fingerprint;
        }

        public T getPayload() {
            return payload;
        }
    }

    public static class NotVerifiedException extends SecurityException {

        public NotVerifiedException(String message) {
            super(message);
        }

    }
}
