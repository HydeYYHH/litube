package com.hhst.youtubelite.downloader.core.impl;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.downloader.core.LiteDownloader;
import com.hhst.youtubelite.downloader.core.MediaMuxer;
import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.hhst.youtubelite.downloader.core.ProgressCallback2;
import com.hhst.youtubelite.downloader.core.StreamDownloader;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.extractor.YoutubeExtractor;

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

@Singleton
public class LiteDownloaderImpl implements LiteDownloader {
	private final Context ctx;
	private final StreamDownloader streamDL;
	private final YoutubeExtractor youtubeExtractor;
	private final MediaMerger mediaMerger;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final Map<String, Task> tasks = new ConcurrentHashMap<>();
	private final Map<String, ProgressCallback2> cbs = new ConcurrentHashMap<>();

	@Inject
	public LiteDownloaderImpl(@ApplicationContext Context ctx,
	                          StreamDownloader streamDL,
	                          YoutubeExtractor youtubeExtractor) {
		this(ctx, streamDL, youtubeExtractor, MediaMuxer::merge);
	}

	LiteDownloaderImpl(@ApplicationContext Context ctx,
	                   StreamDownloader streamDL,
	                   YoutubeExtractor youtubeExtractor,
	                   MediaMerger mediaMerger) {
		this.ctx = ctx;
		this.streamDL = streamDL;
		this.youtubeExtractor = youtubeExtractor;
		this.mediaMerger = mediaMerger;
	}

	@Override
	public void setCallback(@NonNull String vid, ProgressCallback2 cb) {
		if (cb != null) cbs.put(vid, cb);
		else cbs.remove(vid);
	}

	@Override
	public void download(@NonNull Task t) {
		tasks.put(t.vid(), t);
		if (t.subtitle() != null) {
			exec(t, () -> FileUtils.copyURLToFile(new URL(t.subtitle().getContent()), outputFile(t)));
		} else if (t.thumbnail() != null) {
			exec(t, () -> FileUtils.copyURLToFile(new URL(t.thumbnail()), outputFile(t)));
		} else {
			downloadMedia(t);
		}
	}

