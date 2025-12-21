package com.hhst.youtubelite.extension;

import com.tencent.mmkv.MMKV;

import java.util.HashMap;
import java.util.Map;

public class ExtensionManager {

	private final MMKV mmkv;

	public ExtensionManager() {
		this.mmkv = MMKV.defaultMMKV();
		initializeDefaultPreferences();
	}

	private void initializeDefaultPreferences() {
		// Set default preferences in MMKV if they don't exist
		for (Map.Entry<String, Boolean> entry : Constant.defaultPreferences.entrySet()) {
			String mmkvKey = "preferences:" + entry.getKey();
			if (!mmkv.contains(mmkvKey)) mmkv.encode(mmkvKey, entry.getValue());
		}
	}

	public void setEnabled(String key, Boolean enable) {
		String mmkvKey = "preferences:" + key;
		mmkv.encode(mmkvKey, enable);
	}

	public Boolean isEnabled(String key) {
		String mmkvKey = "preferences:" + key;
		// If the key doesn't exist, return the default value
		if (!mmkv.contains(mmkvKey)) return Constant.defaultPreferences.getOrDefault(key, false);
		return mmkv.decodeBool(mmkvKey, Boolean.TRUE.equals(Constant.defaultPreferences.getOrDefault(key, false)));
	}

	public Map<String, Boolean> getAllPreferences() {

		// Add all default preferences as a base
		Map<String, Boolean> allPreferences = new HashMap<>(Constant.defaultPreferences);

		// Override with existing values from MMKV
		for (Map.Entry<String, Boolean> entry : Constant.defaultPreferences.entrySet()) {
			String mmkvKey = "preferences:" + entry.getKey();
			if (mmkv.contains(mmkvKey)) allPreferences.put(entry.getKey(), mmkv.decodeBool(mmkvKey));
		}

		return allPreferences;
	}

}
