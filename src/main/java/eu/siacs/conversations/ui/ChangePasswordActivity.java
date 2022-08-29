package eu.siacs.conversations.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputLayout;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.widget.DisabledActionModeCallback;

public class ChangePasswordActivity extends XmppActivity implements XmppConnectionService.OnAccountPasswordChanged {

	private Button mChangePasswordButton;
	private final View.OnClickListener mOnChangePasswordButtonClicked = new View.OnClickListener() {
		@Override
		public void onClick(View view) {
			if (mAccount != null) {
				final String currentPassword = mCurrentPassword.getText().toString();
				final String newPassword = mNewPassword.getText().toString();
				if (!mAccount.isOptionSet(Account.OPTION_MAGIC_CREATE) && !currentPassword.equals(mAccount.getPassword())) {
					mCurrentPassword.requestFocus();
					mCurrentPasswordLayout.setError(getString(R.string.account_status_unauthorized));
					removeErrorsOnAllBut(mCurrentPasswordLayout);
				} else if (newPassword.trim().isEmpty()) {
					mNewPassword.requestFocus();
					mNewPasswordLayout.setError(getString(R.string.password_should_not_be_empty));
					removeErrorsOnAllBut(mNewPasswordLayout);
				} else {
					mCurrentPasswordLayout.setError(null);
					mNewPasswordLayout.setError(null);
					xmppConnectionService.updateAccountPasswordOnServer(mAccount, newPassword, ChangePasswordActivity.this);
					mChangePasswordButton.setEnabled(false);
					mChangePasswordButton.setText(R.string.updating);
				}
			}
		}
	};
	private EditText mCurrentPassword;
	private EditText mNewPassword;
	private TextInputLayout mNewPasswordLayout;
	private TextInputLayout mCurrentPasswordLayout;
	private Account mAccount;

	@Override
	void onBackendConnected() {
		this.mAccount = extractAccount(getIntent());
		if (this.mAccount != null && this.mAccount.isOptionSet(Account.OPTION_MAGIC_CREATE)) {
			this.mCurrentPasswordLayout.setVisibility(View.GONE);
		} else {
			this.mCurrentPassword.setVisibility(View.VISIBLE);
		}
	}

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_change_password);
		setSupportActionBar(findViewById(R.id.toolbar));
		configureActionBar(getSupportActionBar());
		Button mCancelButton = findViewById(R.id.left_button);
		mCancelButton.setOnClickListener(view -> finish());
		this.mChangePasswordButton = findViewById(R.id.right_button);
		this.mChangePasswordButton.setOnClickListener(this.mOnChangePasswordButtonClicked);
		this.mCurrentPassword = findViewById(R.id.current_password);
		this.mCurrentPassword.setCustomSelectionActionModeCallback(new DisabledActionModeCallback());
		this.mNewPassword = findViewById(R.id.new_password);
		this.mNewPassword.setCustomSelectionActionModeCallback(new DisabledActionModeCallback());
		this.mCurrentPasswordLayout = findViewById(R.id.current_password_layout);
		this.mNewPasswordLayout = findViewById(R.id.new_password_layout);
	}

	@Override
	protected void onStart() {
		super.onStart();
		Intent intent = getIntent();
		String password = intent != null ? intent.getStringExtra("password") : null;
		if (password != null) {
			this.mNewPassword.getEditableText().clear();
			this.mNewPassword.getEditableText().append(password);
		}
	}

	@Override
	public void onPasswordChangeSucceeded() {
		runOnUiThread(() -> {
			Toast.makeText(ChangePasswordActivity.this,R.string.password_changed,Toast.LENGTH_LONG).show();
			finish();
		});
	}

	@Override
	public void onPasswordChangeFailed() {
		runOnUiThread(() -> {
			mNewPasswordLayout.setError(getString(R.string.could_not_change_password));
			mChangePasswordButton.setEnabled(true);
			mChangePasswordButton.setText(R.string.change_password);
		});

	}

	private void removeErrorsOnAllBut(TextInputLayout exception) {
		if (this.mCurrentPasswordLayout != exception) {
			this.mCurrentPasswordLayout.setErrorEnabled(false);
			this.mCurrentPasswordLayout.setError(null);
		}
		if (this.mNewPasswordLayout != exception) {
			this.mNewPasswordLayout.setErrorEnabled(false);
			this.mNewPasswordLayout.setError(null);
		}

	}

	public void refreshUiReal() {

	}
}
