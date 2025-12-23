package com.hhst.youtubelite.downloader.core;

import android.content.Context;
import androidx.annotation.NonNull;

/**
 * Interface for processing a download task.
 * Implementations handle the orchestration of one or more streams.
 */
public interface DownloadProcessor {
    void process(@NonNull DownloadTask task, @NonNull String tag, @NonNull ProgressCallback callback, @NonNull Context context);
    void cancel(@NonNull String tag);
}
