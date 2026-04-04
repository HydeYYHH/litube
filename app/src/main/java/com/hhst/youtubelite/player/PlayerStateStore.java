package com.hhst.youtubelite.player;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Store for persisting player state across recreations.
 */
@Singleton
public final class PlayerStateStore {
	@NonNull
	private final MutableLiveData<PlayerState> state = new MutableLiveData<>(new PlayerState(null, false));

	@Inject
	public PlayerStateStore() {
	}

	@NonNull
	public LiveData<PlayerState> getState() {
		return state;
	}

	public void setVideoId(@Nullable String videoId) {
		PlayerState value = snapshot();
		state.postValue(new PlayerState(videoId, value.miniPlayer()));
	}

	public void setInMiniPlayer(boolean inMiniPlayer) {
		PlayerState value = snapshot();
		state.postValue(new PlayerState(value.videoId(), inMiniPlayer));
	}

	public void clear() {
		state.postValue(new PlayerState(null, false));
	}

	@NonNull
	private PlayerState snapshot() {
		PlayerState value = state.getValue();
		return value != null ? value : new PlayerState(null, false);
	}
}
