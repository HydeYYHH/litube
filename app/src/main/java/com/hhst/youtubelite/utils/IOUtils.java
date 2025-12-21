package com.hhst.youtubelite.utils;

public class IOUtils {

	public static void checkInterrupt() throws InterruptedException {
		if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
	}

}
