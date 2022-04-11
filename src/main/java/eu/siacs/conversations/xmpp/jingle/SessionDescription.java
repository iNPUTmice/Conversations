package eu.siacs.conversations.xmpp.jingle;

import android.util.Log;
import android.util.Pair;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.jingle.stanzas.Group;
import eu.siacs.conversations.xmpp.jingle.stanzas.IceUdpTransportInfo;
import eu.siacs.conversations.xmpp.jingle.stanzas.RtpDescription;

public class SessionDescription {

    public final static String LINE_DIVIDER = "\r\n";
    private final static String HARDCODED_MEDIA_PROTOCOL = "UDP/TLS/RTP/SAVPF"; //probably only true for DTLS-SRTP aka when we have a fingerprint
    private final static int HARDCODED_MEDIA_PORT = 9;
    private final static String HARDCODED_ICE_OPTIONS = "trickle";
    private final static String HARDCODED_CONNECTION = "IN IP4 0.0.0.0";

    public final int version;
    public final String name;
    public final String connectionData;
    public final ArrayListMultimap<String, String> attributes;
    public final List<Media> media;


    public SessionDescription(int version, String name, String connectionData, ArrayListMultimap<String, String> attributes, List<Media> media) {
        this.version = version;
        this.name = name;
        this.connectionData = connectionData;
        this.attributes = attributes;
        this.media = media;
    }

    private static void appendAttributes(StringBuilder s, ArrayListMultimap<String, String> attributes) {
        for (Map.Entry<String, String> attribute : attributes.entries()) {
            final String key = attribute.getKey();
            final String value = attribute.getValue();
            s.append("a=").append(key);
            if (!Strings.isNullOrEmpty(value)) {
                s.append(':').append(value);
            }
            s.append(LINE_DIVIDER);
        }
    }

    public static SessionDescription parse(final String input) {
        final SessionDescriptionBuilder sessionDescriptionBuilder = new SessionDescriptionBuilder();
        MediaBuilder currentMediaBuilder = null;
        ArrayListMultimap<String, String> attributeMap = ArrayListMultimap.create();
        ImmutableList.Builder<Media> mediaBuilder = new ImmutableList.Builder<>();
        for (final String line : input.split(LINE_DIVIDER)) {
            final String[] pair = line.trim().split("=", 2);
            if (pair.length < 2 || pair[0].length() != 1) {
                Log.d(Config.LOGTAG, "skipping sdp parsing on line " + line);
                continue;
            }
            final char key = pair[0].charAt(0);
            final String value = pair[1];
            switch (key) {
                case 'v':
                    sessionDescriptionBuilder.setVersion(ignorantIntParser(value));
                    break;
                case 'c':
                    if (currentMediaBuilder != null) {
                        currentMediaBuilder.setConnectionData(value);
                    } else {
                        sessionDescriptionBuilder.setConnectionData(value);
                    }
                    break;
                case 's':
                    sessionDescriptionBuilder.setName(value);
                    break;
                case 'a':
                    final Pair<String, String> attribute = parseAttribute(value);
                    attributeMap.put(attribute.first, attribute.second);
                    break;
                case 'm':
                    if (currentMediaBuilder == null) {
                        sessionDescriptionBuilder.setAttributes(attributeMap);
                    } else {
                        currentMediaBuilder.setAttributes(attributeMap);
                        mediaBuilder.add(currentMediaBuilder.createMedia());
                    }
                    attributeMap = ArrayListMultimap.create();
                    currentMediaBuilder = new MediaBuilder();
                    final String[] parts = value.split(" ");
                    if (parts.length >= 3) {
                        currentMediaBuilder.setMedia(parts[0]);
                        currentMediaBuilder.setPort(ignorantIntParser(parts[1]));
                        currentMediaBuilder.setProtocol(parts[2]);
                        ImmutableList.Builder<Integer> formats = new ImmutableList.Builder<>();
                        for (int i = 3; i < parts.length; ++i) {
                            formats.add(ignorantIntParser(parts[i]));
                        }
                        currentMediaBuilder.setFormats(formats.build());
                    } else {
                        Log.d(Config.LOGTAG, "skipping media line " + line);
                    }
                    break;
            }

        }
        if (currentMediaBuilder != null) {
            currentMediaBuilder.setAttributes(attributeMap);
            mediaBuilder.add(currentMediaBuilder.createMedia());
        } else {
            sessionDescriptionBuilder.setAttributes(attributeMap);
        }
        sessionDescriptionBuilder.setMedia(mediaBuilder.build());
        return sessionDescriptionBuilder.createSessionDescription();
    }

