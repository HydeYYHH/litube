package com.hhst.youtubelite.player.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.util.StringUtils;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@UnstableApi
public final class PlayerUtils {

    /**
     * Checks if the video should be played in portrait orientation based on its dimensions.
     *
     * @param engine The engine instance
     * @return True if video should be in portrait, false otherwise (landscape by default)
     */
    public static boolean isPortrait(@NonNull final Engine engine) {
        final int videoWidth = engine.getVideoSize().width;
        final int videoHeight = engine.getVideoSize().height;
        return videoWidth > 0 && videoHeight > 0 && videoHeight > videoWidth;
    }

    /**
     * Filters streams to keep only the best quality stream for each resolution.
     *
     * @param streams List of available video streams
     * @return Filtered and sorted list of video streams
     */
    @NonNull
    public static List<VideoStream> filterBestStreams(@Nullable final List<VideoStream> streams) {
        if (streams == null || streams.isEmpty()) return new ArrayList<>();

        final Map<String, VideoStream> bestMap = new HashMap<>();

        for (final VideoStream stream : streams) {
            final String res = stream.getResolution();
            final VideoStream existing = bestMap.get(res);

            if (existing == null || isBetterStream(stream, existing)) bestMap.put(res, stream);
        }

        final List<VideoStream> result = new ArrayList<>(bestMap.values());
        result.sort((s1, s2) -> {
            final int h1 = s1.getHeight();
            final int h2 = s2.getHeight();
            if (h1 != h2) return Integer.compare(h2, h1);
            return Integer.compare(s2.getFps(), s1.getFps());
        });
        return result;
    }

    /**
     * Determines if video stream s1 is better than s2.
     * Priority order: Codec Priority > FPS > Bitrate
     *
     * @param s1 First video stream to compare
     * @param s2 Second video stream to compare
     * @return True if s1 is better than s2
     */
    public static boolean isBetterStream(@NonNull final VideoStream s1, @NonNull final VideoStream s2) {
        final int p1 = getCodecPriority(s1.getCodec());
        final int p2 = getCodecPriority(s2.getCodec());
        if (p1 != p2) return p1 > p2;

        if (s1.getFps() != s2.getFps()) return s1.getFps() > s2.getFps();

        return s1.getBitrate() > s2.getBitrate();
    }

    /**
     * Returns priority score for video codec.
     * Higher score means better compatibility/support.
     * Priority order: AVC/H264 > VP9/VP8 > H265 > AV01 > Others
     *
     * @param codec Codec string
     * @return Priority score (0-4)
     */
    public static int getCodecPriority(@Nullable final String codec) {
        if (codec == null) return 0;
        final String lowerCodec = codec.toLowerCase();
        if (lowerCodec.startsWith("avc") || lowerCodec.startsWith("h264")) return 4;
        if (lowerCodec.contains("vp9") || lowerCodec.contains("vp8")) return 3;
        if (lowerCodec.contains("h265")) return 2;
        if (lowerCodec.contains("av01")) return 1;

        return 0;
    }

    /**
     * Selects the most appropriate video stream based on target resolution.
     *
     * @param streams   List of sorted video streams (highest resolution first)
     * @param targetRes Target resolution string (e.g., "1080p")
     * @return Selected video stream
     */
    @Nullable
    public static VideoStream selectVideoStream(@Nullable final List<VideoStream> streams, @Nullable final String targetRes) {
        if (streams == null || streams.isEmpty()) return null;
        if (targetRes == null) return streams.get(0);

        for (final VideoStream s : streams) if (s.getResolution().equals(targetRes)) return s;

        final int targetHeight = StringUtils.parseHeight(targetRes);
        for (final VideoStream s : streams) if (s.getHeight() <= targetHeight) return s;

        return streams.get(0);
    }

    /**
     * Selects the most appropriate audio stream based on preferred track info.
     *
     * @param streams       List of available audio streams
     * @param preferredInfo Formatted preferred track info string
     * @return Selected audio stream
     */
    @Nullable
    public static AudioStream selectAudioStream(@Nullable final List<AudioStream> streams, @Nullable final String preferredInfo) {
        if (streams == null || streams.isEmpty()) return null;
        if (preferredInfo == null) return streams.get(0);

        for (final AudioStream as : streams) {
            final int bitrate = as.getAverageBitrate();
            final String bitrateStr = bitrate > 0 ? bitrate + "kbps" : "Unknown bitrate";
            final String info = String.format(Locale.getDefault(), "%s - %s - %s", as.getFormat(), as.getCodec(), bitrateStr);
            if (info.equals(preferredInfo)) return as;
        }

        return streams.get(0);
    }
}
