package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

/**
 * Preference storage for download settings.
 */
public final class DownloadPrefs {
	private static final String KEY_VID_ON = "download_single_video_enabled";
	private static final String KEY_AUD_ON = "download_single_audio_enabled";
	private static final String KEY_SUB_ON = "download_subtitle_enabled";
	private static final String KEY_THUMB_ON = "download_thumbnail_enabled";
	private static final String KEY_SUB_LANG = "download_subtitle_language";
	private static final String KEY_MEDIA_MODE = "download_primary_media_mode";
	private static final String KEY_THREADS = "download_thread_count";
	private static final String KEY_VID_ITAG = "download_video_itag";
	private static final String KEY_VID_H = "download_video_height";
	private static final String KEY_VID_FPS = "download_video_fps";
	private static final String KEY_AUD_ITAG = "download_audio_itag";
	private static final int DEF_THREADS = 4;
	private static final int NO_VALUE = -1;

	@NonNull
	private final MMKV kv;

	public DownloadPrefs(@NonNull MMKV kv) {
		this.kv = kv;
	}

	public boolean isSingleVideoEnabled() {
		return kv.decodeBool(KEY_VID_ON, false);
	}

	public void setSingleVideoEnabled(boolean enabled) {
		kv.encode(KEY_VID_ON, enabled);
	}

	public boolean isSingleAudioEnabled() {
		return kv.decodeBool(KEY_AUD_ON, false);
	}

	public void setSingleAudioEnabled(boolean enabled) {
		kv.encode(KEY_AUD_ON, enabled);
	}

	public boolean isSubtitleEnabled() {
		return kv.decodeBool(KEY_SUB_ON, false);
	}

	public void setSubtitleEnabled(boolean enabled) {
		kv.encode(KEY_SUB_ON, enabled);
	}

	public boolean isThumbnailEnabled() {
		return kv.decodeBool(KEY_THUMB_ON, false);
	}

	public void setThumbnailEnabled(boolean enabled) {
		kv.encode(KEY_THUMB_ON, enabled);
	}

	@Nullable
	public String getSubLang() {
		return kv.decodeString(KEY_SUB_LANG, null);
	}

	@NonNull
	public DownloadSelectionConfig.PrimaryMediaMode getPrimaryMediaMode() {
		return readMediaMode();
	}

	public void setPrimaryMediaMode(@NonNull DownloadSelectionConfig.PrimaryMediaMode mode) {
		kv.encode(KEY_MEDIA_MODE, mode.ordinal());
	}

	@NonNull
	private DownloadSelectionConfig.PrimaryMediaMode readMediaMode() {
		int ordinal = kv.decodeInt(KEY_MEDIA_MODE, DownloadSelectionConfig.PrimaryMediaMode.VIDEO.ordinal());
		DownloadSelectionConfig.PrimaryMediaMode[] modes = DownloadSelectionConfig.PrimaryMediaMode.values();
		return ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : DownloadSelectionConfig.PrimaryMediaMode.VIDEO;
	}

	public int getThreadCount() {
		return Math.max(1, kv.decodeInt(KEY_THREADS, DEF_THREADS));
	}

	public void setThreadCount(int threadCount) {
		kv.encode(KEY_THREADS, Math.max(1, threadCount));
	}

	public void saveVideoSelection(@Nullable VideoStream stream) {
		if (stream == null) return;
		kv.encode(KEY_VID_ON, true);
		kv.encode(KEY_VID_ITAG, stream.getItag());
		kv.encode(KEY_VID_H, stream.getHeight());
		kv.encode(KEY_VID_FPS, stream.getFps());
	}

	public void saveAudioSelection(@Nullable AudioStream stream) {
		if (stream == null) return;
		kv.encode(KEY_AUD_ON, true);
		kv.encode(KEY_AUD_ITAG, stream.getItag());
	}

	public void saveSubtitleSelection(@Nullable SubtitlesStream stream) {
		if (stream == null) return;
		kv.encode(KEY_SUB_ON, true);
		kv.encode(KEY_SUB_LANG, stream.getDisplayLanguageName());
	}

	@Nullable
	public VideoStream restoreVideoSelection(@NonNull List<VideoStream> streams) {
		if (streams.isEmpty() || !isSingleVideoEnabled()) return null;

		int itag = kv.decodeInt(KEY_VID_ITAG, NO_VALUE);
		if (itag != NO_VALUE) {
			for (VideoStream stream : streams) {
				if (stream.getItag() == itag) return stream;
			}
		}

		int targetHeight = kv.decodeInt(KEY_VID_H, NO_VALUE);
		if (targetHeight <= 0) return null;
		int targetFps = kv.decodeInt(KEY_VID_FPS, NO_VALUE);

		VideoStream best = null;
		for (VideoStream stream : streams) {
			int height = stream.getHeight();
			if (height <= 0 || height > targetHeight) continue;
			if (targetFps > 0 && height == targetHeight) {
				int fps = stream.getFps();
				if (fps > 0 && fps > targetFps) continue;
			}
			if (best == null || isBetterVid(stream, best)) best = stream;
		}
		return best;
	}

	@Nullable
	public AudioStream restoreAudioSelection(@NonNull List<AudioStream> streams) {
		if (streams.isEmpty() || !isSingleAudioEnabled()) return null;

		int itag = kv.decodeInt(KEY_AUD_ITAG, NO_VALUE);
		if (itag != NO_VALUE) {
			for (AudioStream stream : streams) {
				if (stream.getItag() == itag) return stream;
			}
		}

		for (AudioStream stream : streams) {
			if (stream.getAudioTrackType() == AudioTrackType.ORIGINAL) return stream;
		}
		return streams.get(0);
	}

	@Nullable
	public SubtitlesStream restoreSubtitleSelection(@NonNull List<SubtitlesStream> streams) {
		if (streams.isEmpty() || !isSubtitleEnabled()) return null;

		String language = getSubLang();
		if (language != null) {
			for (SubtitlesStream stream : streams) {
				if (language.equals(stream.getDisplayLanguageName())) return stream;
			}
		}
		return streams.get(0);
	}

	private boolean isBetterVid(@NonNull VideoStream candidate, @NonNull VideoStream best) {
		int candidateHeight = candidate.getHeight();
		int bestHeight = best.getHeight();
		if (candidateHeight != bestHeight) return candidateHeight > bestHeight;

		int candidateFps = candidate.getFps();
		int bestFps = best.getFps();
		if (candidateFps != bestFps) return candidateFps > bestFps;

		return candidate.getBitrate() > best.getBitrate();
	}
}
