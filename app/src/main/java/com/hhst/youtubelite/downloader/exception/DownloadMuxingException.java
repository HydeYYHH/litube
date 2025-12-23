package com.hhst.youtubelite.downloader.exception;

import androidx.annotation.NonNull;

/**
 * Exception thrown when merging video and audio fails.
 */
public class DownloadMuxingException extends DownloadException {
    public DownloadMuxingException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
