package com.hhst.youtubelite.extractor.potoken;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * JSON helpers for PoToken context payloads.
 */
public final class PoTokenJsonUtils {

	private PoTokenJsonUtils() {
	}

	@Nullable
	public static String normalizeEvaluateJavascriptResult(@Nullable String rawValue) {
		if (rawValue == null || "null".equals(rawValue)) {
			return null;
		}
		try {
			JsonElement element = JsonParser.parseString(rawValue);
			if (element.isJsonNull()) {
				return null;
			}
			if (element.isJsonPrimitive()) {
				return element.getAsJsonPrimitive().isString()
								? element.getAsString()
								: element.getAsJsonPrimitive().toString();
			}
			return element.toString();
		} catch (Exception ignored) {
			return rawValue;
		}
	}
}
