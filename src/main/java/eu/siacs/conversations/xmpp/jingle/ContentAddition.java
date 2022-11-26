package eu.siacs.conversations.xmpp.jingle;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;

import java.util.Set;

import eu.siacs.conversations.xmpp.jingle.stanzas.Content;

public final class ContentAddition {

    public final Direction direction;
    public final Set<Summary> summary;

    private ContentAddition(Direction direction, Set<Summary> summary) {
        this.direction = direction;
        this.summary = summary;
    }

    public Set<Media> media() {
        return ImmutableSet.copyOf(Collections2.transform(summary, s -> s.media));
    }

    public static ContentAddition of(final Direction direction, final RtpContentMap rtpContentMap) {
        return new ContentAddition(direction, summary(rtpContentMap));
    }

    public static Set<Summary> summary(final RtpContentMap rtpContentMap) {
        return ImmutableSet.copyOf(
                Collections2.transform(
                        rtpContentMap.contents.entrySet(),
                        e -> {
                            final RtpContentMap.DescriptionTransport dt = e.getValue();
                            return new Summary(e.getKey(), dt.description.getMedia(), dt.senders);
                        }));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("direction", direction)
                .add("summary", summary)
                .toString();
    }

    public enum Direction {
        OUTGOING,
        INCOMING
    }

    public static final class Summary {
        public final String name;
        public final Media media;
        public final Content.Senders senders;

        private Summary(final String name, final Media media, final Content.Senders senders) {
            this.name = name;
            this.media = media;
            this.senders = senders;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Summary summary = (Summary) o;
            return Objects.equal(name, summary.name)
                    && media == summary.media
                    && senders == summary.senders;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(name, media, senders);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("name", name)
                    .add("media", media)
                    .add("senders", senders)
                    .toString();
        }
    }
}
