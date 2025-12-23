package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;

/**
 * Exception thrown when video extraction fails.
 */
public class ExtractionException extends RuntimeException {

	public ExtractionException(@NonNull final String message, @NonNull final Throwable cause) {
		super(message, cause);
	}
}
