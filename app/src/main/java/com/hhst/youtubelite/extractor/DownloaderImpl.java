package com.hhst.youtubelite.extractor;

import android.webkit.CookieManager;

import androidx.annotation.NonNull;

import com.hhst.youtubelite.Constant;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
		this.client = client.newBuilder()
				.readTimeout(20, TimeUnit.SECONDS)
				.connectTimeout(20, TimeUnit.SECONDS)
				.followRedirects(true)
				.build();
	}

	@Override
	public org.schabi.newpipe.extractor.downloader.Response execute(@NonNull final org.schabi.newpipe.extractor.downloader.Request request) throws IOException, ReCaptchaException {
		final String httpMethod = request.httpMethod() != null ? request.httpMethod() : "GET";
		final String url = request.url();
		final Map<String, List<String>> headers = request.headers();
		final byte[] dataToSend = request.dataToSend();

		RequestBody requestBody = null;
		if (dataToSend != null) requestBody = RequestBody.create(dataToSend);

		final Request.Builder builder = new Request.Builder()
				.url(url)
				.method(httpMethod, requestBody)
				.header("User-Agent", Constant.USER_AGENT);

		// Optimized cookie handling
		final String webViewCookies = CookieManager.getInstance().getCookie(url);
		StringBuilder cookieBuilder = new StringBuilder();
		if (webViewCookies != null) {
			cookieBuilder.append(webViewCookies);
		}

		if (url.contains("youtube.com") || url.contains("youtu.be")) {
			if (webViewCookies == null || !webViewCookies.contains("PREF=")) {
				if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
				cookieBuilder.append(YOUTUBE_RESTRICTED_MODE_COOKIE);
			}
		}

		String finalCookies = cookieBuilder.toString();
		if (!finalCookies.isEmpty()) {
			builder.header("Cookie", finalCookies);
		}

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

			final ResponseBody responseBody = response.body();
			return new org.schabi.newpipe.extractor.downloader.Response(
					response.code(),
					response.message(),
					response.headers().toMultimap(),
					responseBody != null ? responseBody.string() : "",
					url
			);
		}
	}
}
