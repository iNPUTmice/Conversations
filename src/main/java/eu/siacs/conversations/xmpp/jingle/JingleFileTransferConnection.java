package eu.siacs.conversations.xmpp.jingle;

import android.util.Base64;
import android.util.Log;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.XmppAxolotlMessage;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.entities.TransferablePlaceholder;
import eu.siacs.conversations.parser.IqParser;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnIqPacketReceived;
import eu.siacs.conversations.xmpp.jingle.stanzas.Content;
import eu.siacs.conversations.xmpp.jingle.stanzas.FileTransferDescription;
import eu.siacs.conversations.xmpp.jingle.stanzas.GenericTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.IbbTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.JinglePacket;
import eu.siacs.conversations.xmpp.jingle.stanzas.Reason;
import eu.siacs.conversations.xmpp.jingle.stanzas.S5BTransportInfo;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;

public class JingleFileTransferConnection extends AbstractJingleConnection implements Transferable {

    private static final int JINGLE_STATUS_TRANSMITTING = 5;
    private static final String JET_OMEMO_CIPHER = "urn:xmpp:ciphers:aes-128-gcm-nopadding";
    private static final int JINGLE_STATUS_INITIATED = 0;
    private static final int JINGLE_STATUS_ACCEPTED = 1;
    private static final int JINGLE_STATUS_FINISHED = 4;
    private static final int JINGLE_STATUS_FAILED = 99;
    private static final int JINGLE_STATUS_OFFERED = -1;

    private static final int MAX_IBB_BLOCK_SIZE = 8192;

    private int ibbBlockSize = MAX_IBB_BLOCK_SIZE;

    private int mJingleStatus = JINGLE_STATUS_OFFERED; //migrate to enum
    private int mStatus = Transferable.STATUS_UNKNOWN;
    private Message message;
    private Jid responder;
    private final List<JingleCandidate> candidates = new ArrayList<>();
    private final ConcurrentHashMap<String, JingleSocks5Transport> connections = new ConcurrentHashMap<>();

    private String transportId;
    private FileTransferDescription description;
    private DownloadableFile file = null;

    private boolean proxyActivationFailed = false;

    private String contentName;
    private Content.Creator contentCreator;
    private Content.Senders contentSenders;
    private Class<? extends GenericTransportInfo> initialTransport;
    private boolean remoteSupportsOmemoJet;

    private int mProgress = 0;

    private boolean receivedCandidate = false;
    private boolean sentCandidate = false;

    private boolean acceptedAutomatically = false;
    private boolean cancelled = false;

    private XmppAxolotlMessage mXmppAxolotlMessage;

    private JingleTransport transport = null;

    private OutputStream mFileOutputStream;
    private InputStream mFileInputStream;

