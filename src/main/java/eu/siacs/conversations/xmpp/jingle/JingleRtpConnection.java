package eu.siacs.conversations.xmpp.jingle;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.VideoTrack;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.CryptoFailedException;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.services.AppRTCAudioManager;
import eu.siacs.conversations.utils.IP;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.stanzas.Group;
import eu.siacs.conversations.xmpp.jingle.stanzas.IceUdpTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.JinglePacket;
import eu.siacs.conversations.xmpp.jingle.stanzas.Proceed;
import eu.siacs.conversations.xmpp.jingle.stanzas.Propose;
import eu.siacs.conversations.xmpp.jingle.stanzas.Reason;
import eu.siacs.conversations.xmpp.jingle.stanzas.RtpDescription;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;

public class JingleRtpConnection extends AbstractJingleConnection implements WebRTCWrapper.EventCallback {

    public static final List<State> STATES_SHOWING_ONGOING_CALL = Arrays.asList(
            State.PROCEED,
            State.SESSION_INITIALIZED_PRE_APPROVED,
            State.SESSION_ACCEPTED
    );
    private static final long BUSY_TIME_OUT = 30;
    private static final List<State> TERMINATED = Arrays.asList(
            State.ACCEPTED,
            State.REJECTED,
            State.REJECTED_RACED,
            State.RETRACTED,
            State.RETRACTED_RACED,
            State.TERMINATED_SUCCESS,
            State.TERMINATED_DECLINED_OR_BUSY,
            State.TERMINATED_CONNECTIVITY_ERROR,
            State.TERMINATED_CANCEL_OR_TIMEOUT,
            State.TERMINATED_APPLICATION_FAILURE,
            State.TERMINATED_SECURITY_ERROR
    );

    private static final Map<State, Collection<State>> VALID_TRANSITIONS;

    static {
        final ImmutableMap.Builder<State, Collection<State>> transitionBuilder = new ImmutableMap.Builder<>();
        transitionBuilder.put(State.NULL, ImmutableList.of(
                State.PROPOSED,
                State.SESSION_INITIALIZED,
                State.TERMINATED_APPLICATION_FAILURE,
                State.TERMINATED_SECURITY_ERROR
        ));
        transitionBuilder.put(State.PROPOSED, ImmutableList.of(
                State.ACCEPTED,
                State.PROCEED,
                State.REJECTED,
                State.RETRACTED,
                State.TERMINATED_APPLICATION_FAILURE,
                State.TERMINATED_SECURITY_ERROR,
                State.TERMINATED_CONNECTIVITY_ERROR //only used when the xmpp connection rebinds
        ));
        transitionBuilder.put(State.PROCEED, ImmutableList.of(
                State.REJECTED_RACED,
                State.RETRACTED_RACED,
                State.SESSION_INITIALIZED_PRE_APPROVED,
                State.TERMINATED_SUCCESS,
                State.TERMINATED_APPLICATION_FAILURE,
                State.TERMINATED_SECURITY_ERROR,
                State.TERMINATED_CONNECTIVITY_ERROR //at this state used for error bounces of the proceed message
        ));
        transitionBuilder.put(State.SESSION_INITIALIZED, ImmutableList.of(
                State.SESSION_ACCEPTED,
                State.TERMINATED_SUCCESS,
                State.TERMINATED_DECLINED_OR_BUSY,
                State.TERMINATED_CONNECTIVITY_ERROR,  //at this state used for IQ errors and IQ timeouts
                State.TERMINATED_CANCEL_OR_TIMEOUT,
                State.TERMINATED_APPLICATION_FAILURE,
                State.TERMINATED_SECURITY_ERROR
        ));
        transitionBuilder.put(State.SESSION_INITIALIZED_PRE_APPROVED, ImmutableList.of(
                State.SESSION_ACCEPTED,
                State.TERMINATED_SUCCESS,
                State.TERMINATED_DECLINED_OR_BUSY,
                State.TERMINATED_CONNECTIVITY_ERROR,  //at this state used for IQ errors and IQ timeouts
                State.TERMINATED_CANCEL_OR_TIMEOUT,
                State.TERMINATED_APPLICATION_FAILURE,
                State.TERMINATED_SECURITY_ERROR
        ));
        transitionBuilder.put(State.SESSION_ACCEPTED, ImmutableList.of(
                State.TERMINATED_SUCCESS,
                State.TERMINATED_DECLINED_OR_BUSY,
                State.TERMINATED_CONNECTIVITY_ERROR,
                State.TERMINATED_CANCEL_OR_TIMEOUT,
                State.TERMINATED_APPLICATION_FAILURE,
                State.TERMINATED_SECURITY_ERROR
        ));
        VALID_TRANSITIONS = transitionBuilder.build();
    }

    private final WebRTCWrapper webRTCWrapper = new WebRTCWrapper(this);
    private final ArrayDeque<Set<Map.Entry<String, RtpContentMap.DescriptionTransport>>> pendingIceCandidates = new ArrayDeque<>();
    private final OmemoVerification omemoVerification = new OmemoVerification();
    private final Message message;
    private State state = State.NULL;
    private StateTransitionException stateTransitionException;
    private Set<Media> proposedMedia;
    private RtpContentMap initiatorRtpContentMap;
    private RtpContentMap responderRtpContentMap;
    private final Stopwatch sessionDuration = Stopwatch.createUnstarted();
    private final Queue<PeerConnection.PeerConnectionState> stateHistory = new LinkedList<>();
    private ScheduledFuture<?> ringingTimeoutFuture;

    JingleRtpConnection(JingleConnectionManager jingleConnectionManager, Id id, Jid initiator) {
        super(jingleConnectionManager, id, initiator);
        final Conversation conversation = jingleConnectionManager.getXmppConnectionService().findOrCreateConversation(
                id.account,
                id.with.asBareJid(),
                false,
                false
        );
        this.message = new Message(
                conversation,
                isInitiator() ? Message.STATUS_SEND : Message.STATUS_RECEIVED,
                Message.TYPE_RTP_SESSION,
                id.sessionId
        );
    }

    private static State reasonToState(Reason reason) {
        switch (reason) {
            case SUCCESS:
                return State.TERMINATED_SUCCESS;
            case DECLINE:
            case BUSY:
                return State.TERMINATED_DECLINED_OR_BUSY;
            case CANCEL:
            case TIMEOUT:
                return State.TERMINATED_CANCEL_OR_TIMEOUT;
            case SECURITY_ERROR:
                return State.TERMINATED_SECURITY_ERROR;
            case FAILED_APPLICATION:
            case UNSUPPORTED_TRANSPORTS:
            case UNSUPPORTED_APPLICATIONS:
                return State.TERMINATED_APPLICATION_FAILURE;
            default:
                return State.TERMINATED_CONNECTIVITY_ERROR;
        }
    }

