package eu.siacs.conversations.xmpp.jingle.stanzas;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.Collection;
import java.util.List;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

public class Group extends Element {

    private Group() {
        super("group", Namespace.JINGLE_APPS_GROUPING);
    }

    public Group(final String semantics, final Collection<String> identificationTags) {
        super("group", Namespace.JINGLE_APPS_GROUPING);
        this.setAttribute("semantics", semantics);
        for (String tag : identificationTags) {
            this.addChild(new Element("content").setAttribute("name", tag));
        }
    }

    public String getSemantics() {
        return this.getAttribute("semantics");
    }

    public List<String> getIdentificationTags() {
        final ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
        for (final Element child : getChildren()) {
            if ("content".equals(child.getName())) {
                final String name = child.getAttribute("name");
                if (name != null) {
                    builder.add(name);
                }
            }
        }
        return builder.build();
    }

    public static Group ofSdpString(final String input) {
        ImmutableList.Builder<String> tagBuilder = new ImmutableList.Builder<>();
        final String[] parts = input.split(" ");
        if (parts.length >= 2) {
            final String semantics = parts[0];
            for(int i = 1; i < parts.length; ++i) {
                tagBuilder.add(parts[i]);
            }
            return new Group(semantics,tagBuilder.build());
        }
        return null;
    }

    public static Group upgrade(final Element element) {
        Preconditions.checkArgument("group".equals(element.getName()));
        Preconditions.checkArgument(Namespace.JINGLE_APPS_GROUPING.equals(element.getNamespace()));
        final Group group = new Group();
        group.setAttributes(element.getAttributes());
        group.setChildren(element.getChildren());
        return group;
    }
}
