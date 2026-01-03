package com.hhst.youtubelite.downloader.core.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DownloadRecord {
	@NonNull
	private String taskId;
	@NonNull
	private String vid;
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
}

