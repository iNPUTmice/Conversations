package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
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
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.voiceengine.WebRtcAudioEffects;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.AppRTCAudioManager;
import eu.siacs.conversations.services.XmppConnectionService;

public class WebRTCWrapper {

    private static final String EXTENDED_LOGGING_TAG = WebRTCWrapper.class.getSimpleName();

    //we should probably keep this in sync with: https://github.com/signalapp/Signal-Android/blob/master/app/src/main/java/org/thoughtcrime/securesms/ApplicationContext.java#L296
    private static final Set<String> HARDWARE_AEC_BLACKLIST = new ImmutableSet.Builder<String>()
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
            .build();

    private static final int CAPTURING_RESOLUTION = 1920;
    private static final int CAPTURING_MAX_FRAME_RATE = 30;

    private final EventCallback eventCallback;
    private final AppRTCAudioManager.AudioManagerEvents audioManagerEvents = new AppRTCAudioManager.AudioManagerEvents() {
        @Override
        public void onAudioDeviceChanged(AppRTCAudioManager.AudioDevice selectedAudioDevice, Set<AppRTCAudioManager.AudioDevice> availableAudioDevices) {
            eventCallback.onAudioDeviceChanged(selectedAudioDevice, availableAudioDevices);
        }
    };
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private VideoTrack localVideoTrack = null;
    private VideoTrack remoteVideoTrack = null;
    private final PeerConnection.Observer peerConnectionObserver = new PeerConnection.Observer() {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(EXTENDED_LOGGING_TAG, "onSignalingChange(" + signalingState + ")");
            //this is called after removeTrack or addTrack
            //and should then trigger a content-add or content-remove or something
            //https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/removeTrack
        }

        @Override
        public void onConnectionChange(final PeerConnection.PeerConnectionState newState) {
            eventCallback.onConnectionChange(newState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(EXTENDED_LOGGING_TAG, "onIceConnectionChange(" + iceConnectionState + ")");
        }

        @Override
        public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {
            Log.d(Config.LOGTAG, "remote candidate selected: " + event.remote);
            Log.d(Config.LOGTAG, "local candidate selected: " + event.local);
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(EXTENDED_LOGGING_TAG, "onIceGatheringChange(" + iceGatheringState + ")");
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            eventCallback.onIceCandidate(iceCandidate);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(EXTENDED_LOGGING_TAG, "onAddStream(numAudioTracks=" + mediaStream.audioTracks.size() + ",numVideoTracks=" + mediaStream.videoTracks.size() + ")");
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {

        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(EXTENDED_LOGGING_TAG, "onRenegotiationNeeded()");
            final PeerConnection.PeerConnectionState currentState = peerConnection == null ? null : peerConnection.connectionState();
            if (currentState != null && currentState != PeerConnection.PeerConnectionState.NEW) {
                eventCallback.onRenegotiationNeeded();
            }
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            final MediaStreamTrack track = rtpReceiver.track();
            Log.d(EXTENDED_LOGGING_TAG, "onAddTrack(kind=" + (track == null ? "null" : track.kind()) + ",numMediaStreams=" + mediaStreams.length + ")");
            if (track instanceof VideoTrack) {
                remoteVideoTrack = (VideoTrack) track;
            }
        }

        @Override
        public void onTrack(RtpTransceiver transceiver) {
            Log.d(EXTENDED_LOGGING_TAG, "onTrack(mid=" + transceiver.getMid() + ",media=" + transceiver.getMediaType() + ")");
        }
    };
    @Nullable
    private PeerConnection peerConnection = null;
    private AudioTrack localAudioTrack = null;
    private AppRTCAudioManager appRTCAudioManager = null;
    private ToneManager toneManager = null;
    private Context context = null;
    private EglBase eglBase = null;
    private CapturerChoice capturerChoice;

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

    @Nullable
    private static CapturerChoice of(CameraEnumerator enumerator, final String deviceName, Set<String> availableCameras) {
        final CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
        if (capturer == null) {
            return null;
        }
        final ArrayList<CameraEnumerationAndroid.CaptureFormat> choices = new ArrayList<>(enumerator.getSupportedFormats(deviceName));
        Collections.sort(choices, (a, b) -> b.width - a.width);
        for (final CameraEnumerationAndroid.CaptureFormat captureFormat : choices) {
            if (captureFormat.width <= CAPTURING_RESOLUTION) {
                return new CapturerChoice(capturer, captureFormat, availableCameras);
            }
        }
        return null;
    }

    private static boolean isFrontFacing(final CameraEnumerator cameraEnumerator, final String deviceName) {
        try {
            return cameraEnumerator.isFrontFacing(deviceName);
        } catch (final NullPointerException e) {
            return false;
        }
    }

    public void setup(final XmppConnectionService service, final AppRTCAudioManager.SpeakerPhonePreference speakerPhonePreference) throws InitializationException {
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(service).createInitializationOptions()
            );
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
        mainHandler.post(() -> {
            appRTCAudioManager = AppRTCAudioManager.create(service, speakerPhonePreference);
            toneManager.setAppRtcAudioManagerHasControl(true);
            appRTCAudioManager.start(audioManagerEvents);
            eventCallback.onAudioDeviceChanged(appRTCAudioManager.getSelectedAudioDevice(), appRTCAudioManager.getAudioDevices());
        });
    }

