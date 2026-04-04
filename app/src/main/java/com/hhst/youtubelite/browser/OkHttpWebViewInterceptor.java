package com.hhst.youtubelite.browser;

import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.cache.WebViewCachePolicy;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.WebResourceUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
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

/**
 * Intercepts WebView traffic through OkHttp.
 */
@UnstableApi
public final class OkHttpWebViewInterceptor {

	@NonNull
	private final OkHttpClient client;
	@NonNull
	private final WebViewCachePolicy cachePolicy;
	@NonNull
	private final CookieAccessCoordinator cookieAccessCoordinator;
	@NonNull
	private final Set<String> refreshingUrls = Collections.newSetFromMap(new ConcurrentHashMap<>());

	public OkHttpWebViewInterceptor(@NonNull OkHttpClient client, @NonNull WebViewCachePolicy cachePolicy) {
		this.cachePolicy = cachePolicy;
		this.client = createResourceClient(client, cachePolicy);
		this.cookieAccessCoordinator = CookieAccessCoordinator.create(CookieManager.getInstance());
	}

	@NonNull
	static OkHttpClient createResourceClient(@NonNull OkHttpClient client, @NonNull WebViewCachePolicy cachePolicy) {
		return client.newBuilder().dispatcher(createDispatcher()).callTimeout(30L, TimeUnit.SECONDS).connectTimeout(10L, TimeUnit.SECONDS).writeTimeout(15L, TimeUnit.SECONDS).readTimeout(20L, TimeUnit.SECONDS).addNetworkInterceptor(chain -> {
			Request request = chain.request();
			Response response = chain.proceed(request);
			final WebViewCachePolicy.CacheRequestInfo cacheRequestInfo = request.tag(WebViewCachePolicy.CacheRequestInfo.class);
			return cachePolicy.maybeRewriteResponse(cacheRequestInfo, request, response);
		}).build();
	}

	@NonNull
	static Dispatcher createDispatcher() {
		Dispatcher dispatcher = new Dispatcher();
		dispatcher.setMaxRequests(64);
		dispatcher.setMaxRequestsPerHost(16);
		return dispatcher;
	}

	private static boolean isInterceptableWebRequest(@Nullable String method, @Nullable Map<String, String> requestHeaders, @Nullable String url) {
		if (!isBodylessMethod(method)) return false;
		if (requestHeaders != null) {
			for (String headerName : requestHeaders.keySet()) {
				if ("range".equalsIgnoreCase(headerName)) return false;
			}
		}
		final String scheme;
		try {
			scheme = url == null ? null : URI.create(url).getScheme();
		} catch (IllegalArgumentException ignored) {
			return false;
		}
		return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
	}

	private static boolean isBodylessMethod(@Nullable String method) {
		return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
	}

	public boolean canExecute(@Nullable WebResourceRequest request) {
		if (request == null) return false;
		String url = request.getUrl().toString();
		return isInterceptableWebRequest(request.getMethod(), request.getRequestHeaders(), url) && !UrlUtils.isGoogleAccountsUrl(url) && UrlUtils.isAllowedUrl(url);
	}

	@Nullable
	public WebResourceResponse intercept(@NonNull WebResourceRequest request) {
		if (!canExecute(request)) return null;
		String url = request.getUrl().toString();
		final WebViewCachePolicy.CacheRequestInfo cacheInfo = cachePolicy.classifyRequest(request.isForMainFrame(), url, request.getUrl().getPath());
		Response response = null;
		try {
			if (cachePolicy.shouldAttemptCacheLookup(cacheInfo)) {
				response = executeCacheOnly(request);
				if (isUsableResponse(response)) {
					if (cachePolicy.shouldRefreshCache(Objects.requireNonNull(response))) {
						scheduleRefresh(request);
					}
					return toResponse(url, Objects.requireNonNull(response));
				}
				closeQuietly(response);
			}

			response = execute(request);
			if (!isUsableResponse(response)) {
				closeQuietly(response);
				return null;
			}
			return toResponse(url, Objects.requireNonNull(response));
		} catch (IOException e) {
			closeQuietly(response);
			return null;
		}
	}

	@Nullable
	public Response execute(@NonNull WebResourceRequest request) throws IOException {
		if (!canExecute(request)) return null;
		return executeRequest(buildRequest(request, null));
	}

