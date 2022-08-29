package eu.siacs.conversations.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.theartofdev.edmodo.cropper.CropImage;

import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.interfaces.OnAvatarPublication;
import eu.siacs.conversations.utils.PhoneHelper;

public class PublishProfilePictureActivity extends XmppActivity implements XmppConnectionService.OnAccountUpdate, OnAvatarPublication {

    public static final int REQUEST_CHOOSE_PICTURE = 0x1337;

    private ImageView avatar;
    private TextView hintOrWarning;
    private TextView secondaryHint;
    private Button cancelButton;
    private Button publishButton;
    private Uri avatarUri;
    private Uri defaultUri;
    private Account account;
    private boolean support = false;
    private boolean publishing = false;
    private final AtomicBoolean handledExternalUri = new AtomicBoolean(false);
    private final OnLongClickListener backToDefaultListener = new OnLongClickListener() {

        @Override
        public boolean onLongClick(View v) {
            avatarUri = defaultUri;
            loadImageIntoPreview(defaultUri);
            return true;
        }
    };
    private boolean mInitialAccountSetup;

    @Override
    public void onAvatarPublicationSucceeded() {
        runOnUiThread(() -> {
            if (mInitialAccountSetup) {
                Intent intent = new Intent(getApplicationContext(), StartConversationActivity.class);
                StartConversationActivity.addInviteUri(intent, getIntent());
                intent.putExtra("init", true);
                intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toEscapedString());
                startActivity(intent);
            }
            Toast.makeText(PublishProfilePictureActivity.this,
                    R.string.avatar_has_been_published,
                    Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    @Override
    public void onAvatarPublicationFailed(int res) {
        runOnUiThread(() -> {
            hintOrWarning.setText(res);
            hintOrWarning.setTextAppearance(this,R.style.TextAppearance_Conversations_Body1_Warning);
            hintOrWarning.setVisibility(View.VISIBLE);
            publishing = false;
            togglePublishButton(true, R.string.publish);
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_publish_profile_picture);
        setSupportActionBar(findViewById(R.id.toolbar));

        this.avatar = findViewById(R.id.account_image);
        this.cancelButton = findViewById(R.id.cancel_button);
        this.publishButton = findViewById(R.id.publish_button);
        this.hintOrWarning = findViewById(R.id.hint_or_warning);
        this.secondaryHint = findViewById(R.id.secondary_hint);
        this.publishButton.setOnClickListener(v -> {
            if (avatarUri != null) {
                publishing = true;
                togglePublishButton(false, R.string.publishing);
                xmppConnectionService.publishAvatar(account, avatarUri, this);
            }
        });
        this.cancelButton.setOnClickListener(v -> {
            if (mInitialAccountSetup) {
                final Intent intent = new Intent(getApplicationContext(), StartConversationActivity.class);
                if (xmppConnectionService != null && xmppConnectionService.getAccounts().size() == 1) {
                    intent.putExtra("init", true);
                }
                StartConversationActivity.addInviteUri(intent, getIntent());
                intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toEscapedString());
                startActivity(intent);
            }
            finish();
        });
        this.avatar.setOnClickListener(v -> chooseAvatar(this));
        this.defaultUri = PhoneHelper.getProfilePictureUri(getApplicationContext());
        if (savedInstanceState != null) {
            this.avatarUri = savedInstanceState.getParcelable("uri");
            this.handledExternalUri.set(savedInstanceState.getBoolean("handle_external_uri",false));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull final Menu menu) {
        getMenuInflater().inflate(R.menu.activity_publish_profile_picture, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == R.id.action_delete_avatar) {
            if (xmppConnectionService != null && account != null) {
                xmppConnectionService.deleteAvatar(account);
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        if (this.avatarUri != null) {
            outState.putParcelable("uri", this.avatarUri);
        }
        outState.putBoolean("handle_external_uri", handledExternalUri.get());
        super.onSaveInstanceState(outState);
    }


    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
            CropImage.ActivityResult result = CropImage.getActivityResult(data);
            if (resultCode == RESULT_OK) {
                this.avatarUri = result.getUri();
                if (xmppConnectionServiceBound) {
                    loadImageIntoPreview(this.avatarUri);
                }
            } else if (resultCode == CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) {
                Exception error = result.getError();
                if (error != null) {
                    Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == REQUEST_CHOOSE_PICTURE) {
            if (resultCode == RESULT_OK) {
                cropUri(this, data.getData());
            }
        }
    }

    public static void chooseAvatar(final Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            activity.startActivityForResult(
                    Intent.createChooser(intent, activity.getString(R.string.attach_choose_picture)),
                    REQUEST_CHOOSE_PICTURE
            );
        } else {
            CropImage.activity()
                    .setOutputCompressFormat(Bitmap.CompressFormat.PNG)
                    .setAspectRatio(1, 1)
                    .setMinCropResultSize(Config.AVATAR_SIZE, Config.AVATAR_SIZE)
                    .start(activity);
        }
    }

    @Override
    protected void onBackendConnected() {
        this.account = extractAccount(getIntent());
        if (this.account != null) {
            reloadAvatar();
        }
    }

    private void reloadAvatar() {
        this.support = this.account.getXmppConnection() != null && this.account.getXmppConnection().getFeatures().pep();
        if (this.avatarUri == null) {
            if (this.account.getAvatar() != null || this.defaultUri == null) {
                loadImageIntoPreview(null);
            } else {
                this.avatarUri = this.defaultUri;
                loadImageIntoPreview(this.defaultUri);
            }
        } else {
            loadImageIntoPreview(avatarUri);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        final Intent intent = getIntent();
        this.mInitialAccountSetup = intent != null && intent.getBooleanExtra("setup", false);

        final Uri uri = intent != null ? intent.getData() : null;

        if (uri != null && handledExternalUri.compareAndSet(false,true)) {
            cropUri(this, uri);
            return;
        }

        if (this.mInitialAccountSetup) {
            this.cancelButton.setText(R.string.skip);
        }
        configureActionBar(getSupportActionBar(), !this.mInitialAccountSetup && !handledExternalUri.get());
    }

    public static void cropUri(final Activity activity, final Uri uri) {
        CropImage.activity(uri).setOutputCompressFormat(Bitmap.CompressFormat.PNG)
                .setAspectRatio(1, 1)
                .setMinCropResultSize(Config.AVATAR_SIZE, Config.AVATAR_SIZE)
                .start(activity);
    }

    protected void loadImageIntoPreview(Uri uri) {

        Bitmap bm = null;
        if (uri == null) {
            bm = avatarService().get(account, (int) getResources().getDimension(R.dimen.publish_avatar_size));
        } else {
            try {
                bm = xmppConnectionService.getFileBackend().cropCenterSquare(uri, (int) getResources().getDimension(R.dimen.publish_avatar_size));
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "unable to load bitmap into image view", e);
            }
        }

        if (bm == null) {
            togglePublishButton(false, R.string.publish);
            this.hintOrWarning.setVisibility(View.VISIBLE);
            this.hintOrWarning.setTextAppearance(this,R.style.TextAppearance_Conversations_Body1_Warning);
            this.hintOrWarning.setText(R.string.error_publish_avatar_converting);
            return;
        }
        this.avatar.setImageBitmap(bm);
        if (support) {
            togglePublishButton(uri != null, R.string.publish);
            this.hintOrWarning.setVisibility(View.INVISIBLE);
        } else {
            togglePublishButton(false, R.string.publish);
            this.hintOrWarning.setVisibility(View.VISIBLE);
            this.hintOrWarning.setTextAppearance(this,R.style.TextAppearance_Conversations_Body1_Warning);
            if (account.getStatus() == Account.State.ONLINE) {
                this.hintOrWarning.setText(R.string.error_publish_avatar_no_server_support);
            } else {
                this.hintOrWarning.setText(R.string.error_publish_avatar_offline);
            }
        }
        if (this.defaultUri == null || this.defaultUri.equals(uri)) {
            this.secondaryHint.setVisibility(View.INVISIBLE);
            this.avatar.setOnLongClickListener(null);
        } else if (this.defaultUri != null) {
            this.secondaryHint.setVisibility(View.VISIBLE);
            this.avatar.setOnLongClickListener(this.backToDefaultListener);
        }
    }

    protected void togglePublishButton(boolean enabled, @StringRes int res) {
        final boolean status = enabled && !publishing;
        this.publishButton.setText(publishing ? R.string.publishing : res);
        this.publishButton.setEnabled(status);
    }

    public void refreshUiReal() {
        if (this.account != null) {
            reloadAvatar();
        }
    }

    @Override
    public void onAccountUpdate() {
        refreshUi();
    }

}