    public static SessionDescription of(final RtpContentMap contentMap) {
        final SessionDescriptionBuilder sessionDescriptionBuilder = new SessionDescriptionBuilder();
        final ArrayListMultimap<String, String> attributeMap = ArrayListMultimap.create();
        final ImmutableList.Builder<Media> mediaListBuilder = new ImmutableList.Builder<>();
        final Group group = contentMap.group;
        if (group != null) {
            final String semantics = group.getSemantics();
            checkNoWhitespace(semantics, "group semantics value must not contain any whitespace");
            attributeMap.put("group", group.getSemantics() + " " + Joiner.on(' ').join(group.getIdentificationTags()));
        }

        attributeMap.put("msid-semantic", " WMS my-media-stream");

        for (final Map.Entry<String, RtpContentMap.DescriptionTransport> entry : contentMap.contents.entrySet()) {
            final String name = entry.getKey();
            RtpContentMap.DescriptionTransport descriptionTransport = entry.getValue();
            RtpDescription description = descriptionTransport.description;
            IceUdpTransportInfo transport = descriptionTransport.transport;
            final ArrayListMultimap<String, String> mediaAttributes = ArrayListMultimap.create();
            final String ufrag = transport.getAttribute("ufrag");
            final String pwd = transport.getAttribute("pwd");
            if (Strings.isNullOrEmpty(ufrag)) {
                throw new IllegalArgumentException("Transport element is missing required ufrag attribute");
            }
            checkNoWhitespace(ufrag, "ufrag value must not contain any whitespaces");
            mediaAttributes.put("ice-ufrag", ufrag);
            if (Strings.isNullOrEmpty(pwd)) {
                throw new IllegalArgumentException("Transport element is missing required pwd attribute");
            }
            checkNoWhitespace(pwd, "pwd value must not contain any whitespaces");
            mediaAttributes.put("ice-pwd", pwd);
            mediaAttributes.put("ice-options", HARDCODED_ICE_OPTIONS);
            final IceUdpTransportInfo.Fingerprint fingerprint = transport.getFingerprint();
            if (fingerprint != null) {
                mediaAttributes.put("fingerprint", fingerprint.getHash() + " " + fingerprint.getContent());
                mediaAttributes.put("setup", fingerprint.getSetup());
            }
            final ImmutableList.Builder<Integer> formatBuilder = new ImmutableList.Builder<>();
            for (RtpDescription.PayloadType payloadType : description.getPayloadTypes()) {
                final String id = payloadType.getId();
                if (Strings.isNullOrEmpty(id)) {
                    throw new IllegalArgumentException("Payload type is missing id");
                }
                if (!isInt(id)) {
                    throw new IllegalArgumentException("Payload id is not numeric");
                }
                formatBuilder.add(payloadType.getIntId());
                mediaAttributes.put("rtpmap", payloadType.toSdpAttribute());
                final List<RtpDescription.Parameter> parameters = payloadType.getParameters();
                if (parameters.size() == 1) {
                    mediaAttributes.put("fmtp", RtpDescription.Parameter.toSdpString(id, parameters.get(0)));
                } else if (parameters.size() > 0) {
                    mediaAttributes.put("fmtp", RtpDescription.Parameter.toSdpString(id, parameters));
                }
                for (RtpDescription.FeedbackNegotiation feedbackNegotiation : payloadType.getFeedbackNegotiations()) {
                    final String type = feedbackNegotiation.getType();
                    final String subtype = feedbackNegotiation.getSubType();
                    if (Strings.isNullOrEmpty(type)) {
                        throw new IllegalArgumentException("a feedback for payload-type " + id + " negotiation is missing type");
                    }
                    checkNoWhitespace(type, "feedback negotiation type must not contain whitespace");
                    mediaAttributes.put("rtcp-fb", id + " " + type + (Strings.isNullOrEmpty(subtype) ? "" : " " + subtype));
                }
                for (RtpDescription.FeedbackNegotiationTrrInt feedbackNegotiationTrrInt : payloadType.feedbackNegotiationTrrInts()) {
                    mediaAttributes.put("rtcp-fb", id + " trr-int " + feedbackNegotiationTrrInt.getValue());
                }
            }

            for (RtpDescription.FeedbackNegotiation feedbackNegotiation : description.getFeedbackNegotiations()) {
                final String type = feedbackNegotiation.getType();
                final String subtype = feedbackNegotiation.getSubType();
                if (Strings.isNullOrEmpty(type)) {
                    throw new IllegalArgumentException("a feedback negotiation is missing type");
                }
                checkNoWhitespace(type, "feedback negotiation type must not contain whitespace");
                mediaAttributes.put("rtcp-fb", "* " + type + (Strings.isNullOrEmpty(subtype) ? "" : " " + subtype));
            }
            for (final RtpDescription.FeedbackNegotiationTrrInt feedbackNegotiationTrrInt : description.feedbackNegotiationTrrInts()) {
                mediaAttributes.put("rtcp-fb", "* trr-int " + feedbackNegotiationTrrInt.getValue());
            }
            for (final RtpDescription.RtpHeaderExtension extension : description.getHeaderExtensions()) {
                final String id = extension.getId();
                final String uri = extension.getUri();
                if (Strings.isNullOrEmpty(id)) {
                    throw new IllegalArgumentException("A header extension is missing id");
                }
                checkNoWhitespace(id, "header extension id must not contain whitespace");
                if (Strings.isNullOrEmpty(uri)) {
                    throw new IllegalArgumentException("A header extension is missing uri");
                }
                checkNoWhitespace(uri, "feedback negotiation uri must not contain whitespace");
                mediaAttributes.put("extmap", id + " " + uri);
            }

            if (description.hasChild("extmap-allow-mixed", Namespace.JINGLE_RTP_HEADER_EXTENSIONS)) {
                mediaAttributes.put("extmap-allow-mixed", "");
            }

            for (final RtpDescription.SourceGroup sourceGroup : description.getSourceGroups()) {
                final String semantics = sourceGroup.getSemantics();
                final List<String> groups = sourceGroup.getSsrcs();
                if (Strings.isNullOrEmpty(semantics)) {
                    throw new IllegalArgumentException("A SSRC group is missing semantics attribute");
                }
                checkNoWhitespace(semantics, "source group semantics must not contain whitespace");
                if (groups.size() == 0) {
                    throw new IllegalArgumentException("A SSRC group is missing SSRC ids");
                }
                mediaAttributes.put("ssrc-group", String.format("%s %s", semantics, Joiner.on(' ').join(groups)));
            }
            for (final RtpDescription.Source source : description.getSources()) {
                for (final RtpDescription.Source.Parameter parameter : source.getParameters()) {
                    final String id = source.getSsrcId();
                    final String parameterName = parameter.getParameterName();
                    final String parameterValue = parameter.getParameterValue();
                    if (Strings.isNullOrEmpty(id)) {
                        throw new IllegalArgumentException("A source specific media attribute is missing the id");
                    }
                    checkNoWhitespace(id, "A source specific media attributes must not contain whitespaces");
                    if (Strings.isNullOrEmpty(parameterName)) {
                        throw new IllegalArgumentException("A source specific media attribute is missing its name");
                    }
                    if (Strings.isNullOrEmpty(parameterValue)) {
                        throw new IllegalArgumentException("A source specific media attribute is missing its value");
                    }
                    mediaAttributes.put("ssrc", id + " " + parameterName + ":" + parameterValue);
                }
            }

            mediaAttributes.put("mid", name);

            //random additional attributes
            mediaAttributes.put("rtcp", "9 IN IP4 0.0.0.0");
            mediaAttributes.put("sendrecv", "");

            if (description.hasChild("rtcp-mux", Namespace.JINGLE_APPS_RTP)) {
                mediaAttributes.put("rtcp-mux", "");
            }

            final MediaBuilder mediaBuilder = new MediaBuilder();
            mediaBuilder.setMedia(description.getMedia().toString().toLowerCase(Locale.ROOT));
            mediaBuilder.setConnectionData(HARDCODED_CONNECTION);
            mediaBuilder.setPort(HARDCODED_MEDIA_PORT);
            mediaBuilder.setProtocol(HARDCODED_MEDIA_PROTOCOL);
            mediaBuilder.setAttributes(mediaAttributes);
            mediaBuilder.setFormats(formatBuilder.build());
            mediaListBuilder.add(mediaBuilder.createMedia());

        }
        sessionDescriptionBuilder.setVersion(0);
        sessionDescriptionBuilder.setName("-");
        sessionDescriptionBuilder.setMedia(mediaListBuilder.build());
        sessionDescriptionBuilder.setAttributes(attributeMap);

        return sessionDescriptionBuilder.createSessionDescription();
    }

