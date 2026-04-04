package com.hhst.youtubelite.extractor;

/**
 * Enumeration of stream candidate kinds.
 */
public enum StreamCandidateKind {
	DASH_MANIFEST,
	HLS_MANIFEST,
	VIDEO_ONLY,
	AUDIO_ONLY,
	MUXED,
	SUBTITLE
}
