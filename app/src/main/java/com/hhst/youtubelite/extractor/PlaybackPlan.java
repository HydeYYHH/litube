package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamType;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chosen playback plan for one video.
 */
@Data
@NoArgsConstructor
public class PlaybackPlan {
	@NonNull
	private PlaybackMode mode = PlaybackMode.NONE;
	@NonNull
	private StreamType streamType = StreamType.VIDEO_STREAM;
	@Nullable
	private Delivery delivery;
	@Nullable
	private StreamCandidate videoCandidate;
	@Nullable
	private StreamCandidate audioCandidate;
	@Nullable
	private StreamCandidate muxedCandidate;

	@Nullable
	public String getManifestUrl() {
		return delivery != null ? delivery.getUrl() : null;
	}
}
