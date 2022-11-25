package eu.siacs.conversations.xmpp.jingle;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.RtpSender;

class TrackWrapper<T extends MediaStreamTrack> {
    public final T track;
    public final RtpSender rtpSender;

    private TrackWrapper(final T track, final RtpSender rtpSender) {
        Preconditions.checkNotNull(track);
        Preconditions.checkNotNull(rtpSender);
        this.track = track;
        this.rtpSender = rtpSender;
    }

    public static <T extends MediaStreamTrack> TrackWrapper<T> addTrack(
            final PeerConnection peerConnection, final T mediaStreamTrack) {
        final RtpSender rtpSender = peerConnection.addTrack(mediaStreamTrack);
        return new TrackWrapper<>(mediaStreamTrack, rtpSender);
    }

    public static <T extends MediaStreamTrack> Optional<T> get(
            final TrackWrapper<T> trackWrapper) {
        return trackWrapper == null ? Optional.absent() : Optional.of(trackWrapper.track);
    }
}