    synchronized void initializePeerConnection(final Set<Media> media, final List<PeerConnection.IceServer> iceServers) throws InitializationException {
        Preconditions.checkState(this.eglBase != null);
        Preconditions.checkNotNull(media);
        Preconditions.checkArgument(media.size() > 0, "media can not be empty when initializing peer connection");
        final boolean setUseHardwareAcousticEchoCanceler = WebRtcAudioEffects.canUseAcousticEchoCanceler() && !HARDWARE_AEC_BLACKLIST.contains(Build.MODEL);
        Log.d(Config.LOGTAG, String.format("setUseHardwareAcousticEchoCanceler(%s) model=%s", setUseHardwareAcousticEchoCanceler, Build.MODEL));
        PeerConnectionFactory peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setAudioDeviceModule(JavaAudioDeviceModule.builder(context)
                        .setUseHardwareAcousticEchoCanceler(setUseHardwareAcousticEchoCanceler)
                        .createAudioDeviceModule()
                )
                .createPeerConnectionFactory();


        final PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED; //XEP-0176 doesn't support tcp
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.NEGOTIATE;
        final PeerConnection peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver);
        if (peerConnection == null) {
            throw new InitializationException("Unable to create PeerConnection");
        }

        final Optional<CapturerChoice> optionalCapturerChoice = media.contains(Media.VIDEO) ? getVideoCapturer() : Optional.absent();

        if (optionalCapturerChoice.isPresent()) {
            this.capturerChoice = optionalCapturerChoice.get();
            final CameraVideoCapturer capturer = this.capturerChoice.cameraVideoCapturer;
            final VideoSource videoSource = peerConnectionFactory.createVideoSource(false);
            SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("webrtc", eglBase.getEglBaseContext());
            capturer.initialize(surfaceTextureHelper, requireContext(), videoSource.getCapturerObserver());
            Log.d(Config.LOGTAG, String.format("start capturing at %dx%d@%d", capturerChoice.captureFormat.width, capturerChoice.captureFormat.height, capturerChoice.getFrameRate()));
            capturer.startCapture(capturerChoice.captureFormat.width, capturerChoice.captureFormat.height, capturerChoice.getFrameRate());

            this.localVideoTrack = peerConnectionFactory.createVideoTrack("my-video-track", videoSource);

            peerConnection.addTrack(this.localVideoTrack);
        }


