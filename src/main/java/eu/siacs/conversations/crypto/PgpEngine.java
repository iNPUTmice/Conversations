package eu.siacs.conversations.crypto;

import android.app.PendingIntent;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.StringRes;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpApi.IOpenPgpCallback;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.UiCallback;
import eu.siacs.conversations.utils.AsciiArmor;

public class PgpEngine {
    private final OpenPgpApi api;
    private final XmppConnectionService mXmppConnectionService;

    public PgpEngine(OpenPgpApi api, XmppConnectionService service) {
        this.api = api;
        this.mXmppConnectionService = service;
    }

    private static void logError(Account account, OpenPgpError error) {
        if (error != null) {
            error.describeContents();
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": OpenKeychain error '" + error.getMessage() + "' code=" + error.getErrorId() + " class=" + error.getClass().getName());
        } else {
            Log.d(Config.LOGTAG, account.getJid().asBareJid().toString() + ": OpenKeychain error with no message");
        }
    }

    public void encrypt(final Message message, final UiCallback<Message> callback) {
        Intent params = new Intent();
        params.setAction(OpenPgpApi.ACTION_ENCRYPT);
        final Conversation conversation = (Conversation) message.getConversation();
        if (conversation.getMode() == Conversation.MODE_SINGLE) {
            long[] keys = {
                    conversation.getContact().getPgpKeyId(),
                    conversation.getAccount().getPgpId()
            };
            params.putExtra(OpenPgpApi.EXTRA_KEY_IDS, keys);
        } else {
            params.putExtra(OpenPgpApi.EXTRA_KEY_IDS, conversation.getMucOptions().getPgpKeyIds());
        }

        if (!message.needsUploading()) {
            params.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);
            String body;
            if (message.hasFileOnRemoteHost()) {
                body = message.getFileParams().url;
            } else {
                body = message.getBody();
            }
            InputStream is = new ByteArrayInputStream(body.getBytes());
            final OutputStream os = new ByteArrayOutputStream();
            api.executeApiAsync(params, is, os, result -> {
                switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                    case OpenPgpApi.RESULT_CODE_SUCCESS:
                        try {
                            os.flush();
                            final ArrayList<String> encryptedMessageBody = new ArrayList<>();
                            final String[] lines = os.toString().split("\n");
                            for (int i = 2; i < lines.length - 1; ++i) {
                                if (!lines[i].contains("Version")) {
                                    encryptedMessageBody.add(lines[i].trim());
                                }
                            }
                            message.setEncryptedBody(Joiner.on('\n').join(encryptedMessageBody));
                            message.setEncryption(Message.ENCRYPTION_DECRYPTED);
                            mXmppConnectionService.sendMessage(message);
                            callback.success(message);
                        } catch (IOException e) {
                            callback.error(R.string.openpgp_error, message);
                        }

                        break;
                    case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                        callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT), message);
                        break;
                    case OpenPgpApi.RESULT_CODE_ERROR:
                        OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                        String errorMessage = error != null ? error.getMessage() : null;
                        @StringRes final int res;
                        if (errorMessage != null && errorMessage.startsWith("Bad key for encryption")) {
                            res = R.string.bad_key_for_encryption;
                        } else {
                            res = R.string.openpgp_error;
                        }
                        logError(conversation.getAccount(), error);
                        callback.error(res, message);
                        break;
                }
            });
        } else {
            try {
                DownloadableFile inputFile = this.mXmppConnectionService
                        .getFileBackend().getFile(message, true);
                DownloadableFile outputFile = this.mXmppConnectionService
                        .getFileBackend().getFile(message, false);
                outputFile.getParentFile().mkdirs();
                outputFile.createNewFile();
                final InputStream is = new FileInputStream(inputFile);
                final OutputStream os = new FileOutputStream(outputFile);
                api.executeApiAsync(params, is, os, result -> {
                    switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                        case OpenPgpApi.RESULT_CODE_SUCCESS:
                            try {
                                os.flush();
                            } catch (IOException ignored) {
                                //ignored
                            }
                            FileBackend.close(os);
                            mXmppConnectionService.sendMessage(message);
                            callback.success(message);
                            break;
                        case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                            callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT), message);
                            break;
                        case OpenPgpApi.RESULT_CODE_ERROR:
                            logError(conversation.getAccount(), result.getParcelableExtra(OpenPgpApi.RESULT_ERROR));
                            callback.error(R.string.openpgp_error, message);
                            break;
                    }
                });
            } catch (final IOException e) {
                callback.error(R.string.openpgp_error, message);
            }
        }
    }

    public long fetchKeyId(final Account account, final String status, final String signature) {
        if (signature == null || api == null) {
            return 0;
        }
        final Intent params = new Intent();
        params.setAction(OpenPgpApi.ACTION_DECRYPT_VERIFY);
        try {
            params.putExtra(OpenPgpApi.RESULT_DETACHED_SIGNATURE, AsciiArmor.decode(signature));
        } catch (final Exception e) {
            Log.d(Config.LOGTAG, "unable to parse signature", e);
            return 0;
        }
        final InputStream is = new ByteArrayInputStream(Strings.nullToEmpty(status).getBytes());
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final Intent result = api.executeApi(params, is, os);
        switch (result.getIntExtra(OpenPgpApi.RESULT_CODE,
                OpenPgpApi.RESULT_CODE_ERROR)) {
            case OpenPgpApi.RESULT_CODE_SUCCESS:
                final OpenPgpSignatureResult sigResult = result.getParcelableExtra(OpenPgpApi.RESULT_SIGNATURE);
                //TODO unsure that sigResult.getResult() is either 1, 2 or 3
                if (sigResult != null) {
                    return sigResult.getKeyId();
                } else {
                    return 0;
                }
            case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                return 0;
            case OpenPgpApi.RESULT_CODE_ERROR:
                logError(account, result.getParcelableExtra(OpenPgpApi.RESULT_ERROR));
                return 0;
        }
        return 0;
    }

    public void chooseKey(final Account account, final UiCallback<Account> callback) {
        Intent p = new Intent();
        p.setAction(OpenPgpApi.ACTION_GET_SIGN_KEY_ID);
        api.executeApiAsync(p, null, null, result -> {
            switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                case OpenPgpApi.RESULT_CODE_SUCCESS:
                    callback.success(account);
                    return;
                case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                    callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT), account);
                    return;
                case OpenPgpApi.RESULT_CODE_ERROR:
                    logError(account, result.getParcelableExtra(OpenPgpApi.RESULT_ERROR));
                    callback.error(R.string.openpgp_error, account);
            }
        });
    }

    public void generateSignature(Intent intent, final Account account, String status, final UiCallback<String> callback) {
        if (account.getPgpId() == 0) {
            return;
        }
        Intent params = intent == null ? new Intent() : intent;
        params.setAction(OpenPgpApi.ACTION_CLEARTEXT_SIGN);
        params.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true);
        params.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, account.getPgpId());
        InputStream is = new ByteArrayInputStream(status.getBytes());
        final OutputStream os = new ByteArrayOutputStream();
        Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": signing status message \"" + status + "\"");
        api.executeApiAsync(params, is, os, result -> {
            switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                case OpenPgpApi.RESULT_CODE_SUCCESS:
                    final ArrayList<String> signature = new ArrayList<>();
                    try {
                        os.flush();
                        boolean sig = false;
                        for (final String line : Splitter.on('\n').split(os.toString())) {
                            if (sig) {
                                if (line.contains("END PGP SIGNATURE")) {
                                    sig = false;
                                } else {
                                    if (!line.contains("Version")) {
                                        signature.add(line.trim());
                                    }
                                }
                            }
                            if (line.contains("BEGIN PGP SIGNATURE")) {
                                sig = true;
                            }
                        }
                    } catch (IOException e) {
                        callback.error(R.string.openpgp_error, null);
                        return;
                    }
                    callback.success(Joiner.on('\n').join(signature));
                    return;
                case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                    callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT), status);
                    return;
                case OpenPgpApi.RESULT_CODE_ERROR:
                    OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                    if (error != null && "signing subkey not found!".equals(error.getMessage())) {
                        callback.error(0, null);
                    } else {
                        logError(account, error);
                        callback.error(R.string.unable_to_connect_to_keychain, null);
                    }
            }
        });
    }

    public void hasKey(final Contact contact, final UiCallback<Contact> callback) {
        Intent params = new Intent();
        params.setAction(OpenPgpApi.ACTION_GET_KEY);
        params.putExtra(OpenPgpApi.EXTRA_KEY_ID, contact.getPgpKeyId());
        api.executeApiAsync(params, null, null, new IOpenPgpCallback() {

            @Override
            public void onReturn(Intent result) {
                switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, 0)) {
                    case OpenPgpApi.RESULT_CODE_SUCCESS:
                        callback.success(contact);
                        return;
                    case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                        callback.userInputRequired(result.getParcelableExtra(OpenPgpApi.RESULT_INTENT), contact);
                        return;
                    case OpenPgpApi.RESULT_CODE_ERROR:
                        logError(contact.getAccount(), result.getParcelableExtra(OpenPgpApi.RESULT_ERROR));
                        callback.error(R.string.openpgp_error, contact);
                }
            }
        });
    }

    public PendingIntent getIntentForKey(long pgpKeyId) {
        Intent params = new Intent();
        params.setAction(OpenPgpApi.ACTION_GET_KEY);
        params.putExtra(OpenPgpApi.EXTRA_KEY_ID, pgpKeyId);
        Intent result = api.executeApi(params, null, null);
        return (PendingIntent) result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
    }
}
