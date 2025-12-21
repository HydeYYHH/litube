package com.hhst.youtubelite.player;

import com.google.gson.Gson;
import com.hhst.youtubelite.extension.Constant;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.tencent.mmkv.MMKV;

import java.util.HashSet;
import java.util.Set;

public class PlayerPreferences {
	final ExtensionManager extensionManager;
	private final MMKV mmkv = MMKV.defaultMMKV();

	public PlayerPreferences(ExtensionManager extensionManager) {
		this.extensionManager = extensionManager;
	}

	public float getSpeed() {
		return mmkv.getFloat("playback_speed", 1f);
	}

	public void setSpeed(float s) {
		mmkv.encode("playback_speed", s);
	}

	public String getQuality() {
		return mmkv.decodeString("video_quality", "480p");
	}

	public void setQuality(String q) {
		mmkv.encode("video_quality", q);
	}

	public boolean isLoopEnabled() {
		return mmkv.decodeBool("loop_enabled", false);
	}

	public void setLoopEnabled(boolean enabled) {
		mmkv.encode("loop_enabled", enabled);
	}

	public String getSubtitleLanguage() {
		return mmkv.decodeString("subtitle_language", null);
	}

	public void setSubtitleLanguage(String language) {
		mmkv.encode("subtitle_language", language);
	}

	public int getResizeMode() {
		boolean enable = extensionManager.isEnabled(Constant.rememberResizeMode);
		if (!enable) return 0; // AspectRatioFrameLayout.RESIZE_MODE_FIT
		return mmkv.decodeInt("resize_mode", 0);
	}

	public void setResizeMode(int mode) {
		boolean enable = extensionManager.isEnabled(Constant.rememberResizeMode);
		if (!enable) return;
		mmkv.encode("resize_mode", mode);
	}

	public String getPreferredAudioTrack() {
		boolean enable = extensionManager.isEnabled(Constant.rememberAudioTrack);
		if (!enable) return null;
		return mmkv.decodeString("preferred_audio_track", null);
	}

	public void setPreferredAudioTrack(String trackInfo) {
		boolean enable = extensionManager.isEnabled(Constant.rememberAudioTrack);
		if (!enable) return;
		mmkv.encode("preferred_audio_track", trackInfo);
	}

	public long getResumePosition(String videoId, long durationMs) {
		boolean enable = extensionManager.isEnabled(Constant.rememberLastPosition);
		if (!enable) return 0;
		String key = "progress:" + videoId;
		String json = mmkv.decodeString(key, null);
		if (json == null) return 0;
		Progress data = new Gson().fromJson(json, Progress.class);
		if (data == null) return 0;
		if (System.currentTimeMillis() > data.expiration) return 0;
		if (data.time <= 0 || (durationMs > 0 && (durationMs - data.time) <= 5000)) return 0;
		return data.time;
	}

	public void persistProgress(String videoId, long posMs) {
		if (videoId == null) return;
		boolean enable = extensionManager.isEnabled(Constant.rememberLastPosition);
		if (!enable) return;
		String key = "progress:" + videoId;
		long expiration = System.currentTimeMillis() + 3L * 24 * 60 * 60 * 1000;
		Progress data = new Progress();
		data.time = posMs;
		data.expiration = expiration;
		mmkv.encode(key, new Gson().toJson(data));
	}

	public Set<String> getSponsorBlockCategories() {
		Set<String> cats = new HashSet<>();
		if (extensionManager.isEnabled(Constant.skipSponsor)) cats.add("sponsor");
		if (extensionManager.isEnabled(Constant.skipSelfPromo)) cats.add("selfpromo");
		if (extensionManager.isEnabled(Constant.skipHighlight)) cats.add("poi_highlight");
		return cats;
	}

	static class Progress {
		public long time;
		public long expiration;
	}
}
