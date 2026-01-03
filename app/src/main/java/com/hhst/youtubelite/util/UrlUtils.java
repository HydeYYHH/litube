package com.hhst.youtubelite.util;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.Constant;

import java.util.List;

public final class UrlUtils {

	public static final String PAGE_UNKNOWN = "unknown";
	public static final String PAGE_CHANNEL = "channel";
	public static final String PAGE_GAMING = "gaming";
	public static final String PAGE_HISTORY = "history";
	public static final String PAGE_CHANNELS = "channels";
	public static final String PAGE_PLAYLISTS = "playlists";
	public static final String PAGE_SELECT_SITE = "select_site";
	public static final String PAGE_USER_MENTION = "@";
	public static final String PAGE_SEARCHING = "searching";

	private static final List<String> ALLOWED_DOMAINS = List.of(
					Constant.YOUTUBE_DOMAIN,
					"youtube.googleapis.com",
					"googlevideo.com",
					"ytimg.com",
					"accounts.google",
					"googleusercontent.com",
					"apis.google.com"
	);

	/**
	 * Determines if the given URI belongs to an allowed domain.
	 *
	 * @param uri The URI to check.
	 * @return True if the domain is allowed, false otherwise.
	 */
	public static boolean isAllowedDomain(@Nullable final Uri uri) {
		if (uri == null) return false;
		final String host = uri.getHost();
		if (host == null) return false;
		for (final String domain : ALLOWED_DOMAINS) {
			if (host.endsWith(domain) || host.startsWith(domain)) return true;
		}
		return false;
	}

	/**
	 * Determines the page class/type from a given URL.
	 *
	 * @param url The URL to parse.
	 * @return The page class constant, or {@link #PAGE_UNKNOWN} if invalid or unknown.
	 */
	@NonNull
	public static String getPageClass(@Nullable final String url) {
		if (url == null || url.isEmpty()) return PAGE_UNKNOWN;

		final Uri uri = Uri.parse(url);
		final String host = uri.getHost();
		if (host == null) return PAGE_UNKNOWN;

		final String lowerHost = host.toLowerCase();
		if (!lowerHost.equals(Constant.YOUTUBE_MOBILE_HOST) && !lowerHost.equals(Constant.YOUTUBE_DOMAIN))
			return PAGE_UNKNOWN;

		final String path = uri.getPath();
		if (path == null || path.equals("/") || path.isEmpty()) return Constant.PAGE_HOME;

		final String lowerPath = path.toLowerCase();
		return switch (lowerPath) {
			case "/shorts" -> Constant.PAGE_SHORTS;
			case "/watch" -> Constant.PAGE_WATCH;
			case "/channel" -> PAGE_CHANNEL;
			case "/gaming" -> PAGE_GAMING;
			case "/feed/subscriptions" -> Constant.PAGE_SUBSCRIPTIONS;
			case "/feed/library" -> Constant.PAGE_LIBRARY;
			case "/feed/history" -> PAGE_HISTORY;
			case "/feed/channels" -> PAGE_CHANNELS;
			case "/feed/playlists" -> PAGE_PLAYLISTS;
			case "/select_site" -> PAGE_SELECT_SITE;
			default -> {
				if (lowerPath.startsWith("/@")) yield PAGE_USER_MENTION;
				// Return path without leading slash, or home if empty
				final String result = lowerPath.startsWith("/") ? lowerPath.substring(1) : lowerPath;
				yield result.isEmpty() ? Constant.PAGE_HOME : result;
			}
		};
	}
}
