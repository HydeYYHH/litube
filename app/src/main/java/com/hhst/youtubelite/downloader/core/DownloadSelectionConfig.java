package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;

/**
 * Configuration for download selection and batching.
 */
public record DownloadSelectionConfig(
				@NonNull PrimaryMediaMode primaryMediaMode,
				boolean subtitleEnabled,
				boolean thumbnailEnabled,
				int threadCount) {

	public DownloadSelectionConfig {
		threadCount = Math.max(1, threadCount);
	}

	public boolean hasAnyOutputEnabled() {
		return primaryMediaMode != PrimaryMediaMode.NONE || subtitleEnabled || thumbnailEnabled;
	}

/**
 * Enumeration of app logic.
 */
	public enum PrimaryMediaMode {
		NONE,
		VIDEO,
		AUDIO
	}
}
