package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Helper that derives stable ids for download records.
 */
public final class DownloadTaskIdHelper {
	public static final String ASSET_VIDEO = "v";
	public static final String ASSET_AUDIO = "a";
	public static final String ASSET_SUBTITLE = "s";
	public static final String ASSET_THUMBNAIL = "t";

	private DownloadTaskIdHelper() {
	}

	@NonNull
	public static String buildTaskId(@NonNull String videoId,
	                                 @NonNull String assetSuffix) {
		return videoId + ":" + assetSuffix;
	}

	@NonNull
	public static String buildPlaylistId(@NonNull String videoId,
	                                     int playlistIndex,
	                                     @NonNull String assetSuffix) {
		return videoId + "#" + playlistIndex + ":" + assetSuffix;
	}

	@NonNull
	public static String buildPlaylistId(@NonNull String videoId,
	                                     int playlistIndex,
	                                     @Nullable String parentId,
	                                     @NonNull String assetSuffix) {
		if (parentId == null || parentId.isBlank()) {
			return buildPlaylistId(videoId, playlistIndex, assetSuffix);
		}
		return videoId + "#" + playlistIndex + "@" + parentId + ":" + assetSuffix;
	}

	@NonNull
	public static String extractVidId(@NonNull String taskId) {
		int suffixIndex = taskId.lastIndexOf(':');
		String base = suffixIndex >= 0 ? taskId.substring(0, suffixIndex) : taskId;
		int playlistIndex = base.indexOf('#');
		return playlistIndex >= 0 ? base.substring(0, playlistIndex) : base;
	}

	@NonNull
	public static String extractItemKey(@NonNull String taskId) {
		int suffixIndex = taskId.lastIndexOf(':');
		return suffixIndex >= 0 ? taskId.substring(0, suffixIndex) : taskId;
	}
}
