package com.hhst.youtubelite.downloader.core;

public enum DownloaderState {
    PENDING,
    RUNNING,
    DOWNLOADING,
    MERGING,
    FINISHED,
    CANCELLED,
    STOPPED
}
