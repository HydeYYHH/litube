package com.hhst.youtubelite.downloader.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.downloader.core.ProgressCallback;

import java.io.File;

public interface AdvancedFileDownloader {

	/**
	 * Downloads a file from the specified URL to the given output file.
	 *
	 * @param url         The URL of the file to download.
	 * @param output      The file where the downloaded content will be saved.
	 * @param callback    A callback to report progress and completion status.
	 * @param tag         A tag to identify the download task, allowing for cancellation or tracking.
	 * @param threadCount The number of threads to use for downloading.
	 */
	void download(@NonNull String url, @NonNull File output, @NonNull ProgressCallback callback, @Nullable String tag, int threadCount);

	default void download(@NonNull final String url, @NonNull final File output, @NonNull final ProgressCallback callback, @Nullable final String tag) {
		download(url, output, callback, tag, 3);
	}

	default void download(@NonNull final String url, @NonNull final File output, @NonNull final ProgressCallback callback) {
		download(url, output, callback, null, 3);
	}

	default void download(@NonNull final String url, @NonNull final File output) {
		download(url, output, new ProgressCallback() {
			@Override
			public void onProgress(final int progress, @Nullable final String message) {
			}

			@Override
			public void onComplete(@NonNull final File file) {
			}

			@Override
			public void onError(@NonNull final Exception error) {
			}

			@Override
			public void onCancel() {
			}

			@Override
			public void onMerge() {
			}
		}, null, 4);
	}

	void cancel(@Nullable String tag);
}
