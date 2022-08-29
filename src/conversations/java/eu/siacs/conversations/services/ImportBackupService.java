package eu.siacs.conversations.services;

import static eu.siacs.conversations.utils.Compatibility.s;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.io.CountingInputStream;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.modes.AEADBlockCipher;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import javax.crypto.BadPaddingException;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.persistance.DatabaseBackend;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.ManageAccountActivity;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.SerialSingleThreadExecutor;
import eu.siacs.conversations.xmpp.Jid;

public class ImportBackupService extends Service {

    private static final int NOTIFICATION_ID = 21;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private final ImportBackupServiceBinder binder = new ImportBackupServiceBinder();
    private final SerialSingleThreadExecutor executor = new SerialSingleThreadExecutor(getClass().getSimpleName());
    private final Set<OnBackupProcessed> mOnBackupProcessedListeners = Collections.newSetFromMap(new WeakHashMap<>());
    private DatabaseBackend mDatabaseBackend;
    private NotificationManager notificationManager;

    private static int count(String input, char c) {
        int count = 0;
        for (char aChar : input.toCharArray()) {
            if (aChar == c) {
                ++count;
            }
        }
        return count;
    }

    @Override
    public void onCreate() {
        mDatabaseBackend = DatabaseBackend.getInstance(getBaseContext());
        notificationManager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        final String password = intent.getStringExtra("password");
        final Uri data = intent.getData();
        final Uri uri;
        if (data == null) {
            final String file = intent.getStringExtra("file");
            uri = file == null ? null : Uri.fromFile(new File(file));
        } else {
            uri = data;
        }

        if (password == null || password.isEmpty() || uri == null) {
            return START_NOT_STICKY;
        }
        if (running.compareAndSet(false, true)) {
            executor.execute(() -> {
                startForegroundService();
                final boolean success = importBackup(uri, password);
                stopForeground(true);
                running.set(false);
                if (success) {
                    notifySuccess();
                }
                stopSelf();
            });
        } else {
            Log.d(Config.LOGTAG, "backup already running");
        }
        return START_NOT_STICKY;
    }

    public boolean getLoadingState() {
        return running.get();
    }

    public void loadBackupFiles(final OnBackupFilesLoaded onBackupFilesLoaded) {
        executor.execute(() -> {
            final List<Jid> accounts = mDatabaseBackend.getAccountJids(false);
            final ArrayList<BackupFile> backupFiles = new ArrayList<>();
            final Set<String> apps = new HashSet<>(Arrays.asList("Conversations", "Quicksy", getString(R.string.app_name)));
            final List<File> directories = new ArrayList<>();
            for (final String app : apps) {
                directories.add(FileBackend.getLegacyBackupDirectory(app));
            }
            directories.add(FileBackend.getBackupDirectory(this));
            for (final File directory : directories) {
                if (!directory.exists() || !directory.isDirectory()) {
                    Log.d(Config.LOGTAG, "directory not found: " + directory.getAbsolutePath());
                    continue;
                }
                final File[] files = directory.listFiles();
                if (files == null) {
                    continue;
                }
                for (final File file : files) {
                    if (file.isFile() && file.getName().endsWith(".ceb")) {
                        try {
                            final BackupFile backupFile = BackupFile.read(file);
                            if (accounts.contains(backupFile.getHeader().getJid())) {
                                Log.d(Config.LOGTAG, "skipping backup for " + backupFile.getHeader().getJid());
                            } else {
                                backupFiles.add(backupFile);
                            }
                        } catch (IOException | IllegalArgumentException e) {
                            Log.d(Config.LOGTAG, "unable to read backup file ", e);
                        }
                    }
                }
            }
            Collections.sort(backupFiles, (a, b) -> a.header.getJid().toString().compareTo(b.header.getJid().toString()));
            onBackupFilesLoaded.onBackupFilesLoaded(backupFiles);
        });
    }

    private void startForegroundService() {
        startForeground(NOTIFICATION_ID, createImportBackupNotification(1, 0));
    }

