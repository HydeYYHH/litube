package com.hhst.youtubelite.downloader.exception;

import androidx.annotation.NonNull;

/**
 * Exception thrown when file operations (create, move, delete) fail.
 */
public class DownloadStorageException extends DownloadException {
    public DownloadStorageException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
