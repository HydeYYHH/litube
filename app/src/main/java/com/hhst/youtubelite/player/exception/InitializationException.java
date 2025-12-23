package com.hhst.youtubelite.player.exception;

public class InitializationException extends RuntimeException {
	public InitializationException(Class<?> c) {
		super("Failed to initialize or Not initialized: " + c.toGenericString());
	}

}
