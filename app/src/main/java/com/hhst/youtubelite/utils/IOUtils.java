package com.hhst.youtubelite.utils;

public final class IOUtils {
	/**
	 * Checks if the current thread has been interrupted and throws {@link InterruptedException} if so.
	 *
	 * @throws InterruptedException if the current thread is interrupted
	 */
	public static void checkInterrupt() throws InterruptedException {
		if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
	}

}
