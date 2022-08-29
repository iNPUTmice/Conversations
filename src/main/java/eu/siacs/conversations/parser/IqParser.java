package eu.siacs.conversations.parser;

import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.google.common.base.CharMatcher;
import com.google.common.io.BaseEncoding;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.PreKeyBundle;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Room;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.InvalidJid;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnIqPacketReceived;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;

public class IqParser extends AbstractParser implements OnIqPacketReceived {

    public IqParser(final XmppConnectionService service) {
        super(service);
    }

    public static List<Jid> items(IqPacket packet) {
        ArrayList<Jid> items = new ArrayList<>();
        final Element query = packet.findChild("query", Namespace.DISCO_ITEMS);
        if (query == null) {
            return items;
        }
        for (Element child : query.getChildren()) {
            if ("item".equals(child.getName())) {
                Jid jid = child.getAttributeAsJid("jid");
                if (jid != null) {
                    items.add(jid);
                }
            }
        }
        return items;
    }

    public static Room parseRoom(IqPacket packet) {
        final Element query = packet.findChild("query", Namespace.DISCO_INFO);
        if (query == null) {
            return null;
        }
        final Element x = query.findChild("x");
        if (x == null) {
            return null;
        }
        final Element identity = query.findChild("identity");
        Data data = Data.parse(x);
        String address = packet.getFrom().toString();
        String name = identity == null ? null : identity.getAttribute("name");
        String roomName = data.getValue("muc#roomconfig_roomname");
        String description = data.getValue("muc#roominfo_description");
        String language = data.getValue("muc#roominfo_lang");
        String occupants = data.getValue("muc#roominfo_occupants");
        int nusers;
        try {
            nusers = occupants == null ? 0 : Integer.parseInt(occupants);
        } catch (NumberFormatException e) {
            nusers = 0;
        }

        return new Room(
                address,
                TextUtils.isEmpty(roomName) ? name : roomName,
                description,
                language,
                nusers
        );
    }

    private void rosterItems(final Account account, final Element query) {
        final String version = query.getAttribute("ver");
        if (version != null) {
            account.getRoster().setVersion(version);
        }
        for (final Element item : query.getChildren()) {
            if (item.getName().equals("item")) {
                final Jid jid = InvalidJid.getNullForInvalid(item.getAttributeAsJid("jid"));
                if (jid == null) {
                    continue;
                }
                final String name = item.getAttribute("name");
                final String subscription = item.getAttribute("subscription");
                final Contact contact = account.getRoster().getContact(jid);
                boolean bothPre = contact.getOption(Contact.Options.TO) && contact.getOption(Contact.Options.FROM);
                if (!contact.getOption(Contact.Options.DIRTY_PUSH)) {
                    contact.setServerName(name);
                    contact.parseGroupsFromElement(item);
                }
                if ("remove".equals(subscription)) {
                    contact.resetOption(Contact.Options.IN_ROSTER);
                    contact.resetOption(Contact.Options.DIRTY_DELETE);
                    contact.resetOption(Contact.Options.PREEMPTIVE_GRANT);
                } else {
                    contact.setOption(Contact.Options.IN_ROSTER);
                    contact.resetOption(Contact.Options.DIRTY_PUSH);
                    contact.parseSubscriptionFromElement(item);
                }
                boolean both = contact.getOption(Contact.Options.TO) && contact.getOption(Contact.Options.FROM);
                if ((both != bothPre) && both) {
                    Log.d(Config.LOGTAG, account.getJid().asBareJid() + ": gained mutual presence subscription with " + contact.getJid());
                    AxolotlService axolotlService = account.getAxolotlService();
                    if (axolotlService != null) {
                        axolotlService.clearErrorsInFetchStatusMap(contact.getJid());
                    }
                }
                mXmppConnectionService.getAvatarService().clear(contact);
            }
        }
        mXmppConnectionService.updateConversationUi();
        mXmppConnectionService.updateRosterUi();
        mXmppConnectionService.getShortcutService().refresh();
        mXmppConnectionService.syncRoster(account);
    }

    public String avatarData(final IqPacket packet) {
        final Element pubsub = packet.findChild("pubsub", Namespace.PUBSUB);
        if (pubsub == null) {
            return null;
        }
        final Element items = pubsub.findChild("items");
        if (items == null) {
            return null;
        }
        return super.avatarData(items);
    }

