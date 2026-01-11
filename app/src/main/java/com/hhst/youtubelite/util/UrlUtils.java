package com.hhst.youtubelite.util;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.Constant;

import java.util.List;
import java.util.Set;

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

	private static final Set<String> ALLOWED_DOMAINS = Set.of(
					Constant.YOUTUBE_DOMAIN,
					"youtube.googleapis.com",
					"googlevideo.com",
					"ytimg.com",
					"accounts.google",
					"accounts.google.com",
					"googleusercontent.com",
					"apis.google.com",
					"gstatic.com"
	);

	public static boolean isAllowedDomain(@Nullable final Uri uri) {
		if (uri == null) return false;
		final String host = uri.getHost();
		if (host == null) return false;
		final String lowerHost = host.toLowerCase();
		return ALLOWED_DOMAINS.stream().anyMatch(domain ->
						lowerHost.equals(domain) || lowerHost.endsWith("." + domain));
	}

	@NonNull
	public static String getPageClass(@Nullable final String url) {
		if (url == null || url.isEmpty()) return PAGE_UNKNOWN;

		final Uri uri = Uri.parse(url);
		final String host = uri.getHost();
		if (host == null) return PAGE_UNKNOWN;

		final String lowerHost = host.toLowerCase();
		if (!lowerHost.equals(Constant.YOUTUBE_MOBILE_HOST) && !lowerHost.equals(Constant.YOUTUBE_DOMAIN))
			return PAGE_UNKNOWN;

		final List<String> segments = uri.getPathSegments();
		if (segments.isEmpty()) return Constant.PAGE_HOME;

		final String s0 = segments.get(0).toLowerCase();
		if (s0.startsWith("@")) return PAGE_USER_MENTION;

		return switch (s0) {
			case "shorts" -> Constant.PAGE_SHORTS;
			case "watch" -> Constant.PAGE_WATCH;
			case "channel" -> PAGE_CHANNEL;
			case "gaming" -> PAGE_GAMING;
			case "select_site" -> PAGE_SELECT_SITE;
			case "results" -> PAGE_SEARCHING;
			case "feed" -> (segments.size() > 1) ? switch (segments.get(1).toLowerCase()) {
				case "subscriptions" -> Constant.PAGE_SUBSCRIPTIONS;
				case "library" -> Constant.PAGE_LIBRARY;
				case "history" -> PAGE_HISTORY;
				case "channels" -> PAGE_CHANNELS;
				case "playlists" -> PAGE_PLAYLISTS;
				default -> String.join("/", segments);
			} : String.join("/", segments);
			default -> String.join("/", segments);
		};
	}
}
