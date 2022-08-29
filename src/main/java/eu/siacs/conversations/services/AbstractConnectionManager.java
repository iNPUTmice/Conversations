package eu.siacs.conversations.services;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.io.CipherOutputStream;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.utils.Compatibility;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

import static eu.siacs.conversations.entities.Transferable.VALID_CRYPTO_EXTENSIONS;

public class AbstractConnectionManager {

    private static final int UI_REFRESH_THRESHOLD = 250;
    private static final AtomicLong LAST_UI_UPDATE_CALL = new AtomicLong(0);
    protected XmppConnectionService mXmppConnectionService;

    public AbstractConnectionManager(XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }

    public static InputStream upgrade(DownloadableFile file, InputStream is) {
        if (file.getKey() != null && file.getIv() != null) {
            AEADBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            cipher.init(true, new AEADParameters(new KeyParameter(file.getKey()), 128, file.getIv()));
            return new CipherInputStream(is, cipher);
        } else {
            return is;
        }
    }


    //For progress tracking see:
    //https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/Progress.java

    public static RequestBody requestBody(final DownloadableFile file, final ProgressListener progressListener) {
        return new RequestBody() {

            @Override
            public long contentLength() {
                return file.getSize() + (file.getKey() != null ? 16 : 0);
            }

            @Nullable
            @Override
            public MediaType contentType() {
                return MediaType.parse(file.getMimeType());
            }

            @Override
            public void writeTo(final BufferedSink sink) throws IOException {
                long transmitted = 0;
                try (final Source source = Okio.source(upgrade(file, new FileInputStream(file)))) {
                    long read;
                    while ((read = source.read(sink.buffer(), 8196)) != -1) {
                        transmitted += read;
                        sink.flush();
                        progressListener.onProgress(transmitted);
                    }
                }
            }
        };
    }

    public interface ProgressListener {
        void onProgress(long progress);
    }

    public static OutputStream createOutputStream(DownloadableFile file, boolean append, boolean decrypt) {
        FileOutputStream os;
        try {
            os = new FileOutputStream(file, append);
            if (file.getKey() == null || !decrypt) {
                return os;
            }
        } catch (FileNotFoundException e) {
            Log.d(Config.LOGTAG, "unable to create output stream", e);
            return null;
        }
        try {
            AEADBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            cipher.init(false, new AEADParameters(new KeyParameter(file.getKey()), 128, file.getIv()));
            return new CipherOutputStream(os, cipher);
        } catch (Exception e) {
            Log.d(Config.LOGTAG, "unable to create cipher output stream", e);
            return null;
        }
    }

    public XmppConnectionService getXmppConnectionService() {
        return this.mXmppConnectionService;
    }

    public long getAutoAcceptFileSize() {
        final long autoAcceptFileSize = this.mXmppConnectionService.getLongPreference("auto_accept_file_size", R.integer.auto_accept_filesize);
        return autoAcceptFileSize <= 0 ? -1 : autoAcceptFileSize;
    }

    public boolean hasStoragePermission() {
        return Compatibility.hasStoragePermission(mXmppConnectionService);
    }

    public void updateConversationUi(boolean force) {
        synchronized (LAST_UI_UPDATE_CALL) {
            if (force || SystemClock.elapsedRealtime() - LAST_UI_UPDATE_CALL.get() >= UI_REFRESH_THRESHOLD) {
                LAST_UI_UPDATE_CALL.set(SystemClock.elapsedRealtime());
                mXmppConnectionService.updateConversationUi();
            }
        }
    }

    public PowerManager.WakeLock createWakeLock(final String name) {
        final PowerManager powerManager = ContextCompat.getSystemService(mXmppConnectionService, PowerManager.class);
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name);
    }

    public static class Extension {
        public final String main;
        public final String secondary;

        private Extension(String main, String secondary) {
            this.main = main;
            this.secondary = secondary;
        }

        public String getExtension() {
            if (VALID_CRYPTO_EXTENSIONS.contains(main)) {
                return secondary;
            } else {
                return main;
            }
        }

        public static Extension of(String path) {
            //TODO accept List<String> pathSegments
            final int pos = path.lastIndexOf('/');
            final String filename = path.substring(pos + 1).toLowerCase();
            final String[] parts = filename.split("\\.");
            final String main = parts.length >= 2 ? parts[parts.length - 1] : null;
            final String secondary = parts.length >= 3 ? parts[parts.length - 2] : null;
            return new Extension(main, secondary);
        }
    }
}
