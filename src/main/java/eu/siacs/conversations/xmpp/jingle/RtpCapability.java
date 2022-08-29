package eu.siacs.conversations.xmpp.jingle;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Presence;
import eu.siacs.conversations.entities.Presences;
import eu.siacs.conversations.entities.ServiceDiscoveryResult;
import eu.siacs.conversations.xml.Namespace;

public class RtpCapability {

    private static final List<String> BASIC_RTP_REQUIREMENTS = Arrays.asList(
            Namespace.JINGLE,
            Namespace.JINGLE_TRANSPORT_ICE_UDP,
            Namespace.JINGLE_APPS_RTP,
            Namespace.JINGLE_APPS_DTLS
    );
    private static final List<String> VIDEO_REQUIREMENTS = Arrays.asList(
            Namespace.JINGLE_FEATURE_AUDIO,
            Namespace.JINGLE_FEATURE_VIDEO
    );

    public static Capability check(final Presence presence) {
        final ServiceDiscoveryResult disco = presence.getServiceDiscoveryResult();
        final List<String> features = disco == null ? Collections.emptyList() : disco.getFeatures();
        if (features.containsAll(BASIC_RTP_REQUIREMENTS)) {
            if (features.containsAll(VIDEO_REQUIREMENTS)) {
                return Capability.VIDEO;
            }
            if (features.contains(Namespace.JINGLE_FEATURE_AUDIO)) {
                return Capability.AUDIO;
            }
        }
        return Capability.NONE;
    }

    public static String[] filterPresences(final Contact contact, Capability required) {
        final Presences presences = contact.getPresences();
        final ArrayList<String> resources = new ArrayList<>();
        for (final Map.Entry<String, Presence> presence : presences.getPresencesMap().entrySet()) {
            final Capability capability = check(presence.getValue());
            if (capability == Capability.NONE) {
                continue;
            }
            if (required == Capability.AUDIO || capability == required) {
                resources.add(presence.getKey());
            }
        }
        return resources.toArray(new String[0]);
    }

    public static Capability check(final Contact contact) {
        return check(contact, true);
    }

    public static Capability check(final Contact contact, final boolean allowFallback) {
        final Presences presences = contact.getPresences();
        if (presences.size() == 0 && allowFallback && contact.getAccount().isEnabled()) {
            return contact.getRtpCapability();
        }
        Capability result = Capability.NONE;
        for (final Presence presence : presences.getPresences()) {
            Capability capability = check(presence);
            if (capability == Capability.VIDEO) {
                result = capability;
            } else if (capability == Capability.AUDIO && result == Capability.NONE) {
                result = capability;
            }
        }
        return result;
    }

    public enum Capability {
        NONE, AUDIO, VIDEO;

        public static Capability of(String value) {
            if (Strings.isNullOrEmpty(value)) {
                return NONE;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }

}