	private void exec(Task t, RunnableIOC r) {
		CompletableFuture.runAsync(() -> {
			try {
				r.run();
				complete(t.vid(), outputFile(t));
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, executor).exceptionally(e -> handleErr(t, e));
	}

	private void downloadMedia(Task t) {
		streamDL.setMaxThreadCount(t.threadCount());
		File vF = tmp(t, "_v"), aF = tmp(t, "_a"), out = outputFile(t);
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
					if (!tasks.containsKey(t.vid())) return;
					if (vFut != null && aFut != null) {
						notify(t.vid(), ProgressCallback2::onMerge);
						File mF = tmp(t, "_m");
						try {
							mediaMerger.merge(vF, aF, mF);
							if (out.exists()) out.delete();
							FileUtils.moveFile(mF, out);
						} finally {
							FileUtils.deleteQuietly(vF);
							FileUtils.deleteQuietly(aF);
							FileUtils.deleteQuietly(mF);
						}
					} else {
						if (out.exists()) out.delete();
						FileUtils.moveFile(vFut != null ? vF : aF, out);
					}
					complete(t.vid(), out);
				} catch (Exception e) {
					throw new CompletionException(e);
				}
			}).exceptionally(e -> handleErr(t, e));
		}
	}

	@Override
	public void pause(@NonNull String vid) {
		Task t = tasks.get(vid);
		if (t != null) {
			if (t.video() != null) streamDL.pause(t.video().getContent());
			if (t.audio() != null) streamDL.pause(t.audio().getContent());
		}
	}

	@Override
	public void resume(@NonNull String vid) {
		Task t = tasks.get(vid);
		if (t != null) {
			if (t.video() != null) streamDL.resume(t.video().getContent());
			if (t.audio() != null) streamDL.resume(t.audio().getContent());
			progress(vid, -1, -1, -1);
		}
	}

	@Override
	public void cancel(@NonNull String vid) {
		Task t = tasks.remove(vid);
		try {
			if (t == null) return;
			if (t.video() != null) streamDL.cancel(t.video().getContent());
			if (t.audio() != null) streamDL.cancel(t.audio().getContent());
			notify(vid, ProgressCallback2::onCancel);
			clean(t);
		} finally {
			clearCallback(vid);
		}
	}

	private ProgressCallback createProgressAdapter(String vid, java.util.function.IntConsumer action) {
		return new ProgressCallback() {
			@Override public void onProgress(int p) { action.accept(p); }
			@Override public void onComplete(File f) {}
			@Override public void onError(Exception e) {}
			@Override public void onCancel() {}
		};
	}

	private Void handleErr(Task t, Throwable e) {
		Throwable c = e instanceof CompletionException ? e.getCause() : e;
		try {
			invalidatePlaybackCacheIfLikelyExpiredStream(t, c);
			if (tasks.containsKey(t.vid())) {
				notify(t.vid(), cb -> cb.onError(c instanceof Exception ? (Exception) c : new Exception(c)));
				clean(tasks.remove(t.vid()));
			}
		} finally {
			clearCallback(t.vid());
		}
		return null;
	}

	void invalidatePlaybackCacheIfLikelyExpiredStream(@NonNull final Task task,
	                                                  @Nullable final Throwable throwable) {
		if (task.video() == null && task.audio() == null) return;
		if (!isLikelyExpiredStreamError(throwable)) return;
		final String videoId = rawVideoId(task.vid());
		if (videoId.isEmpty()) return;
		youtubeExtractor.invalidatePlaybackCacheByVideoId(videoId);
	}

	static boolean isLikelyExpiredStreamError(@Nullable final Throwable throwable) {
		Throwable current = throwable;
		while (current != null) {
			final String message = current.getMessage();
			if (message != null && (message.contains(" 401")
							|| message.contains(" 403")
							|| message.contains(" 404")
							|| message.contains(" 410"))) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	@NonNull
	static String rawVideoId(@NonNull final String taskId) {
		final int suffixIndex = taskId.lastIndexOf(':');
		return suffixIndex >= 0 ? taskId.substring(0, suffixIndex) : taskId;
	}

	private void complete(String vid, File f) {
		try {
			if (tasks.remove(vid) != null) notify(vid, cb -> cb.onComplete(f));
		} finally {
			clearCallback(vid);
		}
	}

	private void progress(String vid, int p, long downloaded, long total) {
		notify(vid, cb -> cb.onProgress(p, downloaded, total));
	}

	private void notify(String vid, CallbackAction action) {
		ProgressCallback2 cb = cbs.get(vid);
		if (cb != null) action.run(cb);
	}

	private void clearCallback(@NonNull final String vid) {
		cbs.remove(vid);
	}

	private void clean(Task t) {
		if (t == null) return;
		if (t.video() != null) FileUtils.deleteQuietly(tmp(t, "_v"));
		if (t.audio() != null) FileUtils.deleteQuietly(tmp(t, "_a"));
		if (t.video() != null && t.audio() != null) FileUtils.deleteQuietly(tmp(t, "_m"));
	}

	private File tmp(Task t, String s) {
		return new File(ctx.getCacheDir(), taskFileKey(t) + s + ".tmp");
	}

	private File outputFile(@NonNull final Task task) {
		if (task.subtitle() != null) {
			return new File(task.desDir(), task.fileName() + "." + task.subtitle().getExtension());
		}
		if (task.thumbnail() != null) {
			return new File(task.desDir(), task.fileName() + ".jpg");
		}
		return new File(task.desDir(), task.fileName() + (task.video() != null ? ".mp4" : ".m4a"));
	}

	private String taskFileKey(@NonNull final Task task) {
		return task.vid().replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	private long len(Stream s) { try { return s.getItagItem().getContentLength(); } catch (Exception e) { return 0; } }

	interface RunnableIOC { void run() throws Exception; }
	interface MediaMerger {
		void merge(@NonNull File videoFile, @NonNull File audioFile, @NonNull File outputFile) throws Exception;
	}
	interface CallbackAction { void run(ProgressCallback2 cb); }
	interface ProgressUpdateListener { void onUpdate(int progress, long downloaded, long total); }

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

		synchronized void updV(int p) { vP = p; calc(); }
		synchronized void updA(int p) { aP = p; calc(); }

		void calc() {
			int totalProgress = (int) ((vP * vSz + aP * aSz) / tot);
			long downloaded = (long) (vSz * (vP / 100.0) + aSz * (aP / 100.0));
			listener.onUpdate(totalProgress, downloaded, tot);
		}
	}
}
