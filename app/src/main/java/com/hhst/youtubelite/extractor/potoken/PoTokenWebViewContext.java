package com.hhst.youtubelite.extractor.potoken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * WebView snapshot used to build PoToken context.
 */
public record PoTokenWebViewContext(@NonNull String url,
                                    long pageEpoch,
                                    @Nullable String visitorData,
                                    @Nullable String dataSyncId,
                                    @Nullable String clientVersion,
                                    @Nullable String serializedExperimentFlags,
                                    boolean loggedIn,
                                    boolean premium) {

	@Nullable
	public static PoTokenWebViewContext fromJson(@Nullable String fallbackUrl,
	                                             final long pageEpoch,
	                                             @Nullable String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			JsonElement element = JsonParser.parseString(json);
			if (element.isJsonNull()) {
				return null;
			}
			if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
				String nestedJson = normalize(element.getAsString());
				if (nestedJson == null) {
					return null;
				}
				element = JsonParser.parseString(nestedJson);
			}
			if (!element.isJsonObject()) {
				return null;
			}
			JsonObject object = element.getAsJsonObject();
			if (getString(object, "error") != null) {
				return null;
			}
			String url = firstNonBlank(
							getString(object, "url"),
							normalize(fallbackUrl));
			if (url == null) {
				return null;
			}
			return new PoTokenWebViewContext(
							url,
							pageEpoch,
							getString(object, "visitorData"),
							getString(object, "dataSyncId"),
							getString(object, "clientVersion"),
							getString(object, "serializedExperimentFlags"),
							getBoolean(object, "loggedIn"),
							getBoolean(object, "premium"));
		} catch (Exception ignored) {
			return null;
		}
	}

	@Nullable
	private static String firstNonBlank(@Nullable String first,
	                                    @Nullable String second) {
		return first != null ? first : second;
	}

	private static boolean getBoolean(@NonNull JsonObject object,
	                                  @NonNull String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return false;
		}
		try {
			return element.getAsBoolean();
		} catch (Exception ignored) {
			return false;
		}
	}

	@Nullable
	private static String getString(@NonNull JsonObject object,
	                                @NonNull String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return null;
		}
		try {
			return normalize(element.getAsString());
		} catch (Exception ignored) {
			return null;
		}
	}

	@Nullable
	private static String normalize(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
