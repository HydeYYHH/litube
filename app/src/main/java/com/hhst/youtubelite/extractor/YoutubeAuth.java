package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.Constant;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds YouTube auth headers from the current session snapshot.
 */
public final class YoutubeAuth {
	private YoutubeAuth() {
	}

	@NonNull
	static Result headers(@NonNull String url,
	                      @Nullable AuthContext auth,
	                      long nowMs) {
		if (auth == null || !auth.loggedIn()) {
			return Result.none();
		}
		String origin = origin(url);
		if (origin == null || !isWebApi(url)) {
			return Result.none();
		}
		String cookies = auth.cookies();
		if (cookies == null || cookies.isBlank()) {
			return Result.skip("missing cookies");
		}
		Sync sync = sync(auth.dataSyncId());
		String pageId = sync.pageId();
		String authorization = authorization(cookies, origin, sync.userId(), nowMs / 1000L);
		if (authorization == null) {
			return Result.skip("missing sid cookie");
		}
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Authorization", authorization);
		headers.put("Origin", origin);
		headers.put("X-Origin", origin);
		if (auth.visitorData() != null) {
			headers.put("X-Goog-Visitor-Id", auth.visitorData());
		}
		if (pageId != null || auth.sessionIndex() != null) {
			headers.put("X-Goog-AuthUser", auth.sessionIndex() != null ? auth.sessionIndex() : "0");
		}
		if (pageId != null) {
			headers.put("X-Goog-PageId", pageId);
		}
		headers.put("X-Youtube-Bootstrap-Logged-In", "true");
		return new Result(Map.copyOf(headers), null);
	}

	static boolean isWebApi(@NonNull String url) {
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			if (host == null) {
				return false;
			}
			String lowerHost = host.toLowerCase(Locale.US);
			if (!lowerHost.equals(Constant.YOUTUBE_DOMAIN)
							&& !lowerHost.endsWith("." + Constant.YOUTUBE_DOMAIN)) {
				return false;
			}
			String path = uri.getPath();
			return path != null && path.startsWith("/youtubei/");
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	@NonNull
	private static Sync sync(@Nullable String dataSyncId) {
		if (dataSyncId == null || dataSyncId.isBlank()) {
			return new Sync(null, null);
		}
		String first;
		String second;
		int split = dataSyncId.indexOf("||");
		if (split >= 0) {
			first = dataSyncId.substring(0, split).trim();
			second = dataSyncId.substring(split + 2).trim();
		} else {
			first = dataSyncId.trim();
			second = "";
		}
		if (!second.isEmpty()) {
			return new Sync(first.isEmpty() ? null : first, second);
		}
		return new Sync(null, first.isEmpty() ? null : first);
	}

	@Nullable
	private static String origin(@NonNull String url) {
		try {
			URI uri = URI.create(url);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (scheme == null || host == null) {
				return null;
			}
			return scheme + "://" + host;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	@Nullable
	private static String authorization(@NonNull String cookies,
	                                    @NonNull String origin,
	                                    @Nullable String userId,
	                                    long nowSeconds) {
		List<String> values = new ArrayList<>(3);
		String sapisid = cookie(cookies, "SAPISID");
		if (sapisid == null) {
			sapisid = cookie(cookies, "__Secure-3PAPISID");
		}
		add(values, "SAPISIDHASH", sapisid, origin, userId, nowSeconds);
		add(values, "SAPISID1PHASH", cookie(cookies, "__Secure-1PAPISID"), origin, userId,
						nowSeconds);
		add(values, "SAPISID3PHASH", cookie(cookies, "__Secure-3PAPISID"), origin, userId,
						nowSeconds);
		if (values.isEmpty()) {
			return null;
		}
		return String.join(" ", values);
	}

	private static void add(@NonNull List<String> values,
	                        @NonNull String name,
	                        @Nullable String sid,
	                        @NonNull String origin,
	                        @Nullable String userId,
	                        long nowSeconds) {
		if (sid == null) {
			return;
		}
		values.add(name + " " + hash(nowSeconds, sid, origin, userId));
	}

	@NonNull
	private static String hash(long nowSeconds,
	                           @NonNull String sid,
	                           @NonNull String origin,
	                           @Nullable String userId) {
		String input = userId == null
						? nowSeconds + " " + sid + " " + origin
						: userId + " " + nowSeconds + " " + sid + " " + origin;
		String suffix = userId == null ? "" : "_u";
		return nowSeconds + "_" + sha1(input) + suffix;
	}

	@NonNull
	private static String sha1(@NonNull String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1")
							.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				builder.append(Character.forDigit((b >>> 4) & 0xf, 16));
				builder.append(Character.forDigit(b & 0xf, 16));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 unavailable", e);
		}
	}

	@Nullable
	private static String cookie(@NonNull String cookies,
	                             @NonNull String name) {
		String prefix = name + "=";
		for (String part : cookies.split(";")) {
			String trimmed = part.trim();
			if (!trimmed.startsWith(prefix)) {
				continue;
			}
			String value = trimmed.substring(prefix.length());
			return value.isBlank() ? null : value;
		}
		return null;
	}

	record Result(@NonNull Map<String, String> headers,
	              @Nullable String note) {
		private static Result none() {
			return new Result(Map.of(), null);
		}

		private static Result skip(@NonNull String note) {
			return new Result(Map.of(), note);
		}
	}

	private record Sync(@Nullable String pageId,
	                    @Nullable String userId) {
	}
}
