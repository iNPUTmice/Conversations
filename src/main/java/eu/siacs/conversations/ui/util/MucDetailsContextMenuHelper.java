package eu.siacs.conversations.ui.util;

import android.app.Activity;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.MucOptions.User;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.ConferenceDetailsActivity;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.MucUsersActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.xmpp.Jid;


public final class MucDetailsContextMenuHelper {

    public static void onCreateContextMenu(ContextMenu menu, View v) {
        final XmppActivity activity = XmppActivity.find(v);
        final Object tag = v.getTag();
        if (tag instanceof MucOptions.User && activity != null) {
            activity.getMenuInflater().inflate(R.menu.muc_details_context, menu);
            final MucOptions.User user = (MucOptions.User) tag;
            String name;
            final Contact contact = user.getContact();
            if (contact != null && contact.showInContactList()) {
                name = contact.getDisplayName();
            } else if (user.getRealJid() != null) {
                name = user.getRealJid().asBareJid().toString();
            } else {
                name = user.getName();
            }
            menu.setHeaderTitle(name);
            MucDetailsContextMenuHelper.configureMucDetailsContextMenu(activity, menu, user.getConversation(), user);
        }
    }

    public static void configureMucDetailsContextMenu(Activity activity, Menu menu, Conversation conversation, User user) {
        final MucOptions mucOptions = conversation.getMucOptions();
        final boolean advancedMode = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("advanced_muc_mode", false);
        final boolean isGroupChat = mucOptions.isPrivateAndNonAnonymous();
        MenuItem sendPrivateMessage = menu.findItem(R.id.send_private_message);
        if (user != null && user.getRealJid() != null) {
            MenuItem showContactDetails = menu.findItem(R.id.action_contact_details);
            MenuItem startConversation = menu.findItem(R.id.start_conversation);
            MenuItem giveMembership = menu.findItem(R.id.give_membership);
            MenuItem removeMembership = menu.findItem(R.id.remove_membership);
            MenuItem giveAdminPrivileges = menu.findItem(R.id.give_admin_privileges);
            MenuItem giveOwnerPrivileges = menu.findItem(R.id.give_owner_privileges);
            MenuItem removeOwnerPrivileges = menu.findItem(R.id.revoke_owner_privileges);
            MenuItem removeAdminPrivileges = menu.findItem(R.id.remove_admin_privileges);
            MenuItem removeFromRoom = menu.findItem(R.id.remove_from_room);
            MenuItem managePermissions = menu.findItem(R.id.manage_permissions);
            removeFromRoom.setTitle(isGroupChat ? R.string.remove_from_room : R.string.remove_from_channel);
            MenuItem banFromConference = menu.findItem(R.id.ban_from_conference);
            banFromConference.setTitle(isGroupChat ? R.string.ban_from_conference : R.string.ban_from_channel);
            MenuItem invite = menu.findItem(R.id.invite);
            startConversation.setVisible(true);
            final Contact contact = user.getContact();
            final User self = conversation.getMucOptions().getSelf();
            if ((contact != null && contact.showInRoster()) || mucOptions.isPrivateAndNonAnonymous()) {
                showContactDetails.setVisible(contact == null || !contact.isSelf());
            }
            if ((activity instanceof ConferenceDetailsActivity || activity instanceof MucUsersActivity) && user.getRole() == MucOptions.Role.NONE) {
                invite.setVisible(true);
            }
            boolean managePermissionsVisible = false;
            if ((self.getAffiliation().ranks(MucOptions.Affiliation.ADMIN) && self.getAffiliation().outranks(user.getAffiliation())) || self.getAffiliation() == MucOptions.Affiliation.OWNER) {
                if (advancedMode) {
                    if (!user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                        managePermissionsVisible = true;
                        giveMembership.setVisible(true);
                    } else if (user.getAffiliation() == MucOptions.Affiliation.MEMBER) {
                        managePermissionsVisible = true;
                        removeMembership.setVisible(true);
                    }
                    if (!Config.DISABLE_BAN) {
                        managePermissionsVisible = true;
                        banFromConference.setVisible(true);
                    }
                } else {
                    if (!Config.DISABLE_BAN || conversation.getMucOptions().membersOnly()) {
                        removeFromRoom.setVisible(true);
                    }
                }
            }
            if (self.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                if (isGroupChat || advancedMode || user.getAffiliation() == MucOptions.Affiliation.OWNER) {
                    if (!user.getAffiliation().ranks(MucOptions.Affiliation.OWNER)) {
                        managePermissionsVisible = true;
                        giveOwnerPrivileges.setVisible(true);
                    } else if (user.getAffiliation() == MucOptions.Affiliation.OWNER){
                        managePermissionsVisible = true;
                        removeOwnerPrivileges.setVisible(true);
                    }
                }
                if (!isGroupChat || advancedMode || user.getAffiliation() == MucOptions.Affiliation.ADMIN) {
                    if (!user.getAffiliation().ranks(MucOptions.Affiliation.ADMIN)) {
                        managePermissionsVisible = true;
                        giveAdminPrivileges.setVisible(true);
                    } else if (user.getAffiliation() == MucOptions.Affiliation.ADMIN) {
                        managePermissionsVisible = true;
                        removeAdminPrivileges.setVisible(true);
                    }
                }
            }
            managePermissions.setVisible(managePermissionsVisible);
            sendPrivateMessage.setVisible(!isGroupChat && mucOptions.allowPm() && user.getRole().ranks(MucOptions.Role.VISITOR));
        } else {
            sendPrivateMessage.setVisible(true);
            sendPrivateMessage.setEnabled(user != null && mucOptions.allowPm() && user.getRole().ranks(MucOptions.Role.VISITOR));
        }
    }

