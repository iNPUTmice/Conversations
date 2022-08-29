package eu.siacs.conversations.ui;

import static java.util.Arrays.asList;
import static eu.siacs.conversations.utils.PermissionUtils.getFirstDenied;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.databinding.DataBindingUtil;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityRtpSessionBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.AppRTCAudioManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.MainThreadExecutor;
import eu.siacs.conversations.ui.util.Rationals;
import eu.siacs.conversations.utils.PermissionUtils;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.JingleConnectionManager;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;

public class RtpSessionActivity extends XmppActivity
        implements XmppConnectionService.OnJingleRtpConnectionUpdate,
                eu.siacs.conversations.ui.widget.SurfaceViewRenderer.OnAspectRatioChanged {

    public static final String EXTRA_WITH = "with";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_LAST_REPORTED_STATE = "last_reported_state";
    public static final String EXTRA_LAST_ACTION = "last_action";
    public static final String ACTION_ACCEPT_CALL = "action_accept_call";
    public static final String ACTION_MAKE_VOICE_CALL = "action_make_voice_call";
    public static final String ACTION_MAKE_VIDEO_CALL = "action_make_video_call";

    private static final int CALL_DURATION_UPDATE_INTERVAL = 333;

    private static final List<RtpEndUserState> END_CARD =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.SECURITY_ERROR,
                    RtpEndUserState.DECLINED_OR_BUSY,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                    RtpEndUserState.RETRACTED);
    private static final List<RtpEndUserState> STATES_SHOWING_HELP_BUTTON =
            Arrays.asList(
                    RtpEndUserState.APPLICATION_ERROR,
                    RtpEndUserState.CONNECTIVITY_ERROR,
                    RtpEndUserState.SECURITY_ERROR);
    private static final List<RtpEndUserState> STATES_SHOWING_SWITCH_TO_CHAT =
            Arrays.asList(
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.CONNECTED,
                    RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_CONSIDERED_CONNECTED =
            Arrays.asList(RtpEndUserState.CONNECTED, RtpEndUserState.RECONNECTING);
    private static final List<RtpEndUserState> STATES_SHOWING_PIP_PLACEHOLDER =
            Arrays.asList(
                    RtpEndUserState.ACCEPTING_CALL,
                    RtpEndUserState.CONNECTING,
                    RtpEndUserState.RECONNECTING);
    private static final String PROXIMITY_WAKE_LOCK_TAG = "conversations:in-rtp-session";
    private static final int REQUEST_ACCEPT_CALL = 0x1111;
    private WeakReference<JingleRtpConnection> rtpConnectionReference;

    private ActivityRtpSessionBinding binding;
    private PowerManager.WakeLock mProximityWakeLock;

    private final Handler mHandler = new Handler();
    private final Runnable mTickExecutor =
            new Runnable() {
                @Override
                public void run() {
                    updateCallDuration();
                    mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
                }
            };

    private static Set<Media> actionToMedia(final String action) {
        if (ACTION_MAKE_VIDEO_CALL.equals(action)) {
            return ImmutableSet.of(Media.AUDIO, Media.VIDEO);
        } else {
            return ImmutableSet.of(Media.AUDIO);
        }
    }

    private static void addSink(
            final VideoTrack videoTrack, final SurfaceViewRenderer surfaceViewRenderer) {
        try {
            videoTrack.addSink(surfaceViewRenderer);
        } catch (final IllegalStateException e) {
            Log.e(
                    Config.LOGTAG,
                    "possible race condition on trying to display video track. ignoring",
                    e);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow()
                .addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_rtp_session);
        setSupportActionBar(binding.toolbar);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.activity_rtp_session, menu);
        final MenuItem help = menu.findItem(R.id.action_help);
        final MenuItem gotoChat = menu.findItem(R.id.action_goto_chat);
        help.setVisible(isHelpButtonVisible());
        gotoChat.setVisible(isSwitchToConversationVisible());
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (xmppConnectionService != null) {
                if (xmppConnectionService.getNotificationService().stopSoundAndVibration()) {
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean isHelpButtonVisible() {
        try {
            return STATES_SHOWING_HELP_BUTTON.contains(requireRtpConnection().getEndUserState());
        } catch (IllegalStateException e) {
            final Intent intent = getIntent();
            final String state =
                    intent != null ? intent.getStringExtra(EXTRA_LAST_REPORTED_STATE) : null;
            if (state != null) {
                return STATES_SHOWING_HELP_BUTTON.contains(RtpEndUserState.valueOf(state));
            } else {
                return false;
            }
        }
    }

    private boolean isSwitchToConversationVisible() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        return connection != null
                && STATES_SHOWING_SWITCH_TO_CHAT.contains(connection.getEndUserState());
    }

    private void switchToConversation() {
        final Contact contact = getWith();
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(
                        contact.getAccount(), contact.getJid(), false, true);
        switchToConversation(conversation);
    }

    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_help:
                launchHelpInBrowser();
                break;
            case R.id.action_goto_chat:
                switchToConversation();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void launchHelpInBrowser() {
        final Intent intent = new Intent(Intent.ACTION_VIEW, Config.HELP);
        try {
            startActivity(intent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_found_to_open_link, Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void endCall(View view) {
        endCall();
    }

    private void endCall() {
        if (this.rtpConnectionReference == null) {
            retractSessionProposal();
            finish();
        } else {
            requireRtpConnection().endCall();
        }
    }

    private void retractSessionProposal() {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final String state = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
        if (!Intent.ACTION_VIEW.equals(action)
                || state == null
                || !END_CARD.contains(RtpEndUserState.valueOf(state))) {
            resetIntent(
                    account, with, RtpEndUserState.RETRACTED, actionToMedia(intent.getAction()));
        }
        xmppConnectionService
                .getJingleConnectionManager()
                .retractSessionProposal(account, with.asBareJid());
    }

    private void rejectCall(View view) {
        requireRtpConnection().rejectCall();
        finish();
    }

    private void acceptCall(View view) {
        requestPermissionsAndAcceptCall();
    }

    private void requestPermissionsAndAcceptCall() {
        final ImmutableList.Builder<String> permissions = ImmutableList.builder();
        if (getMedia().contains(Media.VIDEO)) {
            permissions.add(Manifest.permission.CAMERA).add(Manifest.permission.RECORD_AUDIO);
        } else {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (PermissionUtils.hasPermission(this, permissions.build(), REQUEST_ACCEPT_CALL)) {
            putScreenInCallMode();
            checkRecorderAndAcceptCall();
        }
    }

    private void checkRecorderAndAcceptCall() {
        checkMicrophoneAvailabilityAsync();
        try {
            requireRtpConnection().acceptCall();
        } catch (final IllegalStateException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkMicrophoneAvailabilityAsync() {
        new Thread(this::checkMicrophoneAvailability).start();
    }

    private void checkMicrophoneAvailability() {
        final long start = SystemClock.elapsedRealtime();
        final boolean isMicrophoneAvailable = AppRTCAudioManager.isMicrophoneAvailable();
        final long stop = SystemClock.elapsedRealtime();
        Log.d(Config.LOGTAG, "checking microphone availability took " + (stop - start) + "ms");
        if (isMicrophoneAvailable) {
            return;
        }
        runOnUiThread(
                () ->
                        Toast.makeText(this, R.string.microphone_unavailable, Toast.LENGTH_LONG)
                                .show());
    }

    private void putScreenInCallMode() {
        putScreenInCallMode(requireRtpConnection().getMedia());
    }

    private void putScreenInCallMode(final Set<Media> media) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!media.contains(Media.VIDEO)) {
            final JingleRtpConnection rtpConnection =
                    rtpConnectionReference != null ? rtpConnectionReference.get() : null;
            final AppRTCAudioManager audioManager =
                    rtpConnection == null ? null : rtpConnection.getAudioManager();
            if (audioManager == null
                    || audioManager.getSelectedAudioDevice()
                            == AppRTCAudioManager.AudioDevice.EARPIECE) {
                acquireProximityWakeLock();
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private void acquireProximityWakeLock() {
        final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            Log.e(Config.LOGTAG, "power manager not available");
            return;
        }
        if (isFinishing()) {
            Log.e(Config.LOGTAG, "do not acquire wakelock. activity is finishing");
            return;
        }
        if (this.mProximityWakeLock == null) {
            this.mProximityWakeLock =
                    powerManager.newWakeLock(
                            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_WAKE_LOCK_TAG);
        }
        if (!this.mProximityWakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "acquiring proximity wake lock");
            this.mProximityWakeLock.acquire();
        }
    }

    private void releaseProximityWakeLock() {
        if (this.mProximityWakeLock != null && mProximityWakeLock.isHeld()) {
            Log.d(Config.LOGTAG, "releasing proximity wake lock");
            this.mProximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            this.mProximityWakeLock = null;
        }
    }

    private void putProximityWakeLockInProperState(
            final AppRTCAudioManager.AudioDevice audioDevice) {
        if (audioDevice == AppRTCAudioManager.AudioDevice.EARPIECE) {
            acquireProximityWakeLock();
        } else {
            releaseProximityWakeLock();
        }
    }

    @Override
    protected void refreshUiReal() {}

    @Override
    public void onNewIntent(final Intent intent) {
        Log.d(Config.LOGTAG, this.getClass().getName() + ".onNewIntent()");
        super.onNewIntent(intent);
        setIntent(intent);
        if (xmppConnectionService == null) {
            Log.d(
                    Config.LOGTAG,
                    "RtpSessionActivity: background service wasn't bound in onNewIntent()");
            return;
        }
        final Account account = extractAccount(intent);
        final String action = intent.getAction();
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (sessionId != null) {
            Log.d(Config.LOGTAG, "reinitializing from onNewIntent()");
            if (initializeActivityWithRunningRtpSession(account, with, sessionId)) {
                return;
            }
            if (ACTION_ACCEPT_CALL.equals(intent.getAction())) {
                Log.d(Config.LOGTAG, "accepting call from onNewIntent()");
                requestPermissionsAndAcceptCall();
                resetIntent(intent.getExtras());
            }
        } else if (asList(ACTION_MAKE_VIDEO_CALL, ACTION_MAKE_VOICE_CALL).contains(action)) {
            proposeJingleRtpSession(account, with, actionToMedia(action));
            setWith(account.getRoster().getContact(with), null);
        } else {
            throw new IllegalStateException("received onNewIntent without sessionId");
        }
    }

    @Override
    void onBackendConnected() {
        final Intent intent = getIntent();
        final String action = intent.getAction();
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
        if (sessionId != null) {
            if (initializeActivityWithRunningRtpSession(account, with, sessionId)) {
                return;
            }
            if (ACTION_ACCEPT_CALL.equals(intent.getAction())) {
                Log.d(Config.LOGTAG, "intent action was accept");
                requestPermissionsAndAcceptCall();
                resetIntent(intent.getExtras());
            }
        } else if (asList(ACTION_MAKE_VIDEO_CALL, ACTION_MAKE_VOICE_CALL).contains(action)) {
            proposeJingleRtpSession(account, with, actionToMedia(action));
            setWith(account.getRoster().getContact(with), null);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            final String extraLastState = intent.getStringExtra(EXTRA_LAST_REPORTED_STATE);
            final RtpEndUserState state =
                    extraLastState == null ? null : RtpEndUserState.valueOf(extraLastState);
            if (state != null) {
                Log.d(Config.LOGTAG, "restored last state from intent extra");
                updateButtonConfiguration(state);
                updateVerifiedShield(false);
                updateStateDisplay(state);
                updateIncomingCallScreen(state);
                invalidateOptionsMenu();
            }
            setWith(account.getRoster().getContact(with), state);
            if (xmppConnectionService
                    .getJingleConnectionManager()
                    .fireJingleRtpConnectionStateUpdates()) {
                return;
            }
            if (END_CARD.contains(state)
                    || xmppConnectionService
                            .getJingleConnectionManager()
                            .hasMatchingProposal(account, with)) {
                return;
            }
            Log.d(Config.LOGTAG, "restored state (" + state + ") was not an end card. finishing");
            finish();
        }
    }

    private void setWidth(final RtpEndUserState state) {
        setWith(getWith(), state);
    }

    private void setWith(final Contact contact, final RtpEndUserState state) {
        binding.with.setText(contact.getDisplayName());
        if (Arrays.asList(RtpEndUserState.INCOMING_CALL, RtpEndUserState.ACCEPTING_CALL)
                .contains(state)) {
            binding.withJid.setText(contact.getJid().asBareJid().toEscapedString());
            binding.withJid.setVisibility(View.VISIBLE);
        } else {
            binding.withJid.setVisibility(View.GONE);
        }
    }

    private void proposeJingleRtpSession(
            final Account account, final Jid with, final Set<Media> media) {
        checkMicrophoneAvailabilityAsync();
        if (with.isBareJid()) {
            xmppConnectionService
                    .getJingleConnectionManager()
                    .proposeJingleRtpSession(account, with, media);
        } else {
            final String sessionId =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .initializeRtpSession(account, with, media);
            initializeActivityWithRunningRtpSession(account, with, sessionId);
            resetIntent(account, with, sessionId);
        }
        putScreenInCallMode(media);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        final PermissionUtils.PermissionResult permissionResult =
                PermissionUtils.removeBluetoothConnect(permissions, grantResults);
        if (PermissionUtils.allGranted(permissionResult.grantResults)) {
            if (requestCode == REQUEST_ACCEPT_CALL) {
                checkRecorderAndAcceptCall();
            }
        } else {
            @StringRes int res;
            final String firstDenied =
                    getFirstDenied(permissionResult.grantResults, permissionResult.permissions);
            if (Manifest.permission.RECORD_AUDIO.equals(firstDenied)) {
                res = R.string.no_microphone_permission;
            } else if (Manifest.permission.CAMERA.equals(firstDenied)) {
                res = R.string.no_camera_permission;
            } else {
                throw new IllegalStateException("Invalid permission result request");
            }
            Toast.makeText(this, getString(res, getString(R.string.app_name)), Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mHandler.postDelayed(mTickExecutor, CALL_DURATION_UPDATE_INTERVAL);
        this.binding.remoteVideo.setOnAspectRatioChanged(this);
    }

    @Override
    public void onStop() {
        mHandler.removeCallbacks(mTickExecutor);
        binding.remoteVideo.release();
        binding.remoteVideo.setOnAspectRatioChanged(null);
        binding.localVideo.release();
        final WeakReference<JingleRtpConnection> weakReference = this.rtpConnectionReference;
        final JingleRtpConnection jingleRtpConnection =
                weakReference == null ? null : weakReference.get();
        if (jingleRtpConnection != null) {
            releaseVideoTracks(jingleRtpConnection);
        }
        releaseProximityWakeLock();
        super.onStop();
    }

    private void releaseVideoTracks(final JingleRtpConnection jingleRtpConnection) {
        final Optional<VideoTrack> remoteVideo = jingleRtpConnection.getRemoteVideoTrack();
        if (remoteVideo.isPresent()) {
            remoteVideo.get().removeSink(binding.remoteVideo);
        }
        final Optional<VideoTrack> localVideo = jingleRtpConnection.getLocalVideoTrack();
        if (localVideo.isPresent()) {
            localVideo.get().removeSink(binding.localVideo);
        }
    }

    @Override
    public void onBackPressed() {
        if (isConnected()) {
            if (switchToPictureInPicture()) {
                return;
            }
        } else {
            endCall();
        }
        super.onBackPressed();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (switchToPictureInPicture()) {
            return;
        }
        // TODO apparently this method is not getting called on Android 10 when using the task
        // switcher
        if (emptyReference(rtpConnectionReference) && xmppConnectionService != null) {
            retractSessionProposal();
        }
    }

    private boolean isConnected() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        return connection != null
                && STATES_CONSIDERED_CONNECTED.contains(connection.getEndUserState());
    }

    private boolean switchToPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && deviceSupportsPictureInPicture()) {
            if (shouldBePictureInPicture()) {
                startPictureInPicture();
                return true;
            }
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startPictureInPicture() {
        try {
            final Rational rational = this.binding.remoteVideo.getAspectRatio();
            final Rational clippedRational = Rationals.clip(rational);
            Log.d(
                    Config.LOGTAG,
                    "suggested rational " + rational + ". clipped to " + clippedRational);
            enterPictureInPictureMode(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        } catch (final IllegalStateException e) {
            // this sometimes happens on Samsung phones (possibly when Knox is enabled)
            Log.w(Config.LOGTAG, "unable to enter picture in picture mode", e);
        }
    }

    @Override
    public void onAspectRatioChanged(final Rational rational) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPictureInPicture()) {
            final Rational clippedRational = Rationals.clip(rational);
            Log.d(
                    Config.LOGTAG,
                    "suggested rational after aspect ratio change "
                            + rational
                            + ". clipped to "
                            + clippedRational);
            setPictureInPictureParams(
                    new PictureInPictureParams.Builder().setAspectRatio(clippedRational).build());
        }
    }

    private boolean deviceSupportsPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } else {
            return false;
        }
    }

    private boolean shouldBePictureInPicture() {
        try {
            final JingleRtpConnection rtpConnection = requireRtpConnection();
            return rtpConnection.getMedia().contains(Media.VIDEO)
                    && Arrays.asList(
                                    RtpEndUserState.ACCEPTING_CALL,
                                    RtpEndUserState.CONNECTING,
                                    RtpEndUserState.CONNECTED)
                            .contains(rtpConnection.getEndUserState());
        } catch (final IllegalStateException e) {
            return false;
        }
    }

    private boolean initializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        final WeakReference<JingleRtpConnection> reference =
                xmppConnectionService
                        .getJingleConnectionManager()
                        .findJingleRtpConnection(account, with, sessionId);
        if (reference == null || reference.get() == null) {
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession =
                    xmppConnectionService
                            .getJingleConnectionManager()
                            .getTerminalSessionState(with, sessionId);
            if (terminatedRtpSession == null) {
                throw new IllegalStateException(
                        "failed to initialize activity with running rtp session. session not found");
            }
            initializeWithTerminatedSessionState(account, with, terminatedRtpSession);
            return true;
        }
        this.rtpConnectionReference = reference;
        final RtpEndUserState currentState = requireRtpConnection().getEndUserState();
        final boolean verified = requireRtpConnection().isVerified();
        if (currentState == RtpEndUserState.ENDED) {
            finish();
            return true;
        }
        final Set<Media> media = getMedia();
        if (currentState == RtpEndUserState.INCOMING_CALL) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (JingleRtpConnection.STATES_SHOWING_ONGOING_CALL.contains(
                requireRtpConnection().getState())) {
            putScreenInCallMode();
        }
        setWidth(currentState);
        updateVideoViews(currentState);
        updateStateDisplay(currentState, media);
        updateVerifiedShield(verified && STATES_SHOWING_SWITCH_TO_CHAT.contains(currentState));
        updateButtonConfiguration(currentState, media);
        updateIncomingCallScreen(currentState);
        invalidateOptionsMenu();
        return false;
    }

    private void initializeWithTerminatedSessionState(
            final Account account,
            final Jid with,
            final JingleConnectionManager.TerminatedRtpSession terminatedRtpSession) {
        Log.d(Config.LOGTAG, "initializeWithTerminatedSessionState()");
        if (terminatedRtpSession.state == RtpEndUserState.ENDED) {
            finish();
            return;
        }
        final RtpEndUserState state = terminatedRtpSession.state;
        resetIntent(account, with, terminatedRtpSession.state, terminatedRtpSession.media);
        updateButtonConfiguration(state);
        updateStateDisplay(state);
        updateIncomingCallScreen(state);
        updateCallDuration();
        updateVerifiedShield(false);
        invalidateOptionsMenu();
        setWith(account.getRoster().getContact(with), state);
    }

    private void reInitializeActivityWithRunningRtpSession(
            final Account account, Jid with, String sessionId) {
        runOnUiThread(() -> initializeActivityWithRunningRtpSession(account, with, sessionId));
        resetIntent(account, with, sessionId);
    }

    private void resetIntent(final Account account, final Jid with, final String sessionId) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toEscapedString());
        intent.putExtra(EXTRA_WITH, with.toEscapedString());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        setIntent(intent);
    }

    private void ensureSurfaceViewRendererIsSetup(final SurfaceViewRenderer surfaceViewRenderer) {
        surfaceViewRenderer.setVisibility(View.VISIBLE);
        try {
            surfaceViewRenderer.init(requireRtpConnection().getEglBaseContext(), null);
        } catch (final IllegalStateException e) {
            // Log.d(Config.LOGTAG, "SurfaceViewRenderer was already initialized");
        }
        surfaceViewRenderer.setEnableHardwareScaler(true);
    }

    private void updateStateDisplay(final RtpEndUserState state) {
        updateStateDisplay(state, Collections.emptySet());
    }

    private void updateStateDisplay(final RtpEndUserState state, final Set<Media> media) {
        switch (state) {
            case INCOMING_CALL:
                Preconditions.checkArgument(media.size() > 0, "Media must not be empty");
                if (media.contains(Media.VIDEO)) {
                    setTitle(R.string.rtp_state_incoming_video_call);
                } else {
                    setTitle(R.string.rtp_state_incoming_call);
                }
                break;
            case CONNECTING:
                setTitle(R.string.rtp_state_connecting);
                break;
            case CONNECTED:
                setTitle(R.string.rtp_state_connected);
                break;
            case RECONNECTING:
                setTitle(R.string.rtp_state_reconnecting);
                break;
            case ACCEPTING_CALL:
                setTitle(R.string.rtp_state_accepting_call);
                break;
            case ENDING_CALL:
                setTitle(R.string.rtp_state_ending_call);
                break;
            case FINDING_DEVICE:
                setTitle(R.string.rtp_state_finding_device);
                break;
            case RINGING:
                setTitle(R.string.rtp_state_ringing);
                break;
            case DECLINED_OR_BUSY:
                setTitle(R.string.rtp_state_declined_or_busy);
                break;
            case CONNECTIVITY_ERROR:
                setTitle(R.string.rtp_state_connectivity_error);
                break;
            case CONNECTIVITY_LOST_ERROR:
                setTitle(R.string.rtp_state_connectivity_lost_error);
                break;
            case RETRACTED:
                setTitle(R.string.rtp_state_retracted);
                break;
            case APPLICATION_ERROR:
                setTitle(R.string.rtp_state_application_failure);
                break;
            case SECURITY_ERROR:
                setTitle(R.string.rtp_state_security_error);
                break;
            case ENDED:
                throw new IllegalStateException(
                        "Activity should have called finishAndReleaseWakeLock();");
            default:
                throw new IllegalStateException(
                        String.format("State %s has not been handled in UI", state));
        }
    }

    private void updateVerifiedShield(final boolean verified) {
        if (isPictureInPicture()) {
            this.binding.verified.setVisibility(View.GONE);
            return;
        }
        this.binding.verified.setVisibility(verified ? View.VISIBLE : View.GONE);
    }

    private void updateIncomingCallScreen(final RtpEndUserState state) {
        updateIncomingCallScreen(state, null);
    }

    private void updateIncomingCallScreen(final RtpEndUserState state, final Contact contact) {
        if (state == RtpEndUserState.INCOMING_CALL || state == RtpEndUserState.ACCEPTING_CALL) {
            final boolean show = getResources().getBoolean(R.bool.show_avatar_incoming_call);
            if (show) {
                binding.contactPhoto.setVisibility(View.VISIBLE);
                if (contact == null) {
                    AvatarWorkerTask.loadAvatar(
                            getWith(), binding.contactPhoto, R.dimen.publish_avatar_size);
                } else {
                    AvatarWorkerTask.loadAvatar(
                            contact, binding.contactPhoto, R.dimen.publish_avatar_size);
                }
            } else {
                binding.contactPhoto.setVisibility(View.GONE);
            }
            final Account account = contact == null ? getWith().getAccount() : contact.getAccount();
            binding.usingAccount.setVisibility(View.VISIBLE);
            binding.usingAccount.setText(
                    getString(
                            R.string.using_account,
                            account.getJid().asBareJid().toEscapedString()));
        } else {
            binding.usingAccount.setVisibility(View.GONE);
            binding.contactPhoto.setVisibility(View.GONE);
        }
    }

    private Set<Media> getMedia() {
        return requireRtpConnection().getMedia();
    }

    private void updateButtonConfiguration(final RtpEndUserState state) {
        updateButtonConfiguration(state, Collections.emptySet());
    }

    @SuppressLint("RestrictedApi")
    private void updateButtonConfiguration(final RtpEndUserState state, final Set<Media> media) {
        if (state == RtpEndUserState.ENDING_CALL || isPictureInPicture()) {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        } else if (state == RtpEndUserState.INCOMING_CALL) {
            this.binding.rejectCall.setContentDescription(getString(R.string.dismiss_call));
            this.binding.rejectCall.setOnClickListener(this::rejectCall);
            this.binding.rejectCall.setImageResource(R.drawable.ic_call_end_white_48dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.answer_call));
            this.binding.acceptCall.setOnClickListener(this::acceptCall);
            this.binding.acceptCall.setImageResource(R.drawable.ic_call_white_48dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (state == RtpEndUserState.DECLINED_OR_BUSY) {
            this.binding.rejectCall.setContentDescription(getString(R.string.exit));
            this.binding.rejectCall.setOnClickListener(this::exit);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_white_48dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.record_voice_mail));
            this.binding.acceptCall.setOnClickListener(this::recordVoiceMail);
            this.binding.acceptCall.setImageResource(R.drawable.ic_voicemail_white_24dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else if (asList(
                        RtpEndUserState.CONNECTIVITY_ERROR,
                        RtpEndUserState.CONNECTIVITY_LOST_ERROR,
                        RtpEndUserState.APPLICATION_ERROR,
                        RtpEndUserState.RETRACTED,
                        RtpEndUserState.SECURITY_ERROR)
                .contains(state)) {
            this.binding.rejectCall.setContentDescription(getString(R.string.exit));
            this.binding.rejectCall.setOnClickListener(this::exit);
            this.binding.rejectCall.setImageResource(R.drawable.ic_clear_white_48dp);
            this.binding.rejectCall.setVisibility(View.VISIBLE);
            this.binding.endCall.setVisibility(View.INVISIBLE);
            this.binding.acceptCall.setContentDescription(getString(R.string.try_again));
            this.binding.acceptCall.setOnClickListener(this::retry);
            this.binding.acceptCall.setImageResource(R.drawable.ic_replay_white_48dp);
            this.binding.acceptCall.setVisibility(View.VISIBLE);
        } else {
            this.binding.rejectCall.setVisibility(View.INVISIBLE);
            this.binding.endCall.setContentDescription(getString(R.string.hang_up));
            this.binding.endCall.setOnClickListener(this::endCall);
            this.binding.endCall.setImageResource(R.drawable.ic_call_end_white_48dp);
            this.binding.endCall.setVisibility(View.VISIBLE);
            this.binding.acceptCall.setVisibility(View.INVISIBLE);
        }
        updateInCallButtonConfiguration(state, media);
    }

    private boolean isPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return isInPictureInPictureMode();
        } else {
            return false;
        }
    }

    private void updateInCallButtonConfiguration() {
        updateInCallButtonConfiguration(
                requireRtpConnection().getEndUserState(), requireRtpConnection().getMedia());
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfiguration(
            final RtpEndUserState state, final Set<Media> media) {
        if (STATES_CONSIDERED_CONNECTED.contains(state) && !isPictureInPicture()) {
            Preconditions.checkArgument(media.size() > 0, "Media must not be empty");
            if (media.contains(Media.VIDEO)) {
                final JingleRtpConnection rtpConnection = requireRtpConnection();
                updateInCallButtonConfigurationVideo(
                        rtpConnection.isVideoEnabled(), rtpConnection.isCameraSwitchable());
            } else {
                final AppRTCAudioManager audioManager = requireRtpConnection().getAudioManager();
                updateInCallButtonConfigurationSpeaker(
                        audioManager.getSelectedAudioDevice(),
                        audioManager.getAudioDevices().size());
                this.binding.inCallActionFarRight.setVisibility(View.GONE);
            }
            if (media.contains(Media.AUDIO)) {
                updateInCallButtonConfigurationMicrophone(
                        requireRtpConnection().isMicrophoneEnabled());
            } else {
                this.binding.inCallActionLeft.setVisibility(View.GONE);
            }
        } else {
            this.binding.inCallActionLeft.setVisibility(View.GONE);
            this.binding.inCallActionRight.setVisibility(View.GONE);
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        }
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationSpeaker(
            final AppRTCAudioManager.AudioDevice selectedAudioDevice, final int numberOfChoices) {
        switch (selectedAudioDevice) {
            case EARPIECE:
                this.binding.inCallActionRight.setImageResource(
                        R.drawable.ic_volume_off_black_24dp);
                if (numberOfChoices >= 2) {
                    this.binding.inCallActionRight.setOnClickListener(this::switchToSpeaker);
                } else {
                    this.binding.inCallActionRight.setOnClickListener(null);
                    this.binding.inCallActionRight.setClickable(false);
                }
                break;
            case WIRED_HEADSET:
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_headset_black_24dp);
                this.binding.inCallActionRight.setOnClickListener(null);
                this.binding.inCallActionRight.setClickable(false);
                break;
            case SPEAKER_PHONE:
                this.binding.inCallActionRight.setImageResource(R.drawable.ic_volume_up_black_24dp);
                if (numberOfChoices >= 2) {
                    this.binding.inCallActionRight.setOnClickListener(this::switchToEarpiece);
                } else {
                    this.binding.inCallActionRight.setOnClickListener(null);
                    this.binding.inCallActionRight.setClickable(false);
                }
                break;
            case BLUETOOTH:
                this.binding.inCallActionRight.setImageResource(
                        R.drawable.ic_bluetooth_audio_black_24dp);
                this.binding.inCallActionRight.setOnClickListener(null);
                this.binding.inCallActionRight.setClickable(false);
                break;
        }
        this.binding.inCallActionRight.setVisibility(View.VISIBLE);
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationVideo(
            final boolean videoEnabled, final boolean isCameraSwitchable) {
        this.binding.inCallActionRight.setVisibility(View.VISIBLE);
        if (isCameraSwitchable) {
            this.binding.inCallActionFarRight.setImageResource(
                    R.drawable.ic_flip_camera_android_black_24dp);
            this.binding.inCallActionFarRight.setVisibility(View.VISIBLE);
            this.binding.inCallActionFarRight.setOnClickListener(this::switchCamera);
        } else {
            this.binding.inCallActionFarRight.setVisibility(View.GONE);
        }
        if (videoEnabled) {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_black_24dp);
            this.binding.inCallActionRight.setOnClickListener(this::disableVideo);
        } else {
            this.binding.inCallActionRight.setImageResource(R.drawable.ic_videocam_off_black_24dp);
            this.binding.inCallActionRight.setOnClickListener(this::enableVideo);
        }
    }

    private void switchCamera(final View view) {
        Futures.addCallback(
                requireRtpConnection().switchCamera(),
                new FutureCallback<Boolean>() {
                    @Override
                    public void onSuccess(@Nullable Boolean isFrontCamera) {
                        binding.localVideo.setMirror(isFrontCamera);
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        Log.d(
                                Config.LOGTAG,
                                "could not switch camera",
                                Throwables.getRootCause(throwable));
                        Toast.makeText(
                                        RtpSessionActivity.this,
                                        R.string.could_not_switch_camera,
                                        Toast.LENGTH_LONG)
                                .show();
                    }
                },
                MainThreadExecutor.getInstance());
    }

    private void enableVideo(View view) {
        try {
            requireRtpConnection().setVideoEnabled(true);
        } catch (final IllegalStateException e) {
            Toast.makeText(this, R.string.unable_to_enable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        updateInCallButtonConfigurationVideo(true, requireRtpConnection().isCameraSwitchable());
    }

    private void disableVideo(View view) {
        requireRtpConnection().setVideoEnabled(false);
        updateInCallButtonConfigurationVideo(false, requireRtpConnection().isCameraSwitchable());
    }

    @SuppressLint("RestrictedApi")
    private void updateInCallButtonConfigurationMicrophone(final boolean microphoneEnabled) {
        if (microphoneEnabled) {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_black_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::disableMicrophone);
        } else {
            this.binding.inCallActionLeft.setImageResource(R.drawable.ic_mic_off_black_24dp);
            this.binding.inCallActionLeft.setOnClickListener(this::enableMicrophone);
        }
        this.binding.inCallActionLeft.setVisibility(View.VISIBLE);
    }

    private void updateCallDuration() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null || connection.getMedia().contains(Media.VIDEO)) {
            this.binding.duration.setVisibility(View.GONE);
            return;
        }
        if (connection.zeroDuration()) {
            this.binding.duration.setVisibility(View.GONE);
        } else {
            this.binding.duration.setText(
                    TimeFrameUtils.formatElapsedTime(connection.getCallDuration(), false));
            this.binding.duration.setVisibility(View.VISIBLE);
        }
    }

    private void updateVideoViews(final RtpEndUserState state) {
        if (END_CARD.contains(state) || state == RtpEndUserState.ENDING_CALL) {
            binding.localVideo.setVisibility(View.GONE);
            binding.localVideo.release();
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.remoteVideo.release();
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            if (isPictureInPicture()) {
                binding.appBarLayout.setVisibility(View.GONE);
                binding.pipPlaceholder.setVisibility(View.VISIBLE);
                if (Arrays.asList(
                                RtpEndUserState.APPLICATION_ERROR,
                                RtpEndUserState.CONNECTIVITY_ERROR,
                                RtpEndUserState.SECURITY_ERROR)
                        .contains(state)) {
                    binding.pipWarning.setVisibility(View.VISIBLE);
                    binding.pipWaiting.setVisibility(View.GONE);
                } else {
                    binding.pipWarning.setVisibility(View.GONE);
                    binding.pipWaiting.setVisibility(View.GONE);
                }
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                binding.pipPlaceholder.setVisibility(View.GONE);
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            return;
        }
        if (isPictureInPicture() && STATES_SHOWING_PIP_PLACEHOLDER.contains(state)) {
            binding.localVideo.setVisibility(View.GONE);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.appBarLayout.setVisibility(View.GONE);
            binding.pipPlaceholder.setVisibility(View.VISIBLE);
            binding.pipWarning.setVisibility(View.GONE);
            binding.pipWaiting.setVisibility(View.VISIBLE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            return;
        }
        final Optional<VideoTrack> localVideoTrack = getLocalVideoTrack();
        if (localVideoTrack.isPresent() && !isPictureInPicture()) {
            ensureSurfaceViewRendererIsSetup(binding.localVideo);
            // paint local view over remote view
            binding.localVideo.setZOrderMediaOverlay(true);
            binding.localVideo.setMirror(requireRtpConnection().isFrontCamera());
            addSink(localVideoTrack.get(), binding.localVideo);
        } else {
            binding.localVideo.setVisibility(View.GONE);
        }
        final Optional<VideoTrack> remoteVideoTrack = getRemoteVideoTrack();
        if (remoteVideoTrack.isPresent()) {
            ensureSurfaceViewRendererIsSetup(binding.remoteVideo);
            addSink(remoteVideoTrack.get(), binding.remoteVideo);
            binding.remoteVideo.setScalingType(
                    RendererCommon.ScalingType.SCALE_ASPECT_FILL,
                    RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            if (state == RtpEndUserState.CONNECTED) {
                binding.appBarLayout.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.VISIBLE);
            } else {
                binding.appBarLayout.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                binding.remoteVideoWrapper.setVisibility(View.GONE);
            }
            if (isPictureInPicture() && !requireRtpConnection().isMicrophoneEnabled()) {
                binding.pipLocalMicOffIndicator.setVisibility(View.VISIBLE);
            } else {
                binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            binding.remoteVideoWrapper.setVisibility(View.GONE);
            binding.pipLocalMicOffIndicator.setVisibility(View.GONE);
        }
    }

    private Optional<VideoTrack> getLocalVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getLocalVideoTrack();
    }

    private Optional<VideoTrack> getRemoteVideoTrack() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            return Optional.absent();
        }
        return connection.getRemoteVideoTrack();
    }

    private void disableMicrophone(View view) {
        final JingleRtpConnection rtpConnection = requireRtpConnection();
        if (rtpConnection.setMicrophoneEnabled(false)) {
            updateInCallButtonConfiguration();
        }
    }

    private void enableMicrophone(View view) {
        final JingleRtpConnection rtpConnection = requireRtpConnection();
        if (rtpConnection.setMicrophoneEnabled(true)) {
            updateInCallButtonConfiguration();
        }
    }

    private void switchToEarpiece(View view) {
        requireRtpConnection()
                .getAudioManager()
                .setDefaultAudioDevice(AppRTCAudioManager.AudioDevice.EARPIECE);
        acquireProximityWakeLock();
    }

    private void switchToSpeaker(View view) {
        requireRtpConnection()
                .getAudioManager()
                .setDefaultAudioDevice(AppRTCAudioManager.AudioDevice.SPEAKER_PHONE);
        releaseProximityWakeLock();
    }

    private void retry(View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final String lastAction = intent.getStringExtra(EXTRA_LAST_ACTION);
        final String action = intent.getAction();
        final Set<Media> media = actionToMedia(lastAction == null ? action : lastAction);
        this.rtpConnectionReference = null;
        Log.d(Config.LOGTAG, "attempting retry with " + with.toEscapedString());
        proposeJingleRtpSession(account, with, media);
    }

    private void exit(final View view) {
        finish();
    }

    private void recordVoiceMail(final View view) {
        final Intent intent = getIntent();
        final Account account = extractAccount(intent);
        final Jid with = Jid.ofEscaped(intent.getStringExtra(EXTRA_WITH));
        final Conversation conversation =
                xmppConnectionService.findOrCreateConversation(account, with, false, true);
        final Intent launchIntent = new Intent(this, ConversationsActivity.class);
        launchIntent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
        launchIntent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
        launchIntent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launchIntent.putExtra(
                ConversationsActivity.EXTRA_POST_INIT_ACTION,
                ConversationsActivity.POST_ACTION_RECORD_VOICE);
        startActivity(launchIntent);
        finish();
    }

    private Contact getWith() {
        final AbstractJingleConnection.Id id = requireRtpConnection().getId();
        final Account account = id.account;
        return account.getRoster().getContact(id.with);
    }

    private JingleRtpConnection requireRtpConnection() {
        final JingleRtpConnection connection =
                this.rtpConnectionReference != null ? this.rtpConnectionReference.get() : null;
        if (connection == null) {
            throw new IllegalStateException("No RTP connection found");
        }
        return connection;
    }

    @Override
    public void onJingleRtpConnectionUpdate(
            Account account, Jid with, final String sessionId, RtpEndUserState state) {
        Log.d(Config.LOGTAG, "onJingleRtpConnectionUpdate(" + state + ")");
        if (END_CARD.contains(state)) {
            Log.d(Config.LOGTAG, "end card reached");
            releaseProximityWakeLock();
            runOnUiThread(
                    () -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }
        if (with.isBareJid()) {
            updateRtpSessionProposalState(account, with, state);
            return;
        }
        if (emptyReference(this.rtpConnectionReference)) {
            if (END_CARD.contains(state)) {
                Log.d(Config.LOGTAG, "not reinitializing session");
                return;
            }
            // this happens when going from proposed session to actual session
            reInitializeActivityWithRunningRtpSession(account, with, sessionId);
            return;
        }
        final AbstractJingleConnection.Id id = requireRtpConnection().getId();
        final boolean verified = requireRtpConnection().isVerified();
        final Set<Media> media = getMedia();
        final Contact contact = getWith();
        if (account == id.account && id.with.equals(with) && id.sessionId.equals(sessionId)) {
            if (state == RtpEndUserState.ENDED) {
                finish();
                return;
            }
            runOnUiThread(
                    () -> {
                        updateStateDisplay(state, media);
                        updateVerifiedShield(
                                verified && STATES_SHOWING_SWITCH_TO_CHAT.contains(state));
                        updateButtonConfiguration(state, media);
                        updateVideoViews(state);
                        updateIncomingCallScreen(state, contact);
                        invalidateOptionsMenu();
                    });
            if (END_CARD.contains(state)) {
                final JingleRtpConnection rtpConnection = requireRtpConnection();
                resetIntent(account, with, state, rtpConnection.getMedia());
                releaseVideoTracks(rtpConnection);
                this.rtpConnectionReference = null;
            }
        } else {
            Log.d(Config.LOGTAG, "received update for other rtp session");
        }
    }

    @Override
    public void onAudioDeviceChanged(
            AppRTCAudioManager.AudioDevice selectedAudioDevice,
            Set<AppRTCAudioManager.AudioDevice> availableAudioDevices) {
        Log.d(
                Config.LOGTAG,
                "onAudioDeviceChanged in activity: selected:"
                        + selectedAudioDevice
                        + ", available:"
                        + availableAudioDevices);
        try {
            if (getMedia().contains(Media.VIDEO)) {
                Log.d(Config.LOGTAG, "nothing to do; in video mode");
                return;
            }
            final RtpEndUserState endUserState = requireRtpConnection().getEndUserState();
            if (endUserState == RtpEndUserState.CONNECTED) {
                final AppRTCAudioManager audioManager = requireRtpConnection().getAudioManager();
                updateInCallButtonConfigurationSpeaker(
                        audioManager.getSelectedAudioDevice(),
                        audioManager.getAudioDevices().size());
            } else if (END_CARD.contains(endUserState)) {
                Log.d(
                        Config.LOGTAG,
                        "onAudioDeviceChanged() nothing to do because end card has been reached");
            } else {
                putProximityWakeLockInProperState(selectedAudioDevice);
            }
        } catch (IllegalStateException e) {
            Log.d(Config.LOGTAG, "RTP connection was not available when audio device changed");
        }
    }

    private void updateRtpSessionProposalState(
            final Account account, final Jid with, final RtpEndUserState state) {
        final Intent currentIntent = getIntent();
        final String withExtra =
                currentIntent == null ? null : currentIntent.getStringExtra(EXTRA_WITH);
        if (withExtra == null) {
            return;
        }
        if (Jid.ofEscaped(withExtra).asBareJid().equals(with)) {
            runOnUiThread(
                    () -> {
                        updateVerifiedShield(false);
                        updateStateDisplay(state);
                        updateButtonConfiguration(state);
                        updateIncomingCallScreen(state);
                        invalidateOptionsMenu();
                    });
            resetIntent(account, with, state, actionToMedia(currentIntent.getAction()));
        }
    }

    private void resetIntent(final Bundle extras) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtras(extras);
        setIntent(intent);
    }

    private void resetIntent(
            final Account account, Jid with, final RtpEndUserState state, final Set<Media> media) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().toEscapedString());
        if (account.getRoster()
                .getContact(with)
                .getPresences()
                .anySupport(Namespace.JINGLE_MESSAGE)) {
            intent.putExtra(EXTRA_WITH, with.asBareJid().toEscapedString());
        } else {
            intent.putExtra(EXTRA_WITH, with.toEscapedString());
        }
        intent.putExtra(EXTRA_LAST_REPORTED_STATE, state.toString());
        intent.putExtra(
                EXTRA_LAST_ACTION,
                media.contains(Media.VIDEO) ? ACTION_MAKE_VIDEO_CALL : ACTION_MAKE_VOICE_CALL);
        setIntent(intent);
    }

    private static boolean emptyReference(final WeakReference<?> weakReference) {
        return weakReference == null || weakReference.get() == null;
    }
}
