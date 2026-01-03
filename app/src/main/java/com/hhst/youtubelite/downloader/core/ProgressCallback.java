package com.hhst.youtubelite.downloader.core;

import java.io.File;

public interface ProgressCallback {

	void onProgress(int progress);

	void onComplete(File file);

	void onError(Exception error);

	void onCancel();

}
