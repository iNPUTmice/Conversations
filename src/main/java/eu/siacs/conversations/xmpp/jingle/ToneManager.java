package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.Config;

import static java.util.Arrays.asList;

class ToneManager {

    private final ToneGenerator toneGenerator;
    private final Context context;

    private ToneState state = null;
    private ScheduledFuture<?> currentTone;
    private ScheduledFuture<?> currentResetFuture;
    private boolean appRtcAudioManagerHasControl = false;

    ToneManager(final Context context) {
        ToneGenerator toneGenerator;
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60);
        } catch (final RuntimeException e) {
            Log.e(Config.LOGTAG, "unable to instantiate ToneGenerator", e);
            toneGenerator = null;
        }
        this.toneGenerator = toneGenerator;
        this.context = context;
    }

    private static ToneState of(final boolean isInitiator, final RtpEndUserState state, final Set<Media> media) {
        if (isInitiator) {
            if (asList(RtpEndUserState.FINDING_DEVICE, RtpEndUserState.RINGING, RtpEndUserState.CONNECTING).contains(state)) {
                return ToneState.RINGING;
            }
            if (state == RtpEndUserState.DECLINED_OR_BUSY) {
                return ToneState.BUSY;
            }
        }
        if (state == RtpEndUserState.ENDING_CALL) {
            if (media.contains(Media.VIDEO)) {
                return ToneState.NULL;
            } else {
                return ToneState.ENDING_CALL;
            }
        }
        if (state == RtpEndUserState.CONNECTED) {
            if (media.contains(Media.VIDEO)) {
                return ToneState.NULL;
            } else {
                return ToneState.CONNECTED;
            }
        }
        return ToneState.NULL;
    }

    void transition(final RtpEndUserState state, final Set<Media> media) {
        transition(of(true, state, media), media);
    }

    void transition(final boolean isInitiator, final RtpEndUserState state, final Set<Media> media) {
        transition(of(isInitiator, state, media), media);
    }

    private synchronized void transition(ToneState state, final Set<Media> media) {
        if (this.state == state) {
            return;
        }
        if (state == ToneState.NULL && this.state == ToneState.ENDING_CALL) {
            return;
        }
        cancelCurrentTone();
        Log.d(Config.LOGTAG, getClass().getName() + ".transition(" + state + ")");
        if (state != ToneState.NULL) {
            configureAudioManagerForCall(media);
        }
        switch (state) {
            case RINGING:
                scheduleWaitingTone();
                break;
            case CONNECTED:
                scheduleConnected();
                break;
            case BUSY:
                scheduleBusy();
                break;
            case ENDING_CALL:
                scheduleEnding();
                break;
            case NULL:
                if (noResetScheduled()) {
                    resetAudioManager();
                }
                break;
            default:
                throw new IllegalStateException("Unable to handle transition to "+state);
        }
        this.state = state;
    }

    void setAppRtcAudioManagerHasControl(final boolean appRtcAudioManagerHasControl) {
        this.appRtcAudioManagerHasControl = appRtcAudioManagerHasControl;
    }

    private void scheduleConnected() {
        this.currentTone = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(() -> {
            startTone(ToneGenerator.TONE_PROP_PROMPT, 200);
        }, 0, TimeUnit.SECONDS);
    }

    private void scheduleEnding() {
        this.currentTone = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(() -> {
            startTone(ToneGenerator.TONE_CDMA_CALLDROP_LITE, 375);
        }, 0, TimeUnit.SECONDS);
        this.currentResetFuture = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(this::resetAudioManager, 375, TimeUnit.MILLISECONDS);
    }

    private void scheduleBusy() {
        this.currentTone = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(() -> {
            startTone(ToneGenerator.TONE_CDMA_NETWORK_BUSY, 2500);
        }, 0, TimeUnit.SECONDS);
        this.currentResetFuture = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.schedule(this::resetAudioManager, 2500, TimeUnit.MILLISECONDS);
    }

    private void scheduleWaitingTone() {
        this.currentTone = JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE.scheduleAtFixedRate(() -> {
            startTone(ToneGenerator.TONE_CDMA_DIAL_TONE_LITE, 750);
        }, 0, 3, TimeUnit.SECONDS);
    }

    private boolean noResetScheduled() {
        return this.currentResetFuture == null || this.currentResetFuture.isDone();
    }

    private void cancelCurrentTone() {
        if (currentTone != null) {
            currentTone.cancel(true);
        }
        if (toneGenerator != null) {
            toneGenerator.stopTone();
        }
    }

    private void startTone(final int toneType, final int durationMs) {
        if (toneGenerator != null) {
            this.toneGenerator.startTone(toneType, durationMs);
        } else {
            Log.e(Config.LOGTAG, "failed to start tone. ToneGenerator doesn't exist");
        }
    }

    private void configureAudioManagerForCall(final Set<Media> media) {
        if (appRtcAudioManagerHasControl) {
            Log.d(Config.LOGTAG, ToneManager.class.getName() + ": do not configure audio manager because RTC has control");
            return;
        }
        final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        final boolean isSpeakerPhone = media.contains(Media.VIDEO);
        Log.d(Config.LOGTAG, ToneManager.class.getName() + ": putting AudioManager into communication mode. speaker=" + isSpeakerPhone);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(isSpeakerPhone);
    }

    private void resetAudioManager() {
        if (appRtcAudioManagerHasControl) {
            Log.d(Config.LOGTAG, ToneManager.class.getName() + ": do not reset audio manager because RTC has control");
            return;
        }
        final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return;
        }
        Log.d(Config.LOGTAG, ToneManager.class.getName() + ": putting AudioManager back into normal mode");
        audioManager.setMode(AudioManager.MODE_NORMAL);
        audioManager.setSpeakerphoneOn(false);
    }

    private enum ToneState {
        NULL, RINGING, CONNECTED, BUSY, ENDING_CALL
    }
}
