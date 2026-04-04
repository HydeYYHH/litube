package com.hhst.youtubelite.extractor.exception;

import androidx.annotation.NonNull;

/**
 * Exception thrown when video extraction fails.
 */
public class ExtractionException extends RuntimeException {

	public ExtractionException(@NonNull String message, @NonNull Throwable cause) {
		super(message, cause);
	}
}
