package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Contract for starting and canceling downloads.
 */
public interface LiteDownloader {

	void setCallback(@NonNull String videoId, @Nullable ProgressCallback2 callback);

	void download(@NonNull Task task);

	void cancel(@NonNull String videoId);
}
