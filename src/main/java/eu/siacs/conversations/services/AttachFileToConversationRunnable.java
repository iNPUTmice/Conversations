package eu.siacs.conversations.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.otaliastudios.transcoder.Transcoder;
import com.otaliastudios.transcoder.TranscoderListener;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.PgpEngine;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.utils.TranscoderStrategies;

public class AttachFileToConversationRunnable implements Runnable, TranscoderListener {

    private final XmppConnectionService mXmppConnectionService;
    private final Message message;
    private final Uri uri;
    private final String type;
    private final UiCallback<Message> callback;
    private final boolean isVideoMessage;
    private final long originalFileSize;
    private int currentProgress = -1;

    AttachFileToConversationRunnable(XmppConnectionService xmppConnectionService, Uri uri, String type, Message message, UiCallback<Message> callback) {
        this.uri = uri;
        this.type = type;
        this.mXmppConnectionService = xmppConnectionService;
        this.message = message;
        this.callback = callback;
        final String mimeType = MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type);
        final int autoAcceptFileSize = mXmppConnectionService.getResources().getInteger(R.integer.auto_accept_filesize);
        this.originalFileSize = FileBackend.getFileSize(mXmppConnectionService, uri);
        this.isVideoMessage = (mimeType != null && mimeType.startsWith("video/")) && originalFileSize > autoAcceptFileSize && !"uncompressed".equals(getVideoCompression());
    }

    boolean isVideoMessage() {
        return this.isVideoMessage;
    }

    private void processAsFile() {
        final String path = mXmppConnectionService.getFileBackend().getOriginalPath(uri);
        if (path != null && !FileBackend.isPathBlacklisted(path)) {
            message.setRelativeFilePath(path);
            mXmppConnectionService.getFileBackend().updateFileParams(message);
            if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                mXmppConnectionService.getPgpEngine().encrypt(message, callback);
            } else {
                mXmppConnectionService.sendMessage(message);
                callback.success(message);
            }
        } else {
            try {
                mXmppConnectionService.getFileBackend().copyFileToPrivateStorage(message, uri, type);
                mXmppConnectionService.getFileBackend().updateFileParams(message);
                if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
                    final PgpEngine pgpEngine = mXmppConnectionService.getPgpEngine();
                    if (pgpEngine != null) {
                        pgpEngine.encrypt(message, callback);
                    } else if (callback != null) {
                        callback.error(R.string.unable_to_connect_to_keychain, null);
                    }
                } else {
                    mXmppConnectionService.sendMessage(message);
                    callback.success(message);
                }
            } catch (FileBackend.FileCopyException e) {
                callback.error(e.getResId(), message);
            }
        }
    }

    private void processAsVideo() throws FileNotFoundException {
        Log.d(Config.LOGTAG, "processing file as video");
        mXmppConnectionService.startForcingForegroundNotification();
        mXmppConnectionService.getFileBackend().setupRelativeFilePath(message, String.format("%s.%s", message.getUuid(), "mp4"));
        final DownloadableFile file = mXmppConnectionService.getFileBackend().getFile(message);
        if (Objects.requireNonNull(file.getParentFile()).mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directory for video file");
        }

        final boolean highQuality = "720".equals(getVideoCompression());

        final Future<Void> future = Transcoder.into(file.getAbsolutePath()).
                addDataSource(mXmppConnectionService, uri)
                .setVideoTrackStrategy(highQuality ? TranscoderStrategies.VIDEO_720P : TranscoderStrategies.VIDEO_360P)
                .setAudioTrackStrategy(highQuality ? TranscoderStrategies.AUDIO_HQ : TranscoderStrategies.AUDIO_MQ)
                .setListener(this)
                .transcode();
        try {
            future.get();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Error) {
                mXmppConnectionService.stopForcingForegroundNotification();
                processAsFile();
            } else {
                Log.d(Config.LOGTAG, "ignoring execution exception. Should get handled by onTranscodeFiled() instead", e);
            }
        }
    }

    @Override
    public void onTranscodeProgress(double progress) {
        final int p = (int) Math.round(progress * 100);
        if (p > currentProgress) {
            currentProgress = p;
            mXmppConnectionService.getNotificationService().updateFileAddingNotification(p, message);
        }
    }

    @Override
    public void onTranscodeCompleted(int successCode) {
        mXmppConnectionService.stopForcingForegroundNotification();
        final File file = mXmppConnectionService.getFileBackend().getFile(message);
        long convertedFileSize = mXmppConnectionService.getFileBackend().getFile(message).getSize();
        Log.d(Config.LOGTAG, "originalFileSize=" + originalFileSize + " convertedFileSize=" + convertedFileSize);
        if (originalFileSize != 0 && convertedFileSize >= originalFileSize) {
            if (file.delete()) {
                Log.d(Config.LOGTAG, "original file size was smaller. deleting and processing as file");
                processAsFile();
                return;
            } else {
                Log.d(Config.LOGTAG, "unable to delete converted file");
            }
        }
        mXmppConnectionService.getFileBackend().updateFileParams(message);
        if (message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            mXmppConnectionService.getPgpEngine().encrypt(message, callback);
        } else {
            mXmppConnectionService.sendMessage(message);
            callback.success(message);
        }
    }

    @Override
    public void onTranscodeCanceled() {
        mXmppConnectionService.stopForcingForegroundNotification();
        processAsFile();
    }

    @Override
    public void onTranscodeFailed(@NonNull @NotNull Throwable exception) {
        mXmppConnectionService.stopForcingForegroundNotification();
        Log.d(Config.LOGTAG, "video transcoding failed", exception);
        processAsFile();
    }

    @Override
    public void run() {
        if (this.isVideoMessage()) {
            try {
                processAsVideo();
            } catch (FileNotFoundException e) {
                processAsFile();
            }
        } else {
            processAsFile();
        }
    }

    private String getVideoCompression() {
        return getVideoCompression(mXmppConnectionService);
    }

    public static String getVideoCompression(final Context context) {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getString("video_compression", context.getResources().getString(R.string.video_compression));
    }
}