        if (media.contains(Media.AUDIO)) {
            //set up audio track
            final AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
            this.localAudioTrack = peerConnectionFactory.createAudioTrack("my-audio-track", audioSource);
            peerConnection.addTrack(this.localAudioTrack);
        }
        peerConnection.setAudioPlayout(true);
        peerConnection.setAudioRecording(true);
        this.peerConnection = peerConnection;
    }

    void restartIce() {
        requirePeerConnection().restartIce();
    }

    synchronized void close() {
        final PeerConnection peerConnection = this.peerConnection;
        final CapturerChoice capturerChoice = this.capturerChoice;
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
        if (capturerChoice != null) {
            try {
                capturerChoice.cameraVideoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(Config.LOGTAG, "unable to stop capturing");
            }
        }
        if (eglBase != null) {
            eglBase.release();
            this.eglBase = null;
        }
    }

    synchronized void verifyClosed() {
        if (this.peerConnection != null
                || this.eglBase != null
                || this.localVideoTrack != null
                || this.remoteVideoTrack != null) {
            final IllegalStateException e = new IllegalStateException("WebRTCWrapper hasn't been closed properly");
            Log.e(Config.LOGTAG, "verifyClosed() failed. Going to throw", e);
            throw e;
        }
    }

    boolean isCameraSwitchable() {
        final CapturerChoice capturerChoice = this.capturerChoice;
        return capturerChoice != null && capturerChoice.availableCameras.size() > 1;
    }

    boolean isFrontCamera() {
        final CapturerChoice capturerChoice = this.capturerChoice;
        return capturerChoice == null || capturerChoice.isFrontCamera;
    }

    ListenableFuture<Boolean> switchCamera() {
        final CapturerChoice capturerChoice = this.capturerChoice;
        if (capturerChoice == null) {
            return Futures.immediateFailedFuture(new IllegalStateException("CameraCapturer has not been initialized"));
        }
        final SettableFuture<Boolean> future = SettableFuture.create();
        capturerChoice.cameraVideoCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
            @Override
            public void onCameraSwitchDone(boolean isFrontCamera) {
                capturerChoice.isFrontCamera = isFrontCamera;
                future.set(isFrontCamera);
            }

            @Override
            public void onCameraSwitchError(final String message) {
                future.setException(new IllegalStateException(String.format("Unable to switch camera %s", message)));
            }
        });
        return future;
    }

    boolean isMicrophoneEnabled() {
        final AudioTrack audioTrack = this.localAudioTrack;
        if (audioTrack == null) {
            throw new IllegalStateException("Local audio track does not exist (yet)");
        }
        try {
            return audioTrack.enabled();
        } catch (final IllegalStateException e) {
            //sometimes UI might still be rendering the buttons when a background thread has already ended the call
            return false;
        }
    }

    boolean setMicrophoneEnabled(final boolean enabled) {
        final AudioTrack audioTrack = this.localAudioTrack;
        if (audioTrack == null) {
            throw new IllegalStateException("Local audio track does not exist (yet)");
        }
        try {
            audioTrack.setEnabled(enabled);
            return true;
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle microphone", e);
            //ignoring race condition in case MediaStreamTrack has been disposed
            return false;
        }
    }

    boolean isVideoEnabled() {
        final VideoTrack videoTrack = this.localVideoTrack;
        if (videoTrack == null) {
            return false;
        }
        return videoTrack.enabled();
    }

    void setVideoEnabled(final boolean enabled) {
        final VideoTrack videoTrack = this.localVideoTrack;
        if (videoTrack == null) {
            throw new IllegalStateException("Local video track does not exist");
        }
        videoTrack.setEnabled(enabled);
    }

    ListenableFuture<SessionDescription> setLocalDescription() {
        return Futures.transformAsync(getPeerConnectionFuture(), peerConnection -> {
            final SettableFuture<SessionDescription> future = SettableFuture.create();
            peerConnection.setLocalDescription(new SetSdpObserver() {
                @Override
                public void onSetSuccess() {
                    final SessionDescription description = peerConnection.getLocalDescription();
                    Log.d(EXTENDED_LOGGING_TAG, "set local description:");
                    logDescription(description);
                    future.set(description);
                }

                @Override
                public void onSetFailure(final String message) {
                    future.setException(new FailureToSetDescriptionException(message));
                }
            });
            return future;
        }, MoreExecutors.directExecutor());
    }

    private static void logDescription(final SessionDescription sessionDescription) {
        for (final String line : sessionDescription.description.split(eu.siacs.conversations.xmpp.jingle.SessionDescription.LINE_DIVIDER)) {
            Log.d(EXTENDED_LOGGING_TAG, line);
        }
    }

    ListenableFuture<Void> setRemoteDescription(final SessionDescription sessionDescription) {
        Log.d(EXTENDED_LOGGING_TAG, "setting remote description:");
        logDescription(sessionDescription);
        return Futures.transformAsync(getPeerConnectionFuture(), peerConnection -> {
            final SettableFuture<Void> future = SettableFuture.create();
            peerConnection.setRemoteDescription(new SetSdpObserver() {
                @Override
                public void onSetSuccess() {
                    future.set(null);
                }

                @Override
                public void onSetFailure(final String message) {
                    future.setException(new FailureToSetDescriptionException(message));
                }
            }, sessionDescription);
            return future;
        }, MoreExecutors.directExecutor());
    }

    @Nonnull
    private ListenableFuture<PeerConnection> getPeerConnectionFuture() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            return Futures.immediateFailedFuture(new IllegalStateException("initialize PeerConnection first"));
        } else {
            return Futures.immediateFuture(peerConnection);
        }
    }

    void addIceCandidate(IceCandidate iceCandidate) {
        requirePeerConnection().addIceCandidate(iceCandidate);
    }

    private Optional<CapturerChoice> getVideoCapturer() {
        final CameraEnumerator enumerator = new Camera2Enumerator(requireContext());
        final Set<String> deviceNames = ImmutableSet.copyOf(enumerator.getDeviceNames());
        for (final String deviceName : deviceNames) {
            if (isFrontFacing(enumerator, deviceName)) {
                final CapturerChoice capturerChoice = of(enumerator, deviceName, deviceNames);
                if (capturerChoice == null) {
                    return Optional.absent();
                }
                capturerChoice.isFrontCamera = true;
                return Optional.of(capturerChoice);
            }
        }
        if (deviceNames.size() == 0) {
            return Optional.absent();
        } else {
            return Optional.fromNullable(of(enumerator, Iterables.get(deviceNames, 0), deviceNames));
        }
    }

    public PeerConnection.PeerConnectionState getState() {
        return requirePeerConnection().connectionState();
    }

    EglBase.Context getEglBaseContext() {
        return this.eglBase.getEglBaseContext();
    }

    Optional<VideoTrack> getLocalVideoTrack() {
        return Optional.fromNullable(this.localVideoTrack);
    }

    Optional<VideoTrack> getRemoteVideoTrack() {
        return Optional.fromNullable(this.remoteVideoTrack);
    }

    private PeerConnection requirePeerConnection() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            throw new PeerConnectionNotInitialized();
        }
        return peerConnection;
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

    public interface EventCallback {
        void onIceCandidate(IceCandidate iceCandidate);

        void onConnectionChange(PeerConnection.PeerConnectionState newState);

        void onAudioDeviceChanged(AppRTCAudioManager.AudioDevice selectedAudioDevice, Set<AppRTCAudioManager.AudioDevice> availableAudioDevices);

        void onRenegotiationNeeded();
    }

    private static abstract class SetSdpObserver implements SdpObserver {

        @Override
        public void onCreateSuccess(org.webrtc.SessionDescription sessionDescription) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }

        @Override
        public void onCreateFailure(String s) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }

    }

    private static abstract class CreateSdpObserver implements SdpObserver {


        @Override
        public void onSetSuccess() {
            throw new IllegalStateException("Not able to use CreateSdpObserver");
        }


        @Override
        public void onSetFailure(String s) {
            throw new IllegalStateException("Not able to use CreateSdpObserver");
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

    private static class CapturerChoice {
        private final CameraVideoCapturer cameraVideoCapturer;
        private final CameraEnumerationAndroid.CaptureFormat captureFormat;
        private final Set<String> availableCameras;
        private boolean isFrontCamera = false;

        CapturerChoice(CameraVideoCapturer cameraVideoCapturer, CameraEnumerationAndroid.CaptureFormat captureFormat, Set<String> cameras) {
            this.cameraVideoCapturer = cameraVideoCapturer;
            this.captureFormat = captureFormat;
            this.availableCameras = cameras;
        }

        int getFrameRate() {
            return Math.max(captureFormat.framerate.min, Math.min(CAPTURING_MAX_FRAME_RATE, captureFormat.framerate.max));
        }
    }
}
