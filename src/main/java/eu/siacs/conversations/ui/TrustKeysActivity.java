package eu.siacs.conversations.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.databinding.DataBindingUtil;

import org.whispersystems.libsignal.IdentityKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.databinding.ActivityTrustKeysBinding;
import eu.siacs.conversations.databinding.KeysCardBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.IrregularUnicodeDetector;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnKeyStatusUpdated;


public class TrustKeysActivity extends OmemoActivity implements OnKeyStatusUpdated {
	private final Map<String, Boolean> ownKeysToTrust = new HashMap<>();
	private final Map<Jid, Map<String, Boolean>> foreignKeysToTrust = new HashMap<>();
	private final OnClickListener mCancelButtonListener = v -> {
		setResult(RESULT_CANCELED);
		finish();
	};
	private List<Jid> contactJids;
	private Account mAccount;
	private Conversation mConversation;
	private final OnClickListener mSaveButtonListener = v -> {
		commitTrusts();
		finishOk(false);
	};
	private final AtomicBoolean mUseCameraHintShown = new AtomicBoolean(false);
	private AxolotlService.FetchStatus lastFetchReport = AxolotlService.FetchStatus.SUCCESS;
	private Toast mUseCameraHintToast = null;
	private ActivityTrustKeysBinding binding;

	@Override
	protected void refreshUiReal() {
		invalidateOptionsMenu();
		populateView();
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.binding = DataBindingUtil.setContentView(this, R.layout.activity_trust_keys);
		this.contactJids = new ArrayList<>();
		for (String jid : getIntent().getStringArrayExtra("contacts")) {
			try {
				this.contactJids.add(Jid.of(jid));
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			}
		}

		binding.cancelButton.setOnClickListener(mCancelButtonListener);
		binding.saveButton.setOnClickListener(mSaveButtonListener);

		setSupportActionBar(binding.toolbar);
		configureActionBar(getSupportActionBar());

		if (savedInstanceState != null) {
			mUseCameraHintShown.set(savedInstanceState.getBoolean("camera_hint_shown", false));
		}
	}