    public static String checkNoWhitespace(final String input, final String message) {
        if (CharMatcher.whitespace().matchesAnyOf(input)) {
            throw new IllegalArgumentException(message);
        }
        return input;
    }

    public static int ignorantIntParser(final String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isInt(final String input) {
        if (input == null) {
            return false;
        }
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static Pair<String, String> parseAttribute(final String input) {
        final String[] pair = input.split(":", 2);
        if (pair.length == 2) {
            return new Pair<>(pair[0], pair[1]);
        } else {
            return new Pair<>(pair[0], "");
        }
    }

    @Override
    public String toString() {
        final StringBuilder s = new StringBuilder()
                .append("v=").append(version).append(LINE_DIVIDER)
                //TODO randomize or static
                .append("o=- 8770656990916039506 2 IN IP4 127.0.0.1").append(LINE_DIVIDER) //what ever that means
                .append("s=").append(name).append(LINE_DIVIDER)
                .append("t=0 0").append(LINE_DIVIDER);
        appendAttributes(s, attributes);
        for (Media media : this.media) {
            s.append("m=").append(media.media).append(' ').append(media.port).append(' ').append(media.protocol).append(' ').append(Joiner.on(' ').join(media.formats)).append(LINE_DIVIDER);
            s.append("c=").append(media.connectionData).append(LINE_DIVIDER);
            appendAttributes(s, media.attributes);
        }
        return s.toString();
    }

    public static class Media {
        public final String media;
        public final int port;
        public final String protocol;
        public final List<Integer> formats;
        public final String connectionData;
        public final ArrayListMultimap<String, String> attributes;

        public Media(String media, int port, String protocol, List<Integer> formats, String connectionData, ArrayListMultimap<String, String> attributes) {
            this.media = media;
            this.port = port;
            this.protocol = protocol;
            this.formats = formats;
            this.connectionData = connectionData;
            this.attributes = attributes;
        }
    }

}
