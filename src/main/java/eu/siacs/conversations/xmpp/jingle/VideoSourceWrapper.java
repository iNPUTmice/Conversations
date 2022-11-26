package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.util.Log;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

import javax.annotation.Nullable;

import eu.siacs.conversations.Config;

class VideoSourceWrapper {

    private static final int CAPTURING_RESOLUTION = 1920;
    private static final int CAPTURING_MAX_FRAME_RATE = 30;

    private final CameraVideoCapturer cameraVideoCapturer;
    private final CameraEnumerationAndroid.CaptureFormat captureFormat;
    private final Set<String> availableCameras;
    private boolean isFrontCamera = false;
    private VideoSource videoSource;

    VideoSourceWrapper(
            CameraVideoCapturer cameraVideoCapturer,
            CameraEnumerationAndroid.CaptureFormat captureFormat,
            Set<String> cameras) {
        this.cameraVideoCapturer = cameraVideoCapturer;
        this.captureFormat = captureFormat;
        this.availableCameras = cameras;
    }

    private int getFrameRate() {
        return Math.max(
                captureFormat.framerate.min,
                Math.min(CAPTURING_MAX_FRAME_RATE, captureFormat.framerate.max));
    }

    public void initialize(
            final PeerConnectionFactory peerConnectionFactory,
            final Context context,
            final EglBase.Context eglBaseContext) {
        final SurfaceTextureHelper surfaceTextureHelper =
                SurfaceTextureHelper.create("webrtc", eglBaseContext);
        this.videoSource = peerConnectionFactory.createVideoSource(false);
        this.cameraVideoCapturer.initialize(
                surfaceTextureHelper, context, this.videoSource.getCapturerObserver());
    }

    public VideoSource getVideoSource() {
        final VideoSource videoSource = this.videoSource;
        if (videoSource == null) {
            throw new IllegalStateException("VideoSourceWrapper was not initialized");
        }
        return videoSource;
    }

    public void startCapture() {
        final int frameRate = getFrameRate();
        Log.d(
                Config.LOGTAG,
                String.format(
                        "start capturing at %dx%d@%d",
                        captureFormat.width, captureFormat.height, frameRate));
        this.cameraVideoCapturer.startCapture(captureFormat.width, captureFormat.height, frameRate);
    }

    public void stopCapture() throws InterruptedException {
        this.cameraVideoCapturer.stopCapture();
    }

    public void dispose() {
        this.cameraVideoCapturer.dispose();
        if (this.videoSource != null) {
            this.videoSource.dispose();
        }
    }

    public ListenableFuture<Boolean> switchCamera() {
        final SettableFuture<Boolean> future = SettableFuture.create();
        this.cameraVideoCapturer.switchCamera(
                new CameraVideoCapturer.CameraSwitchHandler() {
                    @Override
                    public void onCameraSwitchDone(final boolean isFrontCamera) {
                        VideoSourceWrapper.this.isFrontCamera = isFrontCamera;
                        future.set(isFrontCamera);
                    }

                    @Override
                    public void onCameraSwitchError(final String message) {
                        future.setException(
                                new IllegalStateException(
                                        String.format("Unable to switch camera %s", message)));
                    }
                });
        return future;
    }

    public boolean isFrontCamera() {
        return this.isFrontCamera;
    }

    public boolean isCameraSwitchable() {
        return this.availableCameras.size() > 1;
    }

    public static class Factory {
        final Context context;

        public Factory(final Context context) {
            this.context = context;
        }

        public VideoSourceWrapper create() {
            final CameraEnumerator enumerator = new Camera2Enumerator(context);
            final Set<String> deviceNames = ImmutableSet.copyOf(enumerator.getDeviceNames());
            for (final String deviceName : deviceNames) {
                if (isFrontFacing(enumerator, deviceName)) {
                    final VideoSourceWrapper videoSourceWrapper =
                            of(enumerator, deviceName, deviceNames);
                    if (videoSourceWrapper == null) {
                        return null;
                    }
                    videoSourceWrapper.isFrontCamera = true;
                    return videoSourceWrapper;
                }
            }
            if (deviceNames.size() == 0) {
                return null;
            } else {
                return of(enumerator, Iterables.get(deviceNames, 0), deviceNames);
            }
        }

        @Nullable
        private VideoSourceWrapper of(
                final CameraEnumerator enumerator,
                final String deviceName,
                final Set<String> availableCameras) {
            final CameraVideoCapturer capturer = enumerator.createCapturer(deviceName, null);
            if (capturer == null) {
                return null;
            }
            final ArrayList<CameraEnumerationAndroid.CaptureFormat> choices =
                    new ArrayList<>(enumerator.getSupportedFormats(deviceName));
            Collections.sort(choices, (a, b) -> b.width - a.width);
            for (final CameraEnumerationAndroid.CaptureFormat captureFormat : choices) {
                if (captureFormat.width <= CAPTURING_RESOLUTION) {
                    return new VideoSourceWrapper(capturer, captureFormat, availableCameras);
                }
            }
            return null;
        }

        private static boolean isFrontFacing(
                final CameraEnumerator cameraEnumerator, final String deviceName) {
            try {
                return cameraEnumerator.isFrontFacing(deviceName);
            } catch (final NullPointerException e) {
                return false;
            }
        }
    }
}
