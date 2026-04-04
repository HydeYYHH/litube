package com.hhst.youtubelite.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.ViewModel;

import com.hhst.youtubelite.player.PlayerState;
import com.hhst.youtubelite.player.PlayerStateStore;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.common.PlayerPreferences;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.queue.QueueState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * View model that owns the main screen state.
 */
@HiltViewModel
public final class MainActivityViewModel extends ViewModel {
	@NonNull
	private final QueueRepository queueRepository;
	@NonNull
	private final MediatorLiveData<UiState> state = new MediatorLiveData<>();
	@NonNull
	private QueueState queue = new QueueState(false, List.of());
	@NonNull
	private PlayerState player = new PlayerState(null, false);
	@NonNull
	private PlayerLoopMode loopMode;

	@Inject
	public MainActivityViewModel(@NonNull QueueRepository queueRepository,
	                             @NonNull PlayerStateStore playerStateStore,
	                             @NonNull PlayerPreferences prefs) {
		this.queueRepository = queueRepository;
		this.loopMode = prefs.getLoopMode();
		state.addSource(queueRepository.getState(), value -> {
			queue = value != null ? value : new QueueState(false, List.of());
			publish();
		});
		state.addSource(playerStateStore.getState(), value -> {
			player = value != null ? value : new PlayerState(null, false);
			publish();
		});
		state.addSource(prefs.getLoopModeState(), value -> {
			loopMode = value != null ? value : PlayerLoopMode.PLAYLIST_NEXT;
			publish();
		});
		publish();
	}

	@NonNull
	public LiveData<UiState> getState() {
		return state;
	}

	public void setQueueEnabled(boolean enabled) {
		queueRepository.setEnabled(enabled);
	}

	public void removeQueueItem(@NonNull String videoId) {
		queueRepository.remove(videoId);
	}

	public void clearQueue() {
		queueRepository.clear();
	}

	public void moveQueue(@NonNull List<QueueItem> order) {
		List<QueueItem> items = new ArrayList<>(queueRepository.getItems());
		for (int to = 0; to < order.size(); to++) {
			String videoId = order.get(to).getVideoId();
			if (videoId == null) continue;
			int from = -1;
			for (int i = 0; i < items.size(); i++) {
				if (Objects.equals(videoId, items.get(i).getVideoId())) {
					from = i;
					break;
				}
			}
			if (from < 0 || from == to) continue;
			if (queueRepository.move(from, to)) {
				QueueItem item = items.remove(from);
				items.add(to, item);
			}
		}
	}

	private void publish() {
		state.setValue(new UiState(
						queue.enabled(),
						queue.items(),
						player.videoId(),
						loopMode,
						player.miniPlayer()));
	}

/**
 * Value object for app logic.
 */
	public record UiState(boolean queueEnabled,
	                      @NonNull List<QueueItem> items,
	                      @Nullable String videoId,
	                      @NonNull PlayerLoopMode loopMode,
	                      boolean miniPlayer) {
	}
}
