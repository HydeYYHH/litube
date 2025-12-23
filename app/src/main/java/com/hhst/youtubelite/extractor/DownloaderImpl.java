package com.hhst.youtubelite.extractor;

import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.downloader.Downloader;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.Getter;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class DownloaderImpl extends Downloader {
	public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
	public static final String YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000";
	public static final String YOUTUBE_DOMAIN = "youtube.com";

	@Getter
	private static DownloaderImpl instance;
	private final OkHttpClient client;

	private DownloaderImpl(final OkHttpClient.Builder builder) {
		this.client = builder.readTimeout(5, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build();
	}

	/**
	 * It's recommended to call exactly once in the entire lifetime of the application.
	 *
	 * @param builder if null, default builder will be used
	 * @return a new instance of {@link DownloaderImpl}
	 */
	public static DownloaderImpl init(@Nullable final OkHttpClient.Builder builder) {
		instance = new DownloaderImpl(builder != null ? builder : new OkHttpClient.Builder());
		return instance;
	}

	@Override
	public org.schabi.newpipe.extractor.downloader.Response execute(@NonNull final org.schabi.newpipe.extractor.downloader.Request request) throws IOException {
		final String httpMethod = request.httpMethod() != null ? request.httpMethod() : "GET";
		final String url = request.url();
		final Map<String, List<String>> headers = request.headers();
		final byte[] dataToSend = request.dataToSend();

		RequestBody requestBody = null;
		if (dataToSend != null) requestBody = RequestBody.create(dataToSend);

		final Request.Builder requestBuilder = new Request.Builder().method(httpMethod, requestBody).url(url);

		// Add default User-Agent
		requestBuilder.header("User-Agent", USER_AGENT);

		// Add cookies from WebView and restricted mode
		final String webViewCookies = CookieManager.getInstance().getCookie(url);
		final String restrictedModeCookie = url.contains(YOUTUBE_DOMAIN) ? YOUTUBE_RESTRICTED_MODE_COOKIE : null;

		final String cookies = Stream.of(webViewCookies, restrictedModeCookie).filter(Objects::nonNull).flatMap(c -> Arrays.stream(c.split("; *"))).filter(s -> !s.isEmpty()).distinct().collect(Collectors.joining("; "));

		if (!cookies.isEmpty()) requestBuilder.header("Cookie", cookies);

		// Add other headers from request
		headers.forEach((headerName, headerValueList) -> {
			requestBuilder.removeHeader(headerName);
			headerValueList.forEach(headerValue -> requestBuilder.addHeader(headerName, headerValue));
		});

		try (Response response = client.newCall(requestBuilder.build()).execute()) {
			String responseBodyToReturn;
			try (ResponseBody body = response.body()) {
				responseBodyToReturn = body.string();
			}

			final String latestUrl = response.request().url().toString();
			return new org.schabi.newpipe.extractor.downloader.Response(response.code(), response.message(), response.headers().toMultimap(), responseBodyToReturn, latestUrl);
		}
	}
}