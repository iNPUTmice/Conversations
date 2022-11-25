package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CandidatePairChangeEvent;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.voiceengine.WebRtcAudioEffects;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.AppRTCAudioManager;
import eu.siacs.conversations.services.XmppConnectionService;

@SuppressWarnings("UnstableApiUsage")
public class WebRTCWrapper {

    private static final String EXTENDED_LOGGING_TAG = WebRTCWrapper.class.getSimpleName();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private static final Set<String> HARDWARE_AEC_BLACKLIST =
            new ImmutableSet.Builder<String>()
                    .add("Pixel")
                    .add("Pixel XL")
                    .add("Moto G5")
                    .add("Moto G (5S) Plus")
                    .add("Moto G4")
                    .add("TA-1053")
                    .add("Mi A1")
                    .add("Mi A2")
                    .add("E5823") // Sony z5 compact
                    .add("Redmi Note 5")
                    .add("FP2") // Fairphone FP2
                    .add("MI 5")
                    .add("GT-I9515") // Samsung Galaxy S4 Value Edition (jfvelte)
                    .add("GT-I9515L") // Samsung Galaxy S4 Value Edition (jfvelte)
                    .add("GT-I9505") // Samsung Galaxy S4 (jfltexx)
                    .build();

    private final EventCallback eventCallback;
    private final AtomicBoolean readyToReceivedIceCandidates = new AtomicBoolean(false);
    private final Queue<IceCandidate> iceCandidates = new LinkedList<>();
    private final AppRTCAudioManager.AudioManagerEvents audioManagerEvents =
            new AppRTCAudioManager.AudioManagerEvents() {
                @Override
                public void onAudioDeviceChanged(
                        AppRTCAudioManager.AudioDevice selectedAudioDevice,
                        Set<AppRTCAudioManager.AudioDevice> availableAudioDevices) {
                    eventCallback.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices);
                }
            };
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TrackWrapper<AudioTrack> localAudioTrack = null;
    private TrackWrapper<VideoTrack> localVideoTrack = null;
    private VideoTrack remoteVideoTrack = null;
    private final PeerConnection.Observer peerConnectionObserver =
            new PeerConnection.Observer() {
                @Override
                public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                    Log.d(EXTENDED_LOGGING_TAG, "onSignalingChange(" + signalingState + ")");
                    // this is called after removeTrack or addTrack
                    // and should then trigger a content-add or content-remove or something
                    // https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/removeTrack
                }

                @Override
                public void onConnectionChange(final PeerConnection.PeerConnectionState newState) {
                    eventCallback.onConnectionChange(newState);
                }

                @Override
                public void onIceConnectionChange(
                        PeerConnection.IceConnectionState iceConnectionState) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onIceConnectionChange(" + iceConnectionState + ")");
                }

                @Override
                public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {
                    Log.d(Config.LOGTAG, "remote candidate selected: " + event.remote);
                    Log.d(Config.LOGTAG, "local candidate selected: " + event.local);
                }

                @Override
                public void onIceConnectionReceivingChange(boolean b) {}

                @Override
                public void onIceGatheringChange(
                        PeerConnection.IceGatheringState iceGatheringState) {
                    Log.d(EXTENDED_LOGGING_TAG, "onIceGatheringChange(" + iceGatheringState + ")");
                }

                @Override
                public void onIceCandidate(IceCandidate iceCandidate) {
                    if (readyToReceivedIceCandidates.get()) {
                        eventCallback.onIceCandidate(iceCandidate);
                    } else {
                        iceCandidates.add(iceCandidate);
                    }
                }

                @Override
                public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

                @Override
                public void onAddStream(MediaStream mediaStream) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onAddStream(numAudioTracks="
                                    + mediaStream.audioTracks.size()
                                    + ",numVideoTracks="
                                    + mediaStream.videoTracks.size()
                                    + ")");
                }

                @Override
                public void onRemoveStream(MediaStream mediaStream) {}

                @Override
                public void onDataChannel(DataChannel dataChannel) {}

                @Override
                public void onRenegotiationNeeded() {
                    Log.d(EXTENDED_LOGGING_TAG, "onRenegotiationNeeded()");
                    final PeerConnection.PeerConnectionState currentState =
                            peerConnection == null ? null : peerConnection.connectionState();
                    if (currentState != null
                            && currentState != PeerConnection.PeerConnectionState.NEW) {
                        eventCallback.onRenegotiationNeeded();
                    }
                }

                @Override
                public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                    final MediaStreamTrack track = rtpReceiver.track();
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onAddTrack(kind="
                                    + (track == null ? "null" : track.kind())
                                    + ",numMediaStreams="
                                    + mediaStreams.length
                                    + ")");
                    if (track instanceof VideoTrack) {
                        remoteVideoTrack = (VideoTrack) track;
                        eventCallback.onTrackModification();
                    }
                }

                @Override
                public void onTrack(final RtpTransceiver transceiver) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onTrack(mid="
                                    + transceiver.getMid()
                                    + ",media="
                                    + transceiver.getMediaType()
                                    + ",direction="
                                    + transceiver.getDirection()
                                    + ")");
                }

                @Override
                public void onRemoveTrack(final RtpReceiver receiver) {
                    Log.d(EXTENDED_LOGGING_TAG, "onRemoveTrack(" + receiver.id() + ")");
                }
            };
    @Nullable private PeerConnectionFactory peerConnectionFactory = null;
    @Nullable private PeerConnection peerConnection = null;
    private AppRTCAudioManager appRTCAudioManager = null;
    private ToneManager toneManager = null;
    private Context context = null;
    private EglBase eglBase = null;
    private VideoSourceWrapper videoSourceWrapper;

    WebRTCWrapper(final EventCallback eventCallback) {
        this.eventCallback = eventCallback;
    }

    private static void dispose(final PeerConnection peerConnection) {
        try {
            peerConnection.dispose();
        } catch (final IllegalStateException e) {
            Log.e(Config.LOGTAG, "unable to dispose of peer connection", e);
        }
    }

    public void setup(
            final XmppConnectionService service,
            final AppRTCAudioManager.SpeakerPhonePreference speakerPhonePreference)
            throws InitializationException {
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(service)
                            .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                            .createInitializationOptions());
        } catch (final UnsatisfiedLinkError e) {
            throw new InitializationException("Unable to initialize PeerConnectionFactory", e);
        }
        try {
            this.eglBase = EglBase.create();
        } catch (final RuntimeException e) {
            throw new InitializationException("Unable to create EGL base", e);
        }
        this.context = service;
        this.toneManager = service.getJingleConnectionManager().toneManager;
        mainHandler.post(
                () -> {
                    appRTCAudioManager = AppRTCAudioManager.create(service, speakerPhonePreference);
                    toneManager.setAppRtcAudioManagerHasControl(true);
                    appRTCAudioManager.start(audioManagerEvents);
                    eventCallback.onAudioDeviceChanged(
                            appRTCAudioManager.getSelectedAudioDevice(),
                            appRTCAudioManager.getAudioDevices());
                });
    }

    synchronized void initializePeerConnection(
            final Set<Media> media, final List<PeerConnection.IceServer> iceServers)
            throws InitializationException {
        Preconditions.checkState(this.eglBase != null);
        Preconditions.checkNotNull(media);
        Preconditions.checkArgument(
                media.size() > 0, "media can not be empty when initializing peer connection");
        final boolean setUseHardwareAcousticEchoCanceler =
                WebRtcAudioEffects.canUseAcousticEchoCanceler()
                        && !HARDWARE_AEC_BLACKLIST.contains(Build.MODEL);
        Log.d(
                Config.LOGTAG,
                String.format(
                        "setUseHardwareAcousticEchoCanceler(%s) model=%s",
                        setUseHardwareAcousticEchoCanceler, Build.MODEL));
        this.peerConnectionFactory =
                PeerConnectionFactory.builder()
                        .setVideoDecoderFactory(
                                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                        .setVideoEncoderFactory(
                                new DefaultVideoEncoderFactory(
                                        eglBase.getEglBaseContext(), true, true))
                        .setAudioDeviceModule(
                                JavaAudioDeviceModule.builder(requireContext())
                                        .setUseHardwareAcousticEchoCanceler(
                                                setUseHardwareAcousticEchoCanceler)
                                        .createAudioDeviceModule())
                        .createPeerConnectionFactory();

        final PeerConnection.RTCConfiguration rtcConfig = buildConfiguration(iceServers);
        final PeerConnection peerConnection =
                requirePeerConnectionFactory()
                        .createPeerConnection(rtcConfig, peerConnectionObserver);
        if (peerConnection == null) {
            throw new InitializationException("Unable to create PeerConnection");
        }

        if (media.contains(Media.VIDEO)) {
            addVideoTrack(peerConnection);
        }

        if (media.contains(Media.AUDIO)) {
            addAudioTrack(peerConnection);
        }
        peerConnection.setAudioPlayout(true);
        peerConnection.setAudioRecording(true);

        this.peerConnection = peerConnection;
    }

    private VideoSourceWrapper initializeVideoSourceWrapper() {
        final VideoSourceWrapper existingVideoSourceWrapper = this.videoSourceWrapper;
        if (existingVideoSourceWrapper != null) {
            existingVideoSourceWrapper.startCapture();
            return existingVideoSourceWrapper;
        }
        final VideoSourceWrapper videoSourceWrapper =
                new VideoSourceWrapper.Factory(requireContext()).create();
        if (videoSourceWrapper == null) {
            throw new IllegalStateException("Could not instantiate VideoSourceWrapper");
        }
        videoSourceWrapper.initialize(
                requirePeerConnectionFactory(), requireContext(), eglBase.getEglBaseContext());
        videoSourceWrapper.startCapture();
        this.videoSourceWrapper = videoSourceWrapper;
        return videoSourceWrapper;
    }

    public synchronized boolean addTrack(final Media media) {
        if (media == Media.VIDEO) {
            return addVideoTrack(requirePeerConnection());
        } else if (media == Media.AUDIO) {
            return addAudioTrack(requirePeerConnection());
        }
        throw new IllegalStateException(String.format("Could not add track for %s", media));
    }

    public synchronized void removeTrack(final Media media) {
        if (media == Media.VIDEO) {
            removeVideoTrack(requirePeerConnection());
        }
    }

    private boolean addAudioTrack(final PeerConnection peerConnection) {
        final AudioSource audioSource =
                requirePeerConnectionFactory().createAudioSource(new MediaConstraints());
        final AudioTrack audioTrack =
                requirePeerConnectionFactory().createAudioTrack("my-audio-track", audioSource);
        this.localAudioTrack = TrackWrapper.addTrack(peerConnection, audioTrack);
        return true;
    }

    private boolean addVideoTrack(final PeerConnection peerConnection) {
        Preconditions.checkState(
                this.localVideoTrack == null, "A local video track already exists");
        final VideoSourceWrapper videoSourceWrapper;
        try {
            videoSourceWrapper = initializeVideoSourceWrapper();
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "could not add video track", e);
            return false;
        }
        final VideoTrack videoTrack =
                requirePeerConnectionFactory()
                        .createVideoTrack("my-video-track", videoSourceWrapper.getVideoSource());
        // TODO do we want to create Transceiver manually and be able to set direction and keep a
        // reference to it for later removal
        this.localVideoTrack = TrackWrapper.addTrack(peerConnection, videoTrack);
        this.eventCallback.onTrackModification();
        return true;
    }

    private void removeVideoTrack(final PeerConnection peerConnection) {
        final TrackWrapper<VideoTrack> localVideoTrack = this.localVideoTrack;
        if (localVideoTrack != null) {
            final boolean success = peerConnection.removeTrack(localVideoTrack.rtpSender);
            Log.d(Config.LOGTAG, "removeVideoTrack. success=" + success);
            for (final RtpTransceiver transceiver : peerConnection.getTransceivers()) {
                if (transceiver.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                    final RtpTransceiver.RtpTransceiverDirection direction =
                            transceiver.getDirection();
                    transceiver.stop();
                    Log.d(Config.LOGTAG, "stopped video transceiver for direction " + direction);
                }
            }
        }
        this.localVideoTrack = null;
        this.eventCallback.onTrackModification();
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        if (videoSourceWrapper != null) {
            try {
                videoSourceWrapper.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private static PeerConnection.RTCConfiguration buildConfiguration(
            final List<PeerConnection.IceServer> iceServers) {
        final PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.tcpCandidatePolicy =
                PeerConnection.TcpCandidatePolicy.DISABLED; // XEP-0176 doesn't support tcp
        rtcConfig.continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.NEGOTIATE;
        rtcConfig.enableImplicitRollback = true;
        return rtcConfig;
    }

    void reconfigurePeerConnection(final List<PeerConnection.IceServer> iceServers) {
        requirePeerConnection().setConfiguration(buildConfiguration(iceServers));
    }

    void restartIce() {
        executorService.execute(() -> requirePeerConnection().restartIce());
    }

    public void setIsReadyToReceiveIceCandidates(final boolean ready) {
        readyToReceivedIceCandidates.set(ready);
        while (ready && iceCandidates.peek() != null) {
            eventCallback.onIceCandidate(iceCandidates.poll());
        }
    }

    synchronized void close() {
        final PeerConnection peerConnection = this.peerConnection;
        final PeerConnectionFactory peerConnectionFactory = this.peerConnectionFactory;
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        final AppRTCAudioManager audioManager = this.appRTCAudioManager;
        final EglBase eglBase = this.eglBase;
        if (peerConnection != null) {
            dispose(peerConnection);
            this.peerConnection = null;
        }
        if (audioManager != null) {
            toneManager.setAppRtcAudioManagerHasControl(false);
            mainHandler.post(audioManager::stop);
        }
        this.localVideoTrack = null;
        this.remoteVideoTrack = null;
        if (videoSourceWrapper != null) {
            try {
                videoSourceWrapper.stopCapture();
            } catch (final InterruptedException e) {
                Log.e(Config.LOGTAG, "unable to stop capturing");
            }
            videoSourceWrapper.dispose();
        }
        if (eglBase != null) {
            eglBase.release();
            this.eglBase = null;
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
        }
    }

    synchronized void verifyClosed() {
        if (this.peerConnection != null
                || this.eglBase != null
                || this.localVideoTrack != null
                || this.remoteVideoTrack != null) {
            final IllegalStateException e =
                    new IllegalStateException("WebRTCWrapper hasn't been closed properly");
            Log.e(Config.LOGTAG, "verifyClosed() failed. Going to throw", e);
            throw e;
        }
    }

    boolean isCameraSwitchable() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        return videoSourceWrapper != null && videoSourceWrapper.isCameraSwitchable();
    }

    boolean isFrontCamera() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        return videoSourceWrapper == null || videoSourceWrapper.isFrontCamera();
    }

    ListenableFuture<Boolean> switchCamera() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        if (videoSourceWrapper == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("VideoSourceWrapper has not been initialized"));
        }
        return videoSourceWrapper.switchCamera();
    }

    boolean isMicrophoneEnabled() {
        final Optional<AudioTrack> audioTrack = TrackWrapper.get(this.localAudioTrack);
        if (audioTrack.isPresent()) {
            try {
                return audioTrack.get().enabled();
            } catch (final IllegalStateException e) {
                // sometimes UI might still be rendering the buttons when a background thread has
                // already ended the call
                return false;
            }
        } else {
            throw new IllegalStateException("Local audio track does not exist (yet)");
        }
    }

    boolean setMicrophoneEnabled(final boolean enabled) {
        final Optional<AudioTrack> audioTrack = TrackWrapper.get(this.localAudioTrack);
        if (audioTrack.isPresent()) {
            try {
                audioTrack.get().setEnabled(enabled);
                return true;
            } catch (final IllegalStateException e) {
                Log.d(Config.LOGTAG, "unable to toggle microphone", e);
                // ignoring race condition in case MediaStreamTrack has been disposed
                return false;
            }
        } else {
            throw new IllegalStateException("Local audio track does not exist (yet)");
        }
    }

    boolean isVideoEnabled() {
        final Optional<VideoTrack> videoTrack = TrackWrapper.get(this.localVideoTrack);
        if (videoTrack.isPresent()) {
            return videoTrack.get().enabled();
        }
        return false;
    }

    void setVideoEnabled(final boolean enabled) {
        final Optional<VideoTrack> videoTrack = TrackWrapper.get(this.localVideoTrack);
        if (videoTrack.isPresent()) {
            videoTrack.get().setEnabled(enabled);
            return;
        }
        throw new IllegalStateException("Local video track does not exist");
    }

    synchronized ListenableFuture<SessionDescription> setLocalDescription() {
        return Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    final SettableFuture<SessionDescription> future = SettableFuture.create();
                    peerConnection.setLocalDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    final SessionDescription description =
                                            peerConnection.getLocalDescription();
                                    Log.d(EXTENDED_LOGGING_TAG, "set local description:");
                                    logDescription(description);
                                    future.set(description);
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            });
                    return future;
                },
                MoreExecutors.directExecutor());
    }

    synchronized ListenableFuture<SessionDescription> rollback() {
        return Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    final SettableFuture<SessionDescription> future = SettableFuture.create();
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    peerConnection.setLocalDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    final SessionDescription description =
                                            peerConnection.getLocalDescription();
                                    Log.d(EXTENDED_LOGGING_TAG, "rollback to local description:");
                                    logDescription(description);
                                    future.set(description);
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            },
                            new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
                    return future;
                },
                MoreExecutors.directExecutor());
    }

    private static void logDescription(final SessionDescription sessionDescription) {
        for (final String line :
                sessionDescription.description.split(
                        eu.siacs.conversations.xmpp.jingle.SessionDescription.LINE_DIVIDER)) {
            Log.d(EXTENDED_LOGGING_TAG, line);
        }
    }

    synchronized ListenableFuture<Void> setRemoteDescription(
            final SessionDescription sessionDescription) {
        Log.d(EXTENDED_LOGGING_TAG, "setting remote description:");
        logDescription(sessionDescription);
        return Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    final SettableFuture<Void> future = SettableFuture.create();
                    peerConnection.setRemoteDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    future.set(null);
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            },
                            sessionDescription);
                    return future;
                },
                MoreExecutors.directExecutor());
    }

    @Nonnull
    private ListenableFuture<PeerConnection> getPeerConnectionFuture() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            return Futures.immediateFailedFuture(new PeerConnectionNotInitialized());
        } else {
            return Futures.immediateFuture(peerConnection);
        }
    }

    @Nonnull
    private PeerConnection requirePeerConnection() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            throw new PeerConnectionNotInitialized();
        }
        return peerConnection;
    }

    @Nonnull
    private PeerConnectionFactory requirePeerConnectionFactory() {
        final PeerConnectionFactory peerConnectionFactory = this.peerConnectionFactory;
        if (peerConnectionFactory == null) {
            throw new IllegalStateException("Make sure PeerConnectionFactory is initialized");
        }
        return peerConnectionFactory;
    }

    void addIceCandidate(IceCandidate iceCandidate) {
        requirePeerConnection().addIceCandidate(iceCandidate);
    }

    PeerConnection.PeerConnectionState getState() {
        return requirePeerConnection().connectionState();
    }

    public PeerConnection.SignalingState getSignalingState() {
        return requirePeerConnection().signalingState();
    }

    EglBase.Context getEglBaseContext() {
        return this.eglBase.getEglBaseContext();
    }

    Optional<VideoTrack> getLocalVideoTrack() {
        return TrackWrapper.get(this.localVideoTrack);
    }

    Optional<VideoTrack> getRemoteVideoTrack() {
        return Optional.fromNullable(this.remoteVideoTrack);
    }

    private Context requireContext() {
        final Context context = this.context;
        if (context == null) {
            throw new IllegalStateException("call setup first");
        }
        return context;
    }

    AppRTCAudioManager getAudioManager() {
        return appRTCAudioManager;
    }

    void execute(final Runnable command) {
        executorService.execute(command);
    }

    public interface EventCallback {
        void onIceCandidate(IceCandidate iceCandidate);

        void onConnectionChange(PeerConnection.PeerConnectionState newState);

        void onAudioDeviceChanged(
                AppRTCAudioManager.AudioDevice selectedAudioDevice,
                Set<AppRTCAudioManager.AudioDevice> availableAudioDevices);

        void onRenegotiationNeeded();

        void onTrackModification();
    }

    private abstract static class SetSdpObserver implements SdpObserver {

        @Override
        public void onCreateSuccess(org.webrtc.SessionDescription sessionDescription) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }

        @Override
        public void onCreateFailure(String s) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }
    }

    static class InitializationException extends Exception {

        private InitializationException(final String message, final Throwable throwable) {
            super(message, throwable);
        }

        private InitializationException(final String message) {
            super(message);
        }
    }

    public static class PeerConnectionNotInitialized extends IllegalStateException {

        private PeerConnectionNotInitialized() {
            super("initialize PeerConnection first");
        }
    }

    private static class FailureToSetDescriptionException extends IllegalArgumentException {
        public FailureToSetDescriptionException(String message) {
            super(message);
        }
    }
}
