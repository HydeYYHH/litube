package com.hhst.youtubelite.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Set;

import okhttp3.HttpUrl;

public final class WebResourceUtils {

	public static final long WEBVIEW_CACHE_MAX_AGE_SECONDS = 60L * 60L * 24L * 365L;
	public static final String ORIGINAL_CACHE_CONTROL_HEADER = "X-Litube-Cache-Control";

	private static final Set<String> DEFAULT_CACHE_EXTENSIONS = Set.of(
					"html",
					"htm",
					"js",
					"ico",
					"css",
					"png",
					"jpg",
					"jpeg",
					"gif",
					"bmp",
					"ttf",
					"woff",
					"woff2",
					"otf",
					"eot",
					"svg",
					"xml",
					"swf",
					"txt",
					"text",
					"conf",
					"webp");

	private WebResourceUtils() {
	}

	public static boolean shouldForceCache(@Nullable final HttpUrl httpUrl) {
		return httpUrl != null && shouldForceCache(httpUrl.encodedPath());
	}

	public static boolean shouldForceCache(@Nullable final String path) {
		if (path == null || path.isEmpty()) return false;
		final int lastSlash = path.lastIndexOf('/');
		final String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
		final int dot = filename.lastIndexOf('.');
		if (dot < 0 || dot == filename.length() - 1) return false;
		final String extension = filename.substring(dot + 1).toLowerCase(Locale.US);
		return DEFAULT_CACHE_EXTENSIONS.contains(extension);
	}

	@NonNull
	public static String guessMimeType(@Nullable final String url) {
		final String path = url == null ? "" : url.toLowerCase(Locale.US);
		if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html";
		if (path.endsWith(".js")) return "application/javascript";
		if (path.endsWith(".css")) return "text/css";
		if (path.endsWith(".ico")) return "image/x-icon";
		if (path.endsWith(".png")) return "image/png";
		if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
		if (path.endsWith(".gif")) return "image/gif";
		if (path.endsWith(".bmp")) return "image/bmp";
		if (path.endsWith(".webp")) return "image/webp";
		if (path.endsWith(".svg")) return "image/svg+xml";
		if (path.endsWith(".xml")) return "text/xml";
		if (path.endsWith(".woff")) return "font/woff";
		if (path.endsWith(".woff2")) return "font/woff2";
		if (path.endsWith(".ttf")) return "font/ttf";
		if (path.endsWith(".otf")) return "font/otf";
		if (path.endsWith(".eot")) return "application/vnd.ms-fontobject";
		if (path.endsWith(".swf")) return "application/x-shockwave-flash";
		if (path.endsWith(".txt") || path.endsWith(".text") || path.endsWith(".conf"))
			return "text/plain";
		return "application/octet-stream";
	}
}
