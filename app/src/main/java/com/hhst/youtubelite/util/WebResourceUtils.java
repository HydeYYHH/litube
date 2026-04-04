package com.hhst.youtubelite.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Helpers for WebView resource handling and MIME checks.
 */
public final class WebResourceUtils {

	private WebResourceUtils() {
	}

	@NonNull
	public static String guessMimeType(@Nullable String url) {
		String path = url == null ? "" : url.toLowerCase(Locale.US);
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
