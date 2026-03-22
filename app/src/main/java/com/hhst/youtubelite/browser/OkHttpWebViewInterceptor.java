package com.hhst.youtubelite.browser;

import android.text.TextUtils;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.WebResourceUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Okio;
import okio.Sink;

@UnstableApi
public final class OkHttpWebViewInterceptor {

	private static final String ACCOUNTS_YOUTUBE_HOST = "accounts.youtube.com";
	private static final Set<String> BLOCKED_REQUEST_HEADERS = Set.of(
					"cache-control",
					"content-length",
					"cookie",
					"host",
					"if-modified-since",
					"if-none-match",
					"pragma");

	private static final Set<String> BLOCKED_RESPONSE_HEADERS = Set.of(
					"content-encoding",
					"content-length",
					"content-type",
					"transfer-encoding");

	private static final long WATCHDOG_MIN_WINDOW_MILLIS = TimeUnit.SECONDS.toMillis(30);
	private static final long WATCHDOG_MAX_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(10);
	private static final long MAX_BUFFERED_IMAGE_BYTES = 4L * 1024L * 1024L;
	static final int RESOURCE_MAX_REQUESTS = 48;
	static final int RESOURCE_MAX_REQUESTS_PER_HOST = 12;
	static final int NATIVE_MAX_REQUESTS = 32;
	static final int NATIVE_MAX_REQUESTS_PER_HOST = 10;
	private static final long RESOURCE_CALL_TIMEOUT_SECONDS = 18L;
	private static final long RESOURCE_CONNECT_TIMEOUT_SECONDS = 6L;
	private static final long RESOURCE_WRITE_TIMEOUT_SECONDS = 10L;
	private static final long RESOURCE_READ_TIMEOUT_SECONDS = 12L;
	private static final long NATIVE_CALL_TIMEOUT_SECONDS = 20L;
	private static final long NATIVE_CONNECT_TIMEOUT_SECONDS = 6L;
	private static final long NATIVE_WRITE_TIMEOUT_SECONDS = 10L;
	private static final long NATIVE_READ_TIMEOUT_SECONDS = 12L;

	@NonNull
	private final OkHttpClient client;
	@NonNull
	private final OkHttpClient nativeRequestClient;
	@NonNull
	private final NativeHttpRequestExecutor nativeHttpRequestExecutor;
	@NonNull
	private final CookieAccessCoordinator cookieAccessCoordinator;
	@NonNull
	private final NativeRequestDeduplicator<NativeHttpRequestExecutor.ResponsePayload> nativeRequestDeduplicator = new NativeRequestDeduplicator<>();
	@NonNull
	private final Set<String> refreshingUrls = Collections.newSetFromMap(new ConcurrentHashMap<>());

	public OkHttpWebViewInterceptor(@NonNull final OkHttpClient client) {
		this.client = createResourceClient(client);
		this.nativeRequestClient = createNativeRequestClient(client);
		this.nativeHttpRequestExecutor = new NativeHttpRequestExecutor(Constant.USER_AGENT);
		this.cookieAccessCoordinator = CookieAccessCoordinator.create(CookieManager.getInstance());
	}

