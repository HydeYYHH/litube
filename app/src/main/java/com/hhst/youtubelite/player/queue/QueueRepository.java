package com.hhst.youtubelite.player.queue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class QueueRepository {
	static final String KEY_QUEUE_ITEMS = "local_queue_items";
	static final String KEY_QUEUE_ENABLED = "local_queue_enabled";
	private static final Type LIST_TYPE = new TypeToken<List<QueueItem>>() {
	}.getType();

	@NonNull
	private final MMKV mmkv;
	@NonNull
	private final Gson gson;
	@NonNull
	private final List<QueueInvalidationListener> listeners = new ArrayList<>();

	@Inject
	public QueueRepository(@NonNull final MMKV mmkv, @NonNull final Gson gson) {
		this.mmkv = mmkv;
		this.gson = gson;
	}

	public synchronized boolean isEnabled() {
		return mmkv.decodeBool(KEY_QUEUE_ENABLED, false);
	}

	public void setEnabled(final boolean enabled) {
		synchronized (this) {
			mmkv.encode(KEY_QUEUE_ENABLED, enabled);
		}
		notifyListeners();
	}

	public synchronized void addListener(@NonNull final QueueInvalidationListener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
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

	public void add(@NonNull final QueueItem item) {
		synchronized (this) {
			final List<QueueItem> items = readItems();
			items.removeIf(it -> sameVideo(it, item));
			items.add(item.copy());
			writeItems(items);
		}
		notifyListeners();
	}

	public boolean remove(@NonNull final String videoId) {
		boolean removed = false;
		synchronized (this) {
			final List<QueueItem> items = readItems();
			final Iterator<QueueItem> iterator = items.iterator();
			while (iterator.hasNext()) {
				if (Objects.equals(iterator.next().getVideoId(), videoId)) {
					iterator.remove();
					removed = true;
					break;
				}
			}
			if (removed) {
				writeItems(items);
			}
		}
		if (removed) {
			notifyListeners();
		}
		return removed;
	}

	public boolean move(final int fromIndex, final int toIndex) {
		boolean moved = false;
		synchronized (this) {
			final List<QueueItem> items = readItems();
			if (isValidIndex(fromIndex, items.size()) && isValidIndex(toIndex, items.size()) && fromIndex != toIndex) {
				final QueueItem item = items.remove(fromIndex);
				items.add(toIndex, item);
				writeItems(items);
				moved = true;
			}
		}
		if (moved) {
			notifyListeners();
		}
		return moved;
	}

	public synchronized boolean containsVideo(@Nullable final String videoId) {
		if (videoId == null) return false;
		for (final QueueItem item : readItems()) {
			if (Objects.equals(item.getVideoId(), videoId)) return true;
		}
		return false;
	}

	public synchronized boolean hasItems() {
		return !readItems().isEmpty();
	}

	public void clear() {
		synchronized (this) {
			mmkv.removeValueForKey(KEY_QUEUE_ITEMS);
		}
		notifyListeners();
	}

	@Nullable
	public synchronized QueueItem findRelative(@Nullable final String videoId, final int offset) {
		final List<QueueItem> items = readItems();
		if (items.isEmpty() || offset == 0) return null;
		final int index = indexOf(items, videoId);
		if (index < 0) {
			return offset > 0 ? items.get(0).copy() : items.get(items.size() - 1).copy();
		}
		final int target = index + offset;
		if (target < 0) return null;
		if (target >= items.size()) {
			if (offset > 0 && items.size() > 1) {
				return items.get(target % items.size()).copy();
			}
			return null;
		}
		return items.get(target).copy();
	}

	@Nullable
	public synchronized QueueItem findRandom(@Nullable final String videoId) {
		final List<QueueItem> items = readItems();
		if (items.isEmpty()) return null;
		if (items.size() == 1) return items.get(0).copy();
		final int index = indexOf(items, videoId);
		final List<QueueItem> candidates = new ArrayList<>(items);
		if (index >= 0) candidates.remove(index);
		if (candidates.isEmpty()) return items.get(0).copy();
		return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).copy();
	}

	private boolean isValidIndex(final int index, final int size) {
		return index >= 0 && index < size;
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
		final String json = mmkv.decodeString(KEY_QUEUE_ITEMS, null);
		if (json == null || json.isBlank()) return new ArrayList<>();
		try {
			final List<QueueItem> items = gson.fromJson(json, LIST_TYPE);
			return items != null ? items : new ArrayList<>();
		} catch (final Exception ignored) {
			return new ArrayList<>();
		}
	}

	private void notifyListeners() {
		final List<QueueInvalidationListener> snapshot;
		synchronized (this) {
			if (listeners.isEmpty()) return;
			snapshot = new ArrayList<>(listeners);
		}
		for (final QueueInvalidationListener listener : snapshot) {
			listener.onQueueInvalidated();
		}
	}

	private void writeItems(@NonNull final List<QueueItem> items) {
		mmkv.encode(KEY_QUEUE_ITEMS, gson.toJson(items, LIST_TYPE));
	}
}
