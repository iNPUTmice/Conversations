package eu.siacs.conversations.xmpp.jingle.stanzas;

import androidx.annotation.NonNull;

import com.google.common.base.Preconditions;

import java.util.Locale;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

public class Content extends Element {

    public Content(final Creator creator, final String name) {
        super("content", Namespace.JINGLE);
        this.setAttribute("creator", creator.toString());
        this.setAttribute("name", name);
    }

    private Content() {
        super("content", Namespace.JINGLE);
    }

    public static Content upgrade(final Element element) {
        Preconditions.checkArgument("content".equals(element.getName()));
        final Content content = new Content();
        content.setAttributes(element.getAttributes());
        content.setChildren(element.getChildren());
        return content;
    }

    public String getContentName() {
        return this.getAttribute("name");
    }

    public Creator getCreator() {
        return Creator.of(getAttribute("creator"));
    }

    public Senders getSenders() {
        return Senders.of(getAttribute("senders"));
    }

    public void setSenders(Senders senders) {
        this.setAttribute("senders", senders.toString());
    }

    public GenericDescription getDescription() {
        final Element description = this.findChild("description");
        if (description == null) {
            return null;
        }
        final String namespace = description.getNamespace();
        if (FileTransferDescription.NAMESPACES.contains(namespace)) {
            return FileTransferDescription.upgrade(description);
        } else if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
            return RtpDescription.upgrade(description);
        } else {
            return GenericDescription.upgrade(description);
        }
    }

    public void setDescription(final GenericDescription description) {
        Preconditions.checkNotNull(description);
        this.addChild(description);
    }

    public String getDescriptionNamespace() {
        final Element description = this.findChild("description");
        return description == null ? null : description.getNamespace();
    }

    public GenericTransportInfo getTransport() {
        final Element transport = this.findChild("transport");
        final String namespace = transport == null ? null : transport.getNamespace();
        if (Namespace.JINGLE_TRANSPORTS_IBB.equals(namespace)) {
            return IbbTransportInfo.upgrade(transport);
        } else if (Namespace.JINGLE_TRANSPORTS_S5B.equals(namespace)) {
            return S5BTransportInfo.upgrade(transport);
        } else if (Namespace.JINGLE_TRANSPORT_ICE_UDP.equals(namespace)) {
            return IceUdpTransportInfo.upgrade(transport);
        } else if (transport != null) {
            return GenericTransportInfo.upgrade(transport);
        } else {
            return null;
        }
    }

    public void setTransport(GenericTransportInfo transportInfo) {
        this.addChild(transportInfo);
    }

    public enum Creator {
        INITIATOR, RESPONDER;

        public static Creator of(final String value) {
            return Creator.valueOf(value.toUpperCase(Locale.ROOT));
        }

        @Override
        @NonNull
        public String toString() {
            return super.toString().toLowerCase(Locale.ROOT);
        }
    }

    public enum Senders {
        BOTH, INITIATOR, NONE, RESPONDER;

        public static Senders of(final String value) {
            return Senders.valueOf(value.toUpperCase(Locale.ROOT));
        }

        @Override
        @NonNull
        public String toString() {
            return super.toString().toLowerCase(Locale.ROOT);
        }
    }
}
