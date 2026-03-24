package com.hhst.youtubelite.extractor;

import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.Constant;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
public final class DownloaderImpl extends Downloader {

	private static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";
	private static final Set<String> AUTH_SENSITIVE_HEADERS = Set.of(
					"authorization",
					"cookie",
					"x-goog-authuser",
					"x-goog-pageid",
					"x-goog-visitor-id",
					"x-youtube-bootstrap-logged-in",
					"x-youtube-client-name",
					"x-youtube-client-version",
					"x-youtube-identity-token");

	private final OkHttpClient client;
	private final ThreadLocal<ExtractionSession> activeSession = new ThreadLocal<>();
	private final Map<ExtractionSession, Boolean> playbackMemoryCacheEligibility = new ConcurrentHashMap<>();

	@Inject
	public DownloaderImpl(final OkHttpClient client) {
		this.client = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(30, TimeUnit.SECONDS).build();
	}

	@NonNull
	<T> T withExtractionSession(@NonNull final StreamInfoSupplier<T> supplier,
	                            @Nullable final ExtractionSession session) throws org.schabi.newpipe.extractor.exceptions.ExtractionException, IOException {
		final ExtractionSession previous = activeSession.get();
		if (session == null) {
			activeSession.remove();
		} else {
			playbackMemoryCacheEligibility.put(session, Boolean.TRUE);
			activeSession.set(session);
		}
		try {
			return supplier.get();
		} finally {
			if (previous == null) {
				activeSession.remove();
			} else {
				activeSession.set(previous);
			}
		}
	}

	boolean canUsePlaybackMemoryCache(@NonNull final String url) {
		if (YoutubeExtractor.getVideoId(url) == null) return false;
		final String host = getHost(url);
		if (host == null) return false;
		final String lowerHost = host.toLowerCase(Locale.US);
		return lowerHost.equals("youtu.be")
						|| lowerHost.equals(Constant.YOUTUBE_DOMAIN)
						|| lowerHost.endsWith("." + Constant.YOUTUBE_DOMAIN);
	}

	@NonNull
	String buildRequestContextFingerprint(@NonNull final String url) {
		return buildRequestContextFingerprint(url, getWebViewCookies(url));
	}

	@NonNull
	String buildRequestContextFingerprint(@NonNull final String url,
	                                      @Nullable final String webViewCookies) {
		return sha256Hex(mergeCookiesForUrl(url, webViewCookies));
	}

	boolean canPopulatePlaybackMemoryCache(@Nullable final ExtractionSession session) {
		return session == null || playbackMemoryCacheEligibility.getOrDefault(session, Boolean.TRUE);
	}

	void clearPlaybackMemoryCacheSession(@Nullable final ExtractionSession session) {
		if (session != null) {
			playbackMemoryCacheEligibility.remove(session);
		}
	}

	void markPlaybackCachePopulationIneligibleIfNeeded(@Nullable final Map<String, List<String>> headers) {
		if (!containsAuthSensitiveHeaderOverrides(headers)) return;
		final ExtractionSession session = activeSession.get();
		if (session != null) {
			playbackMemoryCacheEligibility.put(session, Boolean.FALSE);
		}
	}

	@Override
	public org.schabi.newpipe.extractor.downloader.Response execute(@NonNull final org.schabi.newpipe.extractor.downloader.Request request) throws IOException, ReCaptchaException {
		final String httpMethod = request.httpMethod() != null ? request.httpMethod() : "GET";
		final String url = request.url();
		final Map<String, List<String>> headers = request.headers();
		final byte[] dataToSend = request.dataToSend();

		RequestBody requestBody = null;
		if (dataToSend != null) requestBody = RequestBody.create(dataToSend);

		final Request.Builder builder = new Request.Builder().url(url).method(httpMethod, requestBody).header("User-Agent", Constant.USER_AGENT);

		// Get cookies from WebView
		final String mergedCookies = mergeCookiesForUrl(url, getWebViewCookies(url));

		if (!mergedCookies.isEmpty()) {
			builder.header("Cookie", mergedCookies);
		}

		markPlaybackCachePopulationIneligibleIfNeeded(headers);

		// Override with headers from request
		if (headers != null) {
			for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
				final String headerName = entry.getKey();
				builder.removeHeader(headerName);
				for (final String value : entry.getValue()) {
					builder.addHeader(headerName, value);
				}
			}
		}

