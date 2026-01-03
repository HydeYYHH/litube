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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Singleton
public class StreamDownloaderImpl implements StreamDownloader {
	private static final long MIN_CHUNK_SIZE = 512 * 1024; // 512KB
	private final OkHttpClient client;
	private final MMKV mmkv;
	private final ThreadPoolExecutor executor;
	private final Map<String, TaskContext> tasks = new ConcurrentHashMap<>();

	@Inject
	public StreamDownloaderImpl(OkHttpClient client, MMKV mmkv) {
		this.client = client;
		this.mmkv = mmkv;
		this.executor = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> new Thread(r, "dl-node"));
		this.executor.allowCoreThreadTimeOut(true);
	}

	@Override
	public CompletableFuture<File> download(@NonNull String url, @NonNull File out, @Nullable ProgressCallback cb) {
		CompletableFuture<File> future = new CompletableFuture<>();
		TaskContext ctx = new TaskContext(url, out, "dl_" + md5(url), future, cb);
		tasks.put(url, ctx);
		new Thread(() -> runTask(ctx)).start();
		return future;
	}

	private void runTask(TaskContext ctx) {
		RandomAccessFile raf = null;
		try {
			// 1. fetch metadata
			Response head = client.newCall(new Request.Builder().url(ctx.url).head().build()).execute();
			long total = Long.parseLong(head.header("Content-Length", "-1"));
			boolean range = head.code() == 206 || "bytes".equalsIgnoreCase(head.header("Accept-Ranges"));
			head.close();

			// 2. calculate chunk count
			int chunks;
			if (total <= 0 || !range) chunks = 1;
			else {
				final int candidate = (int) Math.min(128, Math.max(4, total / MIN_CHUNK_SIZE));
				chunks = (total / Math.max(candidate, 1)) > 0 ? candidate : 1;
			}
			long part = total > 0 ? total / chunks : total;

			// 3. resume or initialize
			byte[] saved = mmkv.decodeBytes(ctx.key);
			BitSet bits = (range && saved != null) ? BitSet.valueOf(saved) : new BitSet();
			ctx.done.set(bits.cardinality());
			if (total > 0) {
				final long initialDownloaded = IntStream.range(0, chunks)
								.filter(bits::get)
								.mapToLong(i -> chunkLength(i, chunks, part, total))
								.sum();
				ctx.downloadedBytes.set(initialDownloaded);
				maybeReportProgress(ctx, total);
			}
			raf = new RandomAccessFile(ctx.out, "rw");
			if (total > 0) raf.setLength(total);

			// 4. submit task
			if (ctx.done.get() < chunks) {
				RandomAccessFile finalRaf = raf;
				CompletableFuture.allOf(IntStream.range(0, chunks).filter(i -> !bits.get(i)) // skip finished
								.mapToObj(i -> CompletableFuture.runAsync(() -> downloadChunk(ctx, i, chunks, part, total, finalRaf, bits), executor)).toArray(CompletableFuture[]::new)).join();
			}

			// 5. clean up
			if (!ctx.isInactive()) {
				mmkv.removeValueForKey(ctx.key);
				tasks.remove(ctx.url);
				ctx.future.complete(ctx.out);
				if (ctx.cb != null) ctx.cb.onComplete(ctx.out);
			}
		} catch (Exception e) {
			if (!ctx.isInactive()) {
				tasks.remove(ctx.url);
				ctx.future.completeExceptionally(e);
				if (ctx.cb != null)
					ctx.cb.onError(e instanceof RuntimeException && e.getCause() instanceof Exception ? (Exception) e.getCause() : e);
			}
		} finally {
			try {
				if (raf != null) raf.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void downloadChunk(TaskContext ctx, int idx, int totalChunks, long partSize, long totalLen, RandomAccessFile raf, BitSet bits) {
		if (ctx.isInactive()) return;
		long start = idx * partSize;
		long end = (idx == totalChunks - 1 && totalLen > 0) ? totalLen - 1 : (start + partSize - 1);
		String range = totalLen > 0 ? "bytes=" + start + "-" + end : null;

		Request.Builder rb = new Request.Builder().url(ctx.url);
		if (range != null) rb.header("Range", range);

		try (Response resp = client.newCall(rb.build()).execute(); InputStream is = resp.body().byteStream()) {
			byte[] buf = new byte[8192];
			int read;
			long offset = start;
			while ((read = is.read(buf)) != -1) {
				if (ctx.isInactive()) throw new IOException("Stop");
				synchronized (ctx.lock) {
					raf.seek(offset);
					raf.write(buf, 0, read);
				}
				if (totalLen > 0) {
					ctx.downloadedBytes.addAndGet(read);
					maybeReportProgress(ctx, totalLen);
				}
				offset += read;
			}
			if (range != null) synchronized (ctx.lock) {
				bits.set(idx);
				mmkv.encode(ctx.key, bits.toByteArray());
			}
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static long chunkLength(final int idx, final int totalChunks, final long partSize, final long totalLen) {
		final long start = idx * partSize;
		final long end = (idx == totalChunks - 1 && totalLen > 0) ? totalLen - 1 : (start + partSize - 1);
		if (totalLen <= 0 || end < start) return 0;
		return end - start + 1;
	}

	private static void maybeReportProgress(@NonNull final TaskContext ctx, final long totalLen) {
		if (ctx.cb == null || totalLen <= 0) return;
		final long downloaded = Math.min(totalLen, Math.max(0, ctx.downloadedBytes.get()));
		final int progress = (int) Math.min(99, (downloaded * 100) / totalLen);
		int prev;
		do {
			prev = ctx.lastProgress.get();
			if (progress <= prev) return;
		} while (!ctx.lastProgress.compareAndSet(prev, progress));
		ctx.cb.onProgress(progress);
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
			if (t.cb != null) t.cb.onCancel();
		}
	}

	@Override
	public void resume(@NonNull String url) {
		TaskContext t = tasks.get(url);
		if (t != null && t.paused.compareAndSet(true, false)) new Thread(() -> runTask(t)).start();
	}

	@Override
	public void setMaxThreadCount(int count) {
		executor.setCorePoolSize(count);
		executor.setMaximumPoolSize(count);
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

	@AllArgsConstructor
	private static class TaskContext {
		final String url;
		final File out;
		final String key;
		final CompletableFuture<File> future;
		final ProgressCallback cb;
		final Object lock = new Object();
		final AtomicBoolean paused = new AtomicBoolean(false);
		final AtomicBoolean cancelled = new AtomicBoolean(false);
		final AtomicInteger done = new AtomicInteger(0);
		final AtomicLong downloadedBytes = new AtomicLong(0);
		final AtomicInteger lastProgress = new AtomicInteger(-1);

		boolean isInactive() {
			return paused.get() || cancelled.get() || future.isCancelled();
		}
	}
}
