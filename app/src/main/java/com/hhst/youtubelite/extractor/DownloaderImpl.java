package com.hhst.youtubelite.extractor;

import android.webkit.CookieManager;

import androidx.annotation.NonNull;

import com.hhst.youtubelite.Constant;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Singleton
public final class DownloaderImpl extends Downloader {

	private static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";

	private final OkHttpClient client;

	@Inject
	public DownloaderImpl(final OkHttpClient client) {
		this.client = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(30, TimeUnit.SECONDS).build();
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
		final String webViewCookies = CookieManager.getInstance().getCookie(url);

		// Handle restricted mode cookie
		String restrictedModeCookie = null;
		if (url.contains(Constant.YOUTUBE_DOMAIN)) {
			if (webViewCookies == null || !webViewCookies.contains("PREF=")) {
				restrictedModeCookie = YOUTUBE_RESTRICTED_MODE_COOKIE;
			}
		}

		// Merge and deduplicate cookies
		final String mergedCookies = Stream.of(webViewCookies, restrictedModeCookie).filter(Objects::nonNull).flatMap(cookies -> Arrays.stream(cookies.split("; *"))).filter(s -> !s.isEmpty()).distinct().collect(Collectors.joining("; "));

		if (!mergedCookies.isEmpty()) {
			builder.header("Cookie", mergedCookies);
		}

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

		try (final Response response = client.newCall(builder.build()).execute()) {
			if (response.code() == 429) {
				throw new ReCaptchaException("reCaptcha Challenge requested", url);
			}

			final int responseCode = response.code();
			final String responseMessage = response.message();
			final Map<String, List<String>> responseHeaders = response.headers().toMultimap();
			final ResponseBody responseBody = response.body();
			final String responseBodyString = responseBody.string();

			return new org.schabi.newpipe.extractor.downloader.Response(responseCode, responseMessage, responseHeaders, responseBodyString, url);
		}
	}
}