	@Nullable
	private Response executeCacheOnly(@NonNull WebResourceRequest request) throws IOException {
		if (!canExecute(request)) return null;
		CacheControl cacheControl = new CacheControl.Builder().onlyIfCached().maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS).build();
		return executeRequest(buildRequest(request, cacheControl));
	}

	@NonNull
	private Request buildRequest(@NonNull WebResourceRequest request, @Nullable CacheControl cacheControl) {
		String url = request.getUrl().toString();
		String method = request.getMethod() == null ? "GET" : request.getMethod().trim().toUpperCase(Locale.US);
		final Request.Builder builder = new Request.Builder().url(url).method(method, null);
		if (cacheControl != null) builder.cacheControl(cacheControl);
		builder.tag(WebViewCachePolicy.CacheRequestInfo.class, cachePolicy.classifyRequest(request.isForMainFrame(), url, request.getUrl().getPath()));

		for (final Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
			String name = header.getKey();
			String value = header.getValue();
			if (name == null || name.isEmpty() || value == null) continue;
			if (Set.of("cache-control", "content-length", "cookie", "host", "if-modified-since", "if-none-match", "pragma").contains(name.toLowerCase(Locale.US)))
				continue;
			builder.header(name, value);
		}

		String cookies = cookieAccessCoordinator.getCookie(url);
		if (cookies != null && !cookies.isEmpty()) {
			builder.header("Cookie", Objects.requireNonNull(cookies));
		}
		return builder.build();
	}

	@NonNull
	private Response executeRequest(@NonNull Request request) throws IOException {
		Response response = client.newCall(request).execute();
		cookieAccessCoordinator.syncCookies(response);
		return response;
	}

	@NonNull
	public WebResourceResponse toWebResourceResponse(@NonNull String url, @NonNull Response response, @NonNull InputStream bodyStream) {
		String mimeType = mimeTypeFrom(url, response.body());
		String encoding = encodingFrom(response.body());
		String reasonPhrase = reasonPhraseFrom(response.code(), response.message());
		Map<String, String> headers = buildResponseHeaders(response);

		return new WebResourceResponse(mimeType, encoding, response.code(), reasonPhrase, headers, bodyStream);
	}

	@NonNull
	private WebResourceResponse toResponse(@NonNull String url, @NonNull Response response) throws IOException {
		if (shouldBufferImageResponse(url, response)) {
			return toBufferedWebResourceResponse(url, response);
		}
		return toWebResourceResponse(url, response, Objects.requireNonNull(response.body()).byteStream());
	}

	@NonNull
	private WebResourceResponse toBufferedWebResourceResponse(@NonNull String url, @NonNull Response response) throws IOException {
		ResponseBody body = Objects.requireNonNull(response.body());
		String mimeType = mimeTypeFrom(url, body);
		String encoding = encodingFrom(body);
		String reasonPhrase = reasonPhraseFrom(response.code(), response.message());
		byte[] bytes = body.bytes();
		Map<String, String> headers = buildResponseHeaders(response);
		headers.put("Content-Length", String.valueOf(bytes.length));
		closeQuietly(response);
		return new WebResourceResponse(mimeType, encoding, response.code(), reasonPhrase, headers, new ByteArrayInputStream(bytes));
	}

	@NonNull
	private String mimeTypeFrom(@NonNull String url, @Nullable ResponseBody body) {
		MediaType contentType = body != null ? body.contentType() : null;
		if (contentType != null) {
			return contentType.type() + "/" + contentType.subtype();
		}
		return WebResourceUtils.guessMimeType(url);
	}

	@Nullable
	private String encodingFrom(@Nullable ResponseBody body) {
		MediaType contentType = body != null ? body.contentType() : null;
		Charset charset = contentType != null ? contentType.charset(null) : null;
		return charset != null ? charset.name() : null;
	}

	private boolean isUsableResponse(@Nullable Response response) {
		return response != null && response.code() != 504;
	}

	private void scheduleRefresh(@NonNull WebResourceRequest request) {
		String url = request.getUrl().toString();
		if (!refreshingUrls.add(url)) return;

		CacheControl refreshPolicy = new CacheControl.Builder().noCache().build();
		Request refreshRequest = buildRequest(request, refreshPolicy);
		client.newCall(refreshRequest).enqueue(new Callback() {
			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e) {
				refreshingUrls.remove(url);
			}

			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response) {
				try (response) {
					cookieAccessCoordinator.syncCookies(response);
					drainBody(response.body());
				} catch (IOException ignored) {
				} finally {
					refreshingUrls.remove(url);
				}
			}
		});
	}

	private void drainBody(@Nullable ResponseBody body) throws IOException {
		if (body == null) return;
		try (Sink sink = Okio.blackhole()) {
			body.source().readAll(sink);
		}
	}

	private void closeQuietly(@Nullable Response response) {
		if (response == null) return;
		try {
			response.close();
		} catch (Exception ignored) {
		}
	}

	@NonNull
	private Map<String, String> buildResponseHeaders(@NonNull Response response) {
		Map<String, String> responseHeaders = new LinkedHashMap<>();
		for (String name : response.headers().names()) {
			if (Set.of("content-encoding", "content-length", "content-type", "transfer-encoding").contains(name.toLowerCase(Locale.US)))
				continue;
			responseHeaders.put(name, Objects.requireNonNull(response.header(name)));
		}
		return responseHeaders;
	}

	private boolean shouldBufferImageResponse(@NonNull String url, @NonNull Response response) {
		ResponseBody body = Objects.requireNonNull(response.body());
		long contentLength = body.contentLength();
		if (contentLength <= 0L) return false;
		MediaType contentType = body.contentType();
		String mimeType = contentType != null ? (contentType.type() + "/" + contentType.subtype()).toLowerCase(Locale.US) : WebResourceUtils.guessMimeType(url).toLowerCase(Locale.US);

		return mimeType.startsWith("image/") && contentLength <= 4L * 1024L * 1024L;
	}

	@NonNull
	private String reasonPhraseFrom(int statusCode, @Nullable String message) {
		if (message != null && !message.isEmpty()) return message.trim();
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

}