    public static boolean onContextItemSelected(MenuItem item, User user, XmppActivity activity) {
        return onContextItemSelected(item, user, activity, null);
    }

    public static boolean onContextItemSelected(MenuItem item, User user, XmppActivity activity, final String fingerprint) {
        final Conversation conversation = user.getConversation();
        final XmppConnectionService.OnAffiliationChanged onAffiliationChanged = activity instanceof XmppConnectionService.OnAffiliationChanged ? (XmppConnectionService.OnAffiliationChanged) activity : null;
        Jid jid = user.getRealJid();
        switch (item.getItemId()) {
            case R.id.action_contact_details:
                final Jid realJid = user.getRealJid();
                final Account account = conversation.getAccount();
                final Contact contact = realJid == null ? null : account.getRoster().getContact(realJid);
                if (contact != null) {
                    activity.switchToContactDetails(contact, fingerprint);
                }
                return true;
            case R.id.start_conversation:
                startConversation(user, activity);
                return true;
            case R.id.give_admin_privileges:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.ADMIN, onAffiliationChanged);
                return true;
            case R.id.give_membership:
            case R.id.remove_admin_privileges:
            case R.id.revoke_owner_privileges:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.MEMBER, onAffiliationChanged);
                return true;
            case R.id.give_owner_privileges:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.OWNER, onAffiliationChanged);
                return true;
            case R.id.remove_membership:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.NONE, onAffiliationChanged);
                return true;
            case R.id.remove_from_room:
                removeFromRoom(user, activity, onAffiliationChanged);
                return true;
            case R.id.ban_from_conference:
                activity.xmppConnectionService.changeAffiliationInConference(conversation, jid, MucOptions.Affiliation.OUTCAST, onAffiliationChanged);
                if (user.getRole() != MucOptions.Role.NONE) {
                    activity.xmppConnectionService.changeRoleInConference(conversation, user.getName(), MucOptions.Role.NONE);
                }
                return true;
            case R.id.send_private_message:
                if (activity instanceof ConversationsActivity) {
                    ConversationFragment conversationFragment = ConversationFragment.get(activity);
                    if (conversationFragment != null) {
                        conversationFragment.privateMessageWith(user.getFullJid());
                        return true;
                    }
                }
                activity.privateMsgInMuc(conversation, user.getName());
                return true;
            case R.id.invite:
                if (user.getAffiliation().ranks(MucOptions.Affiliation.MEMBER)) {
                    activity.xmppConnectionService.directInvite(conversation, jid.asBareJid());
                } else {
                    activity.xmppConnectionService.invite(conversation, jid);
                }
                return true;
            default:
                return false;
        }
    }

    private static void removeFromRoom(final User user, XmppActivity activity, XmppConnectionService.OnAffiliationChanged onAffiliationChanged) {
        final Conversation conversation = user.getConversation();
        if (conversation.getMucOptions().membersOnly()) {
            activity.xmppConnectionService.changeAffiliationInConference(conversation, user.getRealJid(), MucOptions.Affiliation.NONE, onAffiliationChanged);
            if (user.getRole() != MucOptions.Role.NONE) {
                activity.xmppConnectionService.changeRoleInConference(conversation, user.getName(), MucOptions.Role.NONE);
            }
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(R.string.ban_from_conference);
            String jid = user.getRealJid().asBareJid().toString();
            SpannableString message = new SpannableString(activity.getString(R.string.removing_from_public_conference, jid));
            int start = message.toString().indexOf(jid);
            if (start >= 0) {
                message.setSpan(new TypefaceSpan("monospace"), start, start + jid.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            builder.setMessage(message);
            builder.setNegativeButton(R.string.cancel, null);
            builder.setPositiveButton(R.string.ban_now, (dialog, which) -> {
                activity.xmppConnectionService.changeAffiliationInConference(conversation, user.getRealJid(), MucOptions.Affiliation.OUTCAST, onAffiliationChanged);
                if (user.getRole() != MucOptions.Role.NONE) {
                    activity.xmppConnectionService.changeRoleInConference(conversation, user.getName(), MucOptions.Role.NONE);
                }
            });
            builder.create().show();
        }
    }

    private static void startConversation(User user, XmppActivity activity) {
        if (user.getRealJid() != null) {
            Conversation newConversation = activity.xmppConnectionService.findOrCreateConversation(user.getAccount(), user.getRealJid().asBareJid(), false, true);
            activity.switchToConversation(newConversation);
        }
    }
}