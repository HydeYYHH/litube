package com.hhst.youtubelite.downloader.core;

import java.io.File;

public interface ProgressCallback2 {

	void onProgress(int progress);

	void onComplete(File file);

	void onError(Exception error);

	void onCancel();

	void onMerge();

}
