package com.hhst.youtubelite.downloader.core.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.tencent.mmkv.MMKV;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

public class StreamDownloaderImplTest {

	private final Map<String, String> mmkvStore = new HashMap<>();
	private StreamDownloaderImpl downloader;
	private File outputFile;

	@Before
	public void setUp() {
		OkHttpClient client = new OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();
		MMKV mmkv = mock(MMKV.class);

		// Realistic mock behavior for MMKV using a Map
		mmkvStore.clear();
		when(mmkv.decodeString(anyString())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			return mmkvStore.get(key);
		});
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			String value = invocation.getArgument(1);
			mmkvStore.put(key, value);
			return null;
		}).when(mmkv).encode(anyString(), anyString());

		downloader = new StreamDownloaderImpl(client, mmkv);
		outputFile = new File("test_download.apk");
	}

	@After
	public void tearDown() {
		if (outputFile != null && outputFile.exists()) {
			boolean ignored = outputFile.delete();
		}
	}

	@Test
	public void testDownload() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicBoolean success = new AtomicBoolean(false);
		AtomicBoolean error = new AtomicBoolean(false);
		long startTime = System.currentTimeMillis();

		downloader.setMaxThreadCount(16);
		ProgressCallback callback = new ProgressCallback() {
			@Override
			public void onProgress(int progress) {
				System.out.println("Download progress: " + progress + "%");
			}

			@Override
			public void onComplete(File file) {
				long endTime = System.currentTimeMillis();
				long durationMs = endTime - startTime;
				long fileSize = file.length();
				double durationSec = durationMs / 1000.0;
				double speedKbPs = (fileSize / 1024.0) / durationSec;

				System.out.println("Download complete: " + file.getAbsolutePath());
				System.out.println("Total Time: " + durationSec + "s");
				System.out.printf("Average Speed: %.2f KB/s (%.2f MB/s)%n", speedKbPs, speedKbPs / 1024.0);

				success.set(true);
				latch.countDown();
			}

			@Override
			public void onError(Exception e) {
				System.err.println("Download error: " + e.getMessage());
				e.printStackTrace();
				error.set(true);
				latch.countDown();
			}

			@Override
			public void onCancel() {
				System.out.println("Download cancelled");
				latch.countDown();
			}
		};

		String testUrl = "https://github.com/HydeYYHH/litube/releases/download/v1.5.3.1/app-universal-release.apk";
		System.out.println("Starting download from: " + testUrl);
		CompletableFuture<File> future = downloader.download(testUrl, outputFile, callback);

		// Use CompletableFuture to handle result (avoiding callback hell in real usage)
		future.thenAccept(file -> System.out.println("Future complete: " + file.getName())).exceptionally(e -> {
			System.err.println("Future error: " + e.getMessage());
			return null;
		});

		// Test pause/resume
		Thread.sleep(4000);
		System.out.println("Pausing task: " + testUrl);
		downloader.pause(testUrl);
		Thread.sleep(5000);
		System.out.println("Resuming task: " + testUrl);
		downloader.resume(testUrl);

		// Wait for download to complete (max 5 minutes)
		boolean finished = latch.await(5, TimeUnit.MINUTES);

		assertTrue("Download timed out", finished);
		assertFalse("Download failed with error", error.get());
		assertTrue("Download did not complete successfully", success.get());
		assertTrue("Output file does not exist", outputFile.exists());
		assertTrue("Output file is empty", outputFile.length() > 0);

		// Verify SHA-256
		String expectedSha256 = "796df9cc9d87819516a06f9efa24794823a5d04183d0761fea570c0eb321adbb";
		String actualSha256 = calculateSHA256(outputFile);
		System.out.println("Expected SHA-256: " + expectedSha256);
		System.out.println("Actual SHA-256:   " + actualSha256);
		assertTrue("SHA-256 verification failed!", expectedSha256.equalsIgnoreCase(actualSha256));

		System.out.println("Test passed! File size: " + outputFile.length() + " bytes");
	}

	private String calculateSHA256(File file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (FileInputStream fis = new FileInputStream(file)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = fis.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}
			byte[] hash = digest.digest();
			StringBuilder hexString = new StringBuilder();
			for (byte b : hash) {
				String hex = Integer.toHexString(0xff & b);
				if (hex.length() == 1) hexString.append('0');
				hexString.append(hex);
			}
			return hexString.toString();
		} catch (Exception e) {
			throw new RuntimeException("Could not calculate SHA-256", e);
		}
	}
}
