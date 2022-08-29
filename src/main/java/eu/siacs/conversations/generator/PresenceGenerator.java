package eu.siacs.conversations.generator;

import android.text.TextUtils;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.stanzas.PresencePacket;

public class PresenceGenerator extends AbstractGenerator {

    public PresenceGenerator(XmppConnectionService service) {
        super(service);
    }

    private PresencePacket subscription(String type, Contact contact) {
        PresencePacket packet = new PresencePacket();
        packet.setAttribute("type", type);
        packet.setTo(contact.getJid());
        packet.setFrom(contact.getAccount().getJid().asBareJid());
        return packet;
    }

    public PresencePacket requestPresenceUpdatesFrom(final Contact contact) {
        return requestPresenceUpdatesFrom(contact, null);
    }

    public PresencePacket requestPresenceUpdatesFrom(final Contact contact, final String preAuth) {
        PresencePacket packet = subscription("subscribe", contact);
        String displayName = contact.getAccount().getDisplayName();
        if (!TextUtils.isEmpty(displayName)) {
            packet.addChild("nick", Namespace.NICK).setContent(displayName);
        }
        if (preAuth != null) {
            packet.addChild("preauth", Namespace.PARS).setAttribute("token", preAuth);
        }
        return packet;
    }

    public PresencePacket stopPresenceUpdatesFrom(Contact contact) {
        return subscription("unsubscribe", contact);
    }

    public PresencePacket stopPresenceUpdatesTo(Contact contact) {
        return subscription("unsubscribed", contact);
    }

    public PresencePacket sendPresenceUpdatesTo(Contact contact) {
        return subscription("subscribed", contact);
    }

    public PresencePacket selfPresence(Account account, Presence.Status status) {
        return selfPresence(account, status, true);
    }

    public PresencePacket selfPresence(final Account account, final Presence.Status status, final boolean personal) {
        final PresencePacket packet = new PresencePacket();
        if (personal) {
            final String sig = account.getPgpSignature();
            final String message = account.getPresenceStatusMessage();
            if (status.toShowString() != null) {
                packet.addChild("show").setContent(status.toShowString());
            }
            if (!TextUtils.isEmpty(message)) {
                packet.addChild(new Element("status").setContent(message));
            }
            if (sig != null && mXmppConnectionService.getPgpEngine() != null) {
                packet.addChild("x", "jabber:x:signed").setContent(sig);
            }
        }
        final String capHash = getCapHash(account);
        if (capHash != null) {
            Element cap = packet.addChild("c",
                    "http://jabber.org/protocol/caps");
            cap.setAttribute("hash", "sha-1");
            cap.setAttribute("node", "http://conversations.im");
            cap.setAttribute("ver", capHash);
        }
        return packet;
    }

    public PresencePacket leave(final MucOptions mucOptions) {
        PresencePacket presencePacket = new PresencePacket();
        presencePacket.setTo(mucOptions.getSelf().getFullJid());
        presencePacket.setFrom(mucOptions.getAccount().getJid());
        presencePacket.setAttribute("type", "unavailable");
        return presencePacket;
    }

    public PresencePacket sendOfflinePresence(Account account) {
        PresencePacket packet = new PresencePacket();
        packet.setFrom(account.getJid());
        packet.setAttribute("type", "unavailable");
        return packet;
    }
}
