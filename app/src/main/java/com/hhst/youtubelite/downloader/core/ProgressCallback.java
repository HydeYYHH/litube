package com.hhst.youtubelite.downloader.core;

import java.io.File;

/**
 * Callback for download progress updates.
 */
public interface ProgressCallback {

	void onProgress(int progress);

	void onComplete(File file);

	void onError(Exception error);

	void onCancel();

}
