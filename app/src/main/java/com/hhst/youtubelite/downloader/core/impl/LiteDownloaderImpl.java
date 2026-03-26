package com.hhst.youtubelite.downloader.core.impl;

import android.content.Context;
import androidx.annotation.NonNull;
import com.hhst.youtubelite.downloader.core.LiteDownloader;
import com.hhst.youtubelite.downloader.core.MediaMuxer;
import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.hhst.youtubelite.downloader.core.ProgressCallback2;
import com.hhst.youtubelite.downloader.core.StreamDownloader;
import com.hhst.youtubelite.downloader.core.Task;
import org.schabi.newpipe.extractor.stream.Stream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import dagger.hilt.android.qualifiers.ApplicationContext;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

@Singleton
public class LiteDownloaderImpl implements LiteDownloader {
    private final Context ctx;
    private final StreamDownloader streamDL;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, ProgressCallback2> cbs = new ConcurrentHashMap<>();
    private final Set<String> pausedTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Queue<Task> queuedTasks = new LinkedList<>();
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    @Inject
    public LiteDownloaderImpl(@ApplicationContext Context ctx, StreamDownloader streamDL) {
        this.ctx = ctx;
        this.streamDL = streamDL;
    }

    @Override
    public void setCallback(@NonNull String vid, ProgressCallback2 cb) {
        if (cb != null) cbs.put(vid, cb);
        else cbs.remove(vid);
    }

    @Override
    public void download(@NonNull Task t) {
        pausedTasks.remove(t.vid());
        synchronized (queuedTasks) {
            queuedTasks.removeIf(task -> task.vid().equals(t.vid()));
            if (isProcessing.get()) {
                tasks.put(t.vid(), t);
                queuedTasks.add(t);
                return;
            }
            isProcessing.set(true);
        }
        tasks.put(t.vid(), t);
        startTaskInternal(t);
    }

    private void startTaskInternal(Task t) {
        if (t.subtitle() != null) {
            exec(t, () -> copyURLToFile(new URL(t.subtitle().getContent()), new File(t.desDir(), t.fileName() + "." + t.subtitle().getExtension())));
        } else if (t.thumbnail() != null) {
            exec(t, () -> copyURLToFile(new URL(t.thumbnail()), new File(t.desDir(), t.fileName() + ".jpg")));
        } else {
            downloadMedia(t);
        }
    }

    private void copyURLToFile(URL url, File file) throws Exception {
        try (InputStream in = url.openStream();
             BufferedSink sink = Okio.buffer(Okio.sink(file))) {
            sink.writeAll(Okio.source(in));
        }
    }

    @Override
    public void pause(@NonNull String vid) {
        pausedTasks.add(vid);
        synchronized (queuedTasks) {
            queuedTasks.removeIf(t -> t.vid().equals(vid));
        }
        Task t = tasks.get(vid);
        if (t != null) {
            if (t.video() != null) streamDL.pause(t.video().getContent());
            if (t.audio() != null) streamDL.pause(t.audio().getContent());
        }
        startNext();
    }

    @Override
    public void resume(@NonNull String vid) {
        pausedTasks.remove(vid);
        Task t = tasks.get(vid);
        if (t != null) {
            if (t.video() != null) streamDL.resume(t.video().getContent());
            if (t.audio() != null) streamDL.resume(t.audio().getContent());
            progress(vid, -1, -1, -1);
        }
    }

    @Override
    public void cancel(@NonNull String vid) {
        pausedTasks.remove(vid);
        synchronized (queuedTasks) {
            queuedTasks.removeIf(t -> t.vid().equals(vid));
        }
        Task t = tasks.remove(vid);
        if (t != null) {
            if (t.video() != null) streamDL.cancel(t.video().getContent());
            if (t.audio() != null) streamDL.cancel(t.audio().getContent());
            notify(vid, ProgressCallback2::onCancel);
            clean(t);
        }
        startNext();
    }

    private void startNext() {
        Task next;
        synchronized (queuedTasks) {
            next = queuedTasks.poll();
            if (next == null) {
                isProcessing.set(false);
                return;
            }
        }
        startTaskInternal(next);
    }

    private void exec(Task t, RunnableIOC r) {
        CompletableFuture.runAsync(() -> {
            try {
                r.run();
                complete(t.vid(), new File(t.desDir(), t.fileName() + (t.thumbnail() != null ? ".jpg" : "." + (t.subtitle() != null ? t.subtitle().getExtension() : "srt"))));
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor).exceptionally(e -> handleErr(t, e));
    }

    private void downloadMedia(Task t) {
        streamDL.setMaxThreadCount(t.threadCount());
        File vF = tmp(t, "_v"), aF = tmp(t, "_a"), out = new File(t.desDir(), t.fileName() + (t.video() != null ? ".mp4" : ".m4a"));
        long vSz = len(t.video()), aSz = len(t.audio());
        Aggregator agg = new Aggregator(vSz, aSz, (p, d, tot) -> progress(t.vid(), p, d, tot));
        
        CompletableFuture<File> vFut = t.video() == null ? null : streamDL.download(t.video().getContent(), vF, createProgressAdapter(t.vid(), p -> {
            if (aSz > 0) agg.updV(p);
            else progress(t.vid(), p, (long) (vSz * (p / 100.0)), vSz);
        }));
        
        CompletableFuture<File> aFut = t.audio() == null ? null : streamDL.download(t.audio().getContent(), aF, createProgressAdapter(t.vid(), p -> {
            if (vSz > 0) agg.updA(p);
            else progress(t.vid(), p, (long) (aSz * (p / 100.0)), aSz);
        }));

        CompletableFuture<?> combined = (vFut != null && aFut != null ? CompletableFuture.allOf(vFut, aFut) : (vFut != null ? vFut : aFut));
        if (combined != null) {
            combined.thenRun(() -> {
                try {
                    if (!tasks.containsKey(t.vid()) || pausedTasks.contains(t.vid())) return;
                    if (vFut != null && aFut != null) {
                        notify(t.vid(), ProgressCallback2::onMerge);
                        File mF = tmp(t, "_m");
                        MediaMuxer.merge(vF, aF, mF);
                        if (out.exists()) out.delete();
                        moveFile(mF, out);
                    } else {
                        if (out.exists()) out.delete();
                        moveFile(vFut != null ? vF : aF, out);
                    }
                    complete(t.vid(), out);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).exceptionally(e -> handleErr(t, e));
        }
    }

    private void moveFile(File src, File dest) throws Exception {
        try (Source source = Okio.source(src);
             BufferedSink sink = Okio.buffer(Okio.sink(dest))) {
            sink.writeAll(source);
        }
        src.delete();
    }

    private ProgressCallback createProgressAdapter(String vid, java.util.function.IntConsumer action) {
        return new ProgressCallback() {
            @Override public void onProgress(int p) {
                if (!pausedTasks.contains(vid)) action.accept(p);
            }
            @Override public void onComplete(File f) {}
            @Override public void onError(Exception e) {}
            @Override public void onCancel() {}
        };
    }

    private Void handleErr(Task t, Throwable e) {
        if (pausedTasks.contains(t.vid())) return null;
        Throwable c = e instanceof CompletionException ? e.getCause() : e;
        if (tasks.containsKey(t.vid())) {
            notify(t.vid(), cb -> cb.onError(c instanceof Exception ? (Exception) c : new Exception(c)));
            tasks.remove(t.vid());
        }
        startNext();
        return null;
    }

    private void complete(String vid, File f) {
        if (pausedTasks.contains(vid)) return;
        Task t = tasks.remove(vid);
        if (t != null) {
            notify(vid, cb -> cb.onComplete(f));
            clean(t);
        }
        startNext();
    }

    private void progress(String vid, int p, long downloaded, long total) {
        if (pausedTasks.contains(vid)) return;
        notify(vid, cb -> cb.onProgress(p, downloaded, total));
    }

    private void notify(String vid, CallbackAction action) {
        ProgressCallback2 cb = cbs.get(vid);
        if (cb != null) action.run(cb);
    }

    private void clean(Task t) {
        if (t == null) return;
        deleteFile(tmp(t, "_v"));
        deleteFile(tmp(t, "_a"));
        deleteFile(tmp(t, "_m"));
    }

    private void deleteFile(File file) {
        if (file.exists()) file.delete();
    }

    private File tmp(Task t, String s) { return new File(ctx.getCacheDir(), t.fileName() + s + ".tmp"); }
    private long len(Stream s) { try { return s.getItagItem().getContentLength(); } catch (Exception e) { return 0; } }

    interface RunnableIOC { void run() throws Exception; }
    interface CallbackAction { void run(ProgressCallback2 cb); }
    interface ProgressUpdateListener { void onUpdate(int progress, long downloaded, long total); }

    private static class Aggregator {
        final long vSz, aSz, tot;
        final ProgressUpdateListener listener;
        int vP, aP;
        Aggregator(long v, long a, ProgressUpdateListener l) { vSz = Math.max(v, 1); aSz = Math.max(a, 1); tot = vSz + aSz; listener = l; }
        synchronized void updV(int p) { vP = p; calc(); }
        synchronized void updA(int p) { aP = p; calc(); }
        void calc() {
            int totalProgress = (int) ((vP * vSz + aP * aSz) / tot);
            long downloaded = (long) (vSz * (vP / 100.0) + aSz * (aP / 100.0));
            listener.onUpdate(totalProgress, downloaded, tot);
        }
    }
}
