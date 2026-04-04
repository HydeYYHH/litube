package com.hhst.youtubelite.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.Constant;

import java.net.URI;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Policy object that decides how WebView responses are cached.
 */
@Singleton
public final class WebViewCachePolicy {

	public static final long WEBVIEW_CACHE_MAX_AGE_SECONDS = 60L * 60L * 24L * 365L;
	public static final String ORIGINAL_CACHE_CONTROL_HEADER = "X-Litube-Cache-Control";

	@Inject
	public WebViewCachePolicy() {
	}

	@NonNull
	public CacheRequestInfo classifyRequest(boolean mainFrame, @Nullable String url, @Nullable String path) {
		return new CacheRequestInfo(shouldForceCacheMainFrame(mainFrame, url), shouldForceCachePath(path));
	}

	public boolean shouldAttemptCacheLookup(@NonNull CacheRequestInfo cacheRequestInfo) {
		return cacheRequestInfo.forceCacheMainFrame() || cacheRequestInfo.forceCacheStaticResource();
	}

	public boolean shouldForceCacheSharedResponse(@NonNull Request request) {
		return "GET".equalsIgnoreCase(request.method()) && shouldForceCachePath(request.url().encodedPath());
	}

	@NonNull
	public Response maybeRewriteResponse(@Nullable CacheRequestInfo cacheRequestInfo, @NonNull Request request, @NonNull Response response) {
		if (response.header(ORIGINAL_CACHE_CONTROL_HEADER) != null) {
			return response;
		}
		if (cacheRequestInfo != null) {
			if (cacheRequestInfo.forceCacheStaticResource()) {
				return rewriteCacheHeaders(response);
			}
			if (cacheRequestInfo.forceCacheMainFrame() && isHtmlLikeResponse(request, response)) {
				return rewriteCacheHeaders(response);
			}
		}
		if (shouldForceCacheSharedResponse(request)) {
			return rewriteCacheHeaders(response);
		}
		return response;
	}

	public boolean shouldRefreshCache(@NonNull Response response) {
		FreshnessInfo freshnessInfo = resolveFreshnessInfo(response);
		if (freshnessInfo == null) return true;
		if (freshnessInfo.remainingMillis() <= 0L) return true;
		return freshnessInfo.remainingMillis() <= computeWatchdogWindowMillis(freshnessInfo.lifetimeMillis());
	}

	public boolean shouldForceCachePath(@Nullable String path) {
		if (path == null || path.isEmpty()) return false;
		int lastSlash = path.lastIndexOf('/');
		String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
		int dot = filename.lastIndexOf('.');
		if (dot < 0 || dot == filename.length() - 1) return false;
		String extension = filename.substring(dot + 1).toLowerCase(Locale.US);
		return Set.of("html", "htm", "js", "ico", "css", "png", "jpg", "jpeg", "gif", "bmp", "ttf", "woff", "woff2", "otf", "eot", "svg", "xml", "swf", "txt", "text", "conf", "webp").contains(extension);
	}

