package eu.siacs.conversations.xmpp.jingle;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.checkerframework.checker.nullness.compatqual.NullableDecl;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import eu.siacs.conversations.xmpp.jingle.stanzas.Content;
import eu.siacs.conversations.xmpp.jingle.stanzas.GenericDescription;
import eu.siacs.conversations.xmpp.jingle.stanzas.GenericTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.Group;
import eu.siacs.conversations.xmpp.jingle.stanzas.IceUdpTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.JinglePacket;
import eu.siacs.conversations.xmpp.jingle.stanzas.OmemoVerifiedIceUdpTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.RtpDescription;

public class RtpContentMap {

    public final Group group;
    public final Map<String, DescriptionTransport> contents;

    public RtpContentMap(Group group, Map<String, DescriptionTransport> contents) {
        this.group = group;
        this.contents = contents;
    }

    public static RtpContentMap of(final JinglePacket jinglePacket) {
        final Map<String, DescriptionTransport> contents = DescriptionTransport.of(jinglePacket.getJingleContents());
        if (isOmemoVerified(contents)) {
            return new OmemoVerifiedRtpContentMap(jinglePacket.getGroup(), contents);
        } else {
            return new RtpContentMap(jinglePacket.getGroup(), contents);
        }
    }

    private static boolean isOmemoVerified(Map<String, DescriptionTransport> contents) {
        final Collection<DescriptionTransport> values = contents.values();
        if (values.size() == 0) {
            return false;
        }
        for (final DescriptionTransport descriptionTransport : values) {
            if (descriptionTransport.transport instanceof OmemoVerifiedIceUdpTransportInfo) {
                continue;
            }
            return false;
        }
        return true;
    }

    public static RtpContentMap of(final SessionDescription sessionDescription) {
        final ImmutableMap.Builder<String, DescriptionTransport> contentMapBuilder = new ImmutableMap.Builder<>();
        for (SessionDescription.Media media : sessionDescription.media) {
            final String id = Iterables.getFirst(media.attributes.get("mid"), null);
            Preconditions.checkNotNull(id, "media has no mid");
            contentMapBuilder.put(id, DescriptionTransport.of(sessionDescription, media));
        }
        final String groupAttribute = Iterables.getFirst(sessionDescription.attributes.get("group"), null);
        final Group group = groupAttribute == null ? null : Group.ofSdpString(groupAttribute);
        return new RtpContentMap(group, contentMapBuilder.build());
    }

    public Set<Media> getMedia() {
        return Sets.newHashSet(Collections2.transform(contents.values(), input -> {
            final RtpDescription rtpDescription = input == null ? null : input.description;
            return rtpDescription == null ? Media.UNKNOWN : input.description.getMedia();
        }));
    }

    public List<String> getNames() {
        return ImmutableList.copyOf(contents.keySet());
    }

    void requireContentDescriptions() {
        if (this.contents.size() == 0) {
            throw new IllegalStateException("No contents available");
        }
        for (Map.Entry<String, DescriptionTransport> entry : this.contents.entrySet()) {
            if (entry.getValue().description == null) {
                throw new IllegalStateException(String.format("%s is lacking content description", entry.getKey()));
            }
        }
    }

    void requireDTLSFingerprint() {
        if (this.contents.size() == 0) {
            throw new IllegalStateException("No contents available");
        }
        for (Map.Entry<String, DescriptionTransport> entry : this.contents.entrySet()) {
            final IceUdpTransportInfo transport = entry.getValue().transport;
            final IceUdpTransportInfo.Fingerprint fingerprint = transport.getFingerprint();
            if (fingerprint == null || Strings.isNullOrEmpty(fingerprint.getContent()) || Strings.isNullOrEmpty(fingerprint.getHash())) {
                throw new SecurityException(String.format("Use of DTLS-SRTP (XEP-0320) is required for content %s", entry.getKey()));
            }
            final IceUdpTransportInfo.Setup setup = fingerprint.getSetup();
            if (setup == null) {
                throw new SecurityException(String.format("Use of DTLS-SRTP (XEP-0320) is required for content %s but missing setup attribute", entry.getKey()));
            }
        }
    }

    JinglePacket toJinglePacket(final JinglePacket.Action action, final String sessionId) {
        final JinglePacket jinglePacket = new JinglePacket(action, sessionId);
        if (this.group != null) {
            jinglePacket.addGroup(this.group);
        }
        for (Map.Entry<String, DescriptionTransport> entry : this.contents.entrySet()) {
            final Content content = new Content(Content.Creator.INITIATOR, entry.getKey());
            if (entry.getValue().description != null) {
                content.addChild(entry.getValue().description);
            }
            content.addChild(entry.getValue().transport);
            jinglePacket.addJingleContent(content);
        }
        return jinglePacket;
    }

