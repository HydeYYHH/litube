package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Playback delivery candidate assembled from stream options.
 */
@Data
@NoArgsConstructor
public class Delivery {
	@NonNull
	private PlaybackMode mode = PlaybackMode.NONE;
	@NonNull
	private StreamType streamType = StreamType.VIDEO_STREAM;
	@Nullable
	private StreamCandidate manifest;
	@NonNull
	private List<StreamCandidate> video = new ArrayList<>();
	@NonNull
	private List<StreamCandidate> audio = new ArrayList<>();
	@NonNull
	private List<StreamCandidate> muxed = new ArrayList<>();
	private boolean abr;
	private boolean trackLock;
	private boolean cache;

	@Nullable
	public String getUrl() {
		return manifest != null ? manifest.getUrl() : null;
	}

	@NonNull
	public List<VideoStream> videoStreams() {
		Map<String, VideoStream> streams = new LinkedHashMap<>();
		for (StreamCandidate candidate : video) {
			if (candidate.getVideoStream() != null) {
				streams.putIfAbsent(candidate.getVideoStream().getContent(), candidate.getVideoStream());
			}
		}
		return new ArrayList<>(streams.values());
	}

	@NonNull
	public List<AudioStream> audioStreams() {
		Map<String, AudioStream> streams = new LinkedHashMap<>();
		for (StreamCandidate candidate : audio) {
			if (candidate.getAudioStream() != null) {
				streams.putIfAbsent(candidate.getAudioStream().getContent(), candidate.getAudioStream());
			}
		}
		return new ArrayList<>(streams.values());
	}

	@NonNull
	public List<VideoStream> muxedStreams() {
		Map<String, VideoStream> streams = new LinkedHashMap<>();
		for (StreamCandidate candidate : muxed) {
			if (candidate.getVideoStream() != null) {
				streams.putIfAbsent(candidate.getVideoStream().getContent(), candidate.getVideoStream());
			}
		}
		return new ArrayList<>(streams.values());
	}
}
