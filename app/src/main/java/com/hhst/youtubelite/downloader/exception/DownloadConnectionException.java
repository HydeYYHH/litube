package com.hhst.youtubelite.downloader.exception;

import androidx.annotation.NonNull;

/**
 * Exception thrown when a download connection fails.
 */
public class DownloadConnectionException extends DownloadException {
    public DownloadConnectionException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
