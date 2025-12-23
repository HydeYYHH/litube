package com.hhst.youtubelite.utils;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class UrlUtils {

	public static final String PAGE_UNKNOWN = "unknown";
	public static final String PAGE_HOME = "home";
	public static final String PAGE_SHORTS = "shorts";
	public static final String PAGE_WATCH = "watch";
	public static final String PAGE_CHANNEL = "channel";
	public static final String PAGE_GAMING = "gaming";
	public static final String PAGE_SUBSCRIPTIONS = "subscriptions";
	public static final String PAGE_LIBRARY = "library";
	public static final String PAGE_HISTORY = "history";
	public static final String PAGE_CHANNELS = "channels";
	public static final String PAGE_PLAYLISTS = "playlists";
	public static final String PAGE_SELECT_SITE = "select_site";
	public static final String PAGE_USER_MENTION = "@";

	private UrlUtils() {
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
		if (!lowerHost.equals("m.youtube.com") && !lowerHost.equals("www.youtube.com") && !lowerHost.equals("youtube.com")) return PAGE_UNKNOWN;

		final String path = uri.getPath();
		if (path == null || path.equals("/") || path.isEmpty()) return PAGE_HOME;
		
		final String lowerPath = path.toLowerCase();
		return switch (lowerPath) {
			case "/shorts" -> PAGE_SHORTS;
			case "/watch" -> PAGE_WATCH;
			case "/channel" -> PAGE_CHANNEL;
			case "/gaming" -> PAGE_GAMING;
			case "/feed/subscriptions" -> PAGE_SUBSCRIPTIONS;
			case "/feed/library" -> PAGE_LIBRARY;
			case "/feed/history" -> PAGE_HISTORY;
			case "/feed/channels" -> PAGE_CHANNELS;
			case "/feed/playlists" -> PAGE_PLAYLISTS;
			case "/select_site" -> PAGE_SELECT_SITE;
			default -> {
				if (lowerPath.startsWith("/@")) yield PAGE_USER_MENTION;
				// Return path without leading slash, or home if empty
				final String result = lowerPath.startsWith("/") ? lowerPath.substring(1) : lowerPath;
				yield result.isEmpty() ? PAGE_HOME : result;
			}
		};
	}
}
