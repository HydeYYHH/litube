package com.hhst.youtubelite.downloader.core.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Repository for persisted download history records.
 */
@Singleton
public final class DownloadHistoryRepository {
	public static final String KEY_DOWNLOAD_HISTORY = "download_history";

	private static final Type LIST_TYPE = new TypeToken<List<DownloadRecord>>() {
	}.getType();

	@NonNull
	private final MMKV mmkv;
	@NonNull
	private final Gson gson;

	@Inject
	public DownloadHistoryRepository(@NonNull MMKV mmkv, @NonNull Gson gson) {
		this.mmkv = mmkv;
		this.gson = gson;
	}

	@NonNull
	public synchronized List<DownloadRecord> getAllSorted() {
		List<DownloadRecord> items = readAllInternal();
		items.sort(Comparator.comparingLong(DownloadRecord::getCreatedAt).reversed());
		return items;
	}

	@NonNull
	public synchronized List<DownloadRecord> getRootsSorted() {
		List<DownloadRecord> roots = new ArrayList<>();
		for (DownloadRecord record : readAllInternal()) {
			if (record.getParentId() == null || record.getParentId().isBlank()) roots.add(record);
		}
		roots.sort(Comparator.comparingLong(DownloadRecord::getCreatedAt).reversed());
		return roots;
	}

	@NonNull
	public synchronized List<DownloadRecord> getChildrenSorted(@NonNull String parentId) {
		List<DownloadRecord> children = new ArrayList<>();
		for (DownloadRecord record : readAllInternal()) {
			if (Objects.equals(parentId, record.getParentId())) children.add(record);
		}
		children.sort(Comparator.comparingLong(DownloadRecord::getCreatedAt).reversed());
		return children;
	}

	@Nullable
	public synchronized DownloadRecord findByTaskId(@Nullable String taskId) {
		if (taskId == null) return null;
		for (DownloadRecord record : readAllInternal()) {
			if (Objects.equals(record.getTaskId(), taskId)) return record;
		}
		return null;
	}

	public synchronized void upsert(@NonNull DownloadRecord record) {
		List<DownloadRecord> items = readAllInternal();
		boolean updated = false;
		for (int i = 0; i < items.size(); i++) {
			if (Objects.equals(items.get(i).getTaskId(), record.getTaskId())) {
				items.set(i, record);
				updated = true;
				break;
			}
		}
		if (!updated) items.add(record);
		writeAllInternal(items);
	}

	public synchronized void remove(@NonNull String taskId) {
		List<DownloadRecord> items = readAllInternal();
		items.removeIf(record -> Objects.equals(record.getTaskId(), taskId));
		writeAllInternal(items);
	}

	public synchronized void removeWithChildren(@NonNull String taskId) {
		List<DownloadRecord> items = readAllInternal();
		items.removeIf(record -> Objects.equals(record.getTaskId(), taskId) || Objects.equals(taskId, record.getParentId()));
		writeAllInternal(items);
	}

	public synchronized void clear() {
		mmkv.removeValueForKey(KEY_DOWNLOAD_HISTORY);
	}

	@NonNull
	private List<DownloadRecord> readAllInternal() {
		String json = mmkv.decodeString(KEY_DOWNLOAD_HISTORY, null);
		if (json == null || json.isBlank()) return new ArrayList<>();
		try {
			List<DownloadRecord> items = gson.fromJson(json, LIST_TYPE);
			return items != null ? items : new ArrayList<>();
		} catch (Exception ignored) {
			return new ArrayList<>();
		}
	}

	private void writeAllInternal(@NonNull List<DownloadRecord> items) {
		mmkv.encode(KEY_DOWNLOAD_HISTORY, gson.toJson(items, LIST_TYPE));
	}
}

