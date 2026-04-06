package com.hhst.youtubelite.util;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.Constant;

import java.net.URI;
import java.util.Locale;
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

	private static final Locale NORMALIZATION_LOCALE = Locale.ROOT;

	private static final Set<String> ALLOWED_DOMAINS = Set.of(
					Constant.YOUTUBE_DOMAIN,
					"youtu.be",
					"youtube.googleapis.com",
					"googlevideo.com",
					"ytimg.com",
					"accounts.google",
					"accounts.google.com",
					"google.com",
					"googleusercontent.com",
					"gstatic.com",
					"googleapis.com",
					"ggpht.com",
					"yt.be",
					"google.ad",
					"doubleclick.net"
	);

	public static boolean isAllowedDomain(@Nullable final Uri uri) {
		if (uri == null) return false;
		return isAllowedHost(uri.getHost());
	}

	public static boolean isAllowedUrl(@Nullable final String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			return isAllowedHost(URI.create(url).getHost());
		} catch (final IllegalArgumentException ignored) {
			return false;
		}
	}

	public static boolean isGoogleAccountsUrl(@Nullable final String url) {
		if (url == null || url.isEmpty()) return false;
		try {
			final String host = URI.create(url).getHost();
			return host != null && isGoogleAccountsHost(host.toLowerCase(NORMALIZATION_LOCALE));
		} catch (final IllegalArgumentException ignored) {
			return false;
		}
	}

	private static boolean isAllowedHost(@Nullable final String host) {
		if (host == null) return false;
		final String lowerHost = host.toLowerCase(NORMALIZATION_LOCALE);
		if (isGoogleAccountsHost(lowerHost)) return true;
		return ALLOWED_DOMAINS.stream().anyMatch(domain ->
						lowerHost.equals(domain) || lowerHost.endsWith("." + domain));
	}

	private static boolean isGoogleAccountsHost(@NonNull final String lowerHost) {
		return lowerHost.equals("accounts.google")
						|| lowerHost.equals("accounts.google.com")
						|| lowerHost.startsWith("accounts.google.")
						|| lowerHost.equals("accounts.youtube.com")
						|| lowerHost.contains("myaccount.google");
	}

	@NonNull
	public static String getPageClass(@Nullable final String url) {
		if (url == null || url.isEmpty()) return PAGE_UNKNOWN;

		try {
			final URI uri = URI.create(url);
			final String host = uri.getHost();
			if (host == null) return PAGE_UNKNOWN;
			final String path = uri.getPath();
			final List<String> segments = path == null || path.isEmpty()
							? List.of()
							: java.util.Arrays.stream(path.split("/"))
											.filter(segment -> !segment.isEmpty())
											.toList();
			return resolvePageClass(host, segments);
		} catch (final IllegalArgumentException ignored) {
			return PAGE_UNKNOWN;
		}
	}

	@NonNull
	static String resolvePageClass(@NonNull final String host, @NonNull final List<String> segments) {
		final String lowerHost = host.toLowerCase(NORMALIZATION_LOCALE);
		if (lowerHost.equals("youtu.be")) {
			return segments.isEmpty() ? PAGE_UNKNOWN : Constant.PAGE_WATCH;
		}
		if (!lowerHost.endsWith(Constant.YOUTUBE_DOMAIN))
			return PAGE_UNKNOWN;

		if (segments.isEmpty()) return Constant.PAGE_HOME;

		final String s0 = segments.get(0).toLowerCase(NORMALIZATION_LOCALE);
		if (s0.startsWith("@")) return PAGE_USER_MENTION;

		return switch (s0) {
			case "shorts" -> Constant.PAGE_SHORTS;
			case "watch" -> Constant.PAGE_WATCH;
			case "channel" -> PAGE_CHANNEL;
			case "gaming" -> PAGE_GAMING;
			case "select_site" -> PAGE_SELECT_SITE;
			case "results" -> PAGE_SEARCHING;
			case "feed" -> (segments.size() > 1) ? switch (segments.get(1).toLowerCase(NORMALIZATION_LOCALE)) {
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
