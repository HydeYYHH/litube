package com.hhst.youtubelite.extension;

import com.tencent.mmkv.MMKV;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class ExtensionManager {

	private final MMKV mmkv;

	@Inject
	public ExtensionManager(MMKV mmkv) {
		this.mmkv = mmkv;
		initializeDefaultPreferences();
	}

	private void initializeDefaultPreferences() {
		// Set default preferences in MMKV if they don't exist
		for (Map.Entry<String, Boolean> entry : Constant.DEFAULT_PREFERENCES.entrySet()) {
			String mmkvKey = "preferences:" + entry.getKey();
			if (!mmkv.contains(mmkvKey)) {
				mmkv.encode(mmkvKey, entry.getValue());
			}
		}
	}

	public void setEnabled(String key, boolean enable) {
		mmkv.encode("preferences:" + key, enable);
	}

	public boolean isEnabled(String key) {
		return mmkv.decodeBool("preferences:" + key, Constant.DEFAULT_PREFERENCES.getOrDefault(key, false));
	}

	public Map<String, Boolean> getAllPreferences() {
		Map<String, Boolean> allPreferences = new HashMap<>();
		for (String key : Constant.DEFAULT_PREFERENCES.keySet()) {
			allPreferences.put(key, isEnabled(key));
		}
		return allPreferences;
	}

}
