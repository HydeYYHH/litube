package com.hhst.youtubelite.downloader.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import lombok.Data;

/**
 * Component that handles app logic.
 */
@Data
public final class PlaylistDownloadItem {
	private final int playlistIndex;
	@NonNull
	private final String videoId;
	@NonNull
	private final String videoUrl;
	@NonNull
	private String title;
	@Nullable
	private String author;
	@Nullable
	private String thumbnailUrl;
	private long durationSeconds;
	@NonNull
	private AvailabilityStatus availabilityStatus;
	@NonNull
	private BatchResultStatus batchResultStatus;
	private boolean selected;
	@Nullable
	private String failureReason;

	public PlaylistDownloadItem(int playlistIndex,
	                            @NonNull String videoId,
	                            @NonNull String videoUrl) {
		this.playlistIndex = playlistIndex;
		this.videoId = videoId;
		this.videoUrl = videoUrl;
		this.title = videoId;
		this.availabilityStatus = AvailabilityStatus.LOADING;
		this.batchResultStatus = BatchResultStatus.NOT_STARTED;
	}

	public void setTitle(@Nullable String title) {
		if (title == null || title.isBlank()) return;
		this.title = title;
	}

	public void setAuthor(@Nullable String author) {
		this.author = author;
	}

	public void setThumbnailUrl(@Nullable String thumbnailUrl) {
		this.thumbnailUrl = thumbnailUrl;
	}

	public void setDurationSeconds(long durationSeconds) {
		this.durationSeconds = Math.max(0L, durationSeconds);
	}

	public void setAvailabilityStatus(@NonNull AvailabilityStatus availabilityStatus) {
		this.availabilityStatus = availabilityStatus;
	}

	public void setBatchResultStatus(@NonNull BatchResultStatus batchResultStatus) {
		this.batchResultStatus = batchResultStatus;
	}

	public void setFailureReason(@Nullable String failureReason) {
		this.failureReason = failureReason;
	}

	public boolean isReady() {
		return availabilityStatus == AvailabilityStatus.READY;
	}

	public boolean isSelectable() {
		return isReady() && batchResultStatus != BatchResultStatus.CANCELED;
	}

/**
 * Enumeration of app logic.
 */
	public enum AvailabilityStatus {
		LOADING,
		READY,
		UNAVAILABLE,
		LOAD_FAILED
	}

/**
 * Enumeration of app logic.
 */
	public enum BatchResultStatus {
		NOT_STARTED,
		PREPARING,
		QUEUED,
		PARTIAL,
		FAILED,
		CANCELED
	}
}
