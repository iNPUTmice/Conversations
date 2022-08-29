package eu.siacs.conversations.xmpp.jingle.stanzas;

import eu.siacs.conversations.xml.Namespace;

public class OmemoVerifiedIceUdpTransportInfo extends IceUdpTransportInfo {


    public void ensureNoPlaintextFingerprint() {
        if (this.findChild("fingerprint", Namespace.JINGLE_APPS_DTLS) != null) {
            throw new IllegalStateException("OmemoVerifiedIceUdpTransportInfo contains plaintext fingerprint");
        }
    }

    public static IceUdpTransportInfo upgrade(final IceUdpTransportInfo transportInfo) {
        if (transportInfo.hasChild("fingerprint", Namespace.JINGLE_APPS_DTLS)) {
            return transportInfo;
        }
        if (transportInfo.hasChild("fingerprint", Namespace.OMEMO_DTLS_SRTP_VERIFICATION)) {
            final OmemoVerifiedIceUdpTransportInfo omemoVerifiedIceUdpTransportInfo = new OmemoVerifiedIceUdpTransportInfo();
            omemoVerifiedIceUdpTransportInfo.setAttributes(transportInfo.getAttributes());
            omemoVerifiedIceUdpTransportInfo.setChildren(transportInfo.getChildren());
            return omemoVerifiedIceUdpTransportInfo;
        }
        return transportInfo;
    }

}