		final ExtractionSession session = activeSession.get();
		final Call call = client.newCall(builder.build());
		if (session != null) {
			session.register(call::cancel);
		}

		try (final Response response = call.execute()) {
			if (response.code() == 429) {
				throw new ReCaptchaException("ReCaptcha Challenge requested", url);
			}

			final int responseCode = response.code();
			final String responseMessage = response.message();
			final Map<String, List<String>> responseHeaders = response.headers().toMultimap();
			final ResponseBody responseBody = response.body();
			final String responseBodyString = responseBody != null ? responseBody.string() : "";

			return new org.schabi.newpipe.extractor.downloader.Response(responseCode, responseMessage, responseHeaders, responseBodyString, url);
		} catch (IOException e) {
			if (session != null && session.isCancelled()) {
				final InterruptedIOException interrupted = new InterruptedIOException("Extraction canceled");
				interrupted.initCause(e);
				throw interrupted;
			}
			throw e;
		}
	}

	@Nullable
	private String getWebViewCookies(@NonNull final String url) {
		return CookieManager.getInstance().getCookie(url);
	}

	@NonNull
	String mergeCookiesForUrl(@NonNull final String url,
	                          @Nullable final String webViewCookies) {
		final String restrictedModeCookie = getRestrictedModeCookie(url, webViewCookies);
		return Stream.of(webViewCookies, restrictedModeCookie)
						.filter(Objects::nonNull)
						.flatMap(cookies -> Arrays.stream(cookies.split("; *")))
						.filter(s -> !s.isEmpty())
						.collect(Collectors.collectingAndThen(
										Collectors.toCollection(LinkedHashSet::new),
										set -> String.join("; ", set)));
	}

	@Nullable
	private String getRestrictedModeCookie(@NonNull final String url,
	                                       @Nullable final String webViewCookies) {
		if (!isYoutubeHost(getHost(url))) return null;
		if (webViewCookies != null && webViewCookies.contains("PREF=")) return null;
		return YOUTUBE_RESTRICTED_MODE_COOKIE;
	}

	private boolean containsAuthSensitiveHeaderOverrides(@Nullable final Map<String, List<String>> headers) {
		if (headers == null || headers.isEmpty()) return false;
		for (final String headerName : headers.keySet()) {
			if (headerName == null) continue;
			if (AUTH_SENSITIVE_HEADERS.contains(headerName.toLowerCase(Locale.US))) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private String getHost(@NonNull final String url) {
		try {
			return URI.create(url).getHost();
		} catch (final IllegalArgumentException ignored) {
			return null;
		}
	}

	private boolean isYoutubeHost(@Nullable final String host) {
		if (host == null) return false;
		final String lowerHost = host.toLowerCase(Locale.US);
		return lowerHost.equals("youtu.be")
						|| lowerHost.equals(Constant.YOUTUBE_DOMAIN)
						|| lowerHost.endsWith("." + Constant.YOUTUBE_DOMAIN);
	}

	@NonNull
	private String sha256Hex(@NonNull final String value) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			final StringBuilder builder = new StringBuilder(hash.length * 2);
			for (final byte b : hash) {
				builder.append(String.format(Locale.US, "%02x", b));
			}
			return builder.toString();
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	@FunctionalInterface
	interface StreamInfoSupplier<T> {
		T get() throws org.schabi.newpipe.extractor.exceptions.ExtractionException, IOException;
	}
}