	@NonNull
	static OkHttpClient createResourceClient(@NonNull final OkHttpClient client) {
		return client.newBuilder()
						.dispatcher(createDispatcher(RESOURCE_MAX_REQUESTS, RESOURCE_MAX_REQUESTS_PER_HOST))
						.callTimeout(RESOURCE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.connectTimeout(RESOURCE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.writeTimeout(RESOURCE_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.readTimeout(RESOURCE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.addNetworkInterceptor(chain -> {
							final Request request = chain.request();
							final Response response = chain.proceed(request);
							final CacheRequestInfo cacheRequestInfo = request.tag(CacheRequestInfo.class);
							if (!shouldRewriteCacheHeaders(cacheRequestInfo, request, response)) {
								return response;
							}
							return rewriteCacheHeaders(response);
						})
						.build();
	}

	@NonNull
	static OkHttpClient createNativeRequestClient(@NonNull final OkHttpClient client) {
		return client.newBuilder()
						.dispatcher(createDispatcher(NATIVE_MAX_REQUESTS, NATIVE_MAX_REQUESTS_PER_HOST))
						.cache(null)
						.callTimeout(NATIVE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.connectTimeout(NATIVE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.writeTimeout(NATIVE_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.readTimeout(NATIVE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.build();
	}

	@NonNull
	static Dispatcher createDispatcher(final int maxRequests, final int maxRequestsPerHost) {
		final Dispatcher dispatcher = new Dispatcher();
		dispatcher.setMaxRequests(maxRequests);
		dispatcher.setMaxRequestsPerHost(maxRequestsPerHost);
		return dispatcher;
	}

	public boolean canExecute(@Nullable final WebResourceRequest request) {
		return request != null && isInterceptableWebRequest(request.getMethod(), request.getRequestHeaders(), request.getUrl().toString());
	}

	@Nullable
	public WebResourceResponse intercept(@NonNull final WebResourceRequest request) {
		if (!shouldProxyRequest(request.getMethod(), request.getRequestHeaders(), request.getUrl().toString())) return null;
		final String url = request.getUrl().toString();
		Response response = null;
		try {
			if (shouldAttemptCacheLookup(request.isForMainFrame(), request.getUrl().toString())) {
				response = executeCacheOnly(request);
				if (isUsableResponse(response)) {
					maybeScheduleRefresh(request, Objects.requireNonNull(response));
					if (shouldBufferImageResponse(url, Objects.requireNonNull(response))) {
						return toBufferedWebResourceResponse(url, Objects.requireNonNull(response));
					}
					return toWebResourceResponse(url, Objects.requireNonNull(response), Objects.requireNonNull(response.body()).byteStream());
				}
				closeQuietly(response);
				response = null;
			}

			response = execute(request);
			if (!isUsableResponse(response)) {
				closeQuietly(response);
				return null;
			}
			if (shouldBufferImageResponse(url, Objects.requireNonNull(response))) {
				return toBufferedWebResourceResponse(url, Objects.requireNonNull(response));
			}
			return toWebResourceResponse(url, Objects.requireNonNull(response), Objects.requireNonNull(response.body()).byteStream());
		} catch (final IOException ignored) {
			closeQuietly(response);
			return null;
		}
	}

	@Nullable
	public Response execute(@NonNull final WebResourceRequest request) throws IOException {
		if (!canExecute(request)) return null;
		return executeRequest(buildRequest(request, null));
	}

	@Nullable
	private Response executeCacheOnly(@NonNull final WebResourceRequest request) throws IOException {
		if (!canExecute(request)) return null;
		final CacheControl cacheControl = new CacheControl.Builder()
						.onlyIfCached()
						.maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS)
						.build();
		return executeRequest(buildRequest(request, cacheControl));
	}

	@NonNull
	private Request buildRequest(@NonNull final WebResourceRequest request, @Nullable final CacheControl cacheControl) {
		final String url = request.getUrl().toString();
		final String method = request.getMethod() == null
						? "GET"
						: request.getMethod().trim().toUpperCase(Locale.US);
		final Request.Builder builder = new Request.Builder().url(url).method(method, null);
		if (cacheControl != null) builder.cacheControl(cacheControl);
		builder.tag(CacheRequestInfo.class, new CacheRequestInfo(request.isForMainFrame(), WebResourceUtils.shouldForceCache(request.getUrl().getPath())));

		for (final Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
			final String name = header.getKey();
			final String value = header.getValue();
			if (TextUtils.isEmpty(name) || value == null) continue;
			if (BLOCKED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.US))) continue;
			builder.header(name, value);
		}

		final String cookies = cookieAccessCoordinator.getCookie(url);
		if (!TextUtils.isEmpty(cookies)) {
			builder.header("Cookie", Objects.requireNonNull(cookies));
		}
		return builder.build();
	}

	@NonNull
	private Response executeRequest(@NonNull final Request request) throws IOException {
		final Response response = client.newCall(request).execute();
		cookieAccessCoordinator.syncFromResponse(response);
		return response;
	}

	public void enqueueNativeRequest(@NonNull final String requestId,
	                                 @Nullable final String payloadJson,
	                                 @NonNull final Consumer<String> onComplete) {
		final NativeHttpRequestExecutor.PreparedRequest prepared = nativeHttpRequestExecutor.prepare(payloadJson, cookieAccessCoordinator::getCookie);
		if (!prepared.intercepted() || prepared.request() == null) {
			onComplete.accept(nativeHttpRequestExecutor.toJson(nativeHttpRequestExecutor.buildUnsupportedPayload(requestId, prepared.reason())));
			return;
		}

		nativeRequestDeduplicator.enqueue(
						requestId,
						prepared.dedupeKey(),
						payload -> onComplete.accept(nativeHttpRequestExecutor.toJson(payload.withRequestId(requestId))),
						completion -> {
							final Call call = nativeRequestClient.newCall(Objects.requireNonNull(prepared.request()));
							call.enqueue(new Callback() {
								@Override
								public void onFailure(@NonNull final Call call, @NonNull final IOException e) {
									completion.complete(nativeHttpRequestExecutor.buildFailurePayload(null, e));
								}

								@Override
								public void onResponse(@NonNull final Call call, @NonNull final Response response) {
									try (response) {
										cookieAccessCoordinator.syncFromResponse(response);
										completion.complete(nativeHttpRequestExecutor.buildResponsePayload(null, response));
									} catch (final IOException e) {
										completion.complete(nativeHttpRequestExecutor.buildFailurePayload(null, e));
									}
								}
							});
							return call::cancel;
						});
	}

	public void cancelNativeRequest(@Nullable final String requestId) {
		nativeRequestDeduplicator.cancel(requestId);
	}

	@NonNull
	public WebResourceResponse toWebResourceResponse(@NonNull final String url, @NonNull final Response response, @NonNull final InputStream bodyStream) {
		final String mimeType = resolveMimeType(url, response.body());
		final String encoding = resolveEncoding(response.body());
		final String reasonPhrase = resolveReasonPhrase(response.code(), response.message());
		final Map<String, String> responseHeaders = buildResponseHeaders(response);

		return new WebResourceResponse(
						mimeType,
						encoding,
						response.code(),
						reasonPhrase,
						responseHeaders,
						bodyStream);
	}

	@NonNull
	private WebResourceResponse toBufferedWebResourceResponse(@NonNull final String url, @NonNull final Response response) throws IOException {
		final ResponseBody body = Objects.requireNonNull(response.body());
		final String mimeType = resolveMimeType(url, body);
		final String encoding = resolveEncoding(body);
		final String reasonPhrase = resolveReasonPhrase(response.code(), response.message());
		final byte[] bytes = body.bytes();
		final Map<String, String> responseHeaders = buildResponseHeaders(response);
		responseHeaders.put("Content-Length", String.valueOf(bytes.length));
		closeQuietly(response);
		return new WebResourceResponse(
						mimeType,
						encoding,
						response.code(),
						reasonPhrase,
						responseHeaders,
						new ByteArrayInputStream(bytes));
	}

	private static boolean isInterceptableWebRequest(@Nullable final String method,
	                                                 @Nullable final Map<String, String> requestHeaders,
	                                                 @Nullable final String url) {
		if (!isBodylessMethod(method)) return false;
		if (requestHeaders != null) {
			for (final String headerName : requestHeaders.keySet()) {
				if ("range".equalsIgnoreCase(headerName)) return false;
			}
		}
		final String scheme;
		try {
			scheme = url == null ? null : URI.create(url).getScheme();
		} catch (final IllegalArgumentException ignored) {
			return false;
		}
		return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
	}

	private static boolean isBodylessMethod(@Nullable final String method) {
		return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
	}

	static boolean shouldProxyRequest(@Nullable final String method,
	                                  @Nullable final Map<String, String> requestHeaders,
	                                  @Nullable final String url) {
		if (!isInterceptableWebRequest(method, requestHeaders, url)) return false;
		if (UrlUtils.isGoogleAccountsUrl(url)) return false;
		return UrlUtils.isAllowedUrl(url);
	}

	static boolean shouldAttemptCacheLookup(final boolean isForMainFrame,
	                                        @Nullable final String url) {
		return shouldUseMainFrameCache(isForMainFrame, url)
						|| WebResourceUtils.shouldForceCache(extractPath(url));
	}

	private static String extractPath(@Nullable final String url) {
		try {
			return url == null ? null : URI.create(url).getPath();
		} catch (final IllegalArgumentException ignored) {
			return null;
		}
	}

	private static boolean shouldUseMainFrameCache(final boolean isForMainFrame,
	                                               @Nullable final String url) {
		return isForMainFrame && isCacheableYouTubeMainFrameUrl(url);
	}

	private static boolean isCacheableYouTubeMainFrameUrl(@Nullable final String url) {
		try {
			if (url == null) return false;
			final String host = URI.create(url).getHost();
			if (TextUtils.isEmpty(host)) return false;
			final String lowerHost = Objects.requireNonNull(host).toLowerCase(Locale.US);
			if (ACCOUNTS_YOUTUBE_HOST.equals(lowerHost)) return false;
			return lowerHost.equals(Constant.YOUTUBE_DOMAIN) || lowerHost.endsWith("." + Constant.YOUTUBE_DOMAIN);
		} catch (final RuntimeException ignored) {
			return false;
		}
	}

	@NonNull
	private String resolveMimeType(@NonNull final String url, @Nullable final ResponseBody body) {
		final MediaType contentType = body != null ? body.contentType() : null;
		if (contentType != null) {
			return contentType.type() + "/" + contentType.subtype();
		}
		return WebResourceUtils.guessMimeType(url);
	}

	@Nullable
	private String resolveEncoding(@Nullable final ResponseBody body) {
		final MediaType contentType = body != null ? body.contentType() : null;
		final Charset charset = contentType != null ? contentType.charset(null) : null;
		return charset != null ? charset.name() : null;
	}

	private boolean isUsableResponse(@Nullable final Response response) {
		return response != null && response.code() != 504;
	}

	private void maybeScheduleRefresh(@NonNull final WebResourceRequest request, @NonNull final Response response) {
		if (shouldRefreshCache(response)) {
			scheduleRefresh(request);
		}
	}

	private static boolean shouldRewriteCacheHeaders(@Nullable final CacheRequestInfo cacheRequestInfo, @NonNull final Request request, @NonNull final Response response) {
		if (cacheRequestInfo == null) return false;
		if (response.header(WebResourceUtils.ORIGINAL_CACHE_CONTROL_HEADER) != null) return false;
		if (cacheRequestInfo.staticResource) return false;
		if (!cacheRequestInfo.mainFrame) return false;
		return isHtmlLikeResponse(request, response);
	}

	@NonNull
	private static Response rewriteCacheHeaders(@NonNull final Response response) {
		final Response.Builder builder = response.newBuilder()
						.removeHeader("Pragma")
						.removeHeader("Cache-Control")
						.header("Cache-Control", "public, max-age=" + WebResourceUtils.WEBVIEW_CACHE_MAX_AGE_SECONDS + ", immutable");
		final String originalCacheControl = response.header("Cache-Control");
		if (!TextUtils.isEmpty(originalCacheControl)) {
			builder.header(WebResourceUtils.ORIGINAL_CACHE_CONTROL_HEADER, Objects.requireNonNull(originalCacheControl));
		}
		return builder.build();
	}

	private static boolean isHtmlLikeResponse(@NonNull final Request request, @NonNull final Response response) {
		final ResponseBody body = response.body();
		final MediaType contentType = body.contentType();
		if (contentType != null) {
			final String type = contentType.type();
			final String subtype = contentType.subtype();
			if ("text".equalsIgnoreCase(type) && "html".equalsIgnoreCase(subtype)) return true;
			if ("application".equalsIgnoreCase(type) && "xhtml+xml".equalsIgnoreCase(subtype))
				return true;
		}
		final String accept = request.header("Accept");
		return accept != null && accept.toLowerCase(Locale.US).contains("text/html");
	}

	private boolean shouldRefreshCache(@NonNull final Response response) {
		final FreshnessInfo freshnessInfo = resolveFreshnessInfo(response);
		if (freshnessInfo == null) return true;
		if (freshnessInfo.remainingMillis <= 0) return true;
		return freshnessInfo.remainingMillis <= computeWatchdogWindowMillis(freshnessInfo.lifetimeMillis);
	}

	@Nullable
	private FreshnessInfo resolveFreshnessInfo(@NonNull final Response response) {
		final String originalCacheControl = response.header(WebResourceUtils.ORIGINAL_CACHE_CONTROL_HEADER);
		final CachePolicy cachePolicy = parseCachePolicy(originalCacheControl);
		if (cachePolicy.noCache || cachePolicy.noStore) {
			return new FreshnessInfo(0L, -1L);
		}
		if (cachePolicy.maxAgeSeconds >= 0) {
			final long lifetimeMillis = TimeUnit.SECONDS.toMillis(cachePolicy.maxAgeSeconds);
			final long ageMillis = computeResponseAgeMillis(response);
			return new FreshnessInfo(lifetimeMillis, lifetimeMillis - ageMillis);
		}

		final long expiresAtMillis = parseHttpDateMillis(response.header("Expires"));
		final long dateMillis = parseHttpDateMillis(response.header("Date"));
		if (expiresAtMillis <= 0 || dateMillis <= 0) return null;

		final long lifetimeMillis = expiresAtMillis - dateMillis;
		if (lifetimeMillis <= 0) {
			return new FreshnessInfo(0L, -1L);
		}

		final long ageMillis = computeResponseAgeMillis(response);
		return new FreshnessInfo(lifetimeMillis, lifetimeMillis - ageMillis);
	}

	private long computeResponseAgeMillis(@NonNull final Response response) {
		final long receivedAtMillis = response.receivedResponseAtMillis();
		long ageMillis = receivedAtMillis <= 0 ? 0L : Math.max(0L, System.currentTimeMillis() - receivedAtMillis);
		final String ageHeader = response.header("Age");
		if (!TextUtils.isEmpty(ageHeader)) {
			try {
				ageMillis += TimeUnit.SECONDS.toMillis(Long.parseLong(Objects.requireNonNull(ageHeader)));
			} catch (final NumberFormatException ignored) {
			}
		}
		return ageMillis;
	}

	private long computeWatchdogWindowMillis(final long lifetimeMillis) {
		if (lifetimeMillis <= 0) return 0L;
		final long proportionalWindow = lifetimeMillis / 10L;
		return Math.max(WATCHDOG_MIN_WINDOW_MILLIS, Math.min(WATCHDOG_MAX_WINDOW_MILLIS, proportionalWindow));
	}

	@NonNull
	private CachePolicy parseCachePolicy(@Nullable final String cacheControl) {
		final CachePolicy cachePolicy = new CachePolicy();
		if (TextUtils.isEmpty(cacheControl)) return cachePolicy;

		final String[] directives = Objects.requireNonNull(cacheControl).split(",");
		for (final String directiveValue : directives) {
			final String directive = directiveValue.trim().toLowerCase(Locale.US);
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
				} catch (final NumberFormatException ignored) {
					cachePolicy.maxAgeSeconds = -1L;
				}
			}
		}
		return cachePolicy;
	}

	private long parseHttpDateMillis(@Nullable final String value) {
		if (TextUtils.isEmpty(value)) return -1L;
		try {
			return ZonedDateTime.parse(Objects.requireNonNull(value), DateTimeFormatter.RFC_1123_DATE_TIME)
							.toInstant()
							.toEpochMilli();
		} catch (final DateTimeParseException ignored) {
			return -1L;
		}
	}

	private void scheduleRefresh(@NonNull final WebResourceRequest request) {
		final String url = request.getUrl().toString();
		if (!refreshingUrls.add(url)) return;

		final CacheControl refreshPolicy = new CacheControl.Builder()
						.noCache()
						.build();
		final Request refreshRequest = buildRequest(request, refreshPolicy);
		client.newCall(refreshRequest).enqueue(new Callback() {
			@Override
			public void onFailure(@NonNull final Call call, @NonNull final IOException e) {
				refreshingUrls.remove(url);
			}

			@Override
			public void onResponse(@NonNull final Call call, @NonNull final Response response) {
				try (response) {
					cookieAccessCoordinator.syncFromResponse(response);
					drainBody(response.body());
				} catch (final IOException ignored) {
				} finally {
					refreshingUrls.remove(url);
				}
			}
		});
	}

	private void drainBody(@Nullable final ResponseBody body) throws IOException {
		if (body == null) return;
		try (Sink sink = Okio.blackhole()) {
			body.source().readAll(sink);
		}
	}

	private void closeQuietly(@Nullable final Response response) {
		if (response == null) return;
		try {
			response.close();
		} catch (final Exception ignored) {
		}
	}

	@NonNull
	private Map<String, String> buildResponseHeaders(@NonNull final Response response) {
		final Map<String, String> responseHeaders = new LinkedHashMap<>();
		for (final String name : response.headers().names()) {
			if (BLOCKED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.US))) continue;
			responseHeaders.put(name, Objects.requireNonNull(response.header(name)));
		}
		return responseHeaders;
	}

	private boolean shouldBufferImageResponse(@NonNull final String url, @NonNull final Response response) {
		final ResponseBody body = response.body();
		final long contentLength = body.contentLength();
		if (contentLength <= 0L) return false;
		final MediaType contentType = body.contentType();
		final String mimeType = contentType != null
						? (contentType.type() + "/" + contentType.subtype()).toLowerCase(Locale.US)
						: WebResourceUtils.guessMimeType(url).toLowerCase(Locale.US);

		return mimeType.startsWith("image/") && contentLength <= MAX_BUFFERED_IMAGE_BYTES;
	}

	@NonNull
	private String resolveReasonPhrase(final int statusCode, @Nullable final String message) {
		if (!TextUtils.isEmpty(message)) return Objects.requireNonNull(message).trim();
		return switch (statusCode) {
			case 200 -> "OK";
			case 201 -> "Created";
			case 202 -> "Accepted";
			case 204 -> "No Content";
			case 206 -> "Partial Content";
			case 301 -> "Moved Permanently";
			case 302 -> "Found";
			case 304 -> "Not Modified";
			case 307 -> "Temporary Redirect";
			case 308 -> "Permanent Redirect";
			case 400 -> "Bad Request";
			case 401 -> "Unauthorized";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 408 -> "Request Timeout";
			case 409 -> "Conflict";
			case 410 -> "Gone";
			case 415 -> "Unsupported Media Type";
			case 429 -> "Too Many Requests";
			case 500 -> "Internal Server Error";
			case 501 -> "Not Implemented";
			case 502 -> "Bad Gateway";
			case 503 -> "Service Unavailable";
			case 504 -> "Gateway Timeout";
			default -> statusCode >= 400 ? "HTTP Error" : "OK";
		};
	}

	private static final class CachePolicy {
		private boolean noCache;
		private boolean noStore;
		private long maxAgeSeconds = -1L;
	}

	private record FreshnessInfo(long lifetimeMillis, long remainingMillis) {
	}

	private record CacheRequestInfo(boolean mainFrame, boolean staticResource) {
	}
}