    private void updateImportBackupNotification(final long total, final long current) {
        final int max;
        final int progress;
        if (total == 0) {
            max = 1;
            progress = 0;
        } else {
            max = 100;
            progress = (int) (current * 100 / total);
        }
        final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this);
        try {
            notificationManager.notify(NOTIFICATION_ID, createImportBackupNotification(max, progress));
        } catch (final RuntimeException e) {
            Log.d(Config.LOGTAG, "unable to make notification", e);
        }
    }

    private Notification createImportBackupNotification(final int max, final int progress) {
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getBaseContext(), "backup");
        mBuilder.setContentTitle(getString(R.string.restoring_backup))
                .setSmallIcon(R.drawable.ic_unarchive_white_24dp)
                .setProgress(max, progress, max == 1 && progress == 0);
        return mBuilder.build();
    }

    private boolean importBackup(final Uri uri, final String password) {
        Log.d(Config.LOGTAG, "importing backup from " + uri);
        final Stopwatch stopwatch = Stopwatch.createStarted();
        try {
            final SQLiteDatabase db = mDatabaseBackend.getWritableDatabase();
            final InputStream inputStream;
            final String path = uri.getPath();
            final long fileSize;
            if ("file".equals(uri.getScheme()) && path != null) {
                final File file = new File(path);
                inputStream = new FileInputStream(file);
                fileSize = file.length();
            } else {
                final Cursor returnCursor = getContentResolver().query(uri, null, null, null, null);
                if (returnCursor == null) {
                    fileSize = 0;
                } else {
                    returnCursor.moveToFirst();
                    fileSize = returnCursor.getLong(returnCursor.getColumnIndex(OpenableColumns.SIZE));
                    returnCursor.close();
                }
                inputStream = getContentResolver().openInputStream(uri);
            }
            if (inputStream == null) {
                synchronized (mOnBackupProcessedListeners) {
                    for (final OnBackupProcessed l : mOnBackupProcessedListeners) {
                        l.onBackupRestoreFailed();
                    }
                }
                return false;
            }
            final CountingInputStream countingInputStream = new CountingInputStream(inputStream);
            final DataInputStream dataInputStream = new DataInputStream(countingInputStream);
            final BackupFileHeader backupFileHeader = BackupFileHeader.read(dataInputStream);
            Log.d(Config.LOGTAG, backupFileHeader.toString());

            if (mDatabaseBackend.getAccountJids(false).contains(backupFileHeader.getJid())) {
                synchronized (mOnBackupProcessedListeners) {
                    for (OnBackupProcessed l : mOnBackupProcessedListeners) {
                        l.onAccountAlreadySetup();
                    }
                }
                return false;
            }

            final byte[] key = ExportBackupService.getKey(password, backupFileHeader.getSalt());

            final AEADBlockCipher cipher = new GCMBlockCipher(new AESEngine());
            cipher.init(false, new AEADParameters(new KeyParameter(key), 128, backupFileHeader.getIv()));
            final CipherInputStream cipherInputStream = new CipherInputStream(countingInputStream, cipher);

            final GZIPInputStream gzipInputStream = new GZIPInputStream(cipherInputStream);
            final BufferedReader reader = new BufferedReader(new InputStreamReader(gzipInputStream, Charsets.UTF_8));
            db.beginTransaction();
            String line;
            StringBuilder multiLineQuery = null;
            while ((line = reader.readLine()) != null) {
                int count = count(line, '\'');
                if (multiLineQuery != null) {
                    multiLineQuery.append('\n');
                    multiLineQuery.append(line);
                    if (count % 2 == 1) {
                        db.execSQL(multiLineQuery.toString());
                        multiLineQuery = null;
                        updateImportBackupNotification(fileSize, countingInputStream.getCount());
                    }
                } else {
                    if (count % 2 == 0) {
                        db.execSQL(line);
                        updateImportBackupNotification(fileSize, countingInputStream.getCount());
                    } else {
                        multiLineQuery = new StringBuilder(line);
                    }
                }
            }
            db.setTransactionSuccessful();
            db.endTransaction();
            final Jid jid = backupFileHeader.getJid();
            final Cursor countCursor = db.rawQuery("select count(messages.uuid) from messages join conversations on conversations.uuid=messages.conversationUuid join accounts on conversations.accountUuid=accounts.uuid where accounts.username=? and accounts.server=?", new String[]{jid.getEscapedLocal(), jid.getDomain().toEscapedString()});
            countCursor.moveToFirst();
            final int count = countCursor.getInt(0);
            Log.d(Config.LOGTAG, String.format("restored %d messages in %s", count, stopwatch.stop().toString()));
            countCursor.close();
            stopBackgroundService();
            synchronized (mOnBackupProcessedListeners) {
                for (OnBackupProcessed l : mOnBackupProcessedListeners) {
                    l.onBackupRestored();
                }
            }
            return true;
        } catch (final Exception e) {
            final Throwable throwable = e.getCause();
            final boolean reasonWasCrypto = throwable instanceof BadPaddingException || e instanceof ZipException;
            synchronized (mOnBackupProcessedListeners) {
                for (OnBackupProcessed l : mOnBackupProcessedListeners) {
                    if (reasonWasCrypto) {
                        l.onBackupDecryptionFailed();
                    } else {
                        l.onBackupRestoreFailed();
                    }
                }
            }
            Log.d(Config.LOGTAG, "error restoring backup " + uri, e);
            return false;
        }
    }

    private void notifySuccess() {
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getBaseContext(), "backup");
        mBuilder.setContentTitle(getString(R.string.notification_restored_backup_title))
                .setContentText(getString(R.string.notification_restored_backup_subtitle))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 145, new Intent(this, ManageAccountActivity.class), s()
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT))
                .setSmallIcon(R.drawable.ic_unarchive_white_24dp);
        notificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }

    private void stopBackgroundService() {
        Intent intent = new Intent(this, XmppConnectionService.class);
        stopService(intent);
    }

    public void removeOnBackupProcessedListener(OnBackupProcessed listener) {
        synchronized (mOnBackupProcessedListeners) {
            mOnBackupProcessedListeners.remove(listener);
        }
    }

    public void addOnBackupProcessedListener(OnBackupProcessed listener) {
        synchronized (mOnBackupProcessedListeners) {
            mOnBackupProcessedListeners.add(listener);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return this.binder;
    }

    public interface OnBackupFilesLoaded {
        void onBackupFilesLoaded(List<BackupFile> files);
    }

    public interface OnBackupProcessed {
        void onBackupRestored();

        void onBackupDecryptionFailed();

        void onBackupRestoreFailed();

        void onAccountAlreadySetup();
    }

    public static class BackupFile {
        private final Uri uri;
        private final BackupFileHeader header;

        private BackupFile(Uri uri, BackupFileHeader header) {
            this.uri = uri;
            this.header = header;
        }

        private static BackupFile read(File file) throws IOException {
            final FileInputStream fileInputStream = new FileInputStream(file);
            final DataInputStream dataInputStream = new DataInputStream(fileInputStream);
            BackupFileHeader backupFileHeader = BackupFileHeader.read(dataInputStream);
            fileInputStream.close();
            return new BackupFile(Uri.fromFile(file), backupFileHeader);
        }

        public static BackupFile read(final Context context, final Uri uri) throws IOException {
            final InputStream inputStream = context.getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                throw new FileNotFoundException();
            }
            final DataInputStream dataInputStream = new DataInputStream(inputStream);
            BackupFileHeader backupFileHeader = BackupFileHeader.read(dataInputStream);
            inputStream.close();
            return new BackupFile(uri, backupFileHeader);
        }

        public BackupFileHeader getHeader() {
            return header;
        }

        public Uri getUri() {
            return uri;
        }
    }

    public class ImportBackupServiceBinder extends Binder {
        public ImportBackupService getService() {
            return ImportBackupService.this;
        }
    }
}