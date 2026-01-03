package com.hhst.youtubelite.downloader.core.history;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

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
	public DownloadHistoryRepository(@NonNull final MMKV mmkv, @NonNull final Gson gson) {
		this.mmkv = mmkv;
		this.gson = gson;
	}

	@NonNull
	public synchronized List<DownloadRecord> getAllSorted() {
		final List<DownloadRecord> list = readAllInternal();
		list.sort(Comparator.comparingLong(DownloadRecord::getCreatedAt).reversed());
		return list;
	}

	@Nullable
	public synchronized DownloadRecord findByTaskId(@Nullable final String taskId) {
		if (taskId == null) return null;
		for (final DownloadRecord r : readAllInternal()) {
			if (Objects.equals(r.getTaskId(), taskId)) return r;
		}
		return null;
	}

	public synchronized void upsert(@NonNull final DownloadRecord record) {
		final List<DownloadRecord> list = readAllInternal();
		boolean updated = false;
		for (int i = 0; i < list.size(); i++) {
			if (Objects.equals(list.get(i).getTaskId(), record.getTaskId())) {
				list.set(i, record);
				updated = true;
				break;
			}
		}
		if (!updated) list.add(record);
		writeAllInternal(list);
	}

	public synchronized void remove(@NonNull final String taskId) {
		final List<DownloadRecord> list = readAllInternal();
		list.removeIf(r -> Objects.equals(r.getTaskId(), taskId));
		writeAllInternal(list);
	}

	public synchronized void clear() {
		mmkv.removeValueForKey(KEY_DOWNLOAD_HISTORY);
	}

	@NonNull
	private List<DownloadRecord> readAllInternal() {
		final String json = mmkv.decodeString(KEY_DOWNLOAD_HISTORY, null);
		if (json == null || json.isBlank()) return new ArrayList<>();
		try {
			final List<DownloadRecord> list = gson.fromJson(json, LIST_TYPE);
			return list != null ? list : new ArrayList<>();
		} catch (Exception ignored) {
			return new ArrayList<>();
		}
	}

	private void writeAllInternal(@NonNull final List<DownloadRecord> list) {
		mmkv.encode(KEY_DOWNLOAD_HISTORY, gson.toJson(list, LIST_TYPE));
	}
}

