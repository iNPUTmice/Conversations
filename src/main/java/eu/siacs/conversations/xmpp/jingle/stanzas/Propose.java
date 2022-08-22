package eu.siacs.conversations.xmpp.jingle.stanzas;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

public class Propose extends Element {
    private Propose() {
        super("propose", Namespace.JINGLE_MESSAGE);
    }

    public List<GenericDescription> getDescriptions() {
        final ImmutableList.Builder<GenericDescription> builder = new ImmutableList.Builder<>();
        for (final Element child : getChildren()) {
            if ("description".equals(child.getName())) {
                final String namespace = child.getNamespace();
                if (FileTransferDescription.NAMESPACES.contains(namespace)) {
                    builder.add(FileTransferDescription.upgrade(child));
                } else if (Namespace.JINGLE_APPS_RTP.equals(namespace)) {
                    builder.add(RtpDescription.upgrade(child));
                } else {
                    builder.add(GenericDescription.upgrade(child));
                }
            }
        }
        return builder.build();
    }

    public static Propose upgrade(final Element element) {
        Preconditions.checkArgument("propose".equals(element.getName()));
        Preconditions.checkArgument(Namespace.JINGLE_MESSAGE.equals(element.getNamespace()));
        final Propose propose = new Propose();
        propose.setAttributes(element.getAttributes());
        propose.setChildren(element.getChildren());
        return propose;
    }
}
