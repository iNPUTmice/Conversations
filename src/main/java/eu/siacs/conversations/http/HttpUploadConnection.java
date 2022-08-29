package eu.siacs.conversations.http;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.services.AbstractConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HttpUploadConnection implements Transferable, AbstractConnectionManager.ProgressListener {

    static final List<String> WHITE_LISTED_HEADERS = Arrays.asList(
            "Authorization",
            "Cookie",
            "Expires"
    );

    private final HttpConnectionManager mHttpConnectionManager;
    private final XmppConnectionService mXmppConnectionService;
    private final Method method;
    private boolean delayed = false;
    private DownloadableFile file;
    private final Message message;
    private SlotRequester.Slot slot;
    private byte[] key = null;

    private long transmitted = 0;
    private Call mostRecentCall;
    private ListenableFuture<SlotRequester.Slot> slotFuture;

    public HttpUploadConnection(Message message, Method method, HttpConnectionManager httpConnectionManager) {
        this.message = message;
        this.method = method;
        this.mHttpConnectionManager = httpConnectionManager;
        this.mXmppConnectionService = httpConnectionManager.getXmppConnectionService();
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public int getStatus() {
        return STATUS_UPLOADING;
    }

    @Override
    public Long getFileSize() {
        return file == null ? null : file.getExpectedSize();
    }

    @Override
    public int getProgress() {
        if (file == null) {
            return 0;
        }
        return (int) ((((double) transmitted) / file.getExpectedSize()) * 100);
    }

    @Override
    public void cancel() {
        final ListenableFuture<SlotRequester.Slot> slotFuture = this.slotFuture;
        if (slotFuture != null && !slotFuture.isDone()) {
            if (slotFuture.cancel(true)) {
                Log.d(Config.LOGTAG,"cancelled slot requester");
            }
        }
        final Call call = this.mostRecentCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
            Log.d(Config.LOGTAG,"cancelled HTTP request");
        }
    }

    private void fail(String errorMessage) {
        finish();
        final Call call = this.mostRecentCall;
        final Future<SlotRequester.Slot> slotFuture = this.slotFuture;
        final boolean cancelled = (call != null && call.isCanceled()) || (slotFuture != null && slotFuture.isCancelled());
        mXmppConnectionService.markMessage(message, Message.STATUS_SEND_FAILED, cancelled ? Message.ERROR_MESSAGE_CANCELLED : errorMessage);
    }

    private void finish() {
        mHttpConnectionManager.finishUploadConnection(this);
        message.setTransferable(null);
    }

    public void init(boolean delay) {
        final Account account = message.getConversation().getAccount();
        this.file = mXmppConnectionService.getFileBackend().getFile(message, false);
        final String mime;
        if (message.getEncryption() == Message.ENCRYPTION_PGP || message.getEncryption() == Message.ENCRYPTION_DECRYPTED) {
            mime = "application/pgp-encrypted";
        } else {
            mime = this.file.getMimeType();
        }
        final long originalFileSize = file.getSize();
        this.delayed = delay;
        if (Config.ENCRYPT_ON_HTTP_UPLOADED
                || message.getEncryption() == Message.ENCRYPTION_AXOLOTL
                || message.getEncryption() == Message.ENCRYPTION_OTR) {
            this.key = new byte[44];
            mXmppConnectionService.getRNG().nextBytes(this.key);
            this.file.setKeyAndIv(this.key);
        }
        this.file.setExpectedSize(originalFileSize + (file.getKey() != null ? 16 : 0));
        message.resetFileParams();
        this.slotFuture = new SlotRequester(mXmppConnectionService).request(method, account, file, mime);
        Futures.addCallback(this.slotFuture, new FutureCallback<SlotRequester.Slot>() {
            @Override
            public void onSuccess(@Nullable SlotRequester.Slot result) {
                HttpUploadConnection.this.slot = result;
                try {
                    HttpUploadConnection.this.upload();
                } catch (final Exception e) {
                    fail(e.getMessage());
                }
            }

            @Override
            public void onFailure(@NonNull final Throwable throwable) {
                Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": unable to request slot", throwable);
                // TODO consider fall back to jingle in 1-on-1 chats with exactly one online presence
                fail(throwable.getMessage());
            }
        }, MoreExecutors.directExecutor());
        message.setTransferable(this);
        mXmppConnectionService.markMessage(message, Message.STATUS_UNSEND);
    }

    private void upload() {
        final OkHttpClient client = mHttpConnectionManager.buildHttpClient(
                slot.put,
                message.getConversation().getAccount(),
                0,
                true
        );
        final RequestBody requestBody = AbstractConnectionManager.requestBody(file, this);
        final Request request = new Request.Builder()
                .url(slot.put)
                .put(requestBody)
                .headers(slot.headers)
                .build();
        Log.d(Config.LOGTAG, "uploading file to " + slot.put);
        this.mostRecentCall = client.newCall(request);
        this.mostRecentCall.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, IOException e) {
                Log.d(Config.LOGTAG, "http upload failed", e);
                fail(e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response)  {
                final int code = response.code();
                if (code == 200 || code == 201) {
                    Log.d(Config.LOGTAG, "finished uploading file");
                    final String get;
                    if (key != null) {
                        get = AesGcmURL.toAesGcmUrl(slot.get.newBuilder().fragment(CryptoHelper.bytesToHex(key)).build());
                    } else {
                        get = slot.get.toString();
                    }
                    mXmppConnectionService.getFileBackend().updateFileParams(message, get);
                    mXmppConnectionService.getFileBackend().updateMediaScanner(file);
                    finish();
                    if (!message.isPrivateMessage()) {
                        message.setCounterpart(message.getConversation().getJid().asBareJid());
                    }
                    mXmppConnectionService.resendMessage(message, delayed);
                } else {
                    Log.d(Config.LOGTAG, "http upload failed because response code was " + code);
                    fail("http upload failed because response code was " + code);
                }
            }
        });
    }

    public Message getMessage() {
        return message;
    }

    @Override
    public void onProgress(final long progress) {
        this.transmitted = progress;
        mHttpConnectionManager.updateConversationUi(false);
    }
}