package com.hhst.youtubelite.player.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.AudioStream;

import java.util.ArrayList;
import java.util.List;

public final class AudioPreferenceUtils {
	private AudioPreferenceUtils() {
	}

	@NonNull
	public static List<AudioStream> reorder(@Nullable final List<AudioStream> audioStreams,
	                                        @NonNull final String savedLang) {
		if (audioStreams == null || audioStreams.isEmpty()) return new ArrayList<>();

		final List<AudioStream> reordered = new ArrayList<>(audioStreams);
		reordered.sort((s1, s2) -> {
			final String n1 = s1.getAudioTrackName() != null ? s1.getAudioTrackName().toLowerCase() : "";
			final String n2 = s2.getAudioTrackName() != null ? s2.getAudioTrackName().toLowerCase() : "";
			final boolean s1IsOriginal = n1.contains("original");
			final boolean s2IsOriginal = n2.contains("original");

			if (s1IsOriginal && !s2IsOriginal) return -1;
			if (!s1IsOriginal && s2IsOriginal) return 1;

			final String l1 = s1.getAudioLocale() != null ? s1.getAudioLocale().getLanguage() : "und";
			final String l2 = s2.getAudioLocale() != null ? s2.getAudioLocale().getLanguage() : "und";
			final boolean s1Matches = l1.equalsIgnoreCase(savedLang);
			final boolean s2Matches = l2.equalsIgnoreCase(savedLang);

			if (s1Matches && !s2Matches) return -1;
			if (!s1Matches && s2Matches) return 1;

			return Integer.compare(s2.getAverageBitrate(), s1.getAverageBitrate());
		});
		return reordered;
	}
}
