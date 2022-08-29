package eu.siacs.conversations.xmpp.jingle;

import java.util.Map;

import eu.siacs.conversations.xmpp.jingle.stanzas.Group;
import eu.siacs.conversations.xmpp.jingle.stanzas.OmemoVerifiedIceUdpTransportInfo;

public class OmemoVerifiedRtpContentMap extends RtpContentMap {
    public OmemoVerifiedRtpContentMap(Group group, Map<String, DescriptionTransport> contents) {
        super(group, contents);
        for(final DescriptionTransport descriptionTransport : contents.values()) {
            if (descriptionTransport.transport instanceof OmemoVerifiedIceUdpTransportInfo) {
                ((OmemoVerifiedIceUdpTransportInfo) descriptionTransport.transport).ensureNoPlaintextFingerprint();
                continue;
            }
            throw new IllegalStateException("OmemoVerifiedRtpContentMap contains non-verified transport info");
        }
    }
}
