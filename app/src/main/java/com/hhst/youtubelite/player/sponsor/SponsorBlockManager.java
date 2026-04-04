package com.hhst.youtubelite.player.sponsor;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hhst.youtubelite.player.common.PlayerPreferences;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.Getter;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Manager that loads SponsorBlock segments and skip rules.
 */
@Singleton
public final class SponsorBlockManager {
	private static final String API_URL = "https://sponsor.ajay.app/api/skipSegments/";
	@NonNull
	private final OkHttpClient client;
	@NonNull
	private final Gson gson;
	@NonNull
	private final PlayerPreferences preferences;
	@Getter
	@NonNull
	private List<long[]> segments = Collections.emptyList();

	@Inject
	public SponsorBlockManager(@NonNull OkHttpClient client, @NonNull Gson gson, @NonNull PlayerPreferences preferences) {
		this.client = client;
		this.gson = gson;
		this.preferences = preferences;
	}


	public void load(@NonNull String videoId) {
		segments = Collections.emptyList();
		try {
			Set<String> cats = preferences.getSponsorBlockCategories();
			if (cats.isEmpty()) return;
			String hash = sha256(videoId).substring(0, 4);
			String categoriesJson = gson.toJson(cats);
			HttpUrl url = HttpUrl.parse(API_URL + hash);
			if (url == null) return;
			url = url.newBuilder().addQueryParameter("service", "YouTube").addQueryParameter("categories", categoriesJson).build();
			Request request = new Request.Builder().url(url).get().build();
			try (Response response = client.newCall(request).execute()) {
				if (!response.isSuccessful()) {
					segments = Collections.emptyList();
					return;
				}
				try (InputStreamReader reader = new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)) {
					parseSegments(reader, videoId, cats);
				}
			}
		} catch (Exception e) {
			Log.e("SponsorBlockManager", "Error loading segments", e);
			segments = Collections.emptyList();
		}
	}

	private void parseSegments(@NonNull InputStreamReader reader, @NonNull String videoId, @NonNull Set<String> targetCats) {
		JsonElement rootElement = JsonParser.parseReader(reader);
		if (!rootElement.isJsonArray()) return;

		List<long[]> newSegments = new ArrayList<>();
		JsonArray root = rootElement.getAsJsonArray();

		for (JsonElement el : root) {
			JsonObject obj = el.getAsJsonObject();
			if (!obj.has("videoID") || !obj.get("videoID").getAsString().equals(videoId)) continue;
			if (!obj.has("segments")) continue;

			for (JsonElement segEl : obj.getAsJsonArray("segments")) {
				JsonObject seg = segEl.getAsJsonObject();
				if (seg.has("category") && targetCats.contains(seg.get("category").getAsString()) && seg.has("segment")) {
					JsonArray pair = seg.getAsJsonArray("segment");
					if (pair.size() >= 2) {
						newSegments.add(new long[]{(long) (pair.get(0).getAsDouble() * 1000), (long) (pair.get(1).getAsDouble() * 1000)});
					}
				}
			}
		}
		segments = newSegments;
	}

	@NonNull
	private String sha256(@NonNull String s) throws Exception {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
		StringBuilder hexString = new StringBuilder(hash.length * 2);
		for (byte b : hash) {
			String hex = Integer.toHexString(0xFF & b);
			if (hex.length() == 1) hexString.append('0');
			hexString.append(hex);
		}
		return hexString.toString();
	}
}