package com.hhst.youtubelite.downloader.core.impl;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hhst.youtubelite.downloader.core.LiteDownloader;
import com.hhst.youtubelite.downloader.core.MediaMuxer;
import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.hhst.youtubelite.downloader.core.ProgressCallback2;
import com.hhst.youtubelite.downloader.core.StreamDownloader;
import com.hhst.youtubelite.downloader.core.Task;

import org.apache.commons.io.FileUtils;
import org.schabi.newpipe.extractor.stream.Stream;

import java.io.File;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Runs file downloads and merge callbacks.
 */
@Singleton
public class LiteDownloaderImpl implements LiteDownloader {
	private final Context context;
	private final StreamDownloader streamDL;
	private final MediaMerger mediaMerger;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final Map<String, Task> tasks = new ConcurrentHashMap<>();
	private final Map<String, ProgressCallback2> callbacks = new ConcurrentHashMap<>();

	@Inject
	public LiteDownloaderImpl(@ApplicationContext Context ctx,
	                          StreamDownloader streamDL) {
		this(ctx, streamDL, MediaMuxer::merge);
	}

	LiteDownloaderImpl(@ApplicationContext Context ctx,
	                   StreamDownloader streamDL,
	                   MediaMerger mediaMerger) {
		this.context = ctx;
		this.streamDL = streamDL;
		this.mediaMerger = mediaMerger;
	}

	@Override
	public void setCallback(@NonNull String videoId, ProgressCallback2 callback) {
		if (callback != null) callbacks.put(videoId, callback);
		else callbacks.remove(videoId);
	}

	@Override
	public void download(@NonNull Task t) {
		tasks.put(t.videoId(), t);
		if (t.subtitle() != null) {
			exec(t, () -> FileUtils.copyURLToFile(new URL(t.subtitle().getContent()), outputFile(t)));
		} else if (t.thumbnail() != null) {
			exec(t, () -> FileUtils.copyURLToFile(new URL(t.thumbnail()), outputFile(t)));
		} else {
			downloadMedia(t);
		}
	}

