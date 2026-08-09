package com.hhst.youtubelite.downloader.core.impl;

import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isAndroidStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isIosStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl;
import static org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isWebStreamingUrl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.hhst.youtubelite.downloader.core.StreamDownloader;
import com.tencent.mmkv.MMKV;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.AllArgsConstructor;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Streams a file by chunk and keeps resume state in MMKV.
 */
@Singleton
public class StreamDownloaderImpl implements StreamDownloader {
	/**
	 * User-Agent of the YouTube VR (Oculus) client. Streaming URLs issued to the ANDROID_VR
	 * client must be requested with this User-Agent: googlevideo validates that the User-Agent
	 * matches the client the URL was generated for and answers HTTP 403 on a mismatch (the
	 * regular Android app User-Agent is rejected for ANDROID_VR URLs). Mirrors
	 * {@link com.hhst.youtubelite.player.engine.datasource.YoutubeHttpDataSource}.
	 */
	private static final String YOUTUBE_ANDROID_VR_USER_AGENT =
			"com.google.android.apps.youtube.vr.oculus/1.65.10 "
					+ "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
	/**
	 * Desktop Chrome User-Agent for web-client (WEB / WEB_EMBEDDED_PLAYER) streaming URLs.
	 * The Android WebView default User-Agent is a known trigger for HTTP 403 responses from
	 * googlevideo on web-client URLs.
	 */
	private static final String YOUTUBE_WEB_USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
					+ "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
	/**
	 * Number of attempts per chunk before the download fails. Transient failures (HTTP 5xx,
	 * dropped connections, read timeouts) are retried automatically; only after every attempt
	 * is exhausted does the chunk — and therefore the whole download — fail.
	 */
	private static final int MAX_CHUNK_ATTEMPTS = 3;
	/**
	 * Base delay between chunk retry attempts, in milliseconds, scaled by the attempt number.
	 */
	private static final long RETRY_BACKOFF_BASE_MS = 500L;
	private final OkHttpClient client;
	private final MMKV mmkv;
	private final ThreadPoolExecutor executor;
	private final Map<String, TaskContext> tasks = new ConcurrentHashMap<>();

	@Inject
	public StreamDownloaderImpl(OkHttpClient client, MMKV mmkv) {
		this.client = client.newBuilder()
						.cache(null)
						.dispatcher(createDispatcher())
						.callTimeout(0L, TimeUnit.MILLISECONDS)
						.connectTimeout(20L, TimeUnit.SECONDS)
						.writeTimeout(30L, TimeUnit.SECONDS)
						.readTimeout(60L, TimeUnit.SECONDS)
						.build();
		this.mmkv = mmkv;
		this.executor = new ThreadPoolExecutor(8, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> new Thread(r, "dl-node"));
		this.executor.allowCoreThreadTimeOut(true);
	}

	private static Dispatcher createDispatcher() {
		Dispatcher dispatcher = new Dispatcher();
		dispatcher.setMaxRequests(24);
		dispatcher.setMaxRequestsPerHost(12);
		return dispatcher;
	}

	/**
	 * Checks if a streaming URL was issued to the {@code ANDROID_VR} (YouTube VR) client.
	 * The library's {@code isAndroidStreamingUrl} also matches {@code ANDROID_VR} URLs
	 * (substring match on {@code &c=ANDROID}), so this must be checked before it: the VR
	 * client has its own dedicated User-Agent. Mirrors
	 * {@link com.hhst.youtubelite.player.engine.datasource.YoutubeHttpDataSource}.
	 */
	private static boolean isAndroidVrStreamingUrl(@NonNull String url) {
		return url.contains("&c=ANDROID_VR") || url.contains("?c=ANDROID_VR");
	}

