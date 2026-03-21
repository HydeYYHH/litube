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
import java.util.Map;
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
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Singleton
public class StreamDownloaderImpl implements StreamDownloader {
    private static final long MIN_CHUNK_SIZE = 512 * 1024;
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
    public CompletableFuture<File> download(@NonNull String url, @NonNull File out, @Nullable ProgressCallback cb) {
        CompletableFuture<File> future = new CompletableFuture<>();
        TaskContext ctx = new TaskContext(url, out, "dl_v6_" + out.getName(), future, cb);
        tasks.put(out.getAbsolutePath(), ctx);
        new Thread(() -> runTask(ctx)).start();
        return future;
    }

    private void runTask(TaskContext ctx) {
        RandomAccessFile raf = null;
        try {
            Response head = client.newCall(new Request.Builder().url(ctx.url).head().build()).execute();
            long total = Long.parseLong(head.header("Content-Length", "-1"));
            boolean rangeSupported = total > 0 && (head.code() == 206 || "bytes".equalsIgnoreCase(head.header("Accept-Ranges")) || ctx.url.contains("googlevideo.com"));
            head.close();

            long savedTotal = mmkv.decodeLong(ctx.key + "_total", -1);
            int chunks;
            if (savedTotal != -1 && savedTotal == total) {
                chunks = mmkv.decodeInt(ctx.key + "_chunks", 1);
            } else {
                for (int i = 0; i < 128; i++) mmkv.removeValueForKey(ctx.key + "_c" + i);
                chunks = (!rangeSupported) ? 1 : (int) Math.min(64, Math.max(4, total / MIN_CHUNK_SIZE));
                mmkv.encode(ctx.key + "_total", total);
                mmkv.encode(ctx.key + "_chunks", chunks);
            }
            long part = total > 0 ? total / chunks : total;

            long initialDownloaded = 0;
            for (int i = 0; i < chunks; i++) {
                initialDownloaded += mmkv.decodeLong(ctx.key + "_c" + i, 0L);
            }
            ctx.downloadedBytes.set(initialDownloaded);
            maybeReportProgress(ctx, total);

            raf = new RandomAccessFile(ctx.out, "rw");
            if (total > 0 && raf.length() != total) raf.setLength(total);

            final RandomAccessFile finalRaf = raf;
            CompletableFuture.allOf(IntStream.range(0, chunks).mapToObj(i ->
                    CompletableFuture.runAsync(() -> downloadChunk(ctx, i, chunks, part, total, finalRaf), executor)
            ).toArray(CompletableFuture[]::new)).join();

            if (!ctx.isInactive()) {
                mmkv.removeValuesForKeys(new String[]{ctx.key + "_total", ctx.key + "_chunks"});
                for (int i = 0; i < chunks; i++) mmkv.removeValueForKey(ctx.key + "_c" + i);
                tasks.remove(ctx.out.getAbsolutePath());
                ctx.future.complete(ctx.out);
                if (ctx.cb != null) ctx.cb.onComplete(ctx.out);
            }
        } catch (Exception e) {
            if (!ctx.isInactive()) {
                tasks.remove(ctx.out.getAbsolutePath());
                ctx.future.completeExceptionally(e);
                if (ctx.cb != null) ctx.cb.onError(e);
            }
        } finally {
            try { if (raf != null) raf.close(); } catch (IOException ignored) {}
        }
    }

    private void downloadChunk(TaskContext ctx, int idx, int totalChunks, long partSize, long totalLen, RandomAccessFile raf) {
        if (ctx.isInactive()) return;
        long chunkStart = idx * partSize;
        long chunkEnd = (idx == totalChunks - 1) ? totalLen - 1 : (chunkStart + partSize - 1);
        long startOffset = mmkv.decodeLong(ctx.key + "_c" + idx, 0L);
        long currentPos = chunkStart + startOffset;
        if (currentPos > chunkEnd) return;

        Request req = new Request.Builder().url(ctx.url).header("Range", "bytes=" + currentPos + "-" + chunkEnd).build();
        try (Response resp = client.newCall(req).execute(); InputStream is = resp.body().byteStream()) {
            byte[] buf = new byte[16384];
            int read;
            long writePos = currentPos;
            while ((read = is.read(buf)) != -1) {
                if (ctx.isInactive()) throw new IOException("Stopped");
                synchronized (ctx.lock) {
                    raf.seek(writePos);
                    raf.write(buf, 0, read);
                }
                writePos += read;
                ctx.downloadedBytes.addAndGet(read);
                mmkv.encode(ctx.key + "_c" + idx, writePos - chunkStart);
                maybeReportProgress(ctx, totalLen);
            }
        } catch (Exception e) {
            if (!ctx.isInactive()) throw new RuntimeException(e);
        }
    }

    @Override public void pause(@NonNull String url) { tasks.values().stream().filter(t -> t.url.equals(url)).forEach(t -> t.paused.set(true)); }
    @Override public void cancel(@NonNull String url) {
        tasks.values().stream().filter(t -> t.url.equals(url)).forEach(t -> {
            t.cancelled.set(true);
            tasks.remove(t.out.getAbsolutePath());
            mmkv.removeValuesForKeys(new String[]{t.key + "_total", t.key + "_chunks"});
            for (int i = 0; i < 128; i++) mmkv.removeValueForKey(t.key + "_c" + i);
            if (t.cb != null) t.cb.onCancel();
        });
    }
    @Override public void resume(@NonNull String url) {
        tasks.values().stream().filter(t -> t.url.equals(url)).findFirst().ifPresent(t -> {
            if (t.paused.compareAndSet(true, false)) new Thread(() -> runTask(t)).start();
        });
    }
    @Override public void setMaxThreadCount(int count) { executor.setCorePoolSize(count); executor.setMaximumPoolSize(count); }

    private static class TaskContext {
        final String url; final File out; final String key; final CompletableFuture<File> future; final ProgressCallback cb;
        final Object lock = new Object();
        final AtomicBoolean paused = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicLong downloadedBytes = new AtomicLong(0);
        final AtomicInteger lastProgress = new AtomicInteger(-1);
        TaskContext(String url, File out, String key, CompletableFuture<File> future, ProgressCallback cb) {
            this.url = url; this.out = out; this.key = key; this.future = future; this.cb = cb;
        }
        boolean isInactive() { return paused.get() || cancelled.get() || future.isCancelled(); }
    }
}