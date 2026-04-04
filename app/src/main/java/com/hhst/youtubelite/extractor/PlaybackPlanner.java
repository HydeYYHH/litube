package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.player.common.PlayerUtils;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Planner that selects the best playback delivery path.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlaybackPlanner {
	private PlaybackPlanner() {
	}

	@NonNull
	public static PlaybackPlan plan(@NonNull DeliveryCatalog deliveries) {
		return plan(deliveries, null, null);
	}

	@NonNull
	public static PlaybackPlan plan(@NonNull DeliveryCatalog deliveries,
	                                @Nullable String preferredQuality,
	                                @Nullable String preferredAudioLanguage) {
		PlaybackPlan plan = new PlaybackPlan();
		plan.setStreamType(deliveries.getStreamType());

		boolean live = deliveries.getStreamType() == StreamType.LIVE_STREAM
						|| deliveries.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
		if (live) {
			Delivery dash = deliveries.first(PlaybackMode.LIVE_DASH);
			if (dash != null) {
				return adaptivePlan(plan, dash, preferredQuality, preferredAudioLanguage);
			}
			Delivery hls = deliveries.first(PlaybackMode.LIVE_HLS);
			if (hls != null) {
				plan.setMode(PlaybackMode.LIVE_HLS);
				plan.setDelivery(hls);
				return plan;
			}
			return plan;
		}

		Delivery adaptive = deliveries.first(PlaybackMode.ADAPTIVE);
		if (adaptive != null) {
			PlaybackPlan adaptivePlan = adaptivePlan(plan, adaptive, preferredQuality, preferredAudioLanguage);
			if (adaptivePlan.getVideoCandidate() != null && adaptivePlan.getAudioCandidate() != null) {
				return adaptivePlan;
			}
		}

		Delivery muxed = deliveries.first(PlaybackMode.MUXED);
		if (muxed != null) {
			VideoStream selected = PlayerUtils.selectVideoStream(muxed.muxedStreams(), preferredQuality);
			plan.setMode(PlaybackMode.MUXED);
			plan.setDelivery(muxed);
			plan.setMuxedCandidate(findVideoCandidate(muxed.getMuxed(), selected));
			return plan;
		}

		Delivery audio = deliveries.first(PlaybackMode.AUDIO_ONLY);
		if (audio != null) {
			List<AudioStream> tracks = reorderAudioTracks(audio.audioStreams(), preferredAudioLanguage);
			plan.setMode(PlaybackMode.AUDIO_ONLY);
			plan.setDelivery(audio);
			plan.setAudioCandidate(findAudioCandidate(audio.getAudio(),
							PlayerUtils.selectAudioStream(tracks, null)));
		}
		return plan;
	}

	@Nullable
	private static StreamCandidate findVideoCandidate(@NonNull List<StreamCandidate> candidates,
	                                                  @Nullable VideoStream stream) {
		if (stream == null) {
			return null;
		}
		for (StreamCandidate candidate : candidates) {
			if (candidate.getVideoStream() != null
							&& stream.getContent().equals(candidate.getVideoStream().getContent())) {
				return candidate;
			}
		}
		return null;
	}

	@Nullable
	private static StreamCandidate findAudioCandidate(@NonNull List<StreamCandidate> candidates,
	                                                  @Nullable AudioStream stream) {
		if (stream == null) {
			return null;
		}
		for (StreamCandidate candidate : candidates) {
			if (candidate.getAudioStream() != null
							&& stream.getContent().equals(candidate.getAudioStream().getContent())) {
				return candidate;
			}
		}
		return null;
	}

	@NonNull
	private static List<AudioStream> reorderAudioTracks(@NonNull List<AudioStream> source,
	                                                    @Nullable String preferredLanguage) {
		if (source.isEmpty()) {
			return source;
		}
		List<AudioStream> reordered = new ArrayList<>(source);
		reordered.sort((first, second) -> compareAudioStreams(first, second, preferredLanguage));
		return reordered;
	}

	private static int compareAudioStreams(@NonNull AudioStream first,
	                                       @NonNull AudioStream second,
	                                       @Nullable String preferredLanguage) {
		int originalComparison = Boolean.compare(
						isOriginal(second),
						isOriginal(first));
		if (originalComparison != 0) {
			return originalComparison;
		}
		int languageComparison = Boolean.compare(
						matchesLanguage(second, preferredLanguage),
						matchesLanguage(first, preferredLanguage));
		if (languageComparison != 0) {
			return languageComparison;
		}
		return Integer.compare(audioBitrate(second), audioBitrate(first));
	}

	private static boolean isOriginal(@NonNull AudioStream stream) {
		return stream.getAudioTrackType() == AudioTrackType.ORIGINAL
						|| (stream.getAudioTrackName() != null
						&& stream.getAudioTrackName().toLowerCase(Locale.ROOT).contains("original"));
	}

	private static boolean matchesLanguage(@NonNull AudioStream stream,
	                                       @Nullable String preferredLanguage) {
		if (preferredLanguage == null || preferredLanguage.isBlank()) {
			return false;
		}
		return stream.getAudioLocale() != null
						&& preferredLanguage.equalsIgnoreCase(stream.getAudioLocale().getLanguage());
	}

	private static int audioBitrate(@NonNull AudioStream stream) {
		return stream.getAverageBitrate() > 0 ? stream.getAverageBitrate() : stream.getBitrate();
	}

	@NonNull
	private static PlaybackPlan adaptivePlan(@NonNull PlaybackPlan plan,
	                                         @NonNull Delivery delivery,
	                                         @Nullable String preferredQuality,
	                                         @Nullable String preferredAudioLanguage) {
		plan.setMode(delivery.getMode());
		plan.setDelivery(delivery);
		VideoStream video = PlayerUtils.selectVideoStream(delivery.videoStreams(), preferredQuality);
		List<AudioStream> tracks = reorderAudioTracks(delivery.audioStreams(), preferredAudioLanguage);
		plan.setVideoCandidate(findVideoCandidate(delivery.getVideo(), video));
		plan.setAudioCandidate(findAudioCandidate(delivery.getAudio(), PlayerUtils.selectAudioStream(tracks, null)));
		return plan;
	}
}
