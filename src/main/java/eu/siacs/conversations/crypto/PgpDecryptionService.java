package eu.siacs.conversations.crypto;

import android.app.PendingIntent;
import android.content.Intent;
import android.util.Log;

import org.openintents.openpgp.OpenPgpMetadata;
import org.openintents.openpgp.util.OpenPgpApi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.http.HttpConnectionManager;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.MimeUtils;

public class PgpDecryptionService {

	protected final ArrayDeque<Message> messages = new ArrayDeque<>();
	protected final HashSet<Message> pendingNotifications = new HashSet<>();
	private final XmppConnectionService mXmppConnectionService;
	private OpenPgpApi openPgpApi = null;
	private Message currentMessage;
	private PendingIntent pendingIntent;
	private Intent userInteractionResult;


	public PgpDecryptionService(XmppConnectionService service) {
		this.mXmppConnectionService = service;
	}

	public synchronized boolean decrypt(final Message message, boolean notify) {
		messages.add(message);
		if (notify && pendingIntent == null) {
			pendingNotifications.add(message);
			continueDecryption();
			return false;
		} else {
			continueDecryption();
			return notify;
		}
	}

	public synchronized void decrypt(final List<Message> list) {
		for (Message message : list) {
			if (message.getEncryption() == Message.ENCRYPTION_PGP) {
				messages.add(message);
			}
		}
		continueDecryption();
	}

	public synchronized void discard(List<Message> discards) {
		this.messages.removeAll(discards);
		this.pendingNotifications.removeAll(discards);
	}

	public synchronized void discard(Message message) {
		this.messages.remove(message);
		this.pendingNotifications.remove(message);
	}

	public void giveUpCurrentDecryption() {
		Message message;
		synchronized (this) {
			if (currentMessage != null) {
				return;
			}
			message = messages.peekFirst();
			if (message == null) {
				return;
			}
			discard(message);
		}
		synchronized (message) {
			if (message.getEncryption() == Message.ENCRYPTION_PGP) {
				message.setEncryption(Message.ENCRYPTION_DECRYPTION_FAILED);
			}
		}
		mXmppConnectionService.updateMessage(message, false);
		continueDecryption(true);
	}

	protected synchronized void decryptNext() {
		if (pendingIntent == null
				&& getOpenPgpApi() != null
				&& (currentMessage = messages.poll()) != null) {
			new Thread(new Runnable() {
				@Override
				public void run() {
					executeApi(currentMessage);
					decryptNext();
				}
			}).start();
		}
	}

	public synchronized void continueDecryption(boolean resetPending) {
		if (resetPending) {
			this.pendingIntent = null;
		}
		continueDecryption();
	}

	public synchronized void continueDecryption(Intent userInteractionResult) {
		this.pendingIntent = null;
		this.userInteractionResult = userInteractionResult;
		continueDecryption();
	}

	public synchronized void continueDecryption() {
		if (currentMessage == null) {
			decryptNext();
		}
	}

	private synchronized OpenPgpApi getOpenPgpApi() {
		if (openPgpApi == null) {
			this.openPgpApi = mXmppConnectionService.getOpenPgpApi();
		}
		return this.openPgpApi;
	}

