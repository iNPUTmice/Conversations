package eu.siacs.conversations.crypto.axolotl;

import android.util.Log;
import android.util.LruCache;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;

public class SQLiteAxolotlStore implements SignalProtocolStore {

	public static final String PREKEY_TABLENAME = "prekeys";
	public static final String SIGNED_PREKEY_TABLENAME = "signed_prekeys";
	public static final String SESSION_TABLENAME = "sessions";
	public static final String IDENTITIES_TABLENAME = "identities";
	public static final String ACCOUNT = "account";
	public static final String DEVICE_ID = "device_id";
	public static final String ID = "id";
	public static final String KEY = "key";
	public static final String FINGERPRINT = "fingerprint";
	public static final String NAME = "name";
	public static final String TRUSTED = "trusted"; //no longer used
	public static final String TRUST = "trust";
	public static final String ACTIVE = "active";
	public static final String LAST_ACTIVATION = "last_activation";
	public static final String OWN = "ownkey";
	public static final String CERTIFICATE = "certificate";

	public static final String JSONKEY_REGISTRATION_ID = "axolotl_reg_id";
	public static final String JSONKEY_CURRENT_PREKEY_ID = "axolotl_cur_prekey_id";

	private static final int NUM_TRUSTS_TO_CACHE = 100;

	private final Account account;
	private final XmppConnectionService mXmppConnectionService;

	private IdentityKeyPair identityKeyPair;
	private int localRegistrationId;
	private int currentPreKeyId = 0;

	private final HashSet<Integer> preKeysMarkedForRemoval = new HashSet<>();

	private final LruCache<String, FingerprintStatus> trustCache =
			new LruCache<String, FingerprintStatus>(NUM_TRUSTS_TO_CACHE) {
				@Override
				protected FingerprintStatus create(String fingerprint) {
					return mXmppConnectionService.databaseBackend.getFingerprintStatus(account, fingerprint);
				}
			};