	/**
	 * Builds the request headers googlevideo expects for a streaming URL, per the client the
	 * URL was issued to. Without the matching User-Agent (and, for web-client URLs, the
	 * browser headers), googlevideo answers HTTP 403 and the download fails — this is what
	 * happens to the non-SABR ANDROID_VR / iOS / WEB streams the extractor now serves for
	 * resolutions above 360p. Mirrors
	 * {@link com.hhst.youtubelite.player.engine.datasource.YoutubeHttpDataSource}.
	 */
	static Map<String, String> clientHeaders(@NonNull String url) {
		Map<String, String> headers = new LinkedHashMap<>();
		boolean web = isWebStreamingUrl(url) || isWebEmbeddedPlayerStreamingUrl(url);
		if (web) {
			headers.put("Origin", "https://www.youtube.com");
			headers.put("Referer", "https://www.youtube.com");
			headers.put("Sec-Fetch-Dest", "empty");
			headers.put("Sec-Fetch-Mode", "cors");
			headers.put("Sec-Fetch-Site", "cross-site");
			headers.put("User-Agent", YOUTUBE_WEB_USER_AGENT);
			return headers;
		}
		String userAgent;
		if (isAndroidVrStreamingUrl(url)) {
			userAgent = YOUTUBE_ANDROID_VR_USER_AGENT;
		} else if (isAndroidStreamingUrl(url)) {
			userAgent = getAndroidUserAgent(null);
		} else if (isIosStreamingUrl(url)) {
			userAgent = getIosUserAgent(null);
		} else {
			userAgent = YOUTUBE_WEB_USER_AGENT;
		}
		headers.put("User-Agent", userAgent);
		return headers;
	}

	private static void applyClientHeaders(@NonNull Request.Builder builder, @NonNull String url) {
		for (Map.Entry<String, String> header : clientHeaders(url).entrySet()) {
			builder.header(header.getKey(), header.getValue());
		}
	}

