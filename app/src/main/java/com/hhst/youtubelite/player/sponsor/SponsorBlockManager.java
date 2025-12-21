package com.hhst.youtubelite.player.sponsor;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hhst.youtubelite.player.PlayerContext;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.Getter;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SponsorBlockManager {
	private static final String API_URL = "https://sponsor.ajay.app/api/skipSegments/";
	private final OkHttpClient client = new OkHttpClient.Builder().build();
	@Getter
	private List<long[]> segments = Collections.emptyList();

	@OptIn(markerClass = UnstableApi.class)
	public void load(String videoId) {
		try {
			Set<String> cats = PlayerContext.getInstance().getPreferences().getSponsorBlockCategories();
			if (cats.isEmpty()) return;
			String hash = sha256(videoId).substring(0, 4);
			String categoriesJson = new Gson().toJson(cats);
			HttpUrl url = Objects.requireNonNull(HttpUrl.parse(API_URL + hash)).newBuilder().addQueryParameter("service", "YouTube").addQueryParameter("categories", categoriesJson).build();
			Request request = new Request.Builder().url(url).get().build();
			try (Response response = client.newCall(request).execute()) {
				if (!response.isSuccessful()) {
					segments = Collections.emptyList();
					return;
				}
				try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(response.body()).byteStream(), StandardCharsets.UTF_8)) {
					parseSegments(reader, videoId, cats);
				}
			}
		} catch (Exception e) {
			Log.e("SponsorBlockManager", "Error loading segments", e);
			segments = Collections.emptyList();
		}
	}

	private void parseSegments(InputStreamReader reader, String videoId, Set<String> targetCats) {
		try {
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
		} catch (Exception e) {
			segments = Collections.emptyList();
			Log.e("SponsorBlockManager", "Error parsing segments", e);
		}
	}

	public long shouldSkip(long posMs) {
		List<long[]> segmentsList = segments;
		if (segmentsList == null || segmentsList.isEmpty()) return -1;
		for (long[] seg : segmentsList) {
			if (posMs >= seg[0] && posMs < seg[1]) return seg[1];
		}
		return -1;
	}

	private String sha256(String s) throws Exception {
		byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder(hash.length * 2);
		for (byte b : hash) {
			String h = Integer.toHexString(0xFF & b);
			if (h.length() == 1) hex.append('0');
			hex.append(h);
		}
		return hex.toString();
	}
}