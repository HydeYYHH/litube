package com.hhst.youtubelite.downloader.core.impl;

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
			// 1. fetch metadata
			final long total;
			final boolean range;
			try (Response head = client.newCall(new Request.Builder().url(task.url).head().build()).execute()) {
				if (!head.isSuccessful()) throw new IOException("HEAD " + head.code());
				total = Long.parseLong(head.header("Content-Length", "-1"));
				range = head.code() == 206 || "bytes".equalsIgnoreCase(head.header("Accept-Ranges"));
			}

			// 2. calculate chunk count
			int chunks;
			if (total <= 0 || !range) chunks = 1;
			else {
				int candidate = (int) Math.min(128, Math.max(4, total / 512 * 1024));
				chunks = (total / Math.max(candidate, 1)) > 0 ? candidate : 1;
			}
			long part = total > 0 ? total / chunks : total;

			// 3. resume or initialize
			byte[] saved = mmkv.decodeBytes(task.key);
			BitSet bits = (range && saved != null) ? BitSet.valueOf(saved) : new BitSet();
			task.done.set(bits.cardinality());
			if (total > 0) {
				long initialDownloaded = IntStream.range(0, chunks)
								.filter(bits::get)
								.mapToLong(i -> chunkLength(i, chunks, part, total))
								.sum();
				task.downloadedBytes.set(initialDownloaded);
				maybeReportProgress(task, total);
			}
			raf = new RandomAccessFile(task.out, "rw");
			if (total > 0) raf.setLength(total);
			else raf.setLength(0);

			// 4. submit task
			if (task.done.get() < chunks) {
				RandomAccessFile finalRaf = raf;
				CompletableFuture.allOf(IntStream.range(0, chunks).filter(i -> !bits.get(i)) // skip finished
								.mapToObj(i -> CompletableFuture.runAsync(() -> downloadChunk(task, i, chunks, part, total, range, finalRaf, bits), executor)).toArray(CompletableFuture[]::new)).join();
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

	private void downloadChunk(TaskContext task, int idx, int totalChunks, long partSize, long totalLen, boolean rangeSupported, RandomAccessFile raf, BitSet bits) {
		if (task.isInactive()) return;
		long start = idx * partSize;
		long end = (idx == totalChunks - 1 && totalLen > 0) ? totalLen - 1 : (start + partSize - 1);
		String range = rangeSupported && totalLen > 0 ? "bytes=" + start + "-" + end : null;

		Request.Builder rb = new Request.Builder().url(task.url);
		if (range != null) rb.header("Range", range);

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
						maybeReportProgress(task, totalLen);
					}
					offset += read;
				}
				if (range != null) synchronized (task.lock) {
					bits.set(idx);
					mmkv.encode(task.key, bits.toByteArray());
				}
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
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
