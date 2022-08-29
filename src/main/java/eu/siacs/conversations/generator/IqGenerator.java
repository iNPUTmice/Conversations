package eu.siacs.conversations.generator;


import android.os.Bundle;
import android.util.Base64;
import android.util.Log;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

import java.nio.ByteBuffer;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.forms.Data;
import eu.siacs.conversations.xmpp.pep.Avatar;
import eu.siacs.conversations.xmpp.stanzas.IqPacket;

public class IqGenerator extends AbstractGenerator {

    public IqGenerator(final XmppConnectionService service) {
        super(service);
    }

    public IqPacket discoResponse(final Account account, final IqPacket request) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.RESULT);
        packet.setId(request.getId());
        packet.setTo(request.getFrom());
        final Element query = packet.addChild("query", "http://jabber.org/protocol/disco#info");
        query.setAttribute("node", request.query().getAttribute("node"));
        final Element identity = query.addChild("identity");
        identity.setAttribute("category", "client");
        identity.setAttribute("type", getIdentityType());
        identity.setAttribute("name", getIdentityName());
        for (final String feature : getFeatures(account)) {
            query.addChild("feature").setAttribute("var", feature);
        }
        return packet;
    }

    public IqPacket versionResponse(final IqPacket request) {
        final IqPacket packet = request.generateResponse(IqPacket.TYPE.RESULT);
        Element query = packet.query("jabber:iq:version");
        query.addChild("name").setContent(mXmppConnectionService.getString(R.string.app_name));
        query.addChild("version").setContent(getIdentityVersion());
        if ("chromium".equals(android.os.Build.BRAND)) {
            query.addChild("os").setContent("Chrome OS");
        } else {
            query.addChild("os").setContent("Android");
        }
        return packet;
    }

    public IqPacket entityTimeResponse(IqPacket request) {
        final IqPacket packet = request.generateResponse(IqPacket.TYPE.RESULT);
        Element time = packet.addChild("time", "urn:xmpp:time");
        final long now = System.currentTimeMillis();
        time.addChild("utc").setContent(getTimestamp(now));
        TimeZone ourTimezone = TimeZone.getDefault();
        long offsetSeconds = ourTimezone.getOffset(now) / 1000;
        long offsetMinutes = Math.abs((offsetSeconds % 3600) / 60);
        long offsetHours = offsetSeconds / 3600;
        String hours;
        if (offsetHours < 0) {
            hours = String.format(Locale.US, "%03d", offsetHours);
        } else {
            hours = String.format(Locale.US, "%02d", offsetHours);
        }
        String minutes = String.format(Locale.US, "%02d", offsetMinutes);
        time.addChild("tzo").setContent(hours + ":" + minutes);
        return packet;
    }

    public IqPacket purgeOfflineMessages() {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        packet.addChild("offline", Namespace.FLEXIBLE_OFFLINE_MESSAGE_RETRIEVAL).addChild("purge");
        return packet;
    }

    protected IqPacket publish(final String node, final Element item, final Bundle options) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        final Element pubsub = packet.addChild("pubsub", Namespace.PUBSUB);
        final Element publish = pubsub.addChild("publish");
        publish.setAttribute("node", node);
        publish.addChild(item);
        if (options != null) {
            final Element publishOptions = pubsub.addChild("publish-options");
            publishOptions.addChild(Data.create(Namespace.PUBSUB_PUBLISH_OPTIONS, options));
        }
        return packet;
    }

    protected IqPacket publish(final String node, final Element item) {
        return publish(node, item, null);
    }

    private IqPacket retrieve(String node, Element item) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.GET);
        final Element pubsub = packet.addChild("pubsub", Namespace.PUBSUB);
        final Element items = pubsub.addChild("items");
        items.setAttribute("node", node);
        if (item != null) {
            items.addChild(item);
        }
        return packet;
    }

    public IqPacket retrieveBookmarks() {
        return retrieve(Namespace.BOOKMARKS2, null);
    }

    public IqPacket publishNick(String nick) {
        final Element item = new Element("item");
        item.setAttribute("id", "current");
        item.addChild("nick", Namespace.NICK).setContent(nick);
        return publish(Namespace.NICK, item);
    }

    public IqPacket deleteNode(final String node) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        final Element pubsub = packet.addChild("pubsub", Namespace.PUBSUB_OWNER);
        pubsub.addChild("delete").setAttribute("node", node);
        return packet;
    }

    public IqPacket deleteItem(final String node, final String id) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        final Element pubsub = packet.addChild("pubsub", Namespace.PUBSUB);
        final Element retract = pubsub.addChild("retract");
        retract.setAttribute("node", node);
        retract.setAttribute("notify","true");
        retract.addChild("item").setAttribute("id", id);
        return packet;
    }

    public IqPacket publishAvatar(Avatar avatar, Bundle options) {
        final Element item = new Element("item");
        item.setAttribute("id", avatar.sha1sum);
        final Element data = item.addChild("data", Namespace.AVATAR_DATA);
        data.setContent(avatar.image);
        return publish(Namespace.AVATAR_DATA, item, options);
    }

    public IqPacket publishElement(final String namespace, final Element element, String id, final Bundle options) {
        final Element item = new Element("item");
        item.setAttribute("id", id);
        item.addChild(element);
        return publish(namespace, item, options);
    }

    public IqPacket publishAvatarMetadata(final Avatar avatar, final Bundle options) {
        final Element item = new Element("item");
        item.setAttribute("id", avatar.sha1sum);
        final Element metadata = item
                .addChild("metadata", Namespace.AVATAR_METADATA);
        final Element info = metadata.addChild("info");
        info.setAttribute("bytes", avatar.size);
        info.setAttribute("id", avatar.sha1sum);
        info.setAttribute("height", avatar.height);
        info.setAttribute("width", avatar.height);
        info.setAttribute("type", avatar.type);
        return publish(Namespace.AVATAR_METADATA, item, options);
    }

    public IqPacket retrievePepAvatar(final Avatar avatar) {
        final Element item = new Element("item");
        item.setAttribute("id", avatar.sha1sum);
        final IqPacket packet = retrieve(Namespace.AVATAR_DATA, item);
        packet.setTo(avatar.owner);
        return packet;
    }

    public IqPacket retrieveVcardAvatar(final Avatar avatar) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.GET);
        packet.setTo(avatar.owner);
        packet.addChild("vCard", "vcard-temp");
        return packet;
    }

    public IqPacket retrieveVcardAvatar(final Jid to) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.GET);
        packet.setTo(to);
        packet.addChild("vCard", "vcard-temp");
        return packet;
    }

    public IqPacket retrieveAvatarMetaData(final Jid to) {
        final IqPacket packet = retrieve("urn:xmpp:avatar:metadata", null);
        if (to != null) {
            packet.setTo(to);
        }
        return packet;
    }

    public IqPacket retrieveDeviceIds(final Jid to) {
        final IqPacket packet = retrieve(AxolotlService.PEP_DEVICE_LIST, null);
        if (to != null) {
            packet.setTo(to);
        }
        return packet;
    }

    public IqPacket retrieveBundlesForDevice(final Jid to, final int deviceid) {
        final IqPacket packet = retrieve(AxolotlService.PEP_BUNDLES + ":" + deviceid, null);
        packet.setTo(to);
        return packet;
    }

    public IqPacket retrieveVerificationForDevice(final Jid to, final int deviceid) {
        final IqPacket packet = retrieve(AxolotlService.PEP_VERIFICATION + ":" + deviceid, null);
        packet.setTo(to);
        return packet;
    }

    public IqPacket publishDeviceIds(final Set<Integer> ids, final Bundle publishOptions) {
        final Element item = new Element("item");
        item.setAttribute("id", "current");
        final Element list = item.addChild("list", AxolotlService.PEP_PREFIX);
        for (Integer id : ids) {
            final Element device = new Element("device");
            device.setAttribute("id", id);
            list.addChild(device);
        }
        return publish(AxolotlService.PEP_DEVICE_LIST, item, publishOptions);
    }

    public Element publishBookmarkItem(final Bookmark bookmark) {
        final String name = bookmark.getBookmarkName();
        final String nick = bookmark.getNick();
        final boolean autojoin = bookmark.autojoin();
        final Element conference = new Element("conference", Namespace.BOOKMARKS2);
        if (name != null) {
            conference.setAttribute("name", name);
        }
        if (nick != null) {
            conference.addChild("nick").setContent(nick);
        }
        conference.setAttribute("autojoin",String.valueOf(autojoin));
        return conference;
    }

    public IqPacket publishBundles(final SignedPreKeyRecord signedPreKeyRecord, final IdentityKey identityKey,
                                   final Set<PreKeyRecord> preKeyRecords, final int deviceId, Bundle publishOptions) {
        final Element item = new Element("item");
        item.setAttribute("id", "current");
        final Element bundle = item.addChild("bundle", AxolotlService.PEP_PREFIX);
        final Element signedPreKeyPublic = bundle.addChild("signedPreKeyPublic");
        signedPreKeyPublic.setAttribute("signedPreKeyId", signedPreKeyRecord.getId());
        ECPublicKey publicKey = signedPreKeyRecord.getKeyPair().getPublicKey();
        signedPreKeyPublic.setContent(Base64.encodeToString(publicKey.serialize(), Base64.NO_WRAP));
        final Element signedPreKeySignature = bundle.addChild("signedPreKeySignature");
        signedPreKeySignature.setContent(Base64.encodeToString(signedPreKeyRecord.getSignature(), Base64.NO_WRAP));
        final Element identityKeyElement = bundle.addChild("identityKey");
        identityKeyElement.setContent(Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP));

        final Element prekeys = bundle.addChild("prekeys", AxolotlService.PEP_PREFIX);
        for (PreKeyRecord preKeyRecord : preKeyRecords) {
            final Element prekey = prekeys.addChild("preKeyPublic");
            prekey.setAttribute("preKeyId", preKeyRecord.getId());
            prekey.setContent(Base64.encodeToString(preKeyRecord.getKeyPair().getPublicKey().serialize(), Base64.NO_WRAP));
        }

        return publish(AxolotlService.PEP_BUNDLES + ":" + deviceId, item, publishOptions);
    }

    public IqPacket publishVerification(byte[] signature, X509Certificate[] certificates, final int deviceId) {
        final Element item = new Element("item");
        item.setAttribute("id", "current");
        final Element verification = item.addChild("verification", AxolotlService.PEP_PREFIX);
        final Element chain = verification.addChild("chain");
        for (int i = 0; i < certificates.length; ++i) {
            try {
                Element certificate = chain.addChild("certificate");
                certificate.setContent(Base64.encodeToString(certificates[i].getEncoded(), Base64.NO_WRAP));
                certificate.setAttribute("index", i);
            } catch (CertificateEncodingException e) {
                Log.d(Config.LOGTAG, "could not encode certificate");
            }
        }
        verification.addChild("signature").setContent(Base64.encodeToString(signature, Base64.NO_WRAP));
        return publish(AxolotlService.PEP_VERIFICATION + ":" + deviceId, item);
    }

    public IqPacket queryMessageArchiveManagement(final MessageArchiveService.Query mam) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        final Element query = packet.query(mam.version.namespace);
        query.setAttribute("queryid", mam.getQueryId());
        final Data data = new Data();
        data.setFormType(mam.version.namespace);
        if (mam.muc()) {
            packet.setTo(mam.getWith());
        } else if (mam.getWith() != null) {
            data.put("with", mam.getWith().toEscapedString());
        }
        final long start = mam.getStart();
        final long end = mam.getEnd();
        if (start != 0) {
            data.put("start", getTimestamp(start));
        }
        if (end != 0) {
            data.put("end", getTimestamp(end));
        }
        data.submit();
        query.addChild(data);
        Element set = query.addChild("set", "http://jabber.org/protocol/rsm");
        if (mam.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
            set.addChild("before").setContent(mam.getReference());
        } else if (mam.getReference() != null) {
            set.addChild("after").setContent(mam.getReference());
        }
        set.addChild("max").setContent(String.valueOf(Config.PAGE_SIZE));
        return packet;
    }

    public IqPacket generateGetBlockList() {
        final IqPacket iq = new IqPacket(IqPacket.TYPE.GET);
        iq.addChild("blocklist", Namespace.BLOCKING);

        return iq;
    }

    public IqPacket generateSetBlockRequest(final Jid jid, boolean reportSpam) {
        final IqPacket iq = new IqPacket(IqPacket.TYPE.SET);
        final Element block = iq.addChild("block", Namespace.BLOCKING);
        final Element item = block.addChild("item").setAttribute("jid", jid);
        if (reportSpam) {
            item.addChild("report", "urn:xmpp:reporting:0").addChild("spam");
        }
        Log.d(Config.LOGTAG, iq.toString());
        return iq;
    }

    public IqPacket generateSetUnblockRequest(final Jid jid) {
        final IqPacket iq = new IqPacket(IqPacket.TYPE.SET);
        final Element block = iq.addChild("unblock", Namespace.BLOCKING);
        block.addChild("item").setAttribute("jid", jid);
        return iq;
    }

    public IqPacket generateSetPassword(final Account account, final String newPassword) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        packet.setTo(account.getDomain());
        final Element query = packet.addChild("query", Namespace.REGISTER);
        final Jid jid = account.getJid();
        query.addChild("username").setContent(jid.getLocal());
        query.addChild("password").setContent(newPassword);
        return packet;
    }

    public IqPacket changeAffiliation(Conversation conference, Jid jid, String affiliation) {
        List<Jid> jids = new ArrayList<>();
        jids.add(jid);
        return changeAffiliation(conference, jids, affiliation);
    }

    public IqPacket changeAffiliation(Conversation conference, List<Jid> jids, String affiliation) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        packet.setTo(conference.getJid().asBareJid());
        packet.setFrom(conference.getAccount().getJid());
        Element query = packet.query("http://jabber.org/protocol/muc#admin");
        for (Jid jid : jids) {
            Element item = query.addChild("item");
            item.setAttribute("jid", jid);
            item.setAttribute("affiliation", affiliation);
        }
        return packet;
    }

    public IqPacket changeRole(Conversation conference, String nick, String role) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        packet.setTo(conference.getJid().asBareJid());
        packet.setFrom(conference.getAccount().getJid());
        Element item = packet.query("http://jabber.org/protocol/muc#admin").addChild("item");
        item.setAttribute("nick", nick);
        item.setAttribute("role", role);
        return packet;
    }

    public IqPacket requestHttpUploadSlot(Jid host, DownloadableFile file, String mime) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.GET);
        packet.setTo(host);
        Element request = packet.addChild("request", Namespace.HTTP_UPLOAD);
        request.setAttribute("filename", convertFilename(file.getName()));
        request.setAttribute("size", file.getExpectedSize());
        request.setAttribute("content-type", mime);
        return packet;
    }

    public IqPacket requestHttpUploadLegacySlot(Jid host, DownloadableFile file, String mime) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.GET);
        packet.setTo(host);
        Element request = packet.addChild("request", Namespace.HTTP_UPLOAD_LEGACY);
        request.addChild("filename").setContent(convertFilename(file.getName()));
        request.addChild("size").setContent(String.valueOf(file.getExpectedSize()));
        request.addChild("content-type").setContent(mime);
        return packet;
    }

    private static String convertFilename(String name) {
        int pos = name.indexOf('.');
        if (pos != -1) {
            try {
                UUID uuid = UUID.fromString(name.substring(0, pos));
                ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
                bb.putLong(uuid.getMostSignificantBits());
                bb.putLong(uuid.getLeastSignificantBits());
                return Base64.encodeToString(bb.array(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP) + name.substring(pos);
            } catch (Exception e) {
                return name;
            }
        } else {
            return name;
        }
    }

    public IqPacket generateCreateAccountWithCaptcha(Account account, String id, Data data) {
        final IqPacket register = new IqPacket(IqPacket.TYPE.SET);
        register.setFrom(account.getJid().asBareJid());
        register.setTo(account.getDomain());
        register.setId(id);
        Element query = register.query(Namespace.REGISTER);
        if (data != null) {
            query.addChild(data);
        }
        return register;
    }

    public IqPacket pushTokenToAppServer(Jid appServer, String token, String deviceId) {
        return pushTokenToAppServer(appServer, token, deviceId, null);
    }

    public IqPacket pushTokenToAppServer(Jid appServer, String token, String deviceId, Jid muc) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        packet.setTo(appServer);
        final Element command = packet.addChild("command", Namespace.COMMANDS);
        command.setAttribute("node", "register-push-fcm");
        command.setAttribute("action", "execute");
        final Data data = new Data();
        data.put("token", token);
        data.put("android-id", deviceId);
        if (muc != null) {
            data.put("muc", muc.toEscapedString());
        }
        data.submit();
        command.addChild(data);
        return packet;
    }

    public IqPacket unregisterChannelOnAppServer(Jid appServer, String deviceId, String channel) {
        final IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        packet.setTo(appServer);
        final Element command = packet.addChild("command", Namespace.COMMANDS);
        command.setAttribute("node", "unregister-push-fcm");
        command.setAttribute("action", "execute");
        final Data data = new Data();
        data.put("channel", channel);
        data.put("android-id", deviceId);
        data.submit();
        command.addChild(data);
        return packet;
    }

    public IqPacket enablePush(final Jid jid, final String node, final String secret) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        Element enable = packet.addChild("enable", Namespace.PUSH);
        enable.setAttribute("jid", jid);
        enable.setAttribute("node", node);
        if (secret != null) {
            Data data = new Data();
            data.setFormType(Namespace.PUBSUB_PUBLISH_OPTIONS);
            data.put("secret", secret);
            data.submit();
            enable.addChild(data);
        }
        return packet;
    }

    public IqPacket disablePush(final Jid jid, final String node) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.SET);
        Element disable = packet.addChild("disable", Namespace.PUSH);
        disable.setAttribute("jid", jid);
        disable.setAttribute("node", node);
        return packet;
    }

    public IqPacket queryAffiliation(Conversation conversation, String affiliation) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.GET);
        packet.setTo(conversation.getJid().asBareJid());
        packet.query("http://jabber.org/protocol/muc#admin").addChild("item").setAttribute("affiliation", affiliation);
        return packet;
    }

    public static Bundle defaultGroupChatConfiguration() {
        Bundle options = new Bundle();
        options.putString("muc#roomconfig_persistentroom", "1");
        options.putString("muc#roomconfig_membersonly", "1");
        options.putString("muc#roomconfig_publicroom", "0");
        options.putString("muc#roomconfig_whois", "anyone");
        options.putString("muc#roomconfig_changesubject", "0");
        options.putString("muc#roomconfig_allowinvites", "0");
        options.putString("muc#roomconfig_enablearchiving", "1"); //prosody
        options.putString("mam", "1"); //ejabberd community
        options.putString("muc#roomconfig_mam", "1"); //ejabberd saas
        return options;
    }

    public static Bundle defaultChannelConfiguration() {
        Bundle options = new Bundle();
        options.putString("muc#roomconfig_persistentroom", "1");
        options.putString("muc#roomconfig_membersonly", "0");
        options.putString("muc#roomconfig_publicroom", "1");
        options.putString("muc#roomconfig_whois", "moderators");
        options.putString("muc#roomconfig_changesubject", "0");
        options.putString("muc#roomconfig_enablearchiving", "1"); //prosody
        options.putString("mam", "1"); //ejabberd community
        options.putString("muc#roomconfig_mam", "1"); //ejabberd saas
        return options;
    }

    public IqPacket requestPubsubConfiguration(Jid jid, String node) {
        return pubsubConfiguration(jid, node, null);
    }

    public IqPacket publishPubsubConfiguration(Jid jid, String node, Data data) {
        return pubsubConfiguration(jid, node, data);
    }

    private IqPacket pubsubConfiguration(Jid jid, String node, Data data) {
        IqPacket packet = new IqPacket(data == null ? IqPacket.TYPE.GET : IqPacket.TYPE.SET);
        packet.setTo(jid);
        Element pubsub = packet.addChild("pubsub", "http://jabber.org/protocol/pubsub#owner");
        Element configure = pubsub.addChild("configure").setAttribute("node", node);
        if (data != null) {
            configure.addChild(data);
        }
        return packet;
    }

    public IqPacket queryDiscoItems(Jid jid) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.GET);
        packet.setTo(jid);
        packet.addChild("query",Namespace.DISCO_ITEMS);
        return packet;
    }

    public IqPacket queryDiscoInfo(Jid jid) {
        IqPacket packet = new IqPacket(IqPacket.TYPE.GET);
        packet.setTo(jid);
        packet.addChild("query",Namespace.DISCO_INFO);
        return packet;
    }
}