    private final OnIqPacketReceived responseListener = (account, packet) -> {
        if (packet.getType() != IqPacket.TYPE.RESULT) {
            if (mJingleStatus != JINGLE_STATUS_FAILED && mJingleStatus != JINGLE_STATUS_FINISHED) {
                fail(IqParser.extractErrorMessage(packet));
            } else {
                Log.d(Config.LOGTAG, "ignoring late delivery of jingle packet to jingle session with status=" + mJingleStatus + ": " + packet.toString());
            }
        }
    };
    private byte[] expectedHash = new byte[0];
    private final OnFileTransmissionStatusChanged onFileTransmissionStatusChanged = new OnFileTransmissionStatusChanged() {

        @Override
        public void onFileTransmitted(DownloadableFile file) {
            if (responding()) {
                if (expectedHash.length > 0) {
                    if (Arrays.equals(expectedHash, file.getSha1Sum())) {
                        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received file matched the expected hash");
                    } else {
                        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": hashes did not match");
                    }
                } else {
                    Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": other party did not include file hash in file transfer");
                }
                sendSuccess();
                xmppConnectionService.getFileBackend().updateFileParams(message);
                xmppConnectionService.databaseBackend.createMessage(message);
                xmppConnectionService.markMessage(message, Message.STATUS_RECEIVED);
                if (acceptedAutomatically) {
                    message.markUnread();
                    if (message.getEncryption() == Message.ENCRYPTION_PGP) {
                        id.account.getPgpDecryptionService().decrypt(message, true);
                    } else {
                        xmppConnectionService.getFileBackend().updateMediaScanner(file, () -> JingleFileTransferConnection.this.xmppConnectionService.getNotificationService().push(message));

                    }
                    Log.d(Config.LOGTAG, "successfully transmitted file:" + file.getAbsolutePath() + " (" + CryptoHelper.bytesToHex(file.getSha1Sum()) + ")");
                    return;
                } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
                    id.account.getPgpDecryptionService().decrypt(message, true);
                }
            } else {
                if (description.getVersion() == FileTransferDescription.Version.FT_5) { //older Conversations will break when receiving a session-info
                    sendHash();
                }
                if (message.getEncryption() == Message.ENCRYPTION_PGP) {
                    id.account.getPgpDecryptionService().decrypt(message, false);
                }
                if (message.getEncryption() == Message.ENCRYPTION_PGP || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                    file.delete();
                }
            }
            Log.d(Config.LOGTAG, "successfully transmitted file:" + file.getAbsolutePath() + " (" + CryptoHelper.bytesToHex(file.getSha1Sum()) + ")");
            if (message.getEncryption() != Message.ENCRYPTION_PGP) {
                xmppConnectionService.getFileBackend().updateMediaScanner(file);
            }
        }

        @Override
        public void onFileTransferAborted() {
            JingleFileTransferConnection.this.sendSessionTerminate(Reason.CONNECTIVITY_ERROR);
            JingleFileTransferConnection.this.fail();
        }
    };
    private final OnTransportConnected onIbbTransportConnected = new OnTransportConnected() {
        @Override
        public void failed() {
            Log.d(Config.LOGTAG, "ibb open failed");
        }

        @Override
        public void established() {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": ibb transport connected. sending file");
            mJingleStatus = JINGLE_STATUS_TRANSMITTING;
            JingleFileTransferConnection.this.transport.send(file, onFileTransmissionStatusChanged);
        }
    };
    private final OnProxyActivated onProxyActivated = new OnProxyActivated() {

        @Override
        public void success() {
            if (isInitiator()) {
                Log.d(Config.LOGTAG, "we were initiating. sending file");
                transport.send(file, onFileTransmissionStatusChanged);
            } else {
                transport.receive(file, onFileTransmissionStatusChanged);
                Log.d(Config.LOGTAG, "we were responding. receiving file");
            }
        }

        @Override
        public void failed() {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": proxy activation failed");
            proxyActivationFailed = true;
            if (isInitiator()) {
                sendFallbackToIbb();
            }
        }
    };

    JingleFileTransferConnection(JingleConnectionManager jingleConnectionManager, Id id, Jid initiator) {
        super(jingleConnectionManager, id, initiator);
    }

    private static long parseLong(final Element element, final long l) {
        final String input = element == null ? null : element.getContent();
        if (input == null) {
            return l;
        }
        try {
            return Long.parseLong(input);
        } catch (Exception e) {
            return l;
        }
    }

    //TODO get rid and use isInitiator() instead
    private boolean responding() {
        return responder != null && responder.equals(id.account.getJid());
    }


    InputStream getFileInputStream() {
        return this.mFileInputStream;
    }

    OutputStream getFileOutputStream() throws IOException {
        if (this.file == null) {
            Log.d(Config.LOGTAG, "file object was not assigned");
            return null;
        }
        final File parent = this.file.getParentFile();
        if (parent != null && parent.mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directories for file " + file.getAbsolutePath());
        }
        if (this.file.createNewFile()) {
            Log.d(Config.LOGTAG, "created output file " + file.getAbsolutePath());
        }
        this.mFileOutputStream = AbstractConnectionManager.createOutputStream(this.file, false, true);
        return this.mFileOutputStream;
    }

    @Override
    void deliverPacket(final JinglePacket packet) {
        final JinglePacket.Action action = packet.getAction();
        //TODO switch case
        if (action == JinglePacket.Action.SESSION_INITIATE) {
            init(packet);
        } else if (action == JinglePacket.Action.SESSION_TERMINATE) {
            final Reason reason = packet.getReason().reason;
            switch (reason) {
                case CANCEL:
                    this.cancelled = true;
                    this.fail();
                    break;
                case SUCCESS:
                    this.receiveSuccess();
                    break;
                default:
                    Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received session-terminate with reason " + reason);
                    this.fail();
                    break;

            }
        } else if (action == JinglePacket.Action.SESSION_ACCEPT) {
            receiveAccept(packet);
        } else if (action == JinglePacket.Action.SESSION_INFO) {
            final Element checksum = packet.getJingleChild("checksum");
            final Element file = checksum == null ? null : checksum.findChild("file");
            final Element hash = file == null ? null : file.findChild("hash", "urn:xmpp:hashes:2");
            if (hash != null && "sha-1".equalsIgnoreCase(hash.getAttribute("algo"))) {
                try {
                    this.expectedHash = Base64.decode(hash.getContent(), Base64.DEFAULT);
                } catch (Exception e) {
                    this.expectedHash = new byte[0];
                }
            }
            respondToIq(packet, true);
        } else if (action == JinglePacket.Action.TRANSPORT_INFO) {
            receiveTransportInfo(packet);
        } else if (action == JinglePacket.Action.TRANSPORT_REPLACE) {
            final Content content = packet.getJingleContent();
            final GenericTransportInfo transportInfo = content == null ? null : content.getTransport();
            if (transportInfo instanceof IbbTransportInfo) {
                receiveFallbackToIbb(packet, (IbbTransportInfo) transportInfo);
            } else {
                Log.d(Config.LOGTAG, "trying to fallback to something unknown" + packet.toString());
                respondToIq(packet, false);
            }
        } else if (action == JinglePacket.Action.TRANSPORT_ACCEPT) {
            receiveTransportAccept(packet);
        } else {
            Log.d(Config.LOGTAG, "packet arrived in connection. action was " + packet.getAction());
            respondToIq(packet, false);
        }
    }

    @Override
    void notifyRebound() {
        if (getJingleStatus() == JINGLE_STATUS_TRANSMITTING) {
            abort(Reason.CONNECTIVITY_ERROR);
        }
    }

    private void respondToIq(final IqPacket packet, final boolean result) {
        final IqPacket response;
        if (result) {
            response = packet.generateResponse(IqPacket.TYPE.RESULT);
        } else {
            response = packet.generateResponse(IqPacket.TYPE.ERROR);
            final Element error = response.addChild("error").setAttribute("type", "cancel");
            error.addChild("not-acceptable", "urn:ietf:params:xml:ns:xmpp-stanzas");
        }
        xmppConnectionService.sendIqPacket(id.account, response, null);
    }

    private void respondToIqWithOutOfOrder(final IqPacket packet) {
        final IqPacket response = packet.generateResponse(IqPacket.TYPE.ERROR);
        final Element error = response.addChild("error").setAttribute("type", "wait");
        error.addChild("unexpected-request", "urn:ietf:params:xml:ns:xmpp-stanzas");
        error.addChild("out-of-order", "urn:xmpp:jingle:errors:1");
        xmppConnectionService.sendIqPacket(id.account, response, null);
    }

    public void init(final Message message) {
        Preconditions.checkArgument(message.isFileOrImage());
        if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
            Conversation conversation = (Conversation) message.getConversation();
            conversation.getAccount().getAxolotlService().prepareKeyTransportMessage(conversation, xmppAxolotlMessage -> {
                if (xmppAxolotlMessage != null) {
                    init(message, xmppAxolotlMessage);
                } else {
                    fail();
                }
            });
        } else {
            init(message, null);
        }
    }

    private void init(final Message message, final XmppAxolotlMessage xmppAxolotlMessage) {
        this.mXmppAxolotlMessage = xmppAxolotlMessage;
        this.contentCreator = Content.Creator.INITIATOR;
        this.contentSenders = Content.Senders.INITIATOR;
        this.contentName = JingleConnectionManager.nextRandomId();
        this.message = message;
        final List<String> remoteFeatures = getRemoteFeatures();
        final FileTransferDescription.Version remoteVersion = getAvailableFileTransferVersion(remoteFeatures);
        this.initialTransport = remoteFeatures.contains(Namespace.JINGLE_TRANSPORTS_S5B) ? S5BTransportInfo.class : IbbTransportInfo.class;
        this.remoteSupportsOmemoJet = remoteFeatures.contains(Namespace.JINGLE_ENCRYPTED_TRANSPORT_OMEMO);
        this.message.setTransferable(this);
        this.mStatus = Transferable.STATUS_UPLOADING;
        this.responder = this.id.with;
        this.transportId = JingleConnectionManager.nextRandomId();
        this.setupDescription(remoteVersion);
        if (this.initialTransport == IbbTransportInfo.class) {
            this.sendInitRequest();
        } else {
            gatherAndConnectDirectCandidates();
            this.jingleConnectionManager.getPrimaryCandidate(id.account, isInitiator(), (success, candidate) -> {
                if (success) {
                    final JingleSocks5Transport socksConnection = new JingleSocks5Transport(this, candidate);
                    connections.put(candidate.getCid(), socksConnection);
                    socksConnection.connect(new OnTransportConnected() {

                        @Override
                        public void failed() {
                            Log.d(Config.LOGTAG, String.format("connection to our own proxy65 candidate failed (%s:%d)", candidate.getHost(), candidate.getPort()));
                            sendInitRequest();
                        }

                        @Override
                        public void established() {
                            Log.d(Config.LOGTAG, "successfully connected to our own proxy65 candidate");
                            mergeCandidate(candidate);
                            sendInitRequest();
                        }
                    });
                    mergeCandidate(candidate);
                } else {
                    Log.d(Config.LOGTAG, "no proxy65 candidate of our own was found");
                    sendInitRequest();
                }
            });
        }

    }

    private void gatherAndConnectDirectCandidates() {
        final List<JingleCandidate> directCandidates;
        if (Config.USE_DIRECT_JINGLE_CANDIDATES) {
            if (id.account.isOnion() || xmppConnectionService.useTorToConnect()) {
                directCandidates = Collections.emptyList();
            } else {
                directCandidates = DirectConnectionUtils.getLocalCandidates(id.account.getJid());
            }
        } else {
            directCandidates = Collections.emptyList();
        }
        for (JingleCandidate directCandidate : directCandidates) {
            final JingleSocks5Transport socksConnection = new JingleSocks5Transport(this, directCandidate);
            connections.put(directCandidate.getCid(), socksConnection);
            candidates.add(directCandidate);
        }
    }

    private FileTransferDescription.Version getAvailableFileTransferVersion(List<String> remoteFeatures) {
        if (remoteFeatures.contains(FileTransferDescription.Version.FT_5.getNamespace())) {
            return FileTransferDescription.Version.FT_5;
        } else if (remoteFeatures.contains(FileTransferDescription.Version.FT_4.getNamespace())) {
            return FileTransferDescription.Version.FT_4;
        } else {
            return FileTransferDescription.Version.FT_3;
        }
    }

    private List<String> getRemoteFeatures() {
        final String resource = Strings.nullToEmpty(this.id.with.getResource());
        final Presence presence = this.id.account.getRoster().getContact(id.with).getPresences().get(resource);
        final ServiceDiscoveryResult result = presence != null ? presence.getServiceDiscoveryResult() : null;
        return result == null ? Collections.emptyList() : result.getFeatures();
    }

    private void init(JinglePacket packet) { //should move to deliverPacket
        //TODO if not 'OFFERED' reply with out-of-order
        this.mJingleStatus = JINGLE_STATUS_INITIATED;
        final Conversation conversation = this.xmppConnectionService.findOrCreateConversation(id.account, id.with.asBareJid(), false, false);
        this.message = new Message(conversation, "", Message.ENCRYPTION_NONE);
        this.message.setStatus(Message.STATUS_RECEIVED);
        this.mStatus = Transferable.STATUS_OFFER;
        this.message.setTransferable(this);
        this.message.setCounterpart(this.id.with);
        this.responder = this.id.account.getJid();
        final Content content = packet.getJingleContent();
        final GenericTransportInfo transportInfo = content.getTransport();
        this.contentCreator = content.getCreator();
        Content.Senders senders;
        try {
            senders = content.getSenders();
        } catch (final Exception e) {
            senders = Content.Senders.INITIATOR;
        }
        this.contentSenders = senders;
        this.contentName = content.getAttribute("name");

        if (transportInfo instanceof S5BTransportInfo) {
            final S5BTransportInfo s5BTransportInfo = (S5BTransportInfo) transportInfo;
            this.transportId = s5BTransportInfo.getTransportId();
            this.initialTransport = s5BTransportInfo.getClass();
            this.mergeCandidates(s5BTransportInfo.getCandidates());
        } else if (transportInfo instanceof IbbTransportInfo) {
            final IbbTransportInfo ibbTransportInfo = (IbbTransportInfo) transportInfo;
            this.initialTransport = ibbTransportInfo.getClass();
            this.transportId = ibbTransportInfo.getTransportId();
            final int remoteBlockSize = ibbTransportInfo.getBlockSize();
            if (remoteBlockSize <= 0) {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": remote party requested invalid ibb block size");
                respondToIq(packet, false);
                this.fail();
            }
            this.ibbBlockSize = Math.min(MAX_IBB_BLOCK_SIZE, ibbTransportInfo.getBlockSize());
        } else {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": remote tried to use unknown transport " + transportInfo.getNamespace());
            respondToIq(packet, false);
            this.fail();
            return;
        }

        this.description = (FileTransferDescription) content.getDescription();

        final Element fileOffer = this.description.getFileOffer();

        if (fileOffer != null) {
            boolean remoteIsUsingJet = false;
            Element encrypted = fileOffer.findChild("encrypted", AxolotlService.PEP_PREFIX);
            if (encrypted == null) {
                final Element security = content.findChild("security", Namespace.JINGLE_ENCRYPTED_TRANSPORT);
                if (security != null && AxolotlService.PEP_PREFIX.equals(security.getAttribute("type"))) {
                    Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received jingle file offer with JET");
                    encrypted = security.findChild("encrypted", AxolotlService.PEP_PREFIX);
                    remoteIsUsingJet = true;
                }
            }
            if (encrypted != null) {
                this.mXmppAxolotlMessage = XmppAxolotlMessage.fromElement(encrypted, packet.getFrom().asBareJid());
            }
            Element fileSize = fileOffer.findChild("size");
            final String path = fileOffer.findChildContent("name");
            if (path != null) {
                AbstractConnectionManager.Extension extension = AbstractConnectionManager.Extension.of(path);
                if (VALID_IMAGE_EXTENSIONS.contains(extension.main)) {
                    message.setType(Message.TYPE_IMAGE);
                    xmppConnectionService.getFileBackend().setupRelativeFilePath(message, message.getUuid() + "." + extension.main);
                } else if (VALID_CRYPTO_EXTENSIONS.contains(extension.main)) {
                    if (VALID_IMAGE_EXTENSIONS.contains(extension.secondary)) {
                        message.setType(Message.TYPE_IMAGE);
                        xmppConnectionService.getFileBackend().setupRelativeFilePath(message,message.getUuid() + "." + extension.secondary);
                    } else {
                        message.setType(Message.TYPE_FILE);
                        xmppConnectionService.getFileBackend().setupRelativeFilePath(message,message.getUuid() + (extension.secondary != null ? ("." + extension.secondary) : ""));
                    }
                    message.setEncryption(Message.ENCRYPTION_PGP);
                } else {
                    message.setType(Message.TYPE_FILE);
                    xmppConnectionService.getFileBackend().setupRelativeFilePath(message,message.getUuid() + (extension.main != null ? ("." + extension.main) : ""));
                }
                long size = parseLong(fileSize, 0);
                message.setBody(Long.toString(size));
                conversation.add(message);
                jingleConnectionManager.updateConversationUi(true);
                this.file = this.xmppConnectionService.getFileBackend().getFile(message, false);
                if (mXmppAxolotlMessage != null) {
                    XmppAxolotlMessage.XmppAxolotlKeyTransportMessage transportMessage = id.account.getAxolotlService().processReceivingKeyTransportMessage(mXmppAxolotlMessage, false);
                    if (transportMessage != null) {
                        message.setEncryption(Message.ENCRYPTION_AXOLOTL);
                        this.file.setKey(transportMessage.getKey());
                        this.file.setIv(transportMessage.getIv());
                        message.setFingerprint(transportMessage.getFingerprint());
                    } else {
                        Log.d(Config.LOGTAG, "could not process KeyTransportMessage");
                    }
                }
                message.resetFileParams();
                //legacy OMEMO encrypted file transfers reported the file size after encryption
                //JET reports the plain text size. however lower levels of our receiving code still
                //expect the cipher text size. so we just + 16 bytes (auth tag size) here
                this.file.setExpectedSize(size + (remoteIsUsingJet ? 16 : 0));

                respondToIq(packet, true);

                if (id.account.getRoster().getContact(id.with).showInContactList()
                        && jingleConnectionManager.hasStoragePermission()
                        && size < this.jingleConnectionManager.getAutoAcceptFileSize()
                        && xmppConnectionService.isDataSaverDisabled()) {
                    Log.d(Config.LOGTAG, "auto accepting file from " + id.with);
                    this.acceptedAutomatically = true;
                    this.sendAccept();
                } else {
                    message.markUnread();
                    Log.d(Config.LOGTAG,
                            "not auto accepting new file offer with size: "
                                    + size
                                    + " allowed size:"
                                    + this.jingleConnectionManager
                                    .getAutoAcceptFileSize());
                    this.xmppConnectionService.getNotificationService().push(message);
                }
                Log.d(Config.LOGTAG, "receiving file: expecting size of " + this.file.getExpectedSize());
                return;
            }
            respondToIq(packet, false);
        }
    }

    private void setupDescription(final FileTransferDescription.Version version) {
        this.file = this.xmppConnectionService.getFileBackend().getFile(message, false);
        final FileTransferDescription description;
        if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
            this.file.setKey(mXmppAxolotlMessage.getInnerKey());
            this.file.setIv(mXmppAxolotlMessage.getIV());
            //legacy OMEMO encrypted file transfer reported file size of the encrypted file
            //JET uses the file size of the plain text file. The difference is only 16 bytes (auth tag)
            this.file.setExpectedSize(file.getSize() + (this.remoteSupportsOmemoJet ? 0 : 16));
            if (remoteSupportsOmemoJet) {
                description = FileTransferDescription.of(this.file, version, null);
            } else {
                description = FileTransferDescription.of(this.file, version, this.mXmppAxolotlMessage);
            }
        } else {
            this.file.setExpectedSize(file.getSize());
            description = FileTransferDescription.of(this.file, version, null);
        }
        this.description = description;
    }

    private void sendInitRequest() {
        final JinglePacket packet = this.bootstrapPacket(JinglePacket.Action.SESSION_INITIATE);
        final Content content = new Content(this.contentCreator, this.contentSenders, this.contentName);
        if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL && remoteSupportsOmemoJet) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": remote announced support for JET");
            final Element security = new Element("security", Namespace.JINGLE_ENCRYPTED_TRANSPORT);
            security.setAttribute("name", this.contentName);
            security.setAttribute("cipher", JET_OMEMO_CIPHER);
            security.setAttribute("type", AxolotlService.PEP_PREFIX);
            security.addChild(mXmppAxolotlMessage.toElement());
            content.addChild(security);
        }
        content.setDescription(this.description);
        message.resetFileParams();
        try {
            this.mFileInputStream = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            fail(e.getMessage());
            return;
        }
        if (this.initialTransport == IbbTransportInfo.class) {
            content.setTransport(new IbbTransportInfo(this.transportId, this.ibbBlockSize));
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": sending IBB offer");
        } else {
            final Collection<JingleCandidate> candidates = getOurCandidates();
            content.setTransport(new S5BTransportInfo(this.transportId, candidates));
            Log.d(Config.LOGTAG, String.format("%s: sending S5B offer with %d candidates", id.account.getJid().asBareJid(), candidates.size()));
        }
        packet.addJingleContent(content);
        this.sendJinglePacket(packet, (account, response) -> {
            if (response.getType() == IqPacket.TYPE.RESULT) {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": other party received offer");
                if (mJingleStatus == JINGLE_STATUS_OFFERED) {
                    mJingleStatus = JINGLE_STATUS_INITIATED;
                    xmppConnectionService.markMessage(message, Message.STATUS_OFFERED);
                } else {
                    Log.d(Config.LOGTAG, "received ack for offer when status was " + mJingleStatus);
                }
            } else {
                fail(IqParser.extractErrorMessage(response));
            }
        });

    }

    private void sendHash() {
        final Element checksum = new Element("checksum", description.getVersion().getNamespace());
        checksum.setAttribute("creator", "initiator");
        checksum.setAttribute("name", "a-file-offer");
        Element hash = checksum.addChild("file").addChild("hash", "urn:xmpp:hashes:2");
        hash.setAttribute("algo", "sha-1").setContent(Base64.encodeToString(file.getSha1Sum(), Base64.NO_WRAP));

        final JinglePacket packet = this.bootstrapPacket(JinglePacket.Action.SESSION_INFO);
        packet.addJingleChild(checksum);
        xmppConnectionService.sendIqPacket(id.account, packet, (account, response) -> {
            if (response.getType() == IqPacket.TYPE.ERROR) {
                Log.d(Config.LOGTAG,account.getJid().asBareJid()+": ignoring error response to our session-info (hash transmission)");
            }
        });
    }

    private Collection<JingleCandidate> getOurCandidates() {
        return Collections2.filter(this.candidates, c -> c != null && c.isOurs());
    }

    private void sendAccept() {
        mJingleStatus = JINGLE_STATUS_ACCEPTED;
        this.mStatus = Transferable.STATUS_DOWNLOADING;
        this.jingleConnectionManager.updateConversationUi(true);
        if (initialTransport == S5BTransportInfo.class) {
            sendAcceptSocks();
        } else {
            sendAcceptIbb();
        }
    }

    private void sendAcceptSocks() {
        gatherAndConnectDirectCandidates();
        this.jingleConnectionManager.getPrimaryCandidate(this.id.account, isInitiator(), (success, candidate) -> {
            final JinglePacket packet = bootstrapPacket(JinglePacket.Action.SESSION_ACCEPT);
            final Content content = new Content(contentCreator, contentSenders, contentName);
            content.setDescription(this.description);
            if (success && candidate != null && !equalCandidateExists(candidate)) {
                final JingleSocks5Transport socksConnection = new JingleSocks5Transport(this, candidate);
                connections.put(candidate.getCid(), socksConnection);
                socksConnection.connect(new OnTransportConnected() {

                    @Override
                    public void failed() {
                        Log.d(Config.LOGTAG, "connection to our own proxy65 candidate failed");
                        content.setTransport(new S5BTransportInfo(transportId, getOurCandidates()));
                        packet.addJingleContent(content);
                        sendJinglePacket(packet);
                        connectNextCandidate();
                    }

                    @Override
                    public void established() {
                        Log.d(Config.LOGTAG, "connected to proxy65 candidate");
                        mergeCandidate(candidate);
                        content.setTransport(new S5BTransportInfo(transportId, getOurCandidates()));
                        packet.addJingleContent(content);
                        sendJinglePacket(packet);
                        connectNextCandidate();
                    }
                });
            } else {
                Log.d(Config.LOGTAG, "did not find a proxy65 candidate for ourselves");
                content.setTransport(new S5BTransportInfo(transportId, getOurCandidates()));
                packet.addJingleContent(content);
                sendJinglePacket(packet);
                connectNextCandidate();
            }
        });
    }

    private void sendAcceptIbb() {
        this.transport = new JingleInBandTransport(this, this.transportId, this.ibbBlockSize);
        final JinglePacket packet = bootstrapPacket(JinglePacket.Action.SESSION_ACCEPT);
        final Content content = new Content(contentCreator, contentSenders, contentName);
        content.setDescription(this.description);
        content.setTransport(new IbbTransportInfo(this.transportId, this.ibbBlockSize));
        packet.addJingleContent(content);
        this.transport.receive(file, onFileTransmissionStatusChanged);
        this.sendJinglePacket(packet);
    }

    private JinglePacket bootstrapPacket(JinglePacket.Action action) {
        final JinglePacket packet = new JinglePacket(action, this.id.sessionId);
        packet.setTo(id.with);
        return packet;
    }

    private void sendJinglePacket(JinglePacket packet) {
        xmppConnectionService.sendIqPacket(id.account, packet, responseListener);
    }

    private void sendJinglePacket(JinglePacket packet, OnIqPacketReceived callback) {
        xmppConnectionService.sendIqPacket(id.account, packet, callback);
    }

    private void receiveAccept(JinglePacket packet) {
        if (responding()) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received out of order session-accept (we were responding)");
            respondToIqWithOutOfOrder(packet);
            return;
        }
        if (this.mJingleStatus != JINGLE_STATUS_INITIATED) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received out of order session-accept");
            respondToIqWithOutOfOrder(packet);
            return;
        }
        this.mJingleStatus = JINGLE_STATUS_ACCEPTED;
        xmppConnectionService.markMessage(message, Message.STATUS_UNSEND);
        final Content content = packet.getJingleContent();
        final GenericTransportInfo transportInfo = content.getTransport();
        //TODO we want to fail if transportInfo doesn’t match our intialTransport and/or our id
        if (transportInfo instanceof S5BTransportInfo) {
            final S5BTransportInfo s5BTransportInfo = (S5BTransportInfo) transportInfo;
            respondToIq(packet, true);
            //TODO calling merge is probably a bug because that might eliminate candidates of the other party and lead to us not sending accept/deny
            //TODO: we probably just want to call add
            mergeCandidates(s5BTransportInfo.getCandidates());
            this.connectNextCandidate();
        } else if (transportInfo instanceof IbbTransportInfo) {
            final IbbTransportInfo ibbTransportInfo = (IbbTransportInfo) transportInfo;
            final int remoteBlockSize = ibbTransportInfo.getBlockSize();
            if (remoteBlockSize > 0) {
                this.ibbBlockSize = Math.min(ibbBlockSize, remoteBlockSize);
            }
            respondToIq(packet, true);
            this.transport = new JingleInBandTransport(this, this.transportId, this.ibbBlockSize);
            this.transport.connect(onIbbTransportConnected);
        } else {
            respondToIq(packet, false);
        }
    }

    private void receiveTransportInfo(JinglePacket packet) {
        final Content content = packet.getJingleContent();
        final GenericTransportInfo transportInfo = content.getTransport();
        if (transportInfo instanceof S5BTransportInfo) {
            final S5BTransportInfo s5BTransportInfo = (S5BTransportInfo) transportInfo;
            if (s5BTransportInfo.hasChild("activated")) {
                respondToIq(packet, true);
                if ((this.transport != null) && (this.transport instanceof JingleSocks5Transport)) {
                    onProxyActivated.success();
                } else {
                    String cid = s5BTransportInfo.findChild("activated").getAttribute("cid");
                    Log.d(Config.LOGTAG, "received proxy activated (" + cid
                            + ")prior to choosing our own transport");
                    JingleSocks5Transport connection = this.connections.get(cid);
                    if (connection != null) {
                        connection.setActivated(true);
                    } else {
                        Log.d(Config.LOGTAG, "activated connection not found");
                        sendSessionTerminate(Reason.FAILED_TRANSPORT);
                        this.fail();
                    }
                }
            } else if (s5BTransportInfo.hasChild("proxy-error")) {
                respondToIq(packet, true);
                onProxyActivated.failed();
            } else if (s5BTransportInfo.hasChild("candidate-error")) {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received candidate error");
                respondToIq(packet, true);
                this.receivedCandidate = true;
                if (mJingleStatus == JINGLE_STATUS_ACCEPTED && this.sentCandidate) {
                    this.connect();
                }
            } else if (s5BTransportInfo.hasChild("candidate-used")) {
                String cid = s5BTransportInfo.findChild("candidate-used").getAttribute("cid");
                if (cid != null) {
                    Log.d(Config.LOGTAG, "candidate used by counterpart:" + cid);
                    JingleCandidate candidate = getCandidate(cid);
                    if (candidate == null) {
                        Log.d(Config.LOGTAG, "could not find candidate with cid=" + cid);
                        respondToIq(packet, false);
                        return;
                    }
                    respondToIq(packet, true);
                    candidate.flagAsUsedByCounterpart();
                    this.receivedCandidate = true;
                    if (mJingleStatus == JINGLE_STATUS_ACCEPTED && this.sentCandidate) {
                        this.connect();
                    } else {
                        Log.d(Config.LOGTAG, "ignoring because file is already in transmission or we haven't sent our candidate yet status=" + mJingleStatus + " sentCandidate=" + sentCandidate);
                    }
                } else {
                    respondToIq(packet, false);
                }
            } else {
                respondToIq(packet, false);
            }
        } else {
            respondToIq(packet, true);
        }
    }

    private void connect() {
        final JingleSocks5Transport connection = chooseConnection();
        this.transport = connection;
        if (connection == null) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": could not find suitable candidate");
            this.disconnectSocks5Connections();
            if (isInitiator()) {
                this.sendFallbackToIbb();
            }
        } else {
            //TODO at this point we can already close other connections to free some resources
            final JingleCandidate candidate = connection.getCandidate();
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": elected candidate " + candidate.toString());
            this.mJingleStatus = JINGLE_STATUS_TRANSMITTING;
            if (connection.needsActivation()) {
                if (connection.getCandidate().isOurs()) {
                    final String sid;
                    if (description.getVersion() == FileTransferDescription.Version.FT_3) {
                        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": use session ID instead of transport ID to activate proxy");
                        sid = id.sessionId;
                    } else {
                        sid = getTransportId();
                    }
                    Log.d(Config.LOGTAG, "candidate "
                            + connection.getCandidate().getCid()
                            + " was our proxy. going to activate");
                    IqPacket activation = new IqPacket(IqPacket.TYPE.SET);
                    activation.setTo(connection.getCandidate().getJid());
                    activation.query("http://jabber.org/protocol/bytestreams")
                            .setAttribute("sid", sid);
                    activation.query().addChild("activate")
                            .setContent(this.id.with.toEscapedString());
                    xmppConnectionService.sendIqPacket(this.id.account, activation, (account, response) -> {
                        if (response.getType() != IqPacket.TYPE.RESULT) {
                            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": " + response.toString());
                            sendProxyError();
                            onProxyActivated.failed();
                        } else {
                            sendProxyActivated(connection.getCandidate().getCid());
                            onProxyActivated.success();
                        }
                    });
                } else {
                    Log.d(Config.LOGTAG,
                            "candidate "
                                    + connection.getCandidate().getCid()
                                    + " was a proxy. waiting for other party to activate");
                }
            } else {
                if (isInitiator()) {
                    Log.d(Config.LOGTAG, "we were initiating. sending file");
                    connection.send(file, onFileTransmissionStatusChanged);
                } else {
                    Log.d(Config.LOGTAG, "we were responding. receiving file");
                    connection.receive(file, onFileTransmissionStatusChanged);
                }
            }
        }
    }

    private JingleSocks5Transport chooseConnection() {
        final List<JingleSocks5Transport> establishedConnections = FluentIterable.from(connections.entrySet())
                .transform(Entry::getValue)
                .filter(c -> (c != null && c.isEstablished() && (c.getCandidate().isUsedByCounterpart() || !c.getCandidate().isOurs())))
                .toSortedList((a, b) -> {
                    final int compare = Integer.compare(b.getCandidate().getPriority(), a.getCandidate().getPriority());
                    if (compare == 0) {
                        if (isInitiator()) {
                            //pick the one we sent a candidate-used for (meaning not ours)
                            return a.getCandidate().isOurs() ? 1 : -1;
                        } else {
                            //pick the one they sent a candidate-used for (meaning ours)
                            return a.getCandidate().isOurs() ? -1 : 1;
                        }
                    }
                    return compare;
                });
        return Iterables.getFirst(establishedConnections, null);
    }

    private void sendSuccess() {
        sendSessionTerminate(Reason.SUCCESS);
        this.disconnectSocks5Connections();
        this.mJingleStatus = JINGLE_STATUS_FINISHED;
        this.message.setStatus(Message.STATUS_RECEIVED);
        this.message.setTransferable(null);
        this.xmppConnectionService.updateMessage(message, false);
        this.jingleConnectionManager.finishConnection(this);
    }

    private void sendFallbackToIbb() {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": sending fallback to ibb");
        final JinglePacket packet = this.bootstrapPacket(JinglePacket.Action.TRANSPORT_REPLACE);
        final Content content = new Content(this.contentCreator, this.contentSenders, this.contentName);
        this.transportId = JingleConnectionManager.nextRandomId();
        content.setTransport(new IbbTransportInfo(this.transportId, this.ibbBlockSize));
        packet.addJingleContent(content);
        this.sendJinglePacket(packet);
    }


    private void receiveFallbackToIbb(final JinglePacket packet, final IbbTransportInfo transportInfo) {
        if (isInitiator()) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received out of order transport-replace (we were initiating)");
            respondToIqWithOutOfOrder(packet);
            return;
        }
        final boolean validState = mJingleStatus == JINGLE_STATUS_ACCEPTED || (proxyActivationFailed && mJingleStatus == JINGLE_STATUS_TRANSMITTING);
        if (!validState) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received out of order transport-replace");
            respondToIqWithOutOfOrder(packet);
            return;
        }
        this.proxyActivationFailed = false; //fallback received; now we no longer need to accept another one;
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": receiving fallback to ibb");
        final int remoteBlockSize = transportInfo.getBlockSize();
        if (remoteBlockSize > 0) {
            this.ibbBlockSize = Math.min(MAX_IBB_BLOCK_SIZE, remoteBlockSize);
        } else {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable to parse block size in transport-replace");
        }
        this.transportId = transportInfo.getTransportId(); //TODO: handle the case where this is null by the remote party
        this.transport = new JingleInBandTransport(this, this.transportId, this.ibbBlockSize);

        final JinglePacket answer = bootstrapPacket(JinglePacket.Action.TRANSPORT_ACCEPT);

        final Content content = new Content(contentCreator, contentSenders, contentName);
        content.setTransport(new IbbTransportInfo(this.transportId, this.ibbBlockSize));
        answer.addJingleContent(content);

        respondToIq(packet, true);

        if (isInitiator()) {
            this.sendJinglePacket(answer, (account, response) -> {
                if (response.getType() == IqPacket.TYPE.RESULT) {
                    Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + " recipient ACKed our transport-accept. creating ibb");
                    transport.connect(onIbbTransportConnected);
                }
            });
        } else {
            this.transport.receive(file, onFileTransmissionStatusChanged);
            this.sendJinglePacket(answer);
        }
    }

    private void receiveTransportAccept(JinglePacket packet) {
        if (responding()) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received out of order transport-accept (we were responding)");
            respondToIqWithOutOfOrder(packet);
            return;
        }
        final boolean validState = mJingleStatus == JINGLE_STATUS_ACCEPTED || (proxyActivationFailed && mJingleStatus == JINGLE_STATUS_TRANSMITTING);
        if (!validState) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received out of order transport-accept");
            respondToIqWithOutOfOrder(packet);
            return;
        }
        this.proxyActivationFailed = false; //fallback accepted; now we no longer need to accept another one;
        final Content content = packet.getJingleContent();
        final GenericTransportInfo transportInfo = content == null ? null : content.getTransport();
        if (transportInfo instanceof IbbTransportInfo) {
            final IbbTransportInfo ibbTransportInfo = (IbbTransportInfo) transportInfo;
            final int remoteBlockSize = ibbTransportInfo.getBlockSize();
            if (remoteBlockSize > 0) {
                this.ibbBlockSize = Math.min(MAX_IBB_BLOCK_SIZE, remoteBlockSize);
            }
            final String sid = ibbTransportInfo.getTransportId();
            this.transport = new JingleInBandTransport(this, this.transportId, this.ibbBlockSize);

            if (sid == null || !sid.equals(this.transportId)) {
                Log.w(Config.LOGTAG, String.format("%s: sid in transport-accept (%s) did not match our sid (%s) ", id.account.getJid().asBareJid(), sid, transportId));
            }
            respondToIq(packet, true);
            //might be receive instead if we are not initiating
            if (isInitiator()) {
                this.transport.connect(onIbbTransportConnected);
            }
        } else {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received invalid transport-accept");
            respondToIq(packet, false);
        }
    }

    private void receiveSuccess() {
        if (isInitiator()) {
            this.mJingleStatus = JINGLE_STATUS_FINISHED;
            this.xmppConnectionService.markMessage(this.message, Message.STATUS_SEND_RECEIVED);
            this.disconnectSocks5Connections();
            if (this.transport instanceof JingleInBandTransport) {
                this.transport.disconnect();
            }
            this.message.setTransferable(null);
            this.jingleConnectionManager.finishConnection(this);
        } else {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received session-terminate/success while responding");
        }
    }

    @Override
    public void cancel() {
        this.cancelled = true;
        abort(Reason.CANCEL);
    }

    private void abort(final Reason reason) {
        this.disconnectSocks5Connections();
        if (this.transport instanceof JingleInBandTransport) {
            this.transport.disconnect();
        }
        sendSessionTerminate(reason);
        this.jingleConnectionManager.finishConnection(this);
        if (responding()) {
            this.message.setTransferable(new TransferablePlaceholder(cancelled ? Transferable.STATUS_CANCELLED : Transferable.STATUS_FAILED));
            if (this.file != null) {
                file.delete();
            }
            this.jingleConnectionManager.updateConversationUi(true);
        } else {
            this.xmppConnectionService.markMessage(this.message, Message.STATUS_SEND_FAILED, cancelled ? Message.ERROR_MESSAGE_CANCELLED : null);
            this.message.setTransferable(null);
        }
    }

    private void fail() {
        fail(null);
    }

    private void fail(String errorMessage) {
        this.mJingleStatus = JINGLE_STATUS_FAILED;
        this.disconnectSocks5Connections();
        if (this.transport instanceof JingleInBandTransport) {
            this.transport.disconnect();
        }
        FileBackend.close(mFileInputStream);
        FileBackend.close(mFileOutputStream);
        if (this.message != null) {
            if (responding()) {
                this.message.setTransferable(new TransferablePlaceholder(cancelled ? Transferable.STATUS_CANCELLED : Transferable.STATUS_FAILED));
                if (this.file != null) {
                    file.delete();
                }
                this.jingleConnectionManager.updateConversationUi(true);
            } else {
                this.xmppConnectionService.markMessage(this.message,
                        Message.STATUS_SEND_FAILED,
                        cancelled ? Message.ERROR_MESSAGE_CANCELLED : errorMessage);
                this.message.setTransferable(null);
            }
        }
        this.jingleConnectionManager.finishConnection(this);
    }

    private void sendSessionTerminate(Reason reason) {
        final JinglePacket packet = bootstrapPacket(JinglePacket.Action.SESSION_TERMINATE);
        packet.setReason(reason, null);
        this.sendJinglePacket(packet);
    }

    private void connectNextCandidate() {
        for (JingleCandidate candidate : this.candidates) {
            if ((!connections.containsKey(candidate.getCid()) && (!candidate
                    .isOurs()))) {
                this.connectWithCandidate(candidate);
                return;
            }
        }
        this.sendCandidateError();
    }

    private void connectWithCandidate(final JingleCandidate candidate) {
        final JingleSocks5Transport socksConnection = new JingleSocks5Transport(
                this, candidate);
        connections.put(candidate.getCid(), socksConnection);
        socksConnection.connect(new OnTransportConnected() {

            @Override
            public void failed() {
                Log.d(Config.LOGTAG,
                        "connection failed with " + candidate.getHost() + ":"
                                + candidate.getPort());
                connectNextCandidate();
            }

            @Override
            public void established() {
                Log.d(Config.LOGTAG,
                        "established connection with " + candidate.getHost()
                                + ":" + candidate.getPort());
                sendCandidateUsed(candidate.getCid());
            }
        });
    }

    private void disconnectSocks5Connections() {
        Iterator<Entry<String, JingleSocks5Transport>> it = this.connections
                .entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, JingleSocks5Transport> pairs = it.next();
            pairs.getValue().disconnect();
            it.remove();
        }
    }

    private void sendProxyActivated(String cid) {
        final JinglePacket packet = bootstrapPacket(JinglePacket.Action.TRANSPORT_INFO);
        final Content content = new Content(this.contentCreator, this.contentSenders, this.contentName);
        content.setTransport(new S5BTransportInfo(this.transportId, new Element("activated").setAttribute("cid", cid)));
        packet.addJingleContent(content);
        this.sendJinglePacket(packet);
    }

    private void sendProxyError() {
        final JinglePacket packet = bootstrapPacket(JinglePacket.Action.TRANSPORT_INFO);
        final Content content = new Content(this.contentCreator, this.contentSenders, this.contentName);
        content.setTransport(new S5BTransportInfo(this.transportId, new Element("proxy-error")));
        packet.addJingleContent(content);
        this.sendJinglePacket(packet);
    }

    private void sendCandidateUsed(final String cid) {
        JinglePacket packet = bootstrapPacket(JinglePacket.Action.TRANSPORT_INFO);
        final Content content = new Content(this.contentCreator, this.contentSenders, this.contentName);
        content.setTransport(new S5BTransportInfo(this.transportId, new Element("candidate-used").setAttribute("cid", cid)));
        packet.addJingleContent(content);
        this.sentCandidate = true;
        if ((receivedCandidate) && (mJingleStatus == JINGLE_STATUS_ACCEPTED)) {
            connect();
        }
        this.sendJinglePacket(packet);
    }

    private void sendCandidateError() {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": sending candidate error");
        JinglePacket packet = bootstrapPacket(JinglePacket.Action.TRANSPORT_INFO);
        Content content = new Content(this.contentCreator, this.contentSenders, this.contentName);
        content.setTransport(new S5BTransportInfo(this.transportId, new Element("candidate-error")));
        packet.addJingleContent(content);
        this.sentCandidate = true;
        this.sendJinglePacket(packet);
        if (receivedCandidate && mJingleStatus == JINGLE_STATUS_ACCEPTED) {
            connect();
        }
    }

    private int getJingleStatus() {
        return this.mJingleStatus;
    }

    private boolean equalCandidateExists(JingleCandidate candidate) {
        for (JingleCandidate c : this.candidates) {
            if (c.equalValues(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void mergeCandidate(JingleCandidate candidate) {
        for (JingleCandidate c : this.candidates) {
            if (c.equals(candidate)) {
                return;
            }
        }
        this.candidates.add(candidate);
    }

    private void mergeCandidates(List<JingleCandidate> candidates) {
        Collections.sort(candidates, (a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        for (JingleCandidate c : candidates) {
            mergeCandidate(c);
        }
    }

    private JingleCandidate getCandidate(String cid) {
        for (JingleCandidate c : this.candidates) {
            if (c.getCid().equals(cid)) {
                return c;
            }
        }
        return null;
    }

    void updateProgress(int i) {
        this.mProgress = i;
        jingleConnectionManager.updateConversationUi(false);
    }

    String getTransportId() {
        return this.transportId;
    }

    FileTransferDescription.Version getFtVersion() {
        return this.description.getVersion();
    }

    public JingleTransport getTransport() {
        return this.transport;
    }

    public boolean start() {
        if (id.account.getStatus() == Account.State.ONLINE) {
            if (mJingleStatus == JINGLE_STATUS_INITIATED) {
                new Thread(this::sendAccept).start();
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public int getStatus() {
        return this.mStatus;
    }

    @Override
    public Long getFileSize() {
        if (this.file != null) {
            return this.file.getExpectedSize();
        } else {
            return null;
        }
    }

    @Override
    public int getProgress() {
        return this.mProgress;
    }

    AbstractConnectionManager getConnectionManager() {
        return this.jingleConnectionManager;
    }

    interface OnProxyActivated {
        void success();

        void failed();
    }
}
