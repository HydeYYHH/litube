package com.hhst.youtubelite.player.queue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

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

/**
 * Repository that persists the playback queue in MMKV.
 */
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
	@NonNull
	private final MutableLiveData<QueueState> state;

	@Inject
	public QueueRepository(@NonNull MMKV mmkv, @NonNull Gson gson) {
		this.mmkv = mmkv;
		this.gson = gson;
		this.state = new MutableLiveData<>(snapshotState());
	}

	public synchronized boolean isEnabled() {
		return mmkv.decodeBool(KEY_QUEUE_ENABLED, false);
	}

	public void setEnabled(boolean enabled) {
		synchronized (this) {
			mmkv.encode(KEY_QUEUE_ENABLED, enabled);
		}
		notifyListeners();
	}

	public synchronized void addListener(@NonNull QueueInvalidationListener listener) {
		if (!listeners.contains(listener)) {
			listeners.add(listener);
		}
	}

	public synchronized void removeListener(@NonNull QueueInvalidationListener listener) {
		listeners.remove(listener);
	}

	@NonNull
	public LiveData<QueueState> getState() {
		return state;
	}

	@NonNull
	public synchronized List<QueueItem> getItems() {
		List<QueueItem> items = readItems();
		List<QueueItem> out = new ArrayList<>(items.size());
		for (QueueItem item : items) {
			out.add(item.copy());
		}
		return out;
	}

	public void add(@NonNull QueueItem item) {
		synchronized (this) {
			List<QueueItem> items = readItems();
			items.removeIf(it -> sameVideo(it, item));
			items.add(item.copy());
			writeItems(items);
		}
		notifyListeners();
	}

	public boolean remove(@NonNull String videoId) {
		boolean removed = false;
		synchronized (this) {
			List<QueueItem> items = readItems();
			Iterator<QueueItem> iterator = items.iterator();
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

	public boolean move(int fromIndex, int toIndex) {
		boolean moved = false;
		synchronized (this) {
			List<QueueItem> items = readItems();
			if (isValidIndex(fromIndex, items.size()) && isValidIndex(toIndex, items.size()) && fromIndex != toIndex) {
				QueueItem item = items.remove(fromIndex);
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

	public synchronized boolean containsVideo(@Nullable String videoId) {
		if (videoId == null) return false;
		for (QueueItem item : readItems()) {
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
	public synchronized QueueItem findRelative(@Nullable String videoId, int offset) {
		List<QueueItem> items = readItems();
		if (items.isEmpty() || offset == 0) return null;
		int index = indexOf(items, videoId);
		if (index < 0) {
			return offset > 0 ? items.get(0).copy() : items.get(items.size() - 1).copy();
		}
		int target = index + offset;
		if (target < 0) return null;
		if (target >= items.size()) {
			if (offset > 0) {
				return items.get(target % items.size()).copy();
			}
			return null;
		}
		return items.get(target).copy();
	}

	@Nullable
	public synchronized QueueItem findRandom(@Nullable String videoId) {
		List<QueueItem> items = readItems();
		if (items.isEmpty()) return null;
		if (items.size() == 1) return items.get(0).copy();
		int index = indexOf(items, videoId);
		List<QueueItem> candidates = new ArrayList<>(items);
		if (index >= 0) candidates.remove(index);
		if (candidates.isEmpty()) return items.get(0).copy();
		return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).copy();
	}

	private boolean isValidIndex(int index, int size) {
		return index >= 0 && index < size;
	}

	private int indexOf(@NonNull List<QueueItem> items, @Nullable String videoId) {
		if (videoId == null) return -1;
		for (int i = 0; i < items.size(); i++) {
			if (Objects.equals(items.get(i).getVideoId(), videoId)) return i;
		}
		return -1;
	}

	private boolean sameVideo(@NonNull QueueItem first, @NonNull QueueItem second) {
		if (first.getVideoId() == null || second.getVideoId() == null) return false;
		return Objects.equals(first.getVideoId(), second.getVideoId());
	}

	@NonNull
	private List<QueueItem> readItems() {
		String json = mmkv.decodeString(KEY_QUEUE_ITEMS, null);
		if (json == null || json.isBlank()) return new ArrayList<>();
		try {
			List<QueueItem> items = gson.fromJson(json, LIST_TYPE);
			return items != null ? items : new ArrayList<>();
		} catch (Exception ignored) {
			return new ArrayList<>();
		}
	}

	private void notifyListeners() {
		QueueState queueState = snapshotState();
		state.postValue(queueState);
		final List<QueueInvalidationListener> out;
		synchronized (this) {
			if (listeners.isEmpty()) return;
			out = new ArrayList<>(listeners);
		}
		for (QueueInvalidationListener listener : out) {
			listener.onQueueInvalidated();
		}
	}

	private void writeItems(@NonNull List<QueueItem> items) {
		mmkv.encode(KEY_QUEUE_ITEMS, gson.toJson(items, LIST_TYPE));
	}

	@NonNull
	private synchronized QueueState snapshotState() {
		return new QueueState(isEnabled(), readItems());
	}
}
