package com.hhst.youtubelite.extractor.exception;

import androidx.annotation.NonNull;

/**
 * Exception thrown when extraction needs a logged-in session.
 */
public final class LoginRequiredExtractionException extends ExtractionException {
	public static final String MESSAGE =
					"This video currently requires signing in to YouTube to confirm that you're not a bot.";

	public LoginRequiredExtractionException(@NonNull Throwable cause) {
		super(MESSAGE, cause);
	}
}
