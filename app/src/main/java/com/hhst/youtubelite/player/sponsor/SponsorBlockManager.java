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
	public SponsorBlockManager(@NonNull final OkHttpClient client, @NonNull final Gson gson, @NonNull final PlayerPreferences preferences) {
		this.client = client;
		this.gson = gson;
		this.preferences = preferences;
	}


	public void load(@NonNull final String videoId) {
		segments = Collections.emptyList();
		try {
			final Set<String> cats = preferences.getSponsorBlockCategories();
			if (cats.isEmpty()) return;
			final String hash = sha256(videoId).substring(0, 4);
			final String categoriesJson = gson.toJson(cats);
			HttpUrl url = HttpUrl.parse(API_URL + hash);
			if (url == null) return;
			url = url.newBuilder().addQueryParameter("service", "YouTube").addQueryParameter("categories", categoriesJson).build();
			final Request request = new Request.Builder().url(url).get().build();
			try (final Response response = client.newCall(request).execute()) {
				if (!response.isSuccessful()) {
					segments = Collections.emptyList();
					return;
				}
				try (final InputStreamReader reader = new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)) {
					parseSegments(reader, videoId, cats);
				}
			}
		} catch (final Exception e) {
			Log.e("SponsorBlockManager", "Error loading segments", e);
			segments = Collections.emptyList();
		}
	}

	private void parseSegments(@NonNull final InputStreamReader reader, @NonNull final String videoId, @NonNull final Set<String> targetCats) {
		final JsonElement rootElement = JsonParser.parseReader(reader);
		if (!rootElement.isJsonArray()) return;

		final List<long[]> newSegments = new ArrayList<>();
		final JsonArray root = rootElement.getAsJsonArray();

		for (final JsonElement el : root) {
			final JsonObject obj = el.getAsJsonObject();
			if (!obj.has("videoID") || !obj.get("videoID").getAsString().equals(videoId)) continue;
			if (!obj.has("segments")) continue;

			for (final JsonElement segEl : obj.getAsJsonArray("segments")) {
				final JsonObject seg = segEl.getAsJsonObject();
				if (seg.has("category") && targetCats.contains(seg.get("category").getAsString()) && seg.has("segment")) {
					final JsonArray pair = seg.getAsJsonArray("segment");
					if (pair.size() >= 2) {
						newSegments.add(new long[]{(long) (pair.get(0).getAsDouble() * 1000), (long) (pair.get(1).getAsDouble() * 1000)});
					}
				}
			}
		}
		segments = newSegments;
	}

	@NonNull
	private String sha256(@NonNull final String s) throws Exception {
		final byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
		final StringBuilder hexString = new StringBuilder(hash.length * 2);
		for (final byte b : hash) {
			final String hex = Integer.toHexString(0xFF & b);
			if (hex.length() == 1) hexString.append('0');
			hexString.append(hex);
		}
		return hexString.toString();
	}
}