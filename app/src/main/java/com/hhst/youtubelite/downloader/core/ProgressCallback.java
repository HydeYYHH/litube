package com.hhst.youtubelite.downloader.core;

import java.io.File;

public interface ProgressCallback {

	/**
	 * Called to update the progress of a download.
	 *
	 * @param progress The current progress percentage (0-100).
	 */
	void onProgress(int progress, String message);

	/**
	 * Called when the download is completed successfully.
	 *
	 * @param file The file that was downloaded.
	 */
	void onComplete(File file);

	/**
	 * Called when the download fails.
	 *
	 * @param error The exception that occurred during the download.
	 */
	void onError(Exception error);

	void onCancel();

	void onMerge();
}