    @Override
    synchronized void deliverPacket(final JinglePacket jinglePacket) {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": packet delivered to JingleRtpConnection");
        switch (jinglePacket.getAction()) {
            case SESSION_INITIATE:
                receiveSessionInitiate(jinglePacket);
                break;
            case TRANSPORT_INFO:
                receiveTransportInfo(jinglePacket);
                break;
            case SESSION_ACCEPT:
                receiveSessionAccept(jinglePacket);
                break;
            case SESSION_TERMINATE:
                receiveSessionTerminate(jinglePacket);
                break;
            default:
                respondOk(jinglePacket);
                Log.d(Config.LOGTAG, String.format("%s: received unhandled jingle action %s", id.account.getJid().asBareJid(), jinglePacket.getAction()));
                break;
        }
    }

    @Override
    synchronized void notifyRebound() {
        if (isTerminated()) {
            return;
        }
        webRTCWrapper.close();
        if (!isInitiator() && isInState(State.PROPOSED, State.SESSION_INITIALIZED)) {
            xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
        }
        if (isInState(State.SESSION_INITIALIZED, State.SESSION_INITIALIZED_PRE_APPROVED, State.SESSION_ACCEPTED)) {
            //we might have already changed resources (full jid) at this point; so this might not even reach the other party
            sendSessionTerminate(Reason.CONNECTIVITY_ERROR);
        } else {
            transitionOrThrow(State.TERMINATED_CONNECTIVITY_ERROR);
            finish();
        }
    }

    private void receiveSessionTerminate(final JinglePacket jinglePacket) {
        respondOk(jinglePacket);
        final JinglePacket.ReasonWrapper wrapper = jinglePacket.getReason();
        final State previous = this.state;
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received session terminate reason=" + wrapper.reason + "(" + Strings.nullToEmpty(wrapper.text) + ") while in state " + previous);
        if (TERMINATED.contains(previous)) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": ignoring session terminate because already in " + previous);
            return;
        }
        webRTCWrapper.close();
        final State target = reasonToState(wrapper.reason);
        transitionOrThrow(target);
        writeLogMessage(target);
        if (previous == State.PROPOSED || previous == State.SESSION_INITIALIZED) {
            xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
        }
        finish();
    }

    private void receiveTransportInfo(final JinglePacket jinglePacket) {
        //Due to the asynchronicity of processing session-init we might move from NULL|PROCEED to INITIALIZED only after transport-info has been received
        if (isInState(State.NULL, State.PROCEED, State.SESSION_INITIALIZED, State.SESSION_INITIALIZED_PRE_APPROVED, State.SESSION_ACCEPTED)) {
            respondOk(jinglePacket);
            final RtpContentMap contentMap;
            try {
                contentMap = RtpContentMap.of(jinglePacket);
            } catch (IllegalArgumentException | NullPointerException e) {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": improperly formatted contents; ignoring", e);
                return;
            }
            final Set<Map.Entry<String, RtpContentMap.DescriptionTransport>> candidates = contentMap.contents.entrySet();
            if (this.state == State.SESSION_ACCEPTED) {
                try {
                    processCandidates(candidates);
                } catch (final WebRTCWrapper.PeerConnectionNotInitialized e) {
                    Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": PeerConnection was not initialized when processing transport info. this usually indicates a race condition that can be ignored");
                }
            } else {
                pendingIceCandidates.push(candidates);
            }
        } else {
            if (isTerminated()) {
                respondOk(jinglePacket);
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": ignoring out-of-order transport info; we where already terminated");
            } else {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received transport info while in state=" + this.state);
                terminateWithOutOfOrder(jinglePacket);
            }
        }
    }

    private void processCandidates(final Set<Map.Entry<String, RtpContentMap.DescriptionTransport>> contents) {
        final RtpContentMap rtpContentMap = isInitiator() ? this.responderRtpContentMap : this.initiatorRtpContentMap;
        final Group originalGroup = rtpContentMap.group;
        final List<String> identificationTags = originalGroup == null ? rtpContentMap.getNames() : originalGroup.getIdentificationTags();
        if (identificationTags.size() == 0) {
            Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": no identification tags found in initial offer. we won't be able to calculate mLineIndices");
        }
        processCandidates(identificationTags, contents);
    }

    private void processCandidates(final List<String> indices, final Set<Map.Entry<String, RtpContentMap.DescriptionTransport>> contents) {
        for (final Map.Entry<String, RtpContentMap.DescriptionTransport> content : contents) {
            final String ufrag = content.getValue().transport.getAttribute("ufrag");
            for (final IceUdpTransportInfo.Candidate candidate : content.getValue().transport.getCandidates()) {
                final String sdp;
                try {
                    sdp = candidate.toSdpAttribute(ufrag);
                } catch (IllegalArgumentException e) {
                    Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": ignoring invalid ICE candidate " + e.getMessage());
                    continue;
                }
                final String sdpMid = content.getKey();
                final int mLineIndex = indices.indexOf(sdpMid);
                if (mLineIndex < 0) {
                    Log.w(Config.LOGTAG, "mLineIndex not found for " + sdpMid + ". available indices " + indices);
                }
                final IceCandidate iceCandidate = new IceCandidate(sdpMid, mLineIndex, sdp);
                Log.d(Config.LOGTAG, "received candidate: " + iceCandidate);
                this.webRTCWrapper.addIceCandidate(iceCandidate);
            }
        }
    }

    private ListenableFuture<RtpContentMap> receiveRtpContentMap(final JinglePacket jinglePacket, final boolean expectVerification) {
        final RtpContentMap receivedContentMap;
        try {
            receivedContentMap = RtpContentMap.of(jinglePacket);
        } catch (final Exception e) {
            return Futures.immediateFailedFuture(e);
        }
        if (receivedContentMap instanceof OmemoVerifiedRtpContentMap) {
            final ListenableFuture<AxolotlService.OmemoVerifiedPayload<RtpContentMap>> future = id.account.getAxolotlService().decrypt((OmemoVerifiedRtpContentMap) receivedContentMap, id.with);
            return Futures.transform(future, omemoVerifiedPayload -> {
                //TODO test if an exception here triggers a correct abort
                omemoVerification.setOrEnsureEqual(omemoVerifiedPayload);
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received verifiable DTLS fingerprint via " + omemoVerification);
                return omemoVerifiedPayload.getPayload();
            }, MoreExecutors.directExecutor());
        } else if (Config.REQUIRE_RTP_VERIFICATION || expectVerification) {
            return Futures.immediateFailedFuture(
                    new SecurityException("DTLS fingerprint was unexpectedly not verifiable")
            );
        } else {
            return Futures.immediateFuture(receivedContentMap);
        }
    }

    private void receiveSessionInitiate(final JinglePacket jinglePacket) {
        if (isInitiator()) {
            Log.d(Config.LOGTAG, String.format("%s: received session-initiate even though we were initiating", id.account.getJid().asBareJid()));
            if (isTerminated()) {
                Log.d(Config.LOGTAG, String.format(
                        "%s: got a reason to terminate with out-of-order. but already in state %s",
                        id.account.getJid().asBareJid(),
                        getState()
                ));
                respondWithOutOfOrder(jinglePacket);
            } else {
                terminateWithOutOfOrder(jinglePacket);
            }
            return;
        }
        final ListenableFuture<RtpContentMap> future = receiveRtpContentMap(jinglePacket, false);
        Futures.addCallback(future, new FutureCallback<RtpContentMap>() {
            @Override
            public void onSuccess(@Nullable RtpContentMap rtpContentMap) {
                receiveSessionInitiate(jinglePacket, rtpContentMap);
            }

            @Override
            public void onFailure(@NonNull final Throwable throwable) {
                respondOk(jinglePacket);
                sendSessionTerminate(Reason.ofThrowable(throwable), throwable.getMessage());
            }
        }, MoreExecutors.directExecutor());
    }

    private void receiveSessionInitiate(final JinglePacket jinglePacket, final RtpContentMap contentMap) {
        try {
            contentMap.requireContentDescriptions();
            contentMap.requireDTLSFingerprint();
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": improperly formatted contents", Throwables.getRootCause(e));
            respondOk(jinglePacket);
            sendSessionTerminate(Reason.of(e), e.getMessage());
            return;
        }
        Log.d(Config.LOGTAG, "processing session-init with " + contentMap.contents.size() + " contents");
        final State target;
        if (this.state == State.PROCEED) {
            Preconditions.checkState(
                    proposedMedia != null && proposedMedia.size() > 0,
                    "proposed media must be set when processing pre-approved session-initiate"
            );
            if (!this.proposedMedia.equals(contentMap.getMedia())) {
                sendSessionTerminate(Reason.SECURITY_ERROR, String.format(
                        "Your session proposal (Jingle Message Initiation) included media %s but your session-initiate was %s",
                        this.proposedMedia,
                        contentMap.getMedia()
                ));
                return;
            }
            target = State.SESSION_INITIALIZED_PRE_APPROVED;
        } else {
            target = State.SESSION_INITIALIZED;
        }
        if (transition(target, () -> this.initiatorRtpContentMap = contentMap)) {
            respondOk(jinglePacket);

            final Set<Map.Entry<String, RtpContentMap.DescriptionTransport>> candidates = contentMap.contents.entrySet();
            if (candidates.size() > 0) {
                pendingIceCandidates.push(candidates);
            }
            if (target == State.SESSION_INITIALIZED_PRE_APPROVED) {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": automatically accepting session-initiate");
                sendSessionAccept();
            } else {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received not pre-approved session-initiate. start ringing");
                startRinging();
            }
        } else {
            Log.d(Config.LOGTAG, String.format("%s: received session-initiate while in state %s", id.account.getJid().asBareJid(), state));
            terminateWithOutOfOrder(jinglePacket);
        }
    }

    private void receiveSessionAccept(final JinglePacket jinglePacket) {
        if (!isInitiator()) {
            Log.d(Config.LOGTAG, String.format("%s: received session-accept even though we were responding", id.account.getJid().asBareJid()));
            terminateWithOutOfOrder(jinglePacket);
            return;
        }
        final ListenableFuture<RtpContentMap> future = receiveRtpContentMap(jinglePacket, this.omemoVerification.hasFingerprint());
        Futures.addCallback(future, new FutureCallback<RtpContentMap>() {
            @Override
            public void onSuccess(@Nullable RtpContentMap rtpContentMap) {
                receiveSessionAccept(jinglePacket, rtpContentMap);
            }

            @Override
            public void onFailure(@NonNull final Throwable throwable) {
                respondOk(jinglePacket);
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": improperly formatted contents in session-accept", throwable);
                webRTCWrapper.close();
                sendSessionTerminate(Reason.ofThrowable(throwable), throwable.getMessage());
            }
        }, MoreExecutors.directExecutor());
    }

    private void receiveSessionAccept(final JinglePacket jinglePacket, final RtpContentMap contentMap) {
        try {
            contentMap.requireContentDescriptions();
            contentMap.requireDTLSFingerprint();
        } catch (final RuntimeException e) {
            respondOk(jinglePacket);
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": improperly formatted contents in session-accept", e);
            webRTCWrapper.close();
            sendSessionTerminate(Reason.of(e), e.getMessage());
            return;
        }
        final Set<Media> initiatorMedia = this.initiatorRtpContentMap.getMedia();
        if (!initiatorMedia.equals(contentMap.getMedia())) {
            sendSessionTerminate(Reason.SECURITY_ERROR, String.format(
                    "Your session-included included media %s but our session-initiate was %s",
                    this.proposedMedia,
                    contentMap.getMedia()
            ));
            return;
        }
        Log.d(Config.LOGTAG, "processing session-accept with " + contentMap.contents.size() + " contents");
        if (transition(State.SESSION_ACCEPTED)) {
            respondOk(jinglePacket);
            receiveSessionAccept(contentMap);
        } else {
            Log.d(Config.LOGTAG, String.format("%s: received session-accept while in state %s", id.account.getJid().asBareJid(), state));
            respondOk(jinglePacket);
        }
    }

    private void receiveSessionAccept(final RtpContentMap contentMap) {
        this.responderRtpContentMap = contentMap;
        final SessionDescription sessionDescription;
        try {
            sessionDescription = SessionDescription.of(contentMap);
        } catch (final IllegalArgumentException | NullPointerException e) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable convert offer from session-accept to SDP", e);
            webRTCWrapper.close();
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.getMessage());
            return;
        }
        final org.webrtc.SessionDescription answer = new org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.ANSWER,
                sessionDescription.toString()
        );
        try {
            this.webRTCWrapper.setRemoteDescription(answer).get();
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable to set remote description after receiving session-accept", Throwables.getRootCause(e));
            webRTCWrapper.close();
            sendSessionTerminate(Reason.FAILED_APPLICATION);
            return;
        }
        final List<String> identificationTags = contentMap.group == null ? contentMap.getNames() : contentMap.group.getIdentificationTags();
        processCandidates(identificationTags, contentMap.contents.entrySet());
    }

    private void sendSessionAccept() {
        final RtpContentMap rtpContentMap = this.initiatorRtpContentMap;
        if (rtpContentMap == null) {
            throw new IllegalStateException("initiator RTP Content Map has not been set");
        }
        final SessionDescription offer;
        try {
            offer = SessionDescription.of(rtpContentMap);
        } catch (final IllegalArgumentException | NullPointerException e) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable convert offer from session-initiate to SDP", e);
            webRTCWrapper.close();
            sendSessionTerminate(Reason.FAILED_APPLICATION, e.getMessage());
            return;
        }
        sendSessionAccept(rtpContentMap.getMedia(), offer);
    }

    private void sendSessionAccept(final Set<Media> media, final SessionDescription offer) {
        discoverIceServers(iceServers -> sendSessionAccept(media, offer, iceServers));
    }

    private synchronized void sendSessionAccept(final Set<Media> media, final SessionDescription offer, final List<PeerConnection.IceServer> iceServers) {
        if (isTerminated()) {
            Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": ICE servers got discovered when session was already terminated. nothing to do.");
            return;
        }
        try {
            setupWebRTC(media, iceServers);
        } catch (final WebRTCWrapper.InitializationException e) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable to initialize WebRTC");
            webRTCWrapper.close();
            sendSessionTerminate(Reason.FAILED_APPLICATION);
            return;
        }
        final org.webrtc.SessionDescription sdp = new org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.OFFER,
                offer.toString()
        );
        try {
            this.webRTCWrapper.setRemoteDescription(sdp).get();
            addIceCandidatesFromBlackLog();
            org.webrtc.SessionDescription webRTCSessionDescription = this.webRTCWrapper.setLocalDescription().get();
            prepareSessionAccept(webRTCSessionDescription);
        } catch (final Exception e) {
            //TODO sending the error text is worthwhile as well. Especially for FailureToSet exceptions
            failureToAcceptSession(e);
        }
    }

    private void failureToAcceptSession(final Throwable throwable) {
        if (isTerminated()) {
            return;
        }
        Log.d(Config.LOGTAG, "unable to send session accept", Throwables.getRootCause(throwable));
        webRTCWrapper.close();
        sendSessionTerminate(Reason.ofThrowable(throwable));
    }

    private void addIceCandidatesFromBlackLog() {
        while (!this.pendingIceCandidates.isEmpty()) {
            processCandidates(this.pendingIceCandidates.poll());
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": added candidates from back log");
        }
    }

    private void prepareSessionAccept(final org.webrtc.SessionDescription webRTCSessionDescription) {
        final SessionDescription sessionDescription = SessionDescription.parse(webRTCSessionDescription.description);
        final RtpContentMap respondingRtpContentMap = RtpContentMap.of(sessionDescription);
        this.responderRtpContentMap = respondingRtpContentMap;
        webRTCWrapper.setIsReadyToReceiveIceCandidates(true);
        final ListenableFuture<RtpContentMap> outgoingContentMapFuture = prepareOutgoingContentMap(respondingRtpContentMap);
        Futures.addCallback(outgoingContentMapFuture,
                new FutureCallback<RtpContentMap>() {
                    @Override
                    public void onSuccess(final RtpContentMap outgoingContentMap) {
                        sendSessionAccept(outgoingContentMap);
                    }

                    @Override
                    public void onFailure(@NonNull Throwable throwable) {
                        failureToAcceptSession(throwable);
                    }
                },
                MoreExecutors.directExecutor()
        );
    }

    private void sendSessionAccept(final RtpContentMap rtpContentMap) {
        if (isTerminated()) {
            Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": preparing session accept was too slow. already terminated. nothing to do.");
            return;
        }
        transitionOrThrow(State.SESSION_ACCEPTED);
        final JinglePacket sessionAccept = rtpContentMap.toJinglePacket(JinglePacket.Action.SESSION_ACCEPT, id.sessionId);
        send(sessionAccept);
    }

    private ListenableFuture<RtpContentMap> prepareOutgoingContentMap(final RtpContentMap rtpContentMap) {
        if (this.omemoVerification.hasDeviceId()) {
            ListenableFuture<AxolotlService.OmemoVerifiedPayload<OmemoVerifiedRtpContentMap>> verifiedPayloadFuture = id.account.getAxolotlService()
                    .encrypt(rtpContentMap, id.with, omemoVerification.getDeviceId());
            return Futures.transform(verifiedPayloadFuture, verifiedPayload -> {
                omemoVerification.setOrEnsureEqual(verifiedPayload);
                return verifiedPayload.getPayload();
            }, MoreExecutors.directExecutor());
        } else {
            return Futures.immediateFuture(rtpContentMap);
        }
    }

    synchronized void deliveryMessage(final Jid from, final Element message, final String serverMessageId, final long timestamp) {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": delivered message to JingleRtpConnection " + message);
        switch (message.getName()) {
            case "propose":
                receivePropose(from, Propose.upgrade(message), serverMessageId, timestamp);
                break;
            case "proceed":
                receiveProceed(from, Proceed.upgrade(message), serverMessageId, timestamp);
                break;
            case "retract":
                receiveRetract(from, serverMessageId, timestamp);
                break;
            case "reject":
                receiveReject(from, serverMessageId, timestamp);
                break;
            case "accept":
                receiveAccept(from, serverMessageId, timestamp);
                break;
            default:
                break;
        }
    }

    void deliverFailedProceed() {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": receive message error for proceed message");
        if (transition(State.TERMINATED_CONNECTIVITY_ERROR)) {
            webRTCWrapper.close();
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": transitioned into connectivity error");
            this.finish();
        }
    }

    private void receiveAccept(final Jid from, final String serverMsgId, final long timestamp) {
        final boolean originatedFromMyself = from.asBareJid().equals(id.account.getJid().asBareJid());
        if (originatedFromMyself) {
            if (transition(State.ACCEPTED)) {
                if (serverMsgId != null) {
                    this.message.setServerMsgId(serverMsgId);
                }
                this.message.setTime(timestamp);
                this.message.setCarbon(true); //indicate that call was accepted on other device
                this.writeLogMessageSuccess(0);
                this.xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
                this.finish();
            } else {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable to transition to accept because already in state=" + this.state);
            }
        } else {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": ignoring 'accept' from " + from);
        }
    }

    private void receiveReject(final Jid from, final String serverMsgId, final long timestamp) {
        final boolean originatedFromMyself = from.asBareJid().equals(id.account.getJid().asBareJid());
        //reject from another one of my clients
        if (originatedFromMyself) {
            receiveRejectFromMyself(serverMsgId, timestamp);
        } else if (isInitiator()) {
            if (from.equals(id.with)) {
                receiveRejectFromResponder();
            } else {
                Log.d(Config.LOGTAG, id.account.getJid() + ": ignoring reject from " + from + " for session with " + id.with);
            }
        } else {
            Log.d(Config.LOGTAG, id.account.getJid() + ": ignoring reject from " + from + " for session with " + id.with);
        }
    }

    private void receiveRejectFromMyself(String serverMsgId, long timestamp) {
        if (transition(State.REJECTED)) {
            this.xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
            this.finish();
            if (serverMsgId != null) {
                this.message.setServerMsgId(serverMsgId);
            }
            this.message.setTime(timestamp);
            this.message.setCarbon(true); //indicate that call was rejected on other device
            writeLogMessageMissed();
        } else {
            Log.d(Config.LOGTAG, "not able to transition into REJECTED because already in " + this.state);
        }
    }

    private void receiveRejectFromResponder() {
        if (isInState(State.PROCEED)) {
            Log.d(Config.LOGTAG, id.account.getJid() + ": received reject while still in proceed. callee reconsidered");
            closeTransitionLogFinish(State.REJECTED_RACED);
            return;
        }
        if (isInState(State.SESSION_INITIALIZED_PRE_APPROVED)) {
            Log.d(Config.LOGTAG, id.account.getJid() + ": received reject while in SESSION_INITIATED_PRE_APPROVED. callee reconsidered before receiving session-init");
            closeTransitionLogFinish(State.TERMINATED_DECLINED_OR_BUSY);
            return;
        }
        Log.d(Config.LOGTAG, id.account.getJid() + ": ignoring reject from responder because already in state " + this.state);
    }

    private void receivePropose(final Jid from, final Propose propose, final String serverMsgId, final long timestamp) {
        final boolean originatedFromMyself = from.asBareJid().equals(id.account.getJid().asBareJid());
        if (originatedFromMyself) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": saw proposal from myself. ignoring");
        } else if (transition(State.PROPOSED, () -> {
            final Collection<RtpDescription> descriptions = Collections2.transform(
                    Collections2.filter(propose.getDescriptions(), d -> d instanceof RtpDescription),
                    input -> (RtpDescription) input
            );
            final Collection<Media> media = Collections2.transform(descriptions, RtpDescription::getMedia);
            Preconditions.checkState(!media.contains(Media.UNKNOWN), "RTP descriptions contain unknown media");
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received session proposal from " + from + " for " + media);
            this.proposedMedia = Sets.newHashSet(media);
        })) {
            if (serverMsgId != null) {
                this.message.setServerMsgId(serverMsgId);
            }
            this.message.setTime(timestamp);
            startRinging();
        } else {
            Log.d(Config.LOGTAG, id.account.getJid() + ": ignoring session proposal because already in " + state);
        }
    }

    private void startRinging() {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received call from " + id.with + ". start ringing");
        ringingTimeoutFuture = jingleConnectionManager.schedule(this::ringingTimeout, BUSY_TIME_OUT, TimeUnit.SECONDS);
        xmppConnectionService.getNotificationService().startRinging(id, getMedia());
    }

    private synchronized void ringingTimeout() {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": timeout reached for ringing");
        switch (this.state) {
            case PROPOSED:
                message.markUnread();
                rejectCallFromProposed();
                break;
            case SESSION_INITIALIZED:
                message.markUnread();
                rejectCallFromSessionInitiate();
                break;
        }
    }

    private void cancelRingingTimeout() {
        final ScheduledFuture<?> future = this.ringingTimeoutFuture;
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }

    private void receiveProceed(final Jid from, final Proceed proceed, final String serverMsgId, final long timestamp) {
        final Set<Media> media = Preconditions.checkNotNull(this.proposedMedia, "Proposed media has to be set before handling proceed");
        Preconditions.checkState(media.size() > 0, "Proposed media should not be empty");
        if (from.equals(id.with)) {
            if (isInitiator()) {
                if (transition(State.PROCEED)) {
                    if (serverMsgId != null) {
                        this.message.setServerMsgId(serverMsgId);
                    }
                    this.message.setTime(timestamp);
                    final Integer remoteDeviceId = proceed.getDeviceId();
                    if (isOmemoEnabled()) {
                        this.omemoVerification.setDeviceId(remoteDeviceId);
                    } else {
                        if (remoteDeviceId != null) {
                            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": remote party signaled support for OMEMO verification but we have OMEMO disabled");
                        }
                        this.omemoVerification.setDeviceId(null);
                    }
                    this.sendSessionInitiate(media, State.SESSION_INITIALIZED_PRE_APPROVED);
                } else {
                    Log.d(Config.LOGTAG, String.format("%s: ignoring proceed because already in %s", id.account.getJid().asBareJid(), this.state));
                }
            } else {
                Log.d(Config.LOGTAG, String.format("%s: ignoring proceed because we were not initializing", id.account.getJid().asBareJid()));
            }
        } else if (from.asBareJid().equals(id.account.getJid().asBareJid())) {
            if (transition(State.ACCEPTED)) {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": moved session with " + id.with + " into state accepted after received carbon copied procced");
                this.xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
                this.finish();
            }
        } else {
            Log.d(Config.LOGTAG, String.format("%s: ignoring proceed from %s. was expected from %s", id.account.getJid().asBareJid(), from, id.with));
        }
    }

    private void receiveRetract(final Jid from, final String serverMsgId, final long timestamp) {
        if (from.equals(id.with)) {
            final State target = this.state == State.PROCEED ? State.RETRACTED_RACED : State.RETRACTED;
            if (transition(target)) {
                xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": session with " + id.with + " has been retracted (serverMsgId=" + serverMsgId + ")");
                if (serverMsgId != null) {
                    this.message.setServerMsgId(serverMsgId);
                }
                this.message.setTime(timestamp);
                if (target == State.RETRACTED) {
                    this.message.markUnread();
                }
                writeLogMessageMissed();
                finish();
            } else {
                Log.d(Config.LOGTAG, "ignoring retract because already in " + this.state);
            }
        } else {
            //TODO parse retract from self
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received retract from " + from + ". expected retract from" + id.with + ". ignoring");
        }
    }

    public void sendSessionInitiate() {
        sendSessionInitiate(this.proposedMedia, State.SESSION_INITIALIZED);
    }

    private void sendSessionInitiate(final Set<Media> media, final State targetState) {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": prepare session-initiate");
        discoverIceServers(iceServers -> sendSessionInitiate(media, targetState, iceServers));
    }

    private synchronized void sendSessionInitiate(final Set<Media> media, final State targetState, final List<PeerConnection.IceServer> iceServers) {
        if (isTerminated()) {
            Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": ICE servers got discovered when session was already terminated. nothing to do.");
            return;
        }
        try {
            setupWebRTC(media, iceServers);
        } catch (final WebRTCWrapper.InitializationException e) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable to initialize WebRTC");
            webRTCWrapper.close();
            sendRetract(Reason.ofThrowable(e));
            return;
        }
        try {
            org.webrtc.SessionDescription webRTCSessionDescription = this.webRTCWrapper.setLocalDescription().get();
            prepareSessionInitiate(webRTCSessionDescription, targetState);
        } catch (final Exception e) {
            //TODO sending the error text is worthwhile as well. Especially for FailureToSet exceptions
            failureToInitiateSession(e, targetState);
        }
    }

    private void failureToInitiateSession(final Throwable throwable, final State targetState) {
        if (isTerminated()) {
            return;
        }
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable to sendSessionInitiate", Throwables.getRootCause(throwable));
        webRTCWrapper.close();
        final Reason reason = Reason.ofThrowable(throwable);
        if (isInState(targetState)) {
            sendSessionTerminate(reason);
        } else {
            sendRetract(reason);
        }
    }

    private void sendRetract(final Reason reason) {
        //TODO embed reason into retract
        sendJingleMessage("retract", id.with.asBareJid());
        transitionOrThrow(reasonToState(reason));
        this.finish();
    }

    private void prepareSessionInitiate(final org.webrtc.SessionDescription webRTCSessionDescription, final State targetState) {
        final SessionDescription sessionDescription = SessionDescription.parse(webRTCSessionDescription.description);
        final RtpContentMap rtpContentMap = RtpContentMap.of(sessionDescription);
        this.initiatorRtpContentMap = rtpContentMap;
        this.webRTCWrapper.setIsReadyToReceiveIceCandidates(true);
        final ListenableFuture<RtpContentMap> outgoingContentMapFuture = encryptSessionInitiate(rtpContentMap);
        Futures.addCallback(outgoingContentMapFuture, new FutureCallback<RtpContentMap>() {
            @Override
            public void onSuccess(final RtpContentMap outgoingContentMap) {
                sendSessionInitiate(outgoingContentMap, targetState);
            }

            @Override
            public void onFailure(@NonNull final Throwable throwable) {
                failureToInitiateSession(throwable, targetState);
            }
        }, MoreExecutors.directExecutor());
    }

    private void sendSessionInitiate(final RtpContentMap rtpContentMap, final State targetState) {
        if (isTerminated()) {
            Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": preparing session was too slow. already terminated. nothing to do.");
            return;
        }
        this.transitionOrThrow(targetState);
        final JinglePacket sessionInitiate = rtpContentMap.toJinglePacket(JinglePacket.Action.SESSION_INITIATE, id.sessionId);
        send(sessionInitiate);
    }

    private ListenableFuture<RtpContentMap> encryptSessionInitiate(final RtpContentMap rtpContentMap) {
        if (this.omemoVerification.hasDeviceId()) {
            final ListenableFuture<AxolotlService.OmemoVerifiedPayload<OmemoVerifiedRtpContentMap>> verifiedPayloadFuture = id.account.getAxolotlService()
                    .encrypt(rtpContentMap, id.with, omemoVerification.getDeviceId());
            final ListenableFuture<RtpContentMap> future = Futures.transform(verifiedPayloadFuture, verifiedPayload -> {
                omemoVerification.setSessionFingerprint(verifiedPayload.getFingerprint());
                return verifiedPayload.getPayload();
            }, MoreExecutors.directExecutor());
            if (Config.REQUIRE_RTP_VERIFICATION) {
                return future;
            }
            return Futures.catching(
                    future,
                    CryptoFailedException.class,
                    e -> {
                        Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable to use OMEMO DTLS verification on outgoing session initiate. falling back", e);
                        return rtpContentMap;
                    },
                    MoreExecutors.directExecutor()
            );
        } else {
            return Futures.immediateFuture(rtpContentMap);
        }
    }

    private void sendSessionTerminate(final Reason reason) {
        sendSessionTerminate(reason, null);
    }

    private void sendSessionTerminate(final Reason reason, final String text) {
        final State previous = this.state;
        final State target = reasonToState(reason);
        transitionOrThrow(target);
        if (previous != State.NULL) {
            writeLogMessage(target);
        }
        final JinglePacket jinglePacket = new JinglePacket(JinglePacket.Action.SESSION_TERMINATE, id.sessionId);
        jinglePacket.setReason(reason, text);
        Log.d(Config.LOGTAG, jinglePacket.toString());
        send(jinglePacket);
        finish();
    }

    private void sendTransportInfo(final String contentName, IceUdpTransportInfo.Candidate candidate) {
        final RtpContentMap transportInfo;
        try {
            final RtpContentMap rtpContentMap = isInitiator() ? this.initiatorRtpContentMap : this.responderRtpContentMap;
            transportInfo = rtpContentMap.transportInfo(contentName, candidate);
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": unable to prepare transport-info from candidate for content=" + contentName);
            return;
        }
        final JinglePacket jinglePacket = transportInfo.toJinglePacket(JinglePacket.Action.TRANSPORT_INFO, id.sessionId);
        send(jinglePacket);
    }

    private void send(final JinglePacket jinglePacket) {
        jinglePacket.setTo(id.with);
        xmppConnectionService.sendIqPacket(id.account, jinglePacket, this::handleIqResponse);
    }

    private synchronized void handleIqResponse(final Account account, final IqPacket response) {
        if (response.getType() == IqPacket.TYPE.ERROR) {
            final String errorCondition = response.getErrorCondition();
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received IQ-error from " + response.getFrom() + " in RTP session. " + errorCondition);
            if (isTerminated()) {
                Log.i(Config.LOGTAG, id.account.getJid().asBareJid() + ": ignoring error because session was already terminated");
                return;
            }
            this.webRTCWrapper.close();
            final State target;
            if (Arrays.asList(
                    "service-unavailable",
                    "recipient-unavailable",
                    "remote-server-not-found",
                    "remote-server-timeout"
            ).contains(errorCondition)) {
                target = State.TERMINATED_CONNECTIVITY_ERROR;
            } else {
                target = State.TERMINATED_APPLICATION_FAILURE;
            }
            transitionOrThrow(target);
            this.finish();
        } else if (response.getType() == IqPacket.TYPE.TIMEOUT) {
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": received IQ timeout in RTP session with " + id.with + ". terminating with connectivity error");
            if (isTerminated()) {
                Log.i(Config.LOGTAG, id.account.getJid().asBareJid() + ": ignoring error because session was already terminated");
                return;
            }
            this.webRTCWrapper.close();
            transitionOrThrow(State.TERMINATED_CONNECTIVITY_ERROR);
            this.finish();
        }
    }

    private void terminateWithOutOfOrder(final JinglePacket jinglePacket) {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": terminating session with out-of-order");
        this.webRTCWrapper.close();
        transitionOrThrow(State.TERMINATED_APPLICATION_FAILURE);
        respondWithOutOfOrder(jinglePacket);
        this.finish();
    }

    private void respondWithOutOfOrder(final JinglePacket jinglePacket) {
        jingleConnectionManager.respondWithJingleError(id.account, jinglePacket, "out-of-order", "unexpected-request", "wait");
    }

    private void respondOk(final JinglePacket jinglePacket) {
        xmppConnectionService.sendIqPacket(id.account, jinglePacket.generateResponse(IqPacket.TYPE.RESULT), null);
    }

    public void throwStateTransitionException() {
        final StateTransitionException exception = this.stateTransitionException;
        if (exception != null) {
            throw new IllegalStateException(String.format("Transition to %s did not call finish", exception.state), exception);
        }
    }

    public RtpEndUserState getEndUserState() {
        switch (this.state) {
            case NULL:
            case PROPOSED:
            case SESSION_INITIALIZED:
                if (isInitiator()) {
                    return RtpEndUserState.RINGING;
                } else {
                    return RtpEndUserState.INCOMING_CALL;
                }
            case PROCEED:
                if (isInitiator()) {
                    return RtpEndUserState.RINGING;
                } else {
                    return RtpEndUserState.ACCEPTING_CALL;
                }
            case SESSION_INITIALIZED_PRE_APPROVED:
                if (isInitiator()) {
                    return RtpEndUserState.RINGING;
                } else {
                    return RtpEndUserState.CONNECTING;
                }
            case SESSION_ACCEPTED:
                return getPeerConnectionStateAsEndUserState();
            case REJECTED:
            case REJECTED_RACED:
            case TERMINATED_DECLINED_OR_BUSY:
                if (isInitiator()) {
                    return RtpEndUserState.DECLINED_OR_BUSY;
                } else {
                    return RtpEndUserState.ENDED;
                }
            case TERMINATED_SUCCESS:
            case ACCEPTED:
            case RETRACTED:
            case TERMINATED_CANCEL_OR_TIMEOUT:
                return RtpEndUserState.ENDED;
            case RETRACTED_RACED:
                if (isInitiator()) {
                    return RtpEndUserState.ENDED;
                } else {
                    return RtpEndUserState.RETRACTED;
                }
            case TERMINATED_CONNECTIVITY_ERROR:
                return zeroDuration() ? RtpEndUserState.CONNECTIVITY_ERROR : RtpEndUserState.CONNECTIVITY_LOST_ERROR;
            case TERMINATED_APPLICATION_FAILURE:
                return RtpEndUserState.APPLICATION_ERROR;
            case TERMINATED_SECURITY_ERROR:
                return RtpEndUserState.SECURITY_ERROR;
        }
        throw new IllegalStateException(String.format("%s has no equivalent EndUserState", this.state));
    }


    private RtpEndUserState getPeerConnectionStateAsEndUserState() {
        final PeerConnection.PeerConnectionState state;
        try {
            state = webRTCWrapper.getState();
        } catch (final WebRTCWrapper.PeerConnectionNotInitialized e) {
            //We usually close the WebRTCWrapper *before* transitioning so we might still
            //be in SESSION_ACCEPTED even though the peerConnection has been torn down
            return RtpEndUserState.ENDING_CALL;
        }
        switch (state) {
            case CONNECTED:
                return RtpEndUserState.CONNECTED;
            case NEW:
            case CONNECTING:
                return RtpEndUserState.CONNECTING;
            case CLOSED:
                return RtpEndUserState.ENDING_CALL;
            default:
                return zeroDuration() ? RtpEndUserState.CONNECTIVITY_ERROR : RtpEndUserState.RECONNECTING;
        }
    }

    public Set<Media> getMedia() {
        final State current = getState();
        if (current == State.NULL) {
            if (isInitiator()) {
                return Preconditions.checkNotNull(
                        this.proposedMedia,
                        "RTP connection has not been initialized properly"
                );
            }
            throw new IllegalStateException("RTP connection has not been initialized yet");
        }
        if (Arrays.asList(State.PROPOSED, State.PROCEED).contains(current)) {
            return Preconditions.checkNotNull(
                    this.proposedMedia,
                    "RTP connection has not been initialized properly"
            );
        }
        final RtpContentMap initiatorContentMap = initiatorRtpContentMap;
        if (initiatorContentMap != null) {
            return initiatorContentMap.getMedia();
        } else if (isTerminated()) {
            return Collections.emptySet(); //we might fail before we ever got a chance to set media
        } else {
            return Preconditions.checkNotNull(this.proposedMedia, "RTP connection has not been initialized properly");
        }
    }


    public boolean isVerified() {
        final String fingerprint = this.omemoVerification.getFingerprint();
        if (fingerprint == null) {
            return false;
        }
        final FingerprintStatus status = id.account.getAxolotlService().getFingerprintTrust(fingerprint);
        return status != null && status.isVerified();
    }

    public synchronized void acceptCall() {
        switch (this.state) {
            case PROPOSED:
                cancelRingingTimeout();
                acceptCallFromProposed();
                break;
            case SESSION_INITIALIZED:
                cancelRingingTimeout();
                acceptCallFromSessionInitialized();
                break;
            case ACCEPTED:
                Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": the call has already been accepted  with another client. UI was just lagging behind");
                break;
            case PROCEED:
            case SESSION_ACCEPTED:
                Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": the call has already been accepted. user probably double tapped the UI");
                break;
            default:
                throw new IllegalStateException("Can not accept call from " + this.state);
        }
    }


    public void notifyPhoneCall() {
        Log.d(Config.LOGTAG, "a phone call has just been started. killing jingle rtp connections");
        if (Arrays.asList(State.PROPOSED, State.SESSION_INITIALIZED).contains(this.state)) {
            rejectCall();
        } else {
            endCall();
        }
    }

    public synchronized void rejectCall() {
        if (isTerminated()) {
            Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": received rejectCall() when session has already been terminated. nothing to do");
            return;
        }
        switch (this.state) {
            case PROPOSED:
                rejectCallFromProposed();
                break;
            case SESSION_INITIALIZED:
                rejectCallFromSessionInitiate();
                break;
            default:
                throw new IllegalStateException("Can not reject call from " + this.state);
        }
    }

    public synchronized void endCall() {
        if (isTerminated()) {
            Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": received endCall() when session has already been terminated. nothing to do");
            return;
        }
        if (isInState(State.PROPOSED) && !isInitiator()) {
            rejectCallFromProposed();
            return;
        }
        if (isInState(State.PROCEED)) {
            if (isInitiator()) {
                retractFromProceed();
            } else {
                rejectCallFromProceed();
            }
            return;
        }
        if (isInitiator() && isInState(State.SESSION_INITIALIZED, State.SESSION_INITIALIZED_PRE_APPROVED)) {
            this.webRTCWrapper.close();
            sendSessionTerminate(Reason.CANCEL);
            return;
        }
        if (isInState(State.SESSION_INITIALIZED)) {
            rejectCallFromSessionInitiate();
            return;
        }
        if (isInState(State.SESSION_INITIALIZED_PRE_APPROVED, State.SESSION_ACCEPTED)) {
            this.webRTCWrapper.close();
            sendSessionTerminate(Reason.SUCCESS);
            return;
        }
        if (isInState(State.TERMINATED_APPLICATION_FAILURE, State.TERMINATED_CONNECTIVITY_ERROR, State.TERMINATED_DECLINED_OR_BUSY)) {
            Log.d(Config.LOGTAG, "ignoring request to end call because already in state " + this.state);
            return;
        }
        throw new IllegalStateException("called 'endCall' while in state " + this.state + ". isInitiator=" + isInitiator());
    }

    private void retractFromProceed() {
        Log.d(Config.LOGTAG, "retract from proceed");
        this.sendJingleMessage("retract");
        closeTransitionLogFinish(State.RETRACTED_RACED);
    }

    private void closeTransitionLogFinish(final State state) {
        this.webRTCWrapper.close();
        transitionOrThrow(state);
        writeLogMessage(state);
        finish();
    }

    private void setupWebRTC(final Set<Media> media, final List<PeerConnection.IceServer> iceServers) throws WebRTCWrapper.InitializationException {
        this.jingleConnectionManager.ensureConnectionIsRegistered(this);
        final AppRTCAudioManager.SpeakerPhonePreference speakerPhonePreference;
        if (media.contains(Media.VIDEO)) {
            speakerPhonePreference = AppRTCAudioManager.SpeakerPhonePreference.SPEAKER;
        } else {
            speakerPhonePreference = AppRTCAudioManager.SpeakerPhonePreference.EARPIECE;
        }
        this.webRTCWrapper.setup(this.xmppConnectionService, speakerPhonePreference);
        this.webRTCWrapper.initializePeerConnection(media, iceServers);
    }

    private void acceptCallFromProposed() {
        transitionOrThrow(State.PROCEED);
        xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
        this.sendJingleMessage("accept", id.account.getJid().asBareJid());
        this.sendJingleMessage("proceed");
    }

    private void rejectCallFromProposed() {
        transitionOrThrow(State.REJECTED);
        writeLogMessageMissed();
        xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
        this.sendJingleMessage("reject");
        finish();
    }

    private void rejectCallFromProceed() {
        this.sendJingleMessage("reject");
        closeTransitionLogFinish(State.REJECTED_RACED);
    }

    private void rejectCallFromSessionInitiate() {
        webRTCWrapper.close();
        sendSessionTerminate(Reason.DECLINE);
        xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
    }

    private void sendJingleMessage(final String action) {
        sendJingleMessage(action, id.with);
    }

    private void sendJingleMessage(final String action, final Jid to) {
        final MessagePacket messagePacket = new MessagePacket();
        messagePacket.setType(MessagePacket.TYPE_CHAT); //we want to carbon copy those
        messagePacket.setTo(to);
        final Element intent = messagePacket.addChild(action, Namespace.JINGLE_MESSAGE).setAttribute("id", id.sessionId);
        if ("proceed".equals(action)) {
            messagePacket.setId(JINGLE_MESSAGE_PROCEED_ID_PREFIX + id.sessionId);
            if (isOmemoEnabled()) {
                final int deviceId = id.account.getAxolotlService().getOwnDeviceId();
                final Element device = intent.addChild("device", Namespace.OMEMO_DTLS_SRTP_VERIFICATION);
                device.setAttribute("id", deviceId);
            }
        }
        messagePacket.addChild("store", "urn:xmpp:hints");
        xmppConnectionService.sendMessagePacket(id.account, messagePacket);
    }

    private boolean isOmemoEnabled() {
        final Conversational conversational = message.getConversation();
        if (conversational instanceof Conversation) {
            return ((Conversation) conversational).getNextEncryption() == Message.ENCRYPTION_AXOLOTL;
        }
        return false;
    }

    private void acceptCallFromSessionInitialized() {
        xmppConnectionService.getNotificationService().cancelIncomingCallNotification();
        sendSessionAccept();
    }

    private synchronized boolean isInState(State... state) {
        return Arrays.asList(state).contains(this.state);
    }

    private boolean transition(final State target) {
        return transition(target, null);
    }

    private synchronized boolean transition(final State target, final Runnable runnable) {
        final Collection<State> validTransitions = VALID_TRANSITIONS.get(this.state);
        if (validTransitions != null && validTransitions.contains(target)) {
            this.state = target;
            this.stateTransitionException = new StateTransitionException(target);
            if (runnable != null) {
                runnable.run();
            }
            Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": transitioned into " + target);
            updateEndUserState();
            updateOngoingCallNotification();
            return true;
        } else {
            return false;
        }
    }

    void transitionOrThrow(final State target) {
        if (!transition(target)) {
            throw new IllegalStateException(String.format("Unable to transition from %s to %s", this.state, target));
        }
    }

    @Override
    public void onIceCandidate(final IceCandidate iceCandidate) {
        final IceUdpTransportInfo.Candidate candidate = IceUdpTransportInfo.Candidate.fromSdpAttribute(iceCandidate.sdp);
        Log.d(Config.LOGTAG, "sending candidate: " + iceCandidate.toString());
        sendTransportInfo(iceCandidate.sdpMid, candidate);
    }

    @Override
    public void onConnectionChange(final PeerConnection.PeerConnectionState newState) {
        Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": PeerConnectionState changed to " + newState);
        this.stateHistory.add(newState);
        if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
            this.sessionDuration.start();
        } else if (this.sessionDuration.isRunning()) {
            this.sessionDuration.stop();
        }

        final boolean neverConnected = !this.stateHistory.contains(PeerConnection.PeerConnectionState.CONNECTED);
        final boolean failedOrDisconnected = Arrays.asList(
                PeerConnection.PeerConnectionState.FAILED,
                PeerConnection.PeerConnectionState.DISCONNECTED
        ).contains(newState);


        if (neverConnected && failedOrDisconnected) {
            if (isTerminated()) {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": not sending session-terminate after connectivity error because session is already in state " + this.state);
                return;
            }
            webRTCWrapper.execute(this::closeWebRTCSessionAfterFailedConnection);
        } else if (newState == PeerConnection.PeerConnectionState.FAILED) {
            Log.d(Config.LOGTAG, "attempting to restart ICE");
            webRTCWrapper.restartIce();
        }
        updateEndUserState();
    }

    @Override
    public void onRenegotiationNeeded() {
        Log.d(Config.LOGTAG, "onRenegotiationNeeded()");
        this.webRTCWrapper.execute(this::renegotiate);
    }

    private void renegotiate() {
        this.webRTCWrapper.setIsReadyToReceiveIceCandidates(false);
        try {
            final SessionDescription sessionDescription = setLocalSessionDescription();
            final RtpContentMap rtpContentMap = RtpContentMap.of(sessionDescription);
            setRenegotiatedContentMap(rtpContentMap);
            this.webRTCWrapper.setIsReadyToReceiveIceCandidates(true);
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "failed to renegotiate", e);
            //TODO send some sort of failure (comparable to when initiating)
        }
    }

    private void setRenegotiatedContentMap(final RtpContentMap rtpContentMap) {
        if (isInitiator()) {
            this.initiatorRtpContentMap = rtpContentMap;
        } else {
            this.responderRtpContentMap = rtpContentMap;
        }
    }

    private SessionDescription setLocalSessionDescription() throws ExecutionException, InterruptedException {
        final org.webrtc.SessionDescription sessionDescription = this.webRTCWrapper.setLocalDescription().get();
        return SessionDescription.parse(sessionDescription.description);
    }

    private void closeWebRTCSessionAfterFailedConnection() {
        this.webRTCWrapper.close();
        synchronized (this) {
            if (isTerminated()) {
                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": no need to send session-terminate after failed connection. Other party already did");
                return;
            }
            sendSessionTerminate(Reason.CONNECTIVITY_ERROR);
        }
    }

    public boolean zeroDuration() {
        return this.sessionDuration.elapsed(TimeUnit.NANOSECONDS) <= 0;
    }

    public long getCallDuration() {
        return this.sessionDuration.elapsed(TimeUnit.MILLISECONDS);
    }

    public AppRTCAudioManager getAudioManager() {
        return webRTCWrapper.getAudioManager();
    }

    public boolean isMicrophoneEnabled() {
        return webRTCWrapper.isMicrophoneEnabled();
    }

    public boolean setMicrophoneEnabled(final boolean enabled) {
        return webRTCWrapper.setMicrophoneEnabled(enabled);
    }

    public boolean isVideoEnabled() {
        return webRTCWrapper.isVideoEnabled();
    }

    public void setVideoEnabled(final boolean enabled) {
        webRTCWrapper.setVideoEnabled(enabled);
    }

    public boolean isCameraSwitchable() {
        return webRTCWrapper.isCameraSwitchable();
    }

    public boolean isFrontCamera() {
        return webRTCWrapper.isFrontCamera();
    }

    public ListenableFuture<Boolean> switchCamera() {
        return webRTCWrapper.switchCamera();
    }

    @Override
    public void onAudioDeviceChanged(AppRTCAudioManager.AudioDevice selectedAudioDevice, Set<AppRTCAudioManager.AudioDevice> availableAudioDevices) {
        xmppConnectionService.notifyJingleRtpConnectionUpdate(selectedAudioDevice, availableAudioDevices);
    }

    private void updateEndUserState() {
        final RtpEndUserState endUserState = getEndUserState();
        jingleConnectionManager.toneManager.transition(isInitiator(), endUserState, getMedia());
        xmppConnectionService.notifyJingleRtpConnectionUpdate(id.account, id.with, id.sessionId, endUserState);
    }

    private void updateOngoingCallNotification() {
        if (STATES_SHOWING_ONGOING_CALL.contains(this.state)) {
            xmppConnectionService.setOngoingCall(id, getMedia());
        } else {
            xmppConnectionService.removeOngoingCall();
        }
    }

    private void discoverIceServers(final OnIceServersDiscovered onIceServersDiscovered) {
        if (id.account.getXmppConnection().getFeatures().externalServiceDiscovery()) {
            final IqPacket request = new IqPacket(IqPacket.TYPE.GET);
            request.setTo(id.account.getDomain());
            request.addChild("services", Namespace.EXTERNAL_SERVICE_DISCOVERY);
            xmppConnectionService.sendIqPacket(id.account, request, (account, response) -> {
                ImmutableList.Builder<PeerConnection.IceServer> listBuilder = new ImmutableList.Builder<>();
                if (response.getType() == IqPacket.TYPE.RESULT) {
                    final Element services = response.findChild("services", Namespace.EXTERNAL_SERVICE_DISCOVERY);
                    final List<Element> children = services == null ? Collections.emptyList() : services.getChildren();
                    for (final Element child : children) {
                        if ("service".equals(child.getName())) {
                            final String type = child.getAttribute("type");
                            final String host = child.getAttribute("host");
                            final String sport = child.getAttribute("port");
                            final Integer port = sport == null ? null : Ints.tryParse(sport);
                            final String transport = child.getAttribute("transport");
                            final String username = child.getAttribute("username");
                            final String password = child.getAttribute("password");
                            if (Strings.isNullOrEmpty(host) || port == null) {
                                continue;
                            }
                            if (port < 0 || port > 65535) {
                                continue;
                            }
                            if (Arrays.asList("stun", "stuns", "turn", "turns").contains(type) && Arrays.asList("udp", "tcp").contains(transport)) {
                                if (Arrays.asList("stuns", "turns").contains(type) && "udp".equals(transport)) {
                                    Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": skipping invalid combination of udp/tls in external services");
                                    continue;
                                }
                                final PeerConnection.IceServer.Builder iceServerBuilder = PeerConnection.IceServer
                                        .builder(String.format("%s:%s:%s?transport=%s", type, IP.wrapIPv6(host), port, transport));
                                iceServerBuilder.setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK);
                                if (username != null && password != null) {
                                    iceServerBuilder.setUsername(username);
                                    iceServerBuilder.setPassword(password);
                                } else if (Arrays.asList("turn", "turns").contains(type)) {
                                    //The WebRTC spec requires throwing an InvalidAccessError when username (from libwebrtc source coder)
                                    //https://chromium.googlesource.com/external/webrtc/+/master/pc/ice_server_parsing.cc
                                    Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": skipping " + type + "/" + transport + " without username and password");
                                    continue;
                                }
                                final PeerConnection.IceServer iceServer = iceServerBuilder.createIceServer();
                                Log.d(Config.LOGTAG, id.account.getJid().asBareJid() + ": discovered ICE Server: " + iceServer);
                                listBuilder.add(iceServer);
                            }
                        }
                    }
                }
                final List<PeerConnection.IceServer> iceServers = listBuilder.build();
                if (iceServers.size() == 0) {
                    Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": no ICE server found " + response);
                }
                onIceServersDiscovered.onIceServersDiscovered(iceServers);
            });
        } else {
            Log.w(Config.LOGTAG, id.account.getJid().asBareJid() + ": has no external service discovery");
            onIceServersDiscovered.onIceServersDiscovered(Collections.emptyList());
        }
    }

    private void finish() {
        if (isTerminated()) {
            this.cancelRingingTimeout();
            this.webRTCWrapper.verifyClosed();
            this.jingleConnectionManager.setTerminalSessionState(id, getEndUserState(), getMedia());
            this.jingleConnectionManager.finishConnectionOrThrow(this);
        } else {
            throw new IllegalStateException(String.format("Unable to call finish from %s", this.state));
        }
    }

    private void writeLogMessage(final State state) {
        final long duration = getCallDuration();
        if (state == State.TERMINATED_SUCCESS || (state == State.TERMINATED_CONNECTIVITY_ERROR && duration > 0)) {
            writeLogMessageSuccess(duration);
        } else {
            writeLogMessageMissed();
        }
    }

    private void writeLogMessageSuccess(final long duration) {
        this.message.setBody(new RtpSessionStatus(true, duration).toString());
        this.writeMessage();
    }

    private void writeLogMessageMissed() {
        this.message.setBody(new RtpSessionStatus(false, 0).toString());
        this.writeMessage();
    }

    private void writeMessage() {
        final Conversational conversational = message.getConversation();
        if (conversational instanceof Conversation) {
            ((Conversation) conversational).add(this.message);
            xmppConnectionService.createMessageAsync(message);
            xmppConnectionService.updateConversationUi();
        } else {
            throw new IllegalStateException("Somehow the conversation in a message was a stub");
        }
    }

    public State getState() {
        return this.state;
    }

    boolean isTerminated() {
        return TERMINATED.contains(this.state);
    }

    public Optional<VideoTrack> getLocalVideoTrack() {
        return webRTCWrapper.getLocalVideoTrack();
    }

    public Optional<VideoTrack> getRemoteVideoTrack() {
        return webRTCWrapper.getRemoteVideoTrack();
    }


    public EglBase.Context getEglBaseContext() {
        return webRTCWrapper.getEglBaseContext();
    }

    void setProposedMedia(final Set<Media> media) {
        this.proposedMedia = media;
    }

    public void fireStateUpdate() {
        final RtpEndUserState endUserState = getEndUserState();
        xmppConnectionService.notifyJingleRtpConnectionUpdate(id.account, id.with, id.sessionId, endUserState);
    }

    private interface OnIceServersDiscovered {
        void onIceServersDiscovered(List<PeerConnection.IceServer> iceServers);
    }

    private static class StateTransitionException extends Exception {
        private final State state;

        private StateTransitionException(final State state) {
            this.state = state;
        }
    }
}
