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

/**
 * Stream selection helpers.
 */
@UnstableApi
public final class PlayerUtils {
	public static boolean isPortrait(@NonNull Engine engine) {
		int videoWidth = engine.getVideoSize().width;
		int videoHeight = engine.getVideoSize().height;
		return videoWidth > 0 && videoHeight > 0 && videoHeight > videoWidth;
	}

	@NonNull
	public static List<VideoStream> filterBestStreams(@Nullable List<VideoStream> streams) {
		if (streams == null || streams.isEmpty()) return new ArrayList<>();

		Map<String, VideoStream> best = new HashMap<>();

		for (VideoStream stream : streams) {
			String res = stream.getResolution();
			String key = res + "#" + stream.getFps();
			VideoStream prev = best.get(key);

			if (prev == null || isBetterStream(stream, prev)) best.put(key, stream);
		}

		List<VideoStream> result = new ArrayList<>(best.values());
		result.sort((s1, s2) -> {
			int h1 = s1.getHeight();
			int h2 = s2.getHeight();
			if (h1 != h2) return Integer.compare(h2, h1);
			return Integer.compare(s2.getFps(), s1.getFps());
		});
		return result;
	}

	public static boolean isBetterStream(@NonNull VideoStream s1, @NonNull VideoStream s2) {
		int p1 = getCodecPriority(s1.getCodec());
		int p2 = getCodecPriority(s2.getCodec());
		if (p1 != p2) return p1 > p2;

		if (s1.getFps() != s2.getFps()) return s1.getFps() > s2.getFps();

		return s1.getBitrate() > s2.getBitrate();
	}

	public static int getCodecPriority(@Nullable String codec) {
		if (codec == null) return 0;
		String lower = codec.toLowerCase(Locale.ROOT);
		if (lower.startsWith("avc") || lower.startsWith("h264")) return 4;
		if (lower.contains("vp9") || lower.contains("vp8")) return 3;
		if (lower.contains("h265")) return 2;
		if (lower.contains("av01")) return 1;

		return 0;
	}

	@Nullable
	public static VideoStream selectVideoStream(@Nullable List<VideoStream> streams, @Nullable String targetRes) {
		if (streams == null || streams.isEmpty()) return null;
		if (targetRes == null) return streams.get(0);

		for (VideoStream s : streams) if (s.getResolution().equals(targetRes)) return s;

		int targetHeight = StringUtils.parseHeight(targetRes);
		for (VideoStream s : streams) if (s.getHeight() <= targetHeight) return s;

		return streams.get(0);
	}

	@Nullable
	public static AudioStream selectAudioStream(@Nullable List<AudioStream> streams, @Nullable String preferredInfo) {
		if (streams == null || streams.isEmpty()) return null;
		if (preferredInfo == null) return streams.get(0);

		for (AudioStream as : streams) {
			int bitrate = as.getAverageBitrate();
			String bitrateStr = bitrate > 0 ? bitrate + "kbps" : "Unknown bitrate";
			String info = String.format(Locale.getDefault(), "%s - %s - %s", as.getFormat(), as.getCodec(), bitrateStr);
			if (info.equals(preferredInfo)) return as;
		}

		return streams.get(0);
	}
}
