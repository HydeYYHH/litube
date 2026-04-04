package com.hhst.youtubelite.player.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.tencent.mmkv.MMKV;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Preference accessors for player behavior.
 */
@Getter
@Singleton
public final class PlayerPreferences {
	private static final String KEY_PLAYBACK_SPEED = "playback_speed";
	private static final String KEY_VIDEO_QUALITY = "video_quality";
	private static final String KEY_LOOP_MODE = "loop_mode";
	private static final String KEY_LOOP_ENABLED = "loop_enabled";
	private static final String KEY_SUBTITLE_ENABLED = "subtitle_enabled";
	private static final String KEY_SUBTITLE_LANGUAGE = "subtitle_language";
	private static final String KEY_RESIZE_MODE = "resize_mode";
	private static final String KEY_MINI_PLAYER_WIDTH_DP = "mini_player_width_dp";
	private static final String KEY_MINI_PLAYER_TRANSLATION_X_DP = "mini_player_translation_x_dp";
	private static final String KEY_MINI_PLAYER_TRANSLATION_Y_DP = "mini_player_translation_y_dp";
	private static final String PREFIX_PROGRESS = "progress:";

	private static final float DEFAULT_SPEED = 1.0f;
	private static final String DEFAULT_QUALITY = "480p";
	private static final long EXPIRATION_DAYS_3 = 3L * 24 * 60 * 60 * 1000;
	private static final int DEFAULT_MINI_PLAYER_WIDTH_DP = -1;
	private static final float DEFAULT_MINI_PLAYER_TRANSLATION_DP = 0.0f;

	@NonNull
	private final ExtensionManager extensionManager;
	@NonNull
	private final MMKV mmkv;
	@NonNull
	private final Gson gson;
	@NonNull
	private final MutableLiveData<PlayerLoopMode> loopModeState;

	@Inject
	public PlayerPreferences(@NonNull ExtensionManager extensionManager, @NonNull MMKV mmkv, @NonNull Gson gson) {
		this.extensionManager = extensionManager;
		this.mmkv = mmkv;
		this.gson = gson;
		this.loopModeState = new MutableLiveData<>(readLoopMode());
	}

	public float getSpeed() {
		boolean enabled = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_PLAYBACK_SPEED);
		if (!enabled) return DEFAULT_SPEED;
		return mmkv.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_SPEED);
	}

	public void setSpeed(float speed) {
		boolean enabled = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_PLAYBACK_SPEED);
		if (!enabled) return;
		mmkv.encode(KEY_PLAYBACK_SPEED, speed);
	}

	@NonNull
	public String getQuality() {
		boolean enabled = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_QUALITY);
		if (!enabled) return DEFAULT_QUALITY;
		return mmkv.decodeString(KEY_VIDEO_QUALITY, DEFAULT_QUALITY);
	}

	public void setQuality(@NonNull String quality) {
		boolean enabled = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_QUALITY);
		if (!enabled) return;
		mmkv.encode(KEY_VIDEO_QUALITY, quality);
	}

	@NonNull
	public PlayerLoopMode getLoopMode() {
		return readLoopMode();
	}

	public void setLoopMode(@NonNull PlayerLoopMode mode) {
		mmkv.encode(KEY_LOOP_MODE, mode.persistedValue());
		mmkv.encode(KEY_LOOP_ENABLED, mode == PlayerLoopMode.LOOP_ONE);
		loopModeState.postValue(mode);
	}

	@NonNull
	public LiveData<PlayerLoopMode> getLoopModeState() {
		return loopModeState;
	}

	@NonNull
	private PlayerLoopMode readLoopMode() {
		int persistedMode = mmkv.decodeInt(KEY_LOOP_MODE, Integer.MIN_VALUE);
		if (persistedMode != Integer.MIN_VALUE) {
			return PlayerLoopMode.fromPersistedValue(persistedMode);
		}
		return mmkv.decodeBool(KEY_LOOP_ENABLED, false) ? PlayerLoopMode.LOOP_ONE : PlayerLoopMode.PLAYLIST_NEXT;
	}

	public boolean isSubtitleEnabled() {
		return mmkv.decodeBool(KEY_SUBTITLE_ENABLED, false);
	}

	public void setSubtitleEnabled(boolean enabled) {
		mmkv.encode(KEY_SUBTITLE_ENABLED, enabled);
	}

	@Nullable
	public String getSubtitleLanguage() {
		return mmkv.decodeString(KEY_SUBTITLE_LANGUAGE, null);
	}

	public void setSubtitleLanguage(@Nullable String language) {
		mmkv.encode(KEY_SUBTITLE_LANGUAGE, language);
	}

	public int getResizeMode() {
		boolean enabled = extensionManager.isEnabled(Constant.REMEMBER_RESIZE_MODE);
		if (!enabled) return 0;
		return mmkv.decodeInt(KEY_RESIZE_MODE, 0);
	}

	public void setResizeMode(int mode) {
		boolean enabled = extensionManager.isEnabled(Constant.REMEMBER_RESIZE_MODE);
		if (!enabled) return;
		mmkv.encode(KEY_RESIZE_MODE, mode);
	}

	public long getResumePosition(@Nullable String videoId) {
		boolean enabled = extensionManager.isEnabled(Constant.REMEMBER_LAST_POSITION);
		if (!enabled || videoId == null) return 0;
		String key = PREFIX_PROGRESS + videoId;
		String json = mmkv.decodeString(key, null);
		if (json == null) return 0;
		Progress progress = gson.fromJson(json, Progress.class);
		if (System.currentTimeMillis() - progress.timestamp > EXPIRATION_DAYS_3) {
			mmkv.removeValueForKey(key);
			return 0;
		}
		return progress.position;
	}

	public void persistProgress(@Nullable String videoId, long position, long duration, TimeUnit unit) {
		boolean enabled = extensionManager.isEnabled(Constant.REMEMBER_LAST_POSITION);
		if (!enabled || videoId == null) return;
		String key = PREFIX_PROGRESS + videoId;
		String json = gson.toJson(new Progress(position, unit.toMillis(duration), System.currentTimeMillis()));
		mmkv.encode(key, json);
	}

	@NonNull
	public MiniPlayerLayoutState getMiniPlayerLayoutState() {
		return new MiniPlayerLayoutState(
						mmkv.decodeInt(KEY_MINI_PLAYER_WIDTH_DP, DEFAULT_MINI_PLAYER_WIDTH_DP),
						mmkv.decodeFloat(KEY_MINI_PLAYER_TRANSLATION_X_DP, DEFAULT_MINI_PLAYER_TRANSLATION_DP),
						mmkv.decodeFloat(KEY_MINI_PLAYER_TRANSLATION_Y_DP, DEFAULT_MINI_PLAYER_TRANSLATION_DP));
	}

	public void persistMiniPlayerLayoutState(int widthDp,
	                                         final float translationX,
	                                         final float translationY) {
		mmkv.encode(KEY_MINI_PLAYER_WIDTH_DP, widthDp);
		mmkv.encode(KEY_MINI_PLAYER_TRANSLATION_X_DP, translationX);
		mmkv.encode(KEY_MINI_PLAYER_TRANSLATION_Y_DP, translationY);
	}

	@NonNull
	public Set<String> getSponsorBlockCategories() {
		Set<String> categories = new HashSet<>();
		if (extensionManager.isEnabled(Constant.SKIP_SPONSORS)) categories.add("sponsor");
		if (extensionManager.isEnabled(Constant.SKIP_SELF_PROMO)) categories.add("selfpromo");
		if (extensionManager.isEnabled(Constant.SKIP_POI_HIGHLIGHT)) categories.add("poi_highlight");
		return categories;
	}

/**
 * Component that handles app logic.
 */
	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class Progress {
		private long position;
		private long duration;
		private long timestamp;
	}

/**
 * Value object for app logic.
 */
	public record MiniPlayerLayoutState(int widthDp, float translationX, float translationY) {
	}
}
