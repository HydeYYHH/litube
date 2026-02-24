package com.hhst.youtubelite.downloader.core;

import java.io.File;

public interface ProgressCallback2 {

    void onProgress(int progress, long downloadedBytes, long totalBytes);

    void onComplete(File file);

    void onError(Exception error);

    void onCancel();

    void onMerge();

}