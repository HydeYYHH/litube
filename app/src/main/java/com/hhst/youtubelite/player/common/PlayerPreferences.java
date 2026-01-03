package com.hhst.youtubelite.player.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

@Getter
@Singleton
public final class PlayerPreferences {
	private static final String KEY_PLAYBACK_SPEED = "playback_speed";
	private static final String KEY_VIDEO_QUALITY = "video_quality";
	private static final String KEY_LOOP_ENABLED = "loop_enabled";
	private static final String KEY_SUBTITLE_ENABLED = "subtitle_enabled";
	private static final String KEY_SUBTITLE_LANGUAGE = "subtitle_language";
	private static final String KEY_RESIZE_MODE = "resize_mode";
	private static final String PREFIX_PROGRESS = "progress:";

	private static final float DEFAULT_SPEED = 1.0f;
	private static final String DEFAULT_QUALITY = "480p";
	private static final long EXPIRATION_DAYS_3 = 3L * 24 * 60 * 60 * 1000;

	@NonNull
	private final ExtensionManager extensionManager;
	@NonNull
	private final MMKV mmkv;
	@NonNull
	private final Gson gson;

	@Inject
	public PlayerPreferences(@NonNull final ExtensionManager extensionManager, @NonNull final MMKV mmkv, @NonNull final Gson gson) {
		this.extensionManager = extensionManager;
		this.mmkv = mmkv;
		this.gson = gson;
	}

	public float getSpeed() {
		final boolean enable = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_PLAYBACK_SPEED);
		if (!enable) return DEFAULT_SPEED;
		return mmkv.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_SPEED);
	}

	public void setSpeed(final float speed) {
		final boolean enable = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_PLAYBACK_SPEED);
		if (!enable) return;
		mmkv.encode(KEY_PLAYBACK_SPEED, speed);
	}

	@NonNull
	public String getQuality() {
		final boolean enable = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_QUALITY);
		if (!enable) return DEFAULT_QUALITY;
		return mmkv.decodeString(KEY_VIDEO_QUALITY, DEFAULT_QUALITY);
	}

	public void setQuality(@NonNull final String quality) {
		final boolean enable = extensionManager.isEnabled(com.hhst.youtubelite.extension.Constant.REMEMBER_QUALITY);
		if (!enable) return;
		mmkv.encode(KEY_VIDEO_QUALITY, quality);
	}

	public boolean isLoopEnabled() {
		return mmkv.decodeBool(KEY_LOOP_ENABLED, false);
	}

	public void setLoopEnabled(final boolean enabled) {
		mmkv.encode(KEY_LOOP_ENABLED, enabled);
	}

	public boolean isSubtitleEnabled() {
		return mmkv.decodeBool(KEY_SUBTITLE_ENABLED, false);
	}

	public void setSubtitleEnabled(final boolean enabled) {
		mmkv.encode(KEY_SUBTITLE_ENABLED, enabled);
	}

	@Nullable
	public String getSubtitleLanguage() {
		return mmkv.decodeString(KEY_SUBTITLE_LANGUAGE, null);
	}

	public void setSubtitleLanguage(@Nullable final String language) {
		mmkv.encode(KEY_SUBTITLE_LANGUAGE, language);
	}

	public int getResizeMode() {
		final boolean enable = extensionManager.isEnabled(Constant.REMEMBER_RESIZE_MODE);
		if (!enable) return 0;
		return mmkv.decodeInt(KEY_RESIZE_MODE, 0);
	}

	public void setResizeMode(final int mode) {
		final boolean enable = extensionManager.isEnabled(Constant.REMEMBER_RESIZE_MODE);
		if (!enable) return;
		mmkv.encode(KEY_RESIZE_MODE, mode);
	}

	public long getResumePosition(@Nullable final String videoId) {
		final boolean enable = extensionManager.isEnabled(Constant.REMEMBER_LAST_POSITION);
		if (!enable || videoId == null) return 0;
		final String key = PREFIX_PROGRESS + videoId;
		final String json = mmkv.decodeString(key, null);
		if (json == null) return 0;
		final Progress progress = gson.fromJson(json, Progress.class);
		if (System.currentTimeMillis() - progress.timestamp > EXPIRATION_DAYS_3) {
			mmkv.removeValueForKey(key);
			return 0;
		}
		return progress.position;
	}

	public void persistProgress(@Nullable final String videoId, final long position, final long duration, TimeUnit unit) {
		final boolean enable = extensionManager.isEnabled(Constant.REMEMBER_LAST_POSITION);
		if (!enable || videoId == null) return;
		final String key = PREFIX_PROGRESS + videoId;
		final String json = gson.toJson(new Progress(position, unit.toMillis(duration), System.currentTimeMillis()));
		mmkv.encode(key, json);
	}

	@NonNull
	public Set<String> getSponsorBlockCategories() {
		final Set<String> cats = new HashSet<>();
		if (extensionManager.isEnabled(Constant.SKIP_SPONSORS)) cats.add("sponsor");
		if (extensionManager.isEnabled(Constant.SKIP_SELF_PROMO)) cats.add("selfpromo");
		if (extensionManager.isEnabled(Constant.SKIP_POI_HIGHLIGHT)) cats.add("poi_highlight");
		return cats;
	}

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	static class Progress {
		private long position;
		private long duration;
		private long timestamp;
	}
}
