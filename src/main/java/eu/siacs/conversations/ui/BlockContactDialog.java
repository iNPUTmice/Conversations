package eu.siacs.conversations.ui;

import android.view.View;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.DialogBlockContactBinding;
import eu.siacs.conversations.entities.Blockable;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.ui.util.JidDialog;

public final class BlockContactDialog {
	public static void show(final XmppActivity xmppActivity, final Blockable blockable) {
		final AlertDialog.Builder builder = new AlertDialog.Builder(xmppActivity);
		final boolean isBlocked = blockable.isBlocked();
		builder.setNegativeButton(R.string.cancel, null);
		DialogBlockContactBinding binding = DataBindingUtil.inflate(xmppActivity.getLayoutInflater(), R.layout.dialog_block_contact, null, false);
		final boolean reporting = blockable.getAccount().getXmppConnection().getFeatures().spamReporting();
		binding.reportSpam.setVisibility(!isBlocked && reporting ? View.VISIBLE : View.GONE);
		builder.setView(binding.getRoot());

		final String value;
		@StringRes int res;
		if (blockable.getJid().isFullJid()) {
			builder.setTitle(isBlocked ? R.string.action_unblock_participant : R.string.action_block_participant);
			value = blockable.getJid().toEscapedString();
			res = isBlocked ? R.string.unblock_contact_text : R.string.block_contact_text;
		} else if (blockable.getJid().getLocal() == null || blockable.getAccount().isBlocked(blockable.getJid().getDomain())) {
			builder.setTitle(isBlocked ? R.string.action_unblock_domain : R.string.action_block_domain);
			value =blockable.getJid().getDomain().toEscapedString();
			res = isBlocked ? R.string.unblock_domain_text : R.string.block_domain_text;
		} else {
			int resBlockAction = blockable instanceof Conversation && ((Conversation) blockable).isWithStranger() ? R.string.block_stranger : R.string.action_block_contact;
			builder.setTitle(isBlocked ? R.string.action_unblock_contact : resBlockAction);
			value = blockable.getJid().asBareJid().toEscapedString();
			res = isBlocked ? R.string.unblock_contact_text : R.string.block_contact_text;
		}
		binding.text.setText(JidDialog.style(xmppActivity, res, value));
		builder.setPositiveButton(isBlocked ? R.string.unblock : R.string.block, (dialog, which) -> {
			if (isBlocked) {
				xmppActivity.xmppConnectionService.sendUnblockRequest(blockable);
			} else {
				boolean toastShown = false;
				if (xmppActivity.xmppConnectionService.sendBlockRequest(blockable, binding.reportSpam.isChecked())) {
					Toast.makeText(xmppActivity, R.string.corresponding_conversations_closed, Toast.LENGTH_SHORT).show();
					toastShown = true;
				}
				if (xmppActivity instanceof ContactDetailsActivity) {
					if (!toastShown) {
						Toast.makeText(xmppActivity, R.string.contact_blocked_past_tense, Toast.LENGTH_SHORT).show();
					}
					xmppActivity.finish();
				}
			}
		});
		builder.create().show();
	}
}
