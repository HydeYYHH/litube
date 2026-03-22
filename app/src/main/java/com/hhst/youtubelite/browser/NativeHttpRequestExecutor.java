package com.hhst.youtubelite.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.util.UrlUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.CacheControl;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class NativeHttpRequestExecutor {

	private static final Gson GSON = new Gson();
	private static final CacheControl NO_CACHE_CONTROL = new CacheControl.Builder()
					.noCache()
					.noStore()
					.build();
	private static final Set<String> BRIDGE_SUPPORTED_METHODS = Set.of(
					"GET",
					"HEAD",
					"POST",
					"PUT",
					"PATCH",
					"DELETE",
					"OPTIONS");
	private static final Set<String> ALLOWED_DOMAINS = Set.of(
					Constant.YOUTUBE_DOMAIN,
					"youtube.googleapis.com",
					"googlevideo.com",
					"ytimg.com",
					"googleusercontent.com",
					"apis.google.com",
					"gstatic.com");

	@Nullable
	private final String defaultUserAgent;

	NativeHttpRequestExecutor(@Nullable final String defaultUserAgent) {
		this.defaultUserAgent = defaultUserAgent;
	}

	@NonNull
	PreparedRequest prepare(@Nullable final String payloadJson, @NonNull final CookieProvider cookieProvider) {
		if (isBlank(payloadJson)) return PreparedRequest.unsupported("blank-payload");

		final RequestPayload payload;
		try {
			payload = GSON.fromJson(payloadJson, RequestPayload.class);
		} catch (final RuntimeException ignored) {
			return PreparedRequest.unsupported("invalid-json");
		}
		if (payload == null) return PreparedRequest.unsupported("null-payload");

		final String url = payload.url;
		final String method = normalizeMethod(payload.method, "POST");
		if (!isAllowedUrl(url)) return PreparedRequest.unsupported("disallowed-url");
		if (!supportsBridgeMethod(method)) return PreparedRequest.unsupported("unsupported-method:" + method);

		final byte[] bodyBytes;
		try {
			bodyBytes = decodeBody(payload.bodyBase64);
		} catch (final IllegalArgumentException ignored) {
			return PreparedRequest.unsupported("invalid-base64-body");
		}

		final Request.Builder builder = new Request.Builder()
						.url(url);
		builder.method(method, "GET".equals(method) || "HEAD".equals(method)
						? null
						: RequestBody.create(bodyBytes));
		final Map<String, String> headers = payload.headers != null ? payload.headers : Map.of();

		for (final Map.Entry<String, String> entry : headers.entrySet()) {
			final String headerName = entry.getKey();
			final String headerValue = entry.getValue();
			if (isBlank(headerName) || headerValue == null) continue;
			builder.header(headerName, headerValue);
		}

		if (!hasHeader(headers, "User-Agent") && !isBlank(defaultUserAgent)) {
			builder.header("User-Agent", defaultUserAgent);
		}
		if (Boolean.FALSE != payload.includeCookies && !hasHeader(headers, "Cookie")) {
			final String cookies = cookieProvider.getCookie(url);
			if (!isBlank(cookies)) {
				builder.header("Cookie", cookies);
			}
		}

		builder.cacheControl(NO_CACHE_CONTROL);
		builder.header("Pragma", "no-cache");
		final Request request = builder.build();
		return PreparedRequest.intercept(request, buildDedupeKey(request, bodyBytes));
	}

	static boolean supportsBridgeMethod(@Nullable final String method) {
		return BRIDGE_SUPPORTED_METHODS.contains(normalizeMethod(method, ""));
	}

	@NonNull
	ResponsePayload buildResponsePayload(@Nullable final String requestId, @NonNull final Response response) throws IOException {
		final String contentType = resolveContentType(response);
		final byte[] bodyBytes = readBodyBytes(response);
		final boolean binaryBody = !isTextLike(contentType);

		return new ResponsePayload(
						requestId,
						true,
						response.request().url().toString(),
						response.code(),
						response.message(),
						response.priorResponse() != null,
						buildResponseHeaders(response),
						binaryBody,
						binaryBody ? "" : decodeTextBody(bodyBytes, contentType),
						binaryBody ? Base64.getEncoder().encodeToString(bodyBytes) : "",
						null);
	}

	@NonNull
	ResponsePayload buildFailurePayload(@Nullable final String requestId, @NonNull final IOException exception) {
		return new ResponsePayload(
						requestId,
						true,
						null,
						0,
						null,
						false,
						Map.of(),
						false,
						"",
						"",
						exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
	}

	@NonNull
	ResponsePayload buildUnsupportedPayload(@Nullable final String requestId, @Nullable final String reason) {
		return new ResponsePayload(requestId, false, null, 0, null, false, Map.of(), false, "", "", reason);
	}

	@NonNull
	String toJson(@NonNull final ResponsePayload payload) {
		return GSON.toJson(payload);
	}

	@NonNull
	private String buildDedupeKey(@NonNull final Request request, @NonNull final byte[] bodyBytes) {
		final StringBuilder builder = new StringBuilder();
		builder.append(request.method())
						.append('\n')
						.append(request.url())
						.append('\n');

		final List<String> headerNames = new ArrayList<>(request.headers().names());
		headerNames.sort(String.CASE_INSENSITIVE_ORDER);
		for (final String headerName : headerNames) {
			final List<String> headerValues = new ArrayList<>(request.headers(headerName));
			Collections.sort(headerValues);
			builder.append(headerName.toLowerCase(Locale.US))
							.append(':')
							.append(String.join("\u0001", headerValues))
							.append('\n');
		}

		builder.append("body-sha256:")
						.append(sha256(bodyBytes));
		return builder.toString();
	}

	@NonNull
	private String sha256(@NonNull final byte[] bodyBytes) {
		try {
			final MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			return Base64.getEncoder().withoutPadding().encodeToString(messageDigest.digest(bodyBytes));
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException("Missing SHA-256 support", e);
		}
	}

	private boolean isAllowedUrl(@Nullable final String url) {
		if (isBlank(url)) return false;
		if (UrlUtils.isGoogleAccountsUrl(url)) return false;
		try {
			final URI uri = new URI(url);
			final String scheme = uri.getScheme();
			if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
				return false;
			}
			final String host = uri.getHost();
			if (isBlank(host)) return false;
			final String lowerHost = host.toLowerCase(Locale.US);
			for (final String domain : ALLOWED_DOMAINS) {
				if (lowerHost.equals(domain) || lowerHost.endsWith("." + domain)) {
					return true;
				}
			}
			return false;
		} catch (final URISyntaxException ignored) {
			return false;
		}
	}

	private boolean hasHeader(@NonNull final Map<String, String> headers, @NonNull final String name) {
		for (final String headerName : headers.keySet()) {
			if (name.equalsIgnoreCase(headerName)) {
				return true;
			}
		}
		return false;
	}

	private boolean isBlank(@Nullable final String value) {
		return value == null || value.trim().isEmpty();
	}

	@NonNull
	private static String normalizeMethod(@Nullable final String method, @NonNull final String fallback) {
		if (method == null) return fallback;
		final String normalized = method.trim().toUpperCase(Locale.US);
		return normalized.isEmpty() ? fallback : normalized;
	}

	@NonNull
	private byte[] decodeBody(@Nullable final String bodyBase64) {
		if (bodyBase64 == null || bodyBase64.isEmpty()) return new byte[0];
		return Base64.getDecoder().decode(bodyBase64);
	}

	@NonNull
	private byte[] readBodyBytes(@NonNull final Response response) throws IOException {
		final ResponseBody body = response.body();
		if (body == null) return new byte[0];
		return body.bytes();
	}

	@NonNull
	private Map<String, String> buildResponseHeaders(@NonNull final Response response) {
		final Map<String, String> headers = new LinkedHashMap<>();
		for (final String name : response.headers().names()) {
			if ("set-cookie".equalsIgnoreCase(name)) continue;
			headers.put(name, response.headers(name).size() > 1
							? String.join(", ", response.headers(name))
							: response.header(name));
		}
		return headers;
	}

	@NonNull
	private String resolveContentType(@NonNull final Response response) {
		final String headerValue = response.header("Content-Type");
		if (headerValue != null && !headerValue.isEmpty()) return headerValue;
		final ResponseBody responseBody = response.body();
		return responseBody != null && responseBody.contentType() != null
						? responseBody.contentType().toString()
						: "";
	}

	private boolean isTextLike(@Nullable final String contentType) {
		if (contentType == null) return false;
		final String lower = contentType.toLowerCase(Locale.US);
		return lower.startsWith("text/")
						|| lower.contains("json")
						|| lower.contains("xml")
						|| lower.contains("javascript")
						|| lower.contains("html")
						|| lower.contains("x-www-form-urlencoded");
	}

	@NonNull
	private String decodeTextBody(@NonNull final byte[] bodyBytes, @Nullable final String contentType) {
		if (bodyBytes.length == 0) return "";
		final Charset charset = resolveCharset(contentType);
		return new String(bodyBytes, charset);
	}

	@NonNull
	private Charset resolveCharset(@Nullable final String contentType) {
		if (contentType == null) return StandardCharsets.UTF_8;
		final String[] segments = contentType.split(";");
		for (int i = 1; i < segments.length; i++) {
			final String segment = segments[i].trim();
			if (!segment.toLowerCase(Locale.US).startsWith("charset=")) continue;
			final String charsetName = segment.substring("charset=".length()).trim().replace("\"", "");
			if (charsetName.isEmpty()) break;
			try {
				return Charset.forName(charsetName);
			} catch (final RuntimeException ignored) {
				return StandardCharsets.UTF_8;
			}
		}
		return StandardCharsets.UTF_8;
	}

	interface CookieProvider {
		@Nullable
		String getCookie(@NonNull String url);
	}

	record PreparedRequest(boolean intercepted,
	                       @Nullable Request request,
	                       @Nullable String dedupeKey,
	                       @Nullable String reason) {
		@NonNull
		static PreparedRequest intercept(@NonNull final Request request,
		                                 @Nullable final String dedupeKey) {
			return new PreparedRequest(true, request, dedupeKey, null);
		}

		@NonNull
		static PreparedRequest unsupported(@Nullable final String reason) {
			return new PreparedRequest(false, null, null, reason);
		}
	}

	record ResponsePayload(
					@Nullable String requestId,
					boolean intercepted,
					@Nullable String url,
					int status,
					@Nullable String statusText,
					boolean redirected,
					@NonNull Map<String, String> headers,
					boolean binaryBody,
					@NonNull String bodyText,
					@NonNull String bodyBase64,
					@Nullable String error) {
		@NonNull
		ResponsePayload withRequestId(@Nullable final String requestId) {
			return new ResponsePayload(
							requestId,
							intercepted,
							url,
							status,
							statusText,
							redirected,
							headers,
							binaryBody,
							bodyText,
							bodyBase64,
							error);
		}
	}

	private static final class RequestPayload {
		@Nullable
		String url;
		@Nullable
		String method;
		@Nullable
		Map<String, String> headers;
		@Nullable
		String bodyBase64;
		@Nullable
		Boolean includeCookies;
	}
}