	private static long parseContentLength(@Nullable String value) {
		if (value == null) return -1;
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static long chunkLength(int idx, int totalChunks, long partSize, long totalLen) {
		long start = idx * partSize;
		long end = (idx == totalChunks - 1 && totalLen > 0) ? totalLen - 1 : (start + partSize - 1);
		if (totalLen <= 0 || end < start) return 0;
		return end - start + 1;
	}

	private static void maybeReportProgress(@NonNull TaskContext task, long totalLen) {
		if (task.callback == null || totalLen <= 0) return;
		long downloaded = Math.min(totalLen, Math.max(0, task.downloadedBytes.get()));
		int progress = (int) Math.min(99, (downloaded * 100) / totalLen);
		synchronized (task.progressLock) {
			int prev;
			do {
				prev = task.lastProgress.get();
				if (progress <= prev) return;
			} while (!task.lastProgress.compareAndSet(prev, progress));
			task.callback.onProgress(progress);
		}
	}

	@Override
	public CompletableFuture<File> download(@NonNull String url, @NonNull File out, @Nullable ProgressCallback callback) {
		CompletableFuture<File> future = new CompletableFuture<>();
		TaskContext task = new TaskContext(
						url,
						out,
						"dl_" + md5(url),
						future,
						callback,
						new AtomicBoolean(),
						new AtomicBoolean(),
						new AtomicInteger(),
						new AtomicLong(),
						new AtomicInteger(-1));
		tasks.put(url, task);
		new Thread(() -> runTask(task)).start();
		return future;
	}

	private void runTask(TaskContext task) {
		RandomAccessFile raf = null;
		try {
			// 1. fetch metadata; a failed HEAD (e.g. HTTP 403 without the client User-Agent)
			// falls back to a single full GET, which carries the client headers below.
			long total = -1;
			boolean range = false;
			Request.Builder headBuilder = new Request.Builder().url(task.url).head();
			applyClientHeaders(headBuilder, task.url);
			try (Response head = client.newCall(headBuilder.build()).execute()) {
				if (head.isSuccessful()) {
					total = parseContentLength(head.header("Content-Length"));
					range = head.code() == 206 || "bytes".equalsIgnoreCase(head.header("Accept-Ranges"));
				}
			}

			// Final copies for lambda capture: total/range are assigned above, and the lambdas
			// below can only capture effectively final locals.
			final long totalLength = total;
			final boolean rangeSupported = range;

			// 2. calculate chunk count
			int chunks;
			if (totalLength <= 0 || !rangeSupported) chunks = 1;
			else {
				int candidate = (int) Math.min(128, Math.max(4, totalLength / 512 * 1024));
				chunks = (totalLength / Math.max(candidate, 1)) > 0 ? candidate : 1;
			}
			long part = totalLength > 0 ? totalLength / chunks : totalLength;

			// 3. resume or initialize — saved chunks are only trusted when the partial file
			// still holds them; if the file was deleted (e.g. after a cancel or a completed
			// download of another stream that reused the temp path), restart this stream
			// cleanly instead of skipping ranges that now contain no data.
			byte[] saved = mmkv.decodeBytes(task.key);
			boolean fileMatches = totalLength > 0 && task.out.isFile() && task.out.length() == totalLength;
			BitSet bits = (rangeSupported && saved != null && fileMatches) ? BitSet.valueOf(saved) : new BitSet();
			if (saved != null && (!fileMatches || !rangeSupported)) mmkv.removeValueForKey(task.key);
			task.done.set(bits.cardinality());
			if (totalLength > 0) {
				long initialDownloaded = IntStream.range(0, chunks)
								.filter(bits::get)
								.mapToLong(i -> chunkLength(i, chunks, part, totalLength))
								.sum();
				task.downloadedBytes.set(initialDownloaded);
				maybeReportProgress(task, totalLength);
			}
			raf = new RandomAccessFile(task.out, "rw");
			if (totalLength > 0) raf.setLength(totalLength);
			else raf.setLength(0);

			// 4. submit task
			if (task.done.get() < chunks) {
				RandomAccessFile finalRaf = raf;
				CompletableFuture.allOf(IntStream.range(0, chunks).filter(i -> !bits.get(i)) // skip finished
								.mapToObj(i -> CompletableFuture.runAsync(() -> downloadChunk(task, i, chunks, part, totalLength, rangeSupported, finalRaf, bits), executor)).toArray(CompletableFuture[]::new)).join();
			}

			// 5. clean up
			if (!task.isInactive()) {
				mmkv.removeValueForKey(task.key);
				tasks.remove(task.url);
				task.future.complete(task.out);
				if (task.callback != null) task.callback.onComplete(task.out);
			}
		} catch (Exception e) {
			if (!task.isInactive()) {
				tasks.remove(task.url);
				task.future.completeExceptionally(e);
				if (task.callback != null)
					task.callback.onError(e instanceof RuntimeException && e.getCause() instanceof Exception ? (Exception) e.getCause() : e);
			}
		} finally {
			try {
				if (raf != null) raf.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void downloadChunk(TaskContext task, int idx, int totalChunks, long partSize, long totalLen,
	                           boolean rangeSupported, RandomAccessFile raf, BitSet bits) {
		int attempt = 0;
		Exception lastError = null;
		while (attempt < MAX_CHUNK_ATTEMPTS) {
			if (task.isInactive()) return; // paused/cancelled: abort without retrying
			attempt++;
			long written = 0;
			try {
				written = fetchChunk(task, idx, totalChunks, partSize, totalLen, rangeSupported, raf, bits);
				return;
			} catch (Exception e) {
				lastError = e;
				// Undo the progress reported by the failed attempt so a retry does not
				// double-count bytes (progress stays monotonic via maybeReportProgress).
				if (written > 0) task.downloadedBytes.addAndGet(-written);
				if (task.isInactive()) throw new RuntimeException(e); // paused/cancelled mid-attempt
				if (attempt < MAX_CHUNK_ATTEMPTS) sleepBeforeRetry(attempt);
			}
		}
		// Clear, actionable error for the UI: the download stops after all attempts.
		throw new RuntimeException("Chunk " + idx + " failed after " + MAX_CHUNK_ATTEMPTS
						+ " attempts" + (lastError != null ? ": " + lastError.getMessage() : ""), lastError);
	}

	private long fetchChunk(TaskContext task, int idx, int totalChunks, long partSize, long totalLen,
	                        boolean rangeSupported, RandomAccessFile raf, BitSet bits) throws IOException {
		long start = idx * partSize;
		long end = (idx == totalChunks - 1 && totalLen > 0) ? totalLen - 1 : (start + partSize - 1);
		String range = rangeSupported && totalLen > 0 ? "bytes=" + start + "-" + end : null;

		Request.Builder rb = new Request.Builder().url(task.url);
		applyClientHeaders(rb, task.url);
		if (range != null) rb.header("Range", range);

		long written = 0;
		try (Response resp = client.newCall(rb.build()).execute()) {
			if (!resp.isSuccessful()) throw new IOException("GET " + resp.code());
			try (InputStream is = resp.body().byteStream()) {
				byte[] buf = new byte[8192];
				int read;
				long offset = start;
				while ((read = is.read(buf)) != -1) {
					if (task.isInactive()) throw new IOException("Stop");
					synchronized (task.lock) {
						raf.seek(offset);
						raf.write(buf, 0, read);
					}
					if (totalLen > 0) {
						task.downloadedBytes.addAndGet(read);
						written += read;
						maybeReportProgress(task, totalLen);
					}
					offset += read;
				}
				if (range != null) synchronized (task.lock) {
					bits.set(idx);
					mmkv.encode(task.key, bits.toByteArray());
				}
			}
		}
		return written;
	}

	private static void sleepBeforeRetry(int attempt) {
		try {
			Thread.sleep(RETRY_BACKOFF_BASE_MS * attempt);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void pause(@NonNull String url) {
		Optional.ofNullable(tasks.get(url)).ifPresent(t -> t.paused.set(true));
	}

	@Override
	public void cancel(@NonNull String url) {
		TaskContext t = tasks.remove(url);
		if (t != null) {
			t.cancelled.set(true);
			t.future.cancel(true);
			mmkv.removeValueForKey(t.key);
			if (t.callback != null) t.callback.onCancel();
		}
	}

	@Override
	public void resume(@NonNull String url) {
		TaskContext t = tasks.get(url);
		if (t != null && t.paused.compareAndSet(true, false)) new Thread(() -> runTask(t)).start();
	}

	@Override
	public synchronized void setMaxThreadCount(int count) {
		int targetCount = Math.max(1, count);
		Dispatcher dispatcher = client.dispatcher();
		dispatcher.setMaxRequests(targetCount);
		dispatcher.setMaxRequestsPerHost(targetCount);
		if (targetCount > executor.getMaximumPoolSize()) {
			executor.setMaximumPoolSize(targetCount);
			executor.setCorePoolSize(targetCount);
		} else {
			executor.setCorePoolSize(targetCount);
			executor.setMaximumPoolSize(targetCount);
		}
	}

	private String md5(String s) {
		try {
			byte[] b = MessageDigest.getInstance("MD5").digest(s.getBytes());
			StringBuilder sb = new StringBuilder();
			for (byte v : b) sb.append(String.format("%02x", v));
			return sb.toString();
		} catch (Exception e) {
			return String.valueOf(s.hashCode());
		}
	}

/**
 * Component that handles app logic.
 */
	@AllArgsConstructor
	private static class TaskContext {
		final String url;
		final File out;
		final String key;
		final CompletableFuture<File> future;
		final ProgressCallback callback;
		final Object lock = new Object();
		final Object progressLock = new Object();
		final AtomicBoolean paused;
		final AtomicBoolean cancelled;
		final AtomicInteger done;
		final AtomicLong downloadedBytes;
		final AtomicInteger lastProgress;

		boolean isInactive() {
			return paused.get() || cancelled.get() || future.isCancelled();
		}
	}
}