	private void exec(Task task, RunnableIOC run) {
		CompletableFuture.runAsync(() -> {
			try {
				run.run();
				complete(task.videoId(), outputFile(task));
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, executor).exceptionally(e -> handleErr(task, e));
	}

	private void downloadMedia(Task task) {
		// Download audio and video separately, then merge when needed.
		streamDL.setMaxThreadCount(task.threadCount());
		File vF = tmp(task, "_v"), aF = tmp(task, "_a"), out = outputFile(task);
		long vSz = len(task.video()), aSz = len(task.audio());

		Aggregator agg = new Aggregator(vSz, aSz, (p, d, tot) -> progress(task.videoId(), p, d, tot));

		CompletableFuture<File> vFut = task.video() == null ? null : streamDL.download(task.video().getContent(), vF, createProgressAdapter(p -> {
			if (aSz > 0) agg.updV(p);
			else progress(task.videoId(), p, (long) (vSz * (p / 100.0)), vSz);
		}));

		CompletableFuture<File> aFut = task.audio() == null ? null : streamDL.download(task.audio().getContent(), aF, createProgressAdapter(p -> {
			if (vSz > 0) agg.updA(p);
			else progress(task.videoId(), p, (long) (aSz * (p / 100.0)), aSz);
		}));

		(vFut != null && aFut != null ? CompletableFuture.allOf(vFut, aFut) : (vFut != null ? vFut : aFut)).thenRun(() -> {
			try {
				if (!tasks.containsKey(task.videoId())) return;
				if (vFut != null && aFut != null) {
					notify(task.videoId(), ProgressCallback2::onMerge);
					File mF = tmp(task, "_m");
					try {
						mediaMerger.merge(vF, aF, mF);
						FileUtils.moveFile(mF, out);
					} finally {
						FileUtils.deleteQuietly(vF);
						FileUtils.deleteQuietly(aF);
						FileUtils.deleteQuietly(mF);
					}
				} else {
					FileUtils.moveFile(vFut != null ? vF : aF, out);
				}
				complete(task.videoId(), out);
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}).exceptionally(e -> handleErr(task, e));
	}

	@Override
	public void cancel(@NonNull String videoId) {
		Task t = tasks.remove(videoId);
		try {
			if (t == null) return;
			if (t.video() != null) streamDL.cancel(t.video().getContent());
			if (t.audio() != null) streamDL.cancel(t.audio().getContent());
			notify(videoId, ProgressCallback2::onCancel);
			clean(t);
		} finally {
			clearCallback(videoId);
		}
	}

	private ProgressCallback createProgressAdapter(java.util.function.IntConsumer action) {
		return new ProgressCallback() {
			@Override
			public void onProgress(int progress) {
				action.accept(progress);
			}

			@Override
			public void onComplete(File file) {
			}

			@Override
			public void onError(Exception e) {
			}

			@Override
			public void onCancel() {
			}
		};
	}

	private Void handleErr(Task t, Throwable e) {
		Throwable cause = e instanceof CompletionException ? e.getCause() : e;
		try {
			if (tasks.containsKey(t.videoId())) {
				notify(t.videoId(), callback -> callback.onError(cause instanceof Exception ? (Exception) cause : new Exception(cause)));
				clean(tasks.remove(t.videoId()));
			}
		} finally {
			clearCallback(t.videoId());
		}
		return null;
	}

	private void complete(String videoId, File f) {
		try {
			if (tasks.remove(videoId) != null) notify(videoId, callback -> callback.onComplete(f));
		} finally {
			clearCallback(videoId);
		}
	}

	private void progress(String videoId, int p, long downloaded, long total) {
		notify(videoId, callback -> callback.onProgress(p, downloaded, total));
	}

	private void notify(String videoId, CallbackAction action) {
		ProgressCallback2 callback = callbacks.get(videoId);
		if (callback != null) action.run(callback);
	}

	private void clearCallback(@NonNull String videoId) {
		callbacks.remove(videoId);
	}

	private void clean(Task task) {
		if (task == null) return;
		if (task.video() != null) FileUtils.deleteQuietly(tmp(task, "_v"));
		if (task.audio() != null) FileUtils.deleteQuietly(tmp(task, "_a"));
		if (task.video() != null && task.audio() != null) FileUtils.deleteQuietly(tmp(task, "_m"));
		FileUtils.deleteQuietly(outputFile(task));
	}

	private File tmp(Task task, String suffix) {
		return new File(context.getCacheDir(), taskFileKey(task) + suffix + ".tmp");
	}

	private File outputFile(@NonNull Task task) {
		if (task.subtitle() != null) {
			return new File(task.desDir(), task.fileName() + "." + task.subtitle().getExtension());
		}
		if (task.thumbnail() != null) {
			return new File(task.desDir(), task.fileName() + ".jpg");
		}
		return new File(task.desDir(), task.fileName() + (task.video() != null ? ".mp4" : ".m4a"));
	}

	private String taskFileKey(@NonNull Task task) {
		return task.videoId().replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	private long len(Stream s) {
		try {
			return s.getItagItem().getContentLength();
		} catch (Exception e) {
			return 0;
		}
	}

/**
 * Contract for app logic.
 */
	interface RunnableIOC {
		void run() throws Exception;
	}

	interface MediaMerger {
		void merge(@NonNull File videoFile, @NonNull File audioFile, @NonNull File outputFile) throws Exception;
	}

/**
 * Contract for app logic.
 */
	interface CallbackAction {
		void run(ProgressCallback2 cb);
	}

	interface ProgressUpdateListener {
		void onUpdate(int progress, long downloaded, long total);
	}

/**
 * Component that handles app logic.
 */
	private static class Aggregator {
		final long vSz, aSz, tot;
		final ProgressUpdateListener listener;
		int vP, aP;

		Aggregator(long v, long a, ProgressUpdateListener l) {
			vSz = Math.max(v, 1);
			aSz = Math.max(a, 1);
			tot = vSz + aSz;
			listener = l;
		}

		synchronized void updV(int p) {
			vP = p;
			calc();
		}

		synchronized void updA(int p) {
			aP = p;
			calc();
		}

		void calc() {
			int totalProgress = (int) ((vP * vSz + aP * aSz) / tot);
			long downloaded = (long) (vSz * (vP / 100.0) + aSz * (aP / 100.0));
			listener.onUpdate(totalProgress, downloaded, tot);
		}
	}
}
