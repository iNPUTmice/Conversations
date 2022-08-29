package eu.siacs.conversations.utils;

import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy;
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy;

public final class TranscoderStrategies {

    public static final DefaultVideoStrategy VIDEO_720P = DefaultVideoStrategy.atMost(720)
            .bitRate(2L * 1000 * 1000)
            .frameRate(30)
            .keyFrameInterval(3F)
            .build();
    
    public static final DefaultVideoStrategy VIDEO_360P = DefaultVideoStrategy.atMost(360)
            .bitRate(1000 * 1000)
            .frameRate(30)
            .keyFrameInterval(3F)
            .build();

    //TODO do we want to add 240p (@500kbs) and 1080p (@4mbs?) ?
    // see suggested bit rates on https://www.videoproc.com/media-converter/bitrate-setting-for-h264.htm

    public static final DefaultAudioStrategy AUDIO_HQ = DefaultAudioStrategy.builder()
            .bitRate(192 * 1000)
            .channels(2)
            .sampleRate(DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT)
            .build();

    public static final DefaultAudioStrategy AUDIO_MQ = DefaultAudioStrategy.builder()
            .bitRate(128 * 1000)
            .channels(2)
            .sampleRate(DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT)
            .build();

    //TODO if we add 144p we definitely want to add a lower audio bit rate as well

    private TranscoderStrategies() {
        throw new IllegalStateException("Do not instantiate me");
    }

}