	@Override
	public void onSaveInstanceState(Bundle savedInstanceState) {
		savedInstanceState.putBoolean("camera_hint_shown", mUseCameraHintShown.get());
		super.onSaveInstanceState(savedInstanceState);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.trust_keys, menu);
		MenuItem scanQrCode = menu.findItem(R.id.action_scan_qr_code);
		scanQrCode.setVisible((ownKeysToTrust.size() > 0 || foreignActuallyHasKeys()) && isCameraFeatureAvailable());
		return super.onCreateOptionsMenu(menu);
	}

	private void showCameraToast() {
		mUseCameraHintToast = Toast.makeText(this, R.string.use_camera_icon_to_scan_barcode, Toast.LENGTH_LONG);
		ActionBar actionBar = getSupportActionBar();
		mUseCameraHintToast.setGravity(Gravity.TOP | Gravity.END, 0, actionBar == null ? 0 : actionBar.getHeight());
		mUseCameraHintToast.show();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.action_scan_qr_code:
				if (hasPendingKeyFetches()) {
					Toast.makeText(this, R.string.please_wait_for_keys_to_be_fetched, Toast.LENGTH_SHORT).show();
				} else {
					ScanActivity.scan(this);
					//new IntentIntegrator(this).initiateScan(Arrays.asList("AZTEC","QR_CODE"));
					return true;
				}
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (mUseCameraHintToast != null) {
			mUseCameraHintToast.cancel();
		}
	}

	@Override
	protected void processFingerprintVerification(XmppUri uri) {
		if (mConversation != null
				&& mAccount != null
				&& uri.hasFingerprints()
				&& mAccount.getAxolotlService().getCryptoTargets(mConversation).contains(uri.getJid())) {
			boolean performedVerification = xmppConnectionService.verifyFingerprints(mAccount.getRoster().getContact(uri.getJid()), uri.getFingerprints());
			boolean keys = reloadFingerprints();
			if (performedVerification && !keys && !hasNoOtherTrustedKeys() && !hasPendingKeyFetches()) {
				Toast.makeText(this, R.string.all_omemo_keys_have_been_verified, Toast.LENGTH_SHORT).show();
				finishOk(false);
				return;
			} else if (performedVerification) {
				Toast.makeText(this, R.string.verified_fingerprints, Toast.LENGTH_SHORT).show();
			}
		} else {
			reloadFingerprints();
			Log.d(Config.LOGTAG, "xmpp uri was: " + uri.getJid() + " has Fingerprints: " + uri.hasFingerprints());
			Toast.makeText(this, R.string.barcode_does_not_contain_fingerprints_for_this_conversation, Toast.LENGTH_SHORT).show();
		}
		populateView();
	}

	private void populateView() {
		setTitle(getString(R.string.trust_omemo_fingerprints));
		binding.ownKeysDetails.removeAllViews();
		binding.foreignKeys.removeAllViews();
		boolean hasOwnKeys = false;
		boolean hasForeignKeys = false;
		for (final String fingerprint : ownKeysToTrust.keySet()) {
			hasOwnKeys = true;
			addFingerprintRowWithListeners(binding.ownKeysDetails, mAccount, fingerprint, false,
					FingerprintStatus.createActive(ownKeysToTrust.get(fingerprint)), false, false,
					(buttonView, isChecked) -> {
						ownKeysToTrust.put(fingerprint, isChecked);
						// own fingerprints have no impact on locked status.
					}
			);
		}

		synchronized (this.foreignKeysToTrust) {
			for (Map.Entry<Jid, Map<String, Boolean>> entry : foreignKeysToTrust.entrySet()) {
				hasForeignKeys = true;
				KeysCardBinding keysCardBinding = DataBindingUtil.inflate(getLayoutInflater(), R.layout.keys_card, binding.foreignKeys, false);
				final Jid jid = entry.getKey();
				keysCardBinding.foreignKeysTitle.setText(IrregularUnicodeDetector.style(this, jid));
				keysCardBinding.foreignKeysTitle.setOnClickListener(v -> switchToContactDetails(mAccount.getRoster().getContact(jid)));
				final Map<String, Boolean> fingerprints = entry.getValue();
				for (final String fingerprint : fingerprints.keySet()) {
					addFingerprintRowWithListeners(keysCardBinding.foreignKeysDetails, mAccount, fingerprint, false,
							FingerprintStatus.createActive(fingerprints.get(fingerprint)), false, false,
							(buttonView, isChecked) -> {
								fingerprints.put(fingerprint, isChecked);
								lockOrUnlockAsNeeded();
							}
					);
				}
				if (fingerprints.size() == 0) {
					keysCardBinding.noKeysToAccept.setVisibility(View.VISIBLE);
					if (hasNoOtherTrustedKeys(jid)) {
						if (!mAccount.getRoster().getContact(jid).mutualPresenceSubscription()) {
							keysCardBinding.noKeysToAccept.setText(R.string.error_no_keys_to_trust_presence);
						} else {
							keysCardBinding.noKeysToAccept.setText(R.string.error_no_keys_to_trust_server_error);
						}
					} else {
						keysCardBinding.noKeysToAccept.setText(getString(R.string.no_keys_just_confirm, mAccount.getRoster().getContact(jid).getDisplayName()));
					}
				} else {
					keysCardBinding.noKeysToAccept.setVisibility(View.GONE);
				}
				binding.foreignKeys.addView(keysCardBinding.foreignKeysCard);
			}
		}

		if ((hasOwnKeys || foreignActuallyHasKeys()) && isCameraFeatureAvailable() && mUseCameraHintShown.compareAndSet(false, true)) {
			showCameraToast();
		}

		binding.ownKeysTitle.setText(mAccount.getJid().asBareJid().toEscapedString());
		binding.ownKeysCard.setVisibility(hasOwnKeys ? View.VISIBLE : View.GONE);
		binding.foreignKeys.setVisibility(hasForeignKeys ? View.VISIBLE : View.GONE);
		if (hasPendingKeyFetches()) {
			setFetching();
			lock();
		} else {
			if (!hasForeignKeys && hasNoOtherTrustedKeys()) {
				binding.keyErrorMessageCard.setVisibility(View.VISIBLE);
				boolean lastReportWasError = lastFetchReport == AxolotlService.FetchStatus.ERROR;
				boolean errorFetchingBundle = mAccount.getAxolotlService().fetchMapHasErrors(contactJids);
				boolean errorFetchingDeviceList = mAccount.getAxolotlService().hasErrorFetchingDeviceList(contactJids);
				boolean anyWithoutMutualPresenceSubscription = anyWithoutMutualPresenceSubscription(contactJids);
				if (errorFetchingDeviceList) {
					binding.keyErrorMessage.setVisibility(View.VISIBLE);
					binding.keyErrorMessage.setText(R.string.error_trustkey_device_list);
				} else if (errorFetchingBundle || lastReportWasError) {
					binding.keyErrorMessage.setVisibility(View.VISIBLE);
					binding.keyErrorMessage.setText(R.string.error_trustkey_bundle);
				} else {
					binding.keyErrorMessage.setVisibility(View.GONE);
				}
				this.binding.keyErrorHintMutual.setVisibility(anyWithoutMutualPresenceSubscription ? View.VISIBLE : View.GONE);
				Contact contact = mAccount.getRoster().getContact(contactJids.get(0));
				binding.keyErrorGeneral.setText(getString(R.string.error_trustkey_general, getString(R.string.app_name), contact.getDisplayName()));
				binding.ownKeysDetails.removeAllViews();
				if (OmemoSetting.isAlways()) {
					binding.disableButton.setVisibility(View.GONE);
				} else {
					binding.disableButton.setVisibility(View.VISIBLE);
					binding.disableButton.setOnClickListener(this::disableEncryptionDialog);
				}
				binding.ownKeysCard.setVisibility(View.GONE);
				binding.foreignKeys.removeAllViews();
				binding.foreignKeys.setVisibility(View.GONE);
			}
			lockOrUnlockAsNeeded();
			setDone();
		}
	}

	private void disableEncryptionDialog(View view) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.disable_encryption);
		builder.setMessage(R.string.disable_encryption_message);
		builder.setPositiveButton(R.string.disable_now, (dialog, which) -> {
			mConversation.setNextEncryption(Message.ENCRYPTION_NONE);
			xmppConnectionService.updateConversation(mConversation);
			finishOk(true);
		});
		builder.setNegativeButton(R.string.cancel, null);
		builder.create().show();
	}

	private boolean anyWithoutMutualPresenceSubscription(List<Jid> contactJids) {
		for (Jid jid : contactJids) {
			if (!mAccount.getRoster().getContact(jid).mutualPresenceSubscription()) {
				return true;
			}
		}
		return false;
	}

	private boolean foreignActuallyHasKeys() {
		synchronized (this.foreignKeysToTrust) {
			for (Map.Entry<Jid, Map<String, Boolean>> entry : foreignKeysToTrust.entrySet()) {
				if (entry.getValue().size() > 0) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean reloadFingerprints() {
		List<Jid> acceptedTargets = mConversation == null ? new ArrayList<>() : mConversation.getAcceptedCryptoTargets();
		ownKeysToTrust.clear();
		if (this.mAccount == null) {
			return false;
		}
		AxolotlService service = this.mAccount.getAxolotlService();
		Set<IdentityKey> ownKeysSet = service.getKeysWithTrust(FingerprintStatus.createActiveUndecided());
		for (final IdentityKey identityKey : ownKeysSet) {
			final String fingerprint = CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
			if (!ownKeysToTrust.containsKey(fingerprint)) {
				ownKeysToTrust.put(fingerprint, false);
			}
		}
		synchronized (this.foreignKeysToTrust) {
			foreignKeysToTrust.clear();
			for (Jid jid : contactJids) {
				Set<IdentityKey> foreignKeysSet = service.getKeysWithTrust(FingerprintStatus.createActiveUndecided(), jid);
				if (hasNoOtherTrustedKeys(jid) && ownKeysSet.size() == 0) {
					foreignKeysSet.addAll(service.getKeysWithTrust(FingerprintStatus.createActive(false), jid));
				}
				Map<String, Boolean> foreignFingerprints = new HashMap<>();
				for (final IdentityKey identityKey : foreignKeysSet) {
					final String fingerprint = CryptoHelper.bytesToHex(identityKey.getPublicKey().serialize());
					if (!foreignFingerprints.containsKey(fingerprint)) {
						foreignFingerprints.put(fingerprint, false);
					}
				}
				if (foreignFingerprints.size() > 0 || !acceptedTargets.contains(jid)) {
					foreignKeysToTrust.put(jid, foreignFingerprints);
				}
			}
		}
		return ownKeysSet.size() + foreignKeysToTrust.size() > 0;
	}

	public void onBackendConnected() {
		Intent intent = getIntent();
		this.mAccount = extractAccount(intent);
		if (this.mAccount != null && intent != null) {
			String uuid = intent.getStringExtra("conversation");
			this.mConversation = xmppConnectionService.findConversationByUuid(uuid);
			if (this.mPendingFingerprintVerificationUri != null) {
				processFingerprintVerification(this.mPendingFingerprintVerificationUri);
				this.mPendingFingerprintVerificationUri = null;
			} else {
				final boolean keysToTrust = reloadFingerprints();
				if (keysToTrust || hasPendingKeyFetches() || hasNoOtherTrustedKeys()) {
					populateView();
					invalidateOptionsMenu();
				} else {
					finishOk(false);
				}
			}
		}
	}

	private boolean hasNoOtherTrustedKeys() {
		return mAccount == null || mAccount.getAxolotlService().anyTargetHasNoTrustedKeys(contactJids);
	}

	private boolean hasNoOtherTrustedKeys(Jid contact) {
		return mAccount == null || mAccount.getAxolotlService().getNumTrustedKeys(contact) == 0;
	}

	private boolean hasPendingKeyFetches() {
		return mAccount != null && mAccount.getAxolotlService().hasPendingKeyFetches(contactJids);
	}


	@Override
	public void onKeyStatusUpdated(final AxolotlService.FetchStatus report) {
		final boolean keysToTrust = reloadFingerprints();
		if (report != null) {
			lastFetchReport = report;
			runOnUiThread(() -> {
				if (mUseCameraHintToast != null && !keysToTrust) {
					mUseCameraHintToast.cancel();
				}
				switch (report) {
					case ERROR:
						Toast.makeText(TrustKeysActivity.this, R.string.error_fetching_omemo_key, Toast.LENGTH_SHORT).show();
						break;
					case SUCCESS_TRUSTED:
						Toast.makeText(TrustKeysActivity.this, R.string.blindly_trusted_omemo_keys, Toast.LENGTH_LONG).show();
						break;
					case SUCCESS_VERIFIED:
						Toast.makeText(TrustKeysActivity.this,
								Config.X509_VERIFICATION ? R.string.verified_omemo_key_with_certificate : R.string.all_omemo_keys_have_been_verified,
								Toast.LENGTH_LONG).show();
						break;
				}
			});

		}
		if (keysToTrust || hasPendingKeyFetches() || hasNoOtherTrustedKeys()) {
			refreshUi();
		} else {
			runOnUiThread(() -> finishOk(false));

		}
	}

	private void finishOk(boolean disabled) {
		Intent data = new Intent();
		data.putExtra("choice", getIntent().getIntExtra("choice", ConversationFragment.ATTACHMENT_CHOICE_INVALID));
		data.putExtra("disabled", disabled);
		setResult(RESULT_OK, data);
		finish();
	}

	private void commitTrusts() {
		for (final String fingerprint : ownKeysToTrust.keySet()) {
			mAccount.getAxolotlService().setFingerprintTrust(
					fingerprint,
					FingerprintStatus.createActive(ownKeysToTrust.get(fingerprint)));
		}
		List<Jid> acceptedTargets = mConversation == null ? new ArrayList<>() : mConversation.getAcceptedCryptoTargets();
		synchronized (this.foreignKeysToTrust) {
			for (Map.Entry<Jid, Map<String, Boolean>> entry : foreignKeysToTrust.entrySet()) {
				Jid jid = entry.getKey();
				Map<String, Boolean> value = entry.getValue();
				if (!acceptedTargets.contains(jid)) {
					acceptedTargets.add(jid);
				}
				for (final String fingerprint : value.keySet()) {
					mAccount.getAxolotlService().setFingerprintTrust(
							fingerprint,
							FingerprintStatus.createActive(value.get(fingerprint)));
				}
			}
		}
		if (mConversation != null && mConversation.getMode() == Conversation.MODE_MULTI) {
			mConversation.setAcceptedCryptoTargets(acceptedTargets);
			xmppConnectionService.updateConversation(mConversation);
		}
	}

	private void unlock() {
		binding.saveButton.setEnabled(true);
	}

	private void lock() {
		binding.saveButton.setEnabled(false);
	}

	private void lockOrUnlockAsNeeded() {
		synchronized (this.foreignKeysToTrust) {
			for (Jid jid : contactJids) {
				Map<String, Boolean> fingerprints = foreignKeysToTrust.get(jid);
				if (hasNoOtherTrustedKeys(jid) && (fingerprints == null || !fingerprints.containsValue(true))) {
					lock();
					return;
				}
			}
		}
		unlock();

	}

	private void setDone() {
		binding.saveButton.setText(getString(R.string.done));
	}

	private void setFetching() {
		binding.saveButton.setText(getString(R.string.fetching_keys));
	}
}
