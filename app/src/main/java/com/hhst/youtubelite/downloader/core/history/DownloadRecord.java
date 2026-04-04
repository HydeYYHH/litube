package com.hhst.youtubelite.downloader.core.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persisted download history record.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadRecord {
	@NonNull
	private String taskId;
	@NonNull
	private String videoId;
	@NonNull
	private DownloadType type;
	@NonNull
	private DownloadStatus status;
	private int progress;
	@NonNull
	private String fileName;
	@NonNull
	private String outputPath;
	private long createdAt;
	private long updatedAt;
	@Nullable
	private String errorMessage;
	private long downloadedSize;
	private long totalSize;
	@Nullable
	private String parentId;
	@Nullable
	private String title;
	@Nullable
	private String thumbnailUrl;
	private int itemCount;
	private int doneCount;
	private int failedCount;
	private int runningCount;
	private boolean sealed;
}
