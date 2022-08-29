package eu.siacs.conversations.xmpp;


import androidx.annotation.NonNull;

import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.parts.Resourcepart;
import org.jxmpp.stringprep.XmppStringprepException;


public class WrappedJid implements eu.siacs.conversations.xmpp.Jid {
    private final Jid inner;

    WrappedJid(Jid inner) {
        this.inner = inner;
    }

    @Override
    public boolean isFullJid() {
        return inner.isEntityFullJid() || inner.isDomainFullJid();
    }

    @Override
    public boolean isBareJid() {
        return inner.isDomainBareJid() || inner.isEntityBareJid();
    }

    @Override
    public boolean isDomainJid() {
        return inner.isDomainBareJid() || inner.isDomainFullJid();
    }

    @Override
    public eu.siacs.conversations.xmpp.Jid asBareJid() {
        return new WrappedJid(inner.asBareJid());
    }

    @Override
    public eu.siacs.conversations.xmpp.Jid withResource(CharSequence resource) {
        final Localpart localpart = inner.getLocalpartOrNull();
        try {
            final Resourcepart resourcepart = Resourcepart.from(resource.toString());
            if (localpart == null) {
                return new WrappedJid(JidCreate.domainFullFrom(inner.getDomain(),resourcepart));
            } else {
                return new WrappedJid(
                        JidCreate.fullFrom(
                                localpart,
                                inner.getDomain(),
                                resourcepart
                        ));
            }
        } catch (XmppStringprepException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public String getLocal() {
        final Localpart localpart = inner.getLocalpartOrNull();
        return localpart == null ? null : localpart.asUnescapedString();
    }

    @Override
    public String getEscapedLocal() {
        final Localpart localpart = inner.getLocalpartOrNull();
        return localpart == null ? null : localpart.toString();
    }

    @Override
    public eu.siacs.conversations.xmpp.Jid getDomain() {
        return new WrappedJid(inner.asDomainBareJid());
    }

    @Override
    public String getResource() {
        final Resourcepart resourcepart = inner.getResourceOrNull();
        return resourcepart == null ? null : resourcepart.toString();
    }

    @Override
    public String toEscapedString() {
        return inner.toString();
    }

    @NonNull
    @Override
    public String toString() {
        return inner.asUnescapedString();
    }

    @Override
    public int length() {
        return inner.length();
    }

    @Override
    public char charAt(int i) {
        return inner.charAt(i);
    }

    @Override
    public CharSequence subSequence(int i, int i1) {
        return inner.subSequence(i,i1);
    }

    @Override
    public int compareTo(eu.siacs.conversations.xmpp.Jid jid) {
        if (jid instanceof WrappedJid) {
            return inner.compareTo(((WrappedJid) jid).inner);
        } else {
            return 0;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WrappedJid that = (WrappedJid) o;
        return inner.equals(that.inner);
    }

    @Override
    public int hashCode() {
        return inner.hashCode();
    }
}
