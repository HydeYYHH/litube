package com.hhst.youtubelite.player.exception;

import androidx.annotation.NonNull;

/**
 * Exception thrown when video playback fails.
 */
public class PlaybackException extends RuntimeException {

	public PlaybackException(@NonNull final String message, @NonNull final Throwable cause) {
		super(message, cause);
	}
}