	boolean shouldForceCacheMainFrame(boolean mainFrame, @Nullable String url) {
		if (!mainFrame || url == null || url.isEmpty()) return false;
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			if (host == null || "accounts.youtube.com".equalsIgnoreCase(host)) return false;
			String lowerHost = host.toLowerCase(Locale.US);
			return lowerHost.equals(Constant.YOUTUBE_DOMAIN) || lowerHost.endsWith("." + Constant.YOUTUBE_DOMAIN);
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	@NonNull
	private Response rewriteCacheHeaders(@NonNull Response response) {
		String originalCacheControl = response.header("Cache-Control");
		final Response.Builder builder = response.newBuilder().removeHeader("Pragma").removeHeader("Cache-Control").header("Cache-Control", "public, max-age=" + WEBVIEW_CACHE_MAX_AGE_SECONDS + ", immutable");
		if (hasText(originalCacheControl)) {
			builder.header(ORIGINAL_CACHE_CONTROL_HEADER, originalCacheControl);
		}
		return builder.build();
	}

	private boolean isHtmlLikeResponse(@NonNull Request request, @NonNull Response response) {
		ResponseBody body = response.body();
		MediaType contentType = body.contentType();
		if (contentType != null) {
			String type = contentType.type();
			String subtype = contentType.subtype();
			if ("text".equalsIgnoreCase(type) && "html".equalsIgnoreCase(subtype)) return true;
			if ("application".equalsIgnoreCase(type) && "xhtml+xml".equalsIgnoreCase(subtype)) {
				return true;
			}
		}
		String accept = request.header("Accept");
		return accept != null && accept.toLowerCase(Locale.US).contains("text/html");
	}

	@Nullable
	private FreshnessInfo resolveFreshnessInfo(@NonNull Response response) {
		String originalCacheControl = response.header(ORIGINAL_CACHE_CONTROL_HEADER);
		CachePolicy cachePolicy = parseCachePolicy(originalCacheControl);
		if (cachePolicy.noCache || cachePolicy.noStore) {
			return new FreshnessInfo(0L, -1L);
		}
		if (cachePolicy.maxAgeSeconds >= 0L) {
			long lifetimeMillis = TimeUnit.SECONDS.toMillis(cachePolicy.maxAgeSeconds);
			long ageMillis = computeResponseAgeMillis(response);
			return new FreshnessInfo(lifetimeMillis, lifetimeMillis - ageMillis);
		}

		long expiresAtMillis = parseHttpDateMillis(response.header("Expires"));
		long dateMillis = parseHttpDateMillis(response.header("Date"));
		if (expiresAtMillis <= 0L || dateMillis <= 0L) return null;

		long lifetimeMillis = expiresAtMillis - dateMillis;
		if (lifetimeMillis <= 0L) {
			return new FreshnessInfo(0L, -1L);
		}

		long ageMillis = computeResponseAgeMillis(response);
		return new FreshnessInfo(lifetimeMillis, lifetimeMillis - ageMillis);
	}

	private long computeResponseAgeMillis(@NonNull Response response) {
		long receivedAtMillis = response.receivedResponseAtMillis();
		long ageMillis = receivedAtMillis <= 0L ? 0L : Math.max(0L, System.currentTimeMillis() - receivedAtMillis);
		String ageHeader = response.header("Age");
		if (hasText(ageHeader)) {
			try {
				ageMillis += TimeUnit.SECONDS.toMillis(Long.parseLong(ageHeader));
			} catch (NumberFormatException ignored) {
			}
		}
		return ageMillis;
	}

	private long computeWatchdogWindowMillis(long lifetimeMillis) {
		if (lifetimeMillis <= 0L) return 0L;
		long proportionalWindow = lifetimeMillis / 10L;
		return Math.max(TimeUnit.SECONDS.toMillis(30L), Math.min(TimeUnit.MINUTES.toMillis(10L), proportionalWindow));
	}

	@NonNull
	private CachePolicy parseCachePolicy(@Nullable String cacheControl) {
		CachePolicy cachePolicy = new CachePolicy();
		if (!hasText(cacheControl)) return cachePolicy;

		String[] directives = cacheControl.split(",");
		for (String directiveValue : directives) {
			String directive = directiveValue.trim().toLowerCase(Locale.US);
			if ("no-cache".equals(directive)) {
				cachePolicy.noCache = true;
				continue;
			}
			if ("no-store".equals(directive)) {
				cachePolicy.noStore = true;
				continue;
			}
			if (directive.startsWith("max-age=")) {
				try {
					cachePolicy.maxAgeSeconds = Long.parseLong(directive.substring("max-age=".length()).trim());
				} catch (NumberFormatException ignored) {
					cachePolicy.maxAgeSeconds = -1L;
				}
			}
		}
		return cachePolicy;
	}

	private long parseHttpDateMillis(@Nullable String value) {
		if (!hasText(value)) return -1L;
		try {
			return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
		} catch (DateTimeParseException ignored) {
			return -1L;
		}
	}

	private boolean hasText(@Nullable String value) {
		return value != null && !value.isEmpty();
	}

/**
 * Component that handles app logic.
 */
	private static final class CachePolicy {
		private boolean noCache;
		private boolean noStore;
		private long maxAgeSeconds = -1L;
	}

/**
 * Value object for app logic.
 */
	private record FreshnessInfo(long lifetimeMillis, long remainingMillis) {
	}

	public record CacheRequestInfo(boolean forceCacheMainFrame, boolean forceCacheStaticResource) {
	}
}
