package com.hhst.youtubelite.util;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.Constant;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * URL helpers for page classification and host checks.
 */
public final class UrlUtils {

	private static final Locale NORMAL_LOCALE = Locale.ROOT;

	private static final Set<String> ALLOWED_DOMAINS = Set.of(
					Constant.YOUTUBE_DOMAIN,
					"youtu.be",
					"youtube.googleapis.com",
					"googlevideo.com",
					"ytimg.com",
					"accounts.google",
					"accounts.google.com",
					"googleusercontent.com",
					"apis.google.com",
					"gstatic.com"
	);

	public static boolean isAllowedDomain(@Nullable Uri uri) {
		if (uri == null) return false;
		return isAllowedHost(uri.getHost());
	}

	public static boolean isAllowedUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			return isAllowedHost(URI.create(url).getHost());
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	public static boolean isGoogleAccountsUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			String host = URI.create(url).getHost();
			return host != null && isGoogleAccountsHost(host.toLowerCase(NORMAL_LOCALE));
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	public static boolean isPlaylistFirstItemUrl(@Nullable String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			String listId = getQueryParameter(url, "list");
			if (listId == null || listId.isBlank()) return false;
			String index = getQueryParameter(url, "index");
			return index == null || index.isBlank() || "1".equals(index);
		} catch (Exception ignored) {
			return false;
		}
	}

	@Nullable
	private static String getQueryParameter(@NonNull String url, @NonNull String key) {
		String query;
		try {
			query = URI.create(url).getRawQuery();
		} catch (IllegalArgumentException ignored) {
			return null;
		}
		if (query == null || query.isBlank()) return null;
		for (String pair : query.split("&")) {
			int sep = pair.indexOf('=');
			String name = sep >= 0 ? pair.substring(0, sep) : pair;
			if (!key.equals(name)) continue;
			return sep >= 0 ? pair.substring(sep + 1) : "";
		}
		return null;
	}

	private static boolean isAllowedHost(@Nullable String host) {
		if (host == null) return false;
		String lowerHost = host.toLowerCase(NORMAL_LOCALE);
		if (isGoogleAccountsHost(lowerHost)) return true;
		return ALLOWED_DOMAINS.stream().anyMatch(domain ->
						lowerHost.equals(domain) || lowerHost.endsWith("." + domain));
	}

	private static boolean isGoogleAccountsHost(@NonNull String lowerHost) {
		return lowerHost.equals("accounts.google")
						|| lowerHost.equals("accounts.google.com")
						|| lowerHost.startsWith("accounts.google.")
						|| lowerHost.equals("accounts.youtube.com");
	}

	@NonNull
	public static String getPageClass(@Nullable String url) {
		if (url == null || url.isEmpty()) return "unknown";

		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			if (host == null) return "unknown";
			String path = uri.getPath();
			List<String> segments = path == null || path.isEmpty()
							? List.of()
							: java.util.Arrays.stream(path.split("/"))
							.filter(segment -> !segment.isEmpty())
							.toList();
			return getPageClassFromHost(host, segments);
		} catch (IllegalArgumentException ignored) {
			return "unknown";
		}
	}

	@NonNull
	static String getPageClassFromHost(@NonNull String host, @NonNull List<String> segments) {
		String lowerHost = host.toLowerCase(NORMAL_LOCALE);
		if (lowerHost.equals("youtu.be")) {
			return segments.isEmpty() ? "unknown" : Constant.PAGE_WATCH;
		}
		if (!lowerHost.equals(Constant.YOUTUBE_MOBILE_HOST) && !lowerHost.equals(Constant.YOUTUBE_DOMAIN))
			return "unknown";

		if (segments.isEmpty()) return Constant.PAGE_HOME;

		String s0 = segments.get(0).toLowerCase(NORMAL_LOCALE);
		if (s0.startsWith("@")) return "@";

		return switch (s0) {
			case "shorts" -> Constant.PAGE_SHORTS;
			case "watch" -> Constant.PAGE_WATCH;
			case "channel" -> "channel";
			case "gaming" -> "gaming";
			case "select_site" -> "select_site";
			case "results" -> "searching";
			case "feed" -> (segments.size() > 1) ? switch (segments.get(1).toLowerCase(NORMAL_LOCALE)) {
				case "subscriptions" -> Constant.PAGE_SUBSCRIPTIONS;
				case "library" -> Constant.PAGE_LIBRARY;
				case "history" -> "history";
				case "channels" -> "channels";
				case "playlists" -> "playlists";
				default -> String.join("/", segments);
			} : String.join("/", segments);
			default -> String.join("/", segments);
		};
	}
}