    RtpContentMap transportInfo(final String contentName, final IceUdpTransportInfo.Candidate candidate) {
        final RtpContentMap.DescriptionTransport descriptionTransport = contents.get(contentName);
        final IceUdpTransportInfo transportInfo = descriptionTransport == null ? null : descriptionTransport.transport;
        if (transportInfo == null) {
            throw new IllegalArgumentException("Unable to find transport info for content name " + contentName);
        }
        final IceUdpTransportInfo newTransportInfo = transportInfo.cloneWrapper();
        newTransportInfo.addChild(candidate);
        return new RtpContentMap(null, ImmutableMap.of(contentName, new DescriptionTransport(null, newTransportInfo)));
    }

    RtpContentMap transportInfo() {
        return new RtpContentMap(
                null,
                Maps.transformValues(contents, dt -> new DescriptionTransport(null, dt.transport.cloneWrapper()))
        );
    }

    public Map<String, IceUdpTransportInfo.Credentials> getCredentials() {
        return Maps.transformValues(contents, dt -> dt.transport.getCredentials());
    }

    public IceUdpTransportInfo.Setup getDtlsSetup() {
        final Set<IceUdpTransportInfo.Setup> setups = ImmutableSet.copyOf(Collections2.transform(
                contents.values(),
                dt->dt.transport.getFingerprint().getSetup()
        ));
        final IceUdpTransportInfo.Setup setup = Iterables.getFirst(setups, null);
        if (setups.size() == 1 && setup != null) {
            return setup;
        }
        throw new IllegalStateException("Content map doesn't have distinct DTLS setup");
    }

    public boolean emptyCandidates() {
        int count = 0;
        for (DescriptionTransport descriptionTransport : contents.values()) {
            count += descriptionTransport.transport.getCandidates().size();
        }
        return count == 0;
    }

    public RtpContentMap modifiedCredentials(Map<String, IceUdpTransportInfo.Credentials> credentialsMap, final IceUdpTransportInfo.Setup setup) {
        final ImmutableMap.Builder<String, DescriptionTransport> contentMapBuilder = new ImmutableMap.Builder<>();
        for (final Map.Entry<String, DescriptionTransport> content : contents.entrySet()) {
            final RtpDescription rtpDescription = content.getValue().description;
            IceUdpTransportInfo transportInfo = content.getValue().transport;
            final IceUdpTransportInfo.Credentials credentials = Objects.requireNonNull(credentialsMap.get(content.getKey()));
            final IceUdpTransportInfo modifiedTransportInfo = transportInfo.modifyCredentials(credentials, setup);
            contentMapBuilder.put(content.getKey(), new DescriptionTransport(rtpDescription, modifiedTransportInfo));
        }
        return new RtpContentMap(this.group, contentMapBuilder.build());
    }

    public static class DescriptionTransport {
        public final RtpDescription description;
        public final IceUdpTransportInfo transport;

        public DescriptionTransport(final RtpDescription description, final IceUdpTransportInfo transport) {
            this.description = description;
            this.transport = transport;
        }

        public static DescriptionTransport of(final Content content) {
            final GenericDescription description = content.getDescription();
            final GenericTransportInfo transportInfo = content.getTransport();
            final RtpDescription rtpDescription;
            final IceUdpTransportInfo iceUdpTransportInfo;
            if (description == null) {
                rtpDescription = null;
            } else if (description instanceof RtpDescription) {
                rtpDescription = (RtpDescription) description;
            } else {
                throw new UnsupportedApplicationException("Content does not contain rtp description");
            }
            if (transportInfo instanceof IceUdpTransportInfo) {
                iceUdpTransportInfo = (IceUdpTransportInfo) transportInfo;
            } else {
                throw new UnsupportedTransportException("Content does not contain ICE-UDP transport");
            }
            return new DescriptionTransport(
                    rtpDescription,
                    OmemoVerifiedIceUdpTransportInfo.upgrade(iceUdpTransportInfo)
            );
        }

        public static DescriptionTransport of(final SessionDescription sessionDescription, final SessionDescription.Media media) {
            final RtpDescription rtpDescription = RtpDescription.of(sessionDescription, media);
            final IceUdpTransportInfo transportInfo = IceUdpTransportInfo.of(sessionDescription, media);
            return new DescriptionTransport(rtpDescription, transportInfo);
        }

        public static Map<String, DescriptionTransport> of(final Map<String, Content> contents) {
            return ImmutableMap.copyOf(Maps.transformValues(contents, new Function<Content, DescriptionTransport>() {
                @NullableDecl
                @Override
                public DescriptionTransport apply(@NullableDecl Content content) {
                    return content == null ? null : of(content);
                }
            }));
        }
    }

    public static class UnsupportedApplicationException extends IllegalArgumentException {
        UnsupportedApplicationException(String message) {
            super(message);
        }
    }

    public static class UnsupportedTransportException extends IllegalArgumentException {
        UnsupportedTransportException(String message) {
            super(message);
        }
    }
}
