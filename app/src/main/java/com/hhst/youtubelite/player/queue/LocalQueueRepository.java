package com.hhst.youtubelite.player.queue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class LocalQueueRepository {
	static final String KEY_LOCAL_QUEUE_ITEMS = "local_queue_items";
	static final String KEY_LOCAL_QUEUE_ENABLED = "local_queue_enabled";
	private static final Type LIST_TYPE = new TypeToken<List<QueueItem>>() {
	}.getType();

	@NonNull
	private final MMKV mmkv;
	@NonNull
	private final Gson gson;

	@Inject
	public LocalQueueRepository(@NonNull final MMKV mmkv, @NonNull final Gson gson) {
		this.mmkv = mmkv;
		this.gson = gson;
	}

	public synchronized boolean isEnabled() {
		return mmkv.decodeBool(KEY_LOCAL_QUEUE_ENABLED, false);
	}

	public synchronized void setEnabled(final boolean enabled) {
		mmkv.encode(KEY_LOCAL_QUEUE_ENABLED, enabled);
	}

	@NonNull
	public synchronized List<QueueItem> getItems() {
		final List<QueueItem> items = readItems();
		final List<QueueItem> copies = new ArrayList<>(items.size());
		for (final QueueItem item : items) {
			copies.add(item.copy());
		}
		return copies;
	}

	public synchronized void add(@NonNull final QueueItem item) {
		final List<QueueItem> items = readItems();
		items.removeIf(existing -> sameVideo(existing, item));
		items.add(item.copy());
		writeItems(items);
	}

	public synchronized boolean containsVideo(@Nullable final String videoId) {
		if (videoId == null) return false;
		for (final QueueItem item : readItems()) {
			if (Objects.equals(item.getVideoId(), videoId)) return true;
		}
		return false;
	}

	public synchronized void clear() {
		mmkv.removeValueForKey(KEY_LOCAL_QUEUE_ITEMS);
	}

	@Nullable
	public synchronized QueueItem findRelative(@Nullable final String currentVideoId, final int offset) {
		final List<QueueItem> items = readItems();
		if (items.isEmpty() || offset == 0) return null;
		final int currentIndex = indexOf(items, currentVideoId);
		if (currentIndex < 0) {
			return offset > 0 ? items.get(0).copy() : items.get(items.size() - 1).copy();
		}
		final int targetIndex = currentIndex + offset;
		if (targetIndex < 0 || targetIndex >= items.size()) return null;
		return items.get(targetIndex).copy();
	}

	@Nullable
	public synchronized QueueItem findRandom(@Nullable final String currentVideoId) {
		final List<QueueItem> items = readItems();
		if (items.isEmpty()) return null;
		if (items.size() == 1) return items.get(0).copy();
		final int currentIndex = indexOf(items, currentVideoId);
		final List<QueueItem> candidates = new ArrayList<>(items);
		if (currentIndex >= 0) candidates.remove(currentIndex);
		if (candidates.isEmpty()) return items.get(0).copy();
		return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).copy();
	}

	private int indexOf(@NonNull final List<QueueItem> items, @Nullable final String videoId) {
		if (videoId == null) return -1;
		for (int i = 0; i < items.size(); i++) {
			if (Objects.equals(items.get(i).getVideoId(), videoId)) return i;
		}
		return -1;
	}

	private boolean sameVideo(@NonNull final QueueItem first, @NonNull final QueueItem second) {
		if (first.getVideoId() == null || second.getVideoId() == null) return false;
		return Objects.equals(first.getVideoId(), second.getVideoId());
	}

	@NonNull
	private List<QueueItem> readItems() {
		final String json = mmkv.decodeString(KEY_LOCAL_QUEUE_ITEMS, null);
		if (json == null || json.isBlank()) return new ArrayList<>();
		try {
			final List<QueueItem> items = gson.fromJson(json, LIST_TYPE);
			return items != null ? items : new ArrayList<>();
		} catch (final Exception ignored) {
			return new ArrayList<>();
		}
	}

	private void writeItems(@NonNull final List<QueueItem> items) {
		mmkv.encode(KEY_LOCAL_QUEUE_ITEMS, gson.toJson(items, LIST_TYPE));
	}
}
