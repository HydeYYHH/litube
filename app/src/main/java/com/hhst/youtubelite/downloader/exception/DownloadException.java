package com.hhst.youtubelite.downloader.exception;

import androidx.annotation.NonNull;

/**
 * Exception thrown when a download task fails.
 */
public class DownloadException extends RuntimeException {

	public DownloadException(@NonNull final String message, @NonNull final Throwable cause) {
		super(message, cause);
	}
}