    public Element getItem(final IqPacket packet) {
        final Element pubsub = packet.findChild("pubsub", Namespace.PUBSUB);
        if (pubsub == null) {
            return null;
        }
        final Element items = pubsub.findChild("items");
        if (items == null) {
            return null;
        }
        return items.findChild("item");
    }

    @NonNull
    public Set<Integer> deviceIds(final Element item) {
        Set<Integer> deviceIds = new HashSet<>();
        if (item != null) {
            final Element list = item.findChild("list");
            if (list != null) {
                for (Element device : list.getChildren()) {
                    if (!device.getName().equals("device")) {
                        continue;
                    }
                    try {
                        Integer id = Integer.valueOf(device.getAttribute("id"));
                        deviceIds.add(id);
                    } catch (NumberFormatException e) {
                        Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Encountered invalid <device> node in PEP (" + e.getMessage() + "):" + device.toString() + ", skipping...");
                    }
                }
            }
        }
        return deviceIds;
    }

    private Integer signedPreKeyId(final Element bundle) {
        final Element signedPreKeyPublic = bundle.findChild("signedPreKeyPublic");
        if (signedPreKeyPublic == null) {
            return null;
        }
        try {
            return Integer.valueOf(signedPreKeyPublic.getAttribute("signedPreKeyId"));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ECPublicKey signedPreKeyPublic(final Element bundle) {
        ECPublicKey publicKey = null;
        final String signedPreKeyPublic = bundle.findChildContent("signedPreKeyPublic");
        if (signedPreKeyPublic == null) {
            return null;
        }
        try {
            publicKey = Curve.decodePoint(base64decode(signedPreKeyPublic), 0);
        } catch (final IllegalArgumentException | InvalidKeyException e) {
            Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Invalid signedPreKeyPublic in PEP: " + e.getMessage());
        }
        return publicKey;
    }

    private byte[] signedPreKeySignature(final Element bundle) {
        final String signedPreKeySignature = bundle.findChildContent("signedPreKeySignature");
        if (signedPreKeySignature == null) {
            return null;
        }
        try {
            return base64decode(signedPreKeySignature);
        } catch (final IllegalArgumentException e) {
            Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : Invalid base64 in signedPreKeySignature");
            return null;
        }
    }

    private IdentityKey identityKey(final Element bundle) {
        final String identityKey = bundle.findChildContent("identityKey");
        if (identityKey == null) {
            return null;
        }
        try {
            return new IdentityKey(base64decode(identityKey), 0);
        } catch (final IllegalArgumentException | InvalidKeyException e) {
            Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Invalid identityKey in PEP: " + e.getMessage());
            return null;
        }
    }

    public Map<Integer, ECPublicKey> preKeyPublics(final IqPacket packet) {
        Map<Integer, ECPublicKey> preKeyRecords = new HashMap<>();
        Element item = getItem(packet);
        if (item == null) {
            Log.d(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Couldn't find <item> in bundle IQ packet: " + packet);
            return null;
        }
        final Element bundleElement = item.findChild("bundle");
        if (bundleElement == null) {
            return null;
        }
        final Element prekeysElement = bundleElement.findChild("prekeys");
        if (prekeysElement == null) {
            Log.d(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Couldn't find <prekeys> in bundle IQ packet: " + packet);
            return null;
        }
        for (Element preKeyPublicElement : prekeysElement.getChildren()) {
            if (!preKeyPublicElement.getName().equals("preKeyPublic")) {
                Log.d(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Encountered unexpected tag in prekeys list: " + preKeyPublicElement);
                continue;
            }
            final String preKey = preKeyPublicElement.getContent();
            if (preKey == null) {
                continue;
            }
            Integer preKeyId = null;
            try {
                preKeyId = Integer.valueOf(preKeyPublicElement.getAttribute("preKeyId"));
                final ECPublicKey preKeyPublic = Curve.decodePoint(base64decode(preKey), 0);
                preKeyRecords.put(preKeyId, preKeyPublic);
            } catch (NumberFormatException e) {
                Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "could not parse preKeyId from preKey " + preKeyPublicElement.toString());
            } catch (Throwable e) {
                Log.e(Config.LOGTAG, AxolotlService.LOGPREFIX + " : " + "Invalid preKeyPublic (ID=" + preKeyId + ") in PEP: " + e.getMessage() + ", skipping...");
            }
        }
        return preKeyRecords;
    }

    private static byte[] base64decode(String input) {
        return BaseEncoding.base64().decode(CharMatcher.whitespace().removeFrom(input));
    }

    public Pair<X509Certificate[], byte[]> verification(final IqPacket packet) {
        Element item = getItem(packet);
        Element verification = item != null ? item.findChild("verification", AxolotlService.PEP_PREFIX) : null;
        Element chain = verification != null ? verification.findChild("chain") : null;
        String signature = verification != null ? verification.findChildContent("signature") : null;
        if (chain != null && signature != null) {
            List<Element> certElements = chain.getChildren();
            X509Certificate[] certificates = new X509Certificate[certElements.size()];
            try {
                CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
                int i = 0;
                for (final Element certElement : certElements) {
                    final String cert = certElement.getContent();
                    if (cert == null) {
                        continue;
                    }
                    certificates[i] = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(BaseEncoding.base64().decode(cert)));
                    ++i;
                }
                return new Pair<>(certificates, BaseEncoding.base64().decode(signature));
            } catch (CertificateException e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public PreKeyBundle bundle(final IqPacket bundle) {
        final Element bundleItem = getItem(bundle);
        if (bundleItem == null) {
            return null;
        }
        final Element bundleElement = bundleItem.findChild("bundle");
        if (bundleElement == null) {
            return null;
        }
        final ECPublicKey signedPreKeyPublic = signedPreKeyPublic(bundleElement);
        final Integer signedPreKeyId = signedPreKeyId(bundleElement);
        final byte[] signedPreKeySignature = signedPreKeySignature(bundleElement);
        final IdentityKey identityKey = identityKey(bundleElement);
        if (signedPreKeyId == null
                || signedPreKeyPublic == null
                || identityKey == null
                || signedPreKeySignature == null
                || signedPreKeySignature.length == 0) {
            return null;
        }
        return new PreKeyBundle(0, 0, 0, null,
                signedPreKeyId, signedPreKeyPublic, signedPreKeySignature, identityKey);
    }

    public List<PreKeyBundle> preKeys(final IqPacket preKeys) {
        List<PreKeyBundle> bundles = new ArrayList<>();
        Map<Integer, ECPublicKey> preKeyPublics = preKeyPublics(preKeys);
        if (preKeyPublics != null) {
            for (Integer preKeyId : preKeyPublics.keySet()) {
                ECPublicKey preKeyPublic = preKeyPublics.get(preKeyId);
                bundles.add(new PreKeyBundle(0, 0, preKeyId, preKeyPublic,
                        0, null, null, null));
            }
        }

        return bundles;
    }

    @Override
    public void onIqPacketReceived(final Account account, final IqPacket packet) {
        final boolean isGet = packet.getType() == IqPacket.TYPE.GET;
        if (packet.getType() == IqPacket.TYPE.ERROR || packet.getType() == IqPacket.TYPE.TIMEOUT) {
            return;
        }
        if (packet.hasChild("query", Namespace.ROSTER) && packet.fromServer(account)) {
            final Element query = packet.findChild("query");
            // If this is in response to a query for the whole roster:
            if (packet.getType() == IqPacket.TYPE.RESULT) {
                account.getRoster().markAllAsNotInRoster();
            }
            this.rosterItems(account, query);
        } else if ((packet.hasChild("block", Namespace.BLOCKING) || packet.hasChild("blocklist", Namespace.BLOCKING)) &&
                packet.fromServer(account)) {
            // Block list or block push.
            Log.d(Config.LOGTAG, "Received blocklist update from server");
            final Element blocklist = packet.findChild("blocklist", Namespace.BLOCKING);
            final Element block = packet.findChild("block", Namespace.BLOCKING);
            final Collection<Element> items = blocklist != null ? blocklist.getChildren() :
                    (block != null ? block.getChildren() : null);
            // If this is a response to a blocklist query, clear the block list and replace with the new one.
            // Otherwise, just update the existing blocklist.
            if (packet.getType() == IqPacket.TYPE.RESULT) {
                account.clearBlocklist();
                account.getXmppConnection().getFeatures().setBlockListRequested(true);
            }
            if (items != null) {
                final Collection<Jid> jids = new ArrayList<>(items.size());
                // Create a collection of Jids from the packet
                for (final Element item : items) {
                    if (item.getName().equals("item")) {
                        final Jid jid = InvalidJid.getNullForInvalid(item.getAttributeAsJid("jid"));
                        if (jid != null) {
                            jids.add(jid);
                        }
                    }
                }
                account.getBlocklist().addAll(jids);
                if (packet.getType() == IqPacket.TYPE.SET) {
                    boolean removed = false;
                    for (Jid jid : jids) {
                        removed |= mXmppConnectionService.removeBlockedConversations(account, jid);
                    }
                    if (removed) {
                        mXmppConnectionService.updateConversationUi();
                    }
                }
            }
            // Update the UI
            mXmppConnectionService.updateBlocklistUi(OnUpdateBlocklist.Status.BLOCKED);
            if (packet.getType() == IqPacket.TYPE.SET) {
                final IqPacket response = packet.generateResponse(IqPacket.TYPE.RESULT);
                mXmppConnectionService.sendIqPacket(account, response, null);
            }
        } else if (packet.hasChild("unblock", Namespace.BLOCKING) &&
                packet.fromServer(account) && packet.getType() == IqPacket.TYPE.SET) {
            Log.d(Config.LOGTAG, "Received unblock update from server");
            final Collection<Element> items = packet.findChild("unblock", Namespace.BLOCKING).getChildren();
            if (items.size() == 0) {
                // No children to unblock == unblock all
                account.getBlocklist().clear();
            } else {
                final Collection<Jid> jids = new ArrayList<>(items.size());
                for (final Element item : items) {
                    if (item.getName().equals("item")) {
                        final Jid jid = InvalidJid.getNullForInvalid(item.getAttributeAsJid("jid"));
                        if (jid != null) {
                            jids.add(jid);
                        }
                    }
                }
                account.getBlocklist().removeAll(jids);
            }
            mXmppConnectionService.updateBlocklistUi(OnUpdateBlocklist.Status.UNBLOCKED);
            final IqPacket response = packet.generateResponse(IqPacket.TYPE.RESULT);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("open", "http://jabber.org/protocol/ibb")
                || packet.hasChild("data", "http://jabber.org/protocol/ibb")
                || packet.hasChild("close", "http://jabber.org/protocol/ibb")) {
            mXmppConnectionService.getJingleConnectionManager()
                    .deliverIbbPacket(account, packet);
        } else if (packet.hasChild("query", "http://jabber.org/protocol/disco#info")) {
            final IqPacket response = mXmppConnectionService.getIqGenerator().discoResponse(account, packet);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("query", "jabber:iq:version") && isGet) {
            final IqPacket response = mXmppConnectionService.getIqGenerator().versionResponse(packet);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("ping", "urn:xmpp:ping") && isGet) {
            final IqPacket response = packet.generateResponse(IqPacket.TYPE.RESULT);
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else if (packet.hasChild("time", "urn:xmpp:time") && isGet) {
            final IqPacket response;
            if (mXmppConnectionService.useTorToConnect() || account.isOnion()) {
                response = packet.generateResponse(IqPacket.TYPE.ERROR);
                final Element error = response.addChild("error");
                error.setAttribute("type", "cancel");
                error.addChild("not-allowed", "urn:ietf:params:xml:ns:xmpp-stanzas");
            } else {
                response = mXmppConnectionService.getIqGenerator().entityTimeResponse(packet);
            }
            mXmppConnectionService.sendIqPacket(account, response, null);
        } else {
            if (packet.getType() == IqPacket.TYPE.GET || packet.getType() == IqPacket.TYPE.SET) {
                final IqPacket response = packet.generateResponse(IqPacket.TYPE.ERROR);
                final Element error = response.addChild("error");
                error.setAttribute("type", "cancel");
                error.addChild("feature-not-implemented", "urn:ietf:params:xml:ns:xmpp-stanzas");
                account.getXmppConnection().sendIqPacket(response, null);
            }
        }
    }

}