	private void executeApi(Message message) {
		boolean skipNotificationPush = false;
		synchronized (message) {
			Intent params = userInteractionResult != null ? userInteractionResult : new Intent();
			params.setAction(OpenPgpApi.ACTION_DECRYPT_VERIFY);
			if (message.getType() == Message.TYPE_TEXT) {
				InputStream is = new ByteArrayInputStream(message.getBody().getBytes());
				final OutputStream os = new ByteArrayOutputStream();
				Intent result = getOpenPgpApi().executeApi(params, is, os);
				switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
					case OpenPgpApi.RESULT_CODE_SUCCESS:
						try {
							os.flush();
							final String body = os.toString();
							message.setBody(body);
							message.setEncryption(Message.ENCRYPTION_DECRYPTED);
							final HttpConnectionManager manager = mXmppConnectionService.getHttpConnectionManager();
							if (message.trusted()
									&& message.treatAsDownloadable()
									&& manager.getAutoAcceptFileSize() > 0) {
								manager.createNewDownloadConnection(message);
							}
						} catch (IOException e) {
							message.setEncryption(Message.ENCRYPTION_DECRYPTION_FAILED);
						}
						mXmppConnectionService.updateMessage(message);
						break;
					case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
						synchronized (PgpDecryptionService.this) {
							PendingIntent pendingIntent = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
							messages.addFirst(message);
							currentMessage = null;
							storePendingIntent(pendingIntent);
						}
						break;
					case OpenPgpApi.RESULT_CODE_ERROR:
						message.setEncryption(Message.ENCRYPTION_DECRYPTION_FAILED);
						mXmppConnectionService.updateMessage(message);
						break;
				}
			} else if (message.isFileOrImage()) {
				try {
					final DownloadableFile inputFile = mXmppConnectionService.getFileBackend().getFile(message, false);
					final DownloadableFile outputFile = mXmppConnectionService.getFileBackend().getFile(message, true);
					if (outputFile.getParentFile().mkdirs()) {
						Log.d(Config.LOGTAG,"created parent directories for "+outputFile.getAbsolutePath());
					}
					outputFile.createNewFile();
					InputStream is = new FileInputStream(inputFile);
					OutputStream os = new FileOutputStream(outputFile);
					Intent result = getOpenPgpApi().executeApi(params, is, os);
					switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
						case OpenPgpApi.RESULT_CODE_SUCCESS:
							OpenPgpMetadata openPgpMetadata = result.getParcelableExtra(OpenPgpApi.RESULT_METADATA);
							String originalFilename = openPgpMetadata.getFilename();
							String originalExtension = originalFilename == null ? null : MimeUtils.extractRelevantExtension(originalFilename);
							if (originalExtension != null && MimeUtils.extractRelevantExtension(outputFile.getName()) == null) {
								Log.d(Config.LOGTAG,"detected original filename during pgp decryption");
								final String mime = MimeUtils.guessMimeTypeFromExtension(originalExtension);
								final String filename = outputFile.getName()+"."+originalExtension;
								final File fixedFile = mXmppConnectionService.getFileBackend().getStorageLocation(filename,mime);
								if (fixedFile.getParentFile().mkdirs()) {
									Log.d(Config.LOGTAG,"created parent directories for "+fixedFile.getAbsolutePath());
								}
								synchronized (mXmppConnectionService.FILENAMES_TO_IGNORE_DELETION) {
									mXmppConnectionService.FILENAMES_TO_IGNORE_DELETION.add(outputFile.getAbsolutePath());
								}
								if (outputFile.renameTo(fixedFile)) {
									Log.d(Config.LOGTAG, "renamed " + outputFile.getAbsolutePath() + " to " + fixedFile.getAbsolutePath());
									message.setRelativeFilePath(fixedFile.getAbsolutePath());
								}
							}
							final String url = message.getFileParams().url;
							message.setEncryption(Message.ENCRYPTION_DECRYPTED);
							mXmppConnectionService.getFileBackend().updateFileParams(message, url);
							mXmppConnectionService.updateMessage(message);
							if (!inputFile.delete()) {
								Log.w(Config.LOGTAG,"unable to delete pgp encrypted source file "+inputFile.getAbsolutePath());
							}
							skipNotificationPush = true;
							mXmppConnectionService.getFileBackend().updateMediaScanner(outputFile, () -> notifyIfPending(message));
							break;
						case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
							synchronized (PgpDecryptionService.this) {
								PendingIntent pendingIntent = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
								messages.addFirst(message);
								currentMessage = null;
								storePendingIntent(pendingIntent);
							}
							break;
						case OpenPgpApi.RESULT_CODE_ERROR:
							message.setEncryption(Message.ENCRYPTION_DECRYPTION_FAILED);
							mXmppConnectionService.updateMessage(message);
							break;
					}
				} catch (final IOException e) {
					message.setEncryption(Message.ENCRYPTION_DECRYPTION_FAILED);
					mXmppConnectionService.updateMessage(message);
				}
			}
		}
		if (!skipNotificationPush) {
			notifyIfPending(message);
		}
	}

	private synchronized void notifyIfPending(Message message) {
		if (pendingNotifications.remove(message)) {
			mXmppConnectionService.getNotificationService().push(message);
		}
	}

	private void storePendingIntent(PendingIntent pendingIntent) {
		this.pendingIntent = pendingIntent;
		mXmppConnectionService.updateConversationUi();
	}

	public synchronized boolean hasPendingIntent(Conversation conversation) {
		if (pendingIntent == null) {
			return false;
		} else {
			for (Message message : messages) {
				if (message.getConversation() == conversation) {
					return true;
				}
			}
			return false;
		}
	}

	public PendingIntent getPendingIntent() {
		return pendingIntent;
	}

	public boolean isConnected() {
		return getOpenPgpApi() != null;
	}
}
