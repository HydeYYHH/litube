package com.hhst.youtubelite.player.queue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.YoutubeExtractor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class QueueWarmer {
	@NonNull
	private final YoutubeExtractor youtubeExtractor;
	@NonNull
	private final Executor executor;
	@NonNull
	private final Object lock = new Object();
	@NonNull
	private final Deque<WarmRequest> pending = new ArrayDeque<>();
	@NonNull
	private final Map<String, WarmRequest> pendingByVideoId = new HashMap<>();
	@NonNull
	private final Set<String> runningVideoIds = new HashSet<>();
	private boolean draining;

	@Inject
	public QueueWarmer(@NonNull final YoutubeExtractor youtubeExtractor,
	                   @NonNull final Executor executor) {
		this.youtubeExtractor = youtubeExtractor;
		this.executor = executor;
	}

	public void warmItems(@NonNull final List<QueueItem> items) {
		for (final QueueItem item : items) {
			warmItem(item);
		}
	}

	public void warmItem(@Nullable final QueueItem item) {
		enqueue(createRequest(item), false);
	}

	public void prioritizeItem(@Nullable final QueueItem item) {
		enqueue(createRequest(item), true);
	}

	public void prioritizeUrl(@Nullable final String url) {
		enqueue(createRequest(url), true);
	}

	private void enqueue(@Nullable final WarmRequest request, final boolean prioritize) {
		if (request == null) return;
		final boolean shouldSchedule;
		synchronized (lock) {
			if (runningVideoIds.contains(request.videoId())) {
				return;
			}
			final WarmRequest prior = pendingByVideoId.remove(request.videoId());
			if (prior != null) {
				pending.remove(prior);
			}
			pendingByVideoId.put(request.videoId(), request);
			if (prioritize) {
				pending.addFirst(request);
			} else {
				pending.addLast(request);
			}
			shouldSchedule = !draining;
			if (shouldSchedule) {
				draining = true;
			}
		}
		if (!shouldSchedule) return;
		try {
			executor.execute(this::drainQueue);
		} catch (final RuntimeException e) {
			synchronized (lock) {
				draining = false;
				pending.clear();
				pendingByVideoId.clear();
			}
			throw e;
		}
	}

	private void drainQueue() {
		while (true) {
			final WarmRequest request;
			synchronized (lock) {
				request = pending.pollFirst();
				if (request == null) {
					draining = false;
					return;
				}
				pendingByVideoId.remove(request.videoId());
				runningVideoIds.add(request.videoId());
			}
			runWarm(request);
		}
	}

	private void runWarm(@NonNull final WarmRequest request) {
		try {
			youtubeExtractor.getPlaybackDetails(request.url(), new ExtractionSession());
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (final Exception ignored) {
		} finally {
			synchronized (lock) {
				runningVideoIds.remove(request.videoId());
			}
		}
	}

	@Nullable
	private WarmRequest createRequest(@Nullable final QueueItem item) {
		if (item == null) return null;
		final String url = normalize(item.getUrl());
		if (url == null) return null;
		final String videoId = resolveVideoId(item, url);
		return videoId != null ? new WarmRequest(videoId, url) : null;
	}

	@Nullable
	private WarmRequest createRequest(@Nullable final String url) {
		final String normalizedUrl = normalize(url);
		if (normalizedUrl == null) return null;
		final String videoId = resolveVideoId(null, normalizedUrl);
		return videoId != null ? new WarmRequest(videoId, normalizedUrl) : null;
	}

	@Nullable
	private String resolveVideoId(@Nullable final QueueItem item, @NonNull final String url) {
		if (item != null) {
			final String itemVideoId = normalize(item.getVideoId());
			if (itemVideoId != null) {
				return itemVideoId;
			}
		}
		return normalize(YoutubeExtractor.getVideoId(url));
	}

	@Nullable
	private String normalize(@Nullable final String value) {
		if (value == null) return null;
		final String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private record WarmRequest(@NonNull String videoId, @NonNull String url) {
	}
}