	private static IdentityKeyPair generateIdentityKeyPair() {
		Log.i(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Generating axolotl IdentityKeyPair...");
		ECKeyPair identityKeyPairKeys = Curve.generateKeyPair();
		return new IdentityKeyPair(new IdentityKey(identityKeyPairKeys.getPublicKey()),
				identityKeyPairKeys.getPrivateKey());
	}

	private static int generateRegistrationId() {
		Log.i(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Generating axolotl registration ID...");
		return KeyHelper.generateRegistrationId(true);
	}

	public SQLiteAxolotlStore(Account account, XmppConnectionService service) {
		this.account = account;
		this.mXmppConnectionService = service;
		this.localRegistrationId = loadRegistrationId();
		this.currentPreKeyId = loadCurrentPreKeyId();
	}

	public int getCurrentPreKeyId() {
		return currentPreKeyId;
	}

	// --------------------------------------
	// IdentityKeyStore
	// --------------------------------------

	private IdentityKeyPair loadIdentityKeyPair() {
		synchronized (mXmppConnectionService) {
			IdentityKeyPair ownKey = mXmppConnectionService.databaseBackend.loadOwnIdentityKeyPair(account);

			if (ownKey != null) {
				return ownKey;
			} else {
				Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Could not retrieve own IdentityKeyPair");
				ownKey = generateIdentityKeyPair();
				mXmppConnectionService.databaseBackend.storeOwnIdentityKeyPair(account, ownKey);
			}
			return ownKey;
		}
	}

	private int loadRegistrationId() {
		return loadRegistrationId(false);
	}

	private int loadRegistrationId(boolean regenerate) {
		String regIdString = this.account.getKey(JSONKEY_REGISTRATION_ID);
		int reg_id;
		if (!regenerate && regIdString != null) {
			reg_id = Integer.valueOf(regIdString);
		} else {
			Log.i(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Could not retrieve axolotl registration id for account " + account.getJid());
			reg_id = generateRegistrationId();
			boolean success = this.account.setKey(JSONKEY_REGISTRATION_ID, Integer.toString(reg_id));
			if (success) {
				mXmppConnectionService.databaseBackend.updateAccount(account);
			} else {
				Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to write new key to the database!");
			}
		}
		return reg_id;
	}

	private int loadCurrentPreKeyId() {
		String prekeyIdString = this.account.getKey(JSONKEY_CURRENT_PREKEY_ID);
		int prekey_id;
		if (prekeyIdString != null) {
			prekey_id = Integer.valueOf(prekeyIdString);
		} else {
			Log.w(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Could not retrieve current prekey id for account " + account.getJid());
			prekey_id = 0;
		}
		return prekey_id;
	}

	public void regenerate() {
		mXmppConnectionService.databaseBackend.wipeAxolotlDb(account);
		trustCache.evictAll();
		account.setKey(JSONKEY_CURRENT_PREKEY_ID, Integer.toString(0));
		identityKeyPair = loadIdentityKeyPair();
		localRegistrationId = loadRegistrationId(true);
		currentPreKeyId = 0;
		mXmppConnectionService.updateAccountUi();
	}

	/**
	 * Get the local client's identity key pair.
	 *
	 * @return The local client's persistent identity key pair.
	 */
	@Override
	public IdentityKeyPair getIdentityKeyPair() {
		if (identityKeyPair == null) {
			identityKeyPair = loadIdentityKeyPair();
		}
		return identityKeyPair;
	}

	/**
	 * Return the local client's registration ID.
	 * <p/>
	 * Clients should maintain a registration ID, a random number
	 * between 1 and 16380 that's generated once at install time.
	 *
	 * @return the local client's registration ID.
	 */
	@Override
	public int getLocalRegistrationId() {
		return localRegistrationId;
	}

	/**
	 * Save a remote client's identity key
	 * <p/>
	 * Store a remote client's identity key as trusted.
	 *
	 * @param address     The address of the remote client.
	 * @param identityKey The remote client's identity key.
	 * @return true on success
	 */
	@Override
	public boolean saveIdentity(SignalProtocolAddress address, IdentityKey identityKey) {
		if (!mXmppConnectionService.databaseBackend.loadIdentityKeys(account, address.getName()).contains(identityKey)) {
			String fingerprint = CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
			FingerprintStatus status = getFingerprintStatus(fingerprint);
			if (status == null) {
				if (mXmppConnectionService.blindTrustBeforeVerification() && !account.getAxolotlService().hasVerifiedKeys(address.getName())) {
					Log.d(Config.LOGTAG,account.getJid().asBareJid()+": blindly trusted "+fingerprint+" of "+address.getName());
					status = FingerprintStatus.createActiveTrusted();
				} else {
					status = FingerprintStatus.createActiveUndecided();
				}
			} else {
				status = status.toActive();
			}
			mXmppConnectionService.databaseBackend.storeIdentityKey(account, address.getName(), identityKey, status);
			trustCache.remove(fingerprint);
		}
		return true;
	}

	/**
	 * Verify a remote client's identity key.
	 * <p/>
	 * Determine whether a remote client's identity is trusted.  Convention is
	 * that the TextSecure protocol is 'trust on first use.'  This means that
	 * an identity key is considered 'trusted' if there is no entry for the recipient
	 * in the local store, or if it matches the saved key for a recipient in the local
	 * store.  Only if it mismatches an entry in the local store is it considered
	 * 'untrusted.'
	 *
	 * @param identityKey The identity key to verify.
	 * @return true if trusted, false if untrusted.
	 */
	@Override
	public boolean isTrustedIdentity(SignalProtocolAddress address, IdentityKey identityKey, Direction direction) {
		return true;
	}

	public FingerprintStatus getFingerprintStatus(String fingerprint) {
		return (fingerprint == null)? null : trustCache.get(fingerprint);
	}

	public void setFingerprintStatus(String fingerprint, FingerprintStatus status) {
		mXmppConnectionService.databaseBackend.setIdentityKeyTrust(account, fingerprint, status);
		trustCache.remove(fingerprint);
	}

	public void setFingerprintCertificate(String fingerprint, X509Certificate x509Certificate) {
		mXmppConnectionService.databaseBackend.setIdentityKeyCertificate(account, fingerprint, x509Certificate);
	}

	public X509Certificate getFingerprintCertificate(String fingerprint) {
		return mXmppConnectionService.databaseBackend.getIdentityKeyCertifcate(account, fingerprint);
	}

	public Set<IdentityKey> getContactKeysWithTrust(String bareJid, FingerprintStatus status) {
		return mXmppConnectionService.databaseBackend.loadIdentityKeys(account, bareJid, status);
	}

	public long getContactNumTrustedKeys(String bareJid) {
		return mXmppConnectionService.databaseBackend.numTrustedKeys(account, bareJid);
	}

	// --------------------------------------
	// SessionStore
	// --------------------------------------

	/**
	 * Returns a copy of the {@link SessionRecord} corresponding to the recipientId + deviceId tuple,
	 * or a new SessionRecord if one does not currently exist.
	 * <p/>
	 * It is important that implementations return a copy of the current durable information.  The
	 * returned SessionRecord may be modified, but those changes should not have an effect on the
	 * durable session state (what is returned by subsequent calls to this method) without the
	 * store method being called here first.
	 *
	 * @param address The name and device ID of the remote client.
	 * @return a copy of the SessionRecord corresponding to the recipientId + deviceId tuple, or
	 * a new SessionRecord if one does not currently exist.
	 */
	@Override
	public SessionRecord loadSession(SignalProtocolAddress address) {
		SessionRecord session = mXmppConnectionService.databaseBackend.loadSession(this.account, address);
		return (session != null) ? session : new SessionRecord();
	}

	/**
	 * Returns all known devices with active sessions for a recipient
	 *
	 * @param name the name of the client.
	 * @return all known sub-devices with active sessions.
	 */
	@Override
	public List<Integer> getSubDeviceSessions(String name) {
		return mXmppConnectionService.databaseBackend.getSubDeviceSessions(account,
				new SignalProtocolAddress(name, 0));
	}


	public List<String> getKnownAddresses() {
		return mXmppConnectionService.databaseBackend.getKnownSignalAddresses(account);
	}
	/**
	 * Commit to storage the {@link SessionRecord} for a given recipientId + deviceId tuple.
	 *
	 * @param address the address of the remote client.
	 * @param record  the current SessionRecord for the remote client.
	 */
	@Override
	public void storeSession(SignalProtocolAddress address, SessionRecord record) {
		mXmppConnectionService.databaseBackend.storeSession(account, address, record);
	}

	/**
	 * Determine whether there is a committed {@link SessionRecord} for a recipientId + deviceId tuple.
	 *
	 * @param address the address of the remote client.
	 * @return true if a {@link SessionRecord} exists, false otherwise.
	 */
	@Override
	public boolean containsSession(SignalProtocolAddress address) {
		return mXmppConnectionService.databaseBackend.containsSession(account, address);
	}

	/**
	 * Remove a {@link SessionRecord} for a recipientId + deviceId tuple.
	 *
	 * @param address the address of the remote client.
	 */
	@Override
	public void deleteSession(SignalProtocolAddress address) {
		mXmppConnectionService.databaseBackend.deleteSession(account, address);
	}

	/**
	 * Remove the {@link SessionRecord}s corresponding to all devices of a recipientId.
	 *
	 * @param name the name of the remote client.
	 */
	@Override
	public void deleteAllSessions(String name) {
		SignalProtocolAddress address = new SignalProtocolAddress(name, 0);
		mXmppConnectionService.databaseBackend.deleteAllSessions(account,
				address);
	}

	// --------------------------------------
	// PreKeyStore
	// --------------------------------------

	/**
	 * Load a local PreKeyRecord.
	 *
	 * @param preKeyId the ID of the local PreKeyRecord.
	 * @return the corresponding PreKeyRecord.
	 * @throws InvalidKeyIdException when there is no corresponding PreKeyRecord.
	 */
	@Override
	public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
		PreKeyRecord record = mXmppConnectionService.databaseBackend.loadPreKey(account, preKeyId);
		if (record == null) {
			throw new InvalidKeyIdException("No such PreKeyRecord: " + preKeyId);
		}
		return record;
	}

	/**
	 * Store a local PreKeyRecord.
	 *
	 * @param preKeyId the ID of the PreKeyRecord to store.
	 * @param record   the PreKeyRecord.
	 */
	@Override
	public void storePreKey(int preKeyId, PreKeyRecord record) {
		mXmppConnectionService.databaseBackend.storePreKey(account, record);
		currentPreKeyId = preKeyId;
		boolean success = this.account.setKey(JSONKEY_CURRENT_PREKEY_ID, Integer.toString(preKeyId));
		if (success) {
			mXmppConnectionService.databaseBackend.updateAccount(account);
		} else {
			Log.e(Config.LOGTAG, AxolotlService.getLogprefix(account) + "Failed to write new prekey id to the database!");
		}
	}

	/**
	 * @param preKeyId A PreKeyRecord ID.
	 * @return true if the store has a record for the preKeyId, otherwise false.
	 */
	@Override
	public boolean containsPreKey(int preKeyId) {
		return mXmppConnectionService.databaseBackend.containsPreKey(account, preKeyId);
	}

	/**
	 * Delete a PreKeyRecord from local storage.
	 *
	 * @param preKeyId The ID of the PreKeyRecord to remove.
	 */
	@Override
	public void removePreKey(int preKeyId) {
		Log.d(Config.LOGTAG,"mark prekey for removal "+preKeyId);
		synchronized (preKeysMarkedForRemoval) {
			preKeysMarkedForRemoval.add(preKeyId);
		}
	}


	public boolean flushPreKeys() {
		Log.d(Config.LOGTAG,"flushing pre keys");
		int count = 0;
		synchronized (preKeysMarkedForRemoval) {
			for(Integer preKeyId : preKeysMarkedForRemoval) {
				count += mXmppConnectionService.databaseBackend.deletePreKey(account, preKeyId);
			}
			preKeysMarkedForRemoval.clear();
		}
		return count > 0;
	}

	// --------------------------------------
	// SignedPreKeyStore
	// --------------------------------------

	/**
	 * Load a local SignedPreKeyRecord.
	 *
	 * @param signedPreKeyId the ID of the local SignedPreKeyRecord.
	 * @return the corresponding SignedPreKeyRecord.
	 * @throws InvalidKeyIdException when there is no corresponding SignedPreKeyRecord.
	 */
	@Override
	public SignedPreKeyRecord loadSignedPreKey(int signedPreKeyId) throws InvalidKeyIdException {
		SignedPreKeyRecord record = mXmppConnectionService.databaseBackend.loadSignedPreKey(account, signedPreKeyId);
		if (record == null) {
			throw new InvalidKeyIdException("No such SignedPreKeyRecord: " + signedPreKeyId);
		}
		return record;
	}

	/**
	 * Load all local SignedPreKeyRecords.
	 *
	 * @return All stored SignedPreKeyRecords.
	 */
	@Override
	public List<SignedPreKeyRecord> loadSignedPreKeys() {
		return mXmppConnectionService.databaseBackend.loadSignedPreKeys(account);
	}

	public int getSignedPreKeysCount() {
		return mXmppConnectionService.databaseBackend.getSignedPreKeysCount(account);
	}

	/**
	 * Store a local SignedPreKeyRecord.
	 *
	 * @param signedPreKeyId the ID of the SignedPreKeyRecord to store.
	 * @param record         the SignedPreKeyRecord.
	 */
	@Override
	public void storeSignedPreKey(int signedPreKeyId, SignedPreKeyRecord record) {
		mXmppConnectionService.databaseBackend.storeSignedPreKey(account, record);
	}

	/**
	 * @param signedPreKeyId A SignedPreKeyRecord ID.
	 * @return true if the store has a record for the signedPreKeyId, otherwise false.
	 */
	@Override
	public boolean containsSignedPreKey(int signedPreKeyId) {
		return mXmppConnectionService.databaseBackend.containsSignedPreKey(account, signedPreKeyId);
	}

	/**
	 * Delete a SignedPreKeyRecord from local storage.
	 *
	 * @param signedPreKeyId The ID of the SignedPreKeyRecord to remove.
	 */
	@Override
	public void removeSignedPreKey(int signedPreKeyId) {
		mXmppConnectionService.databaseBackend.deleteSignedPreKey(account, signedPreKeyId);
	}

	public void preVerifyFingerprint(Account account, String name, String fingerprint) {
		mXmppConnectionService.databaseBackend.storePreVerification(account,name,fingerprint,FingerprintStatus.createInactiveVerified());
	}
}
