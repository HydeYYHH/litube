package com.hhst.youtubelite.extractor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloaderImplTest {

	private static final String WATCH_URL = "https://www.youtube.com/watch?v=mAdodMaERp0";

	private DownloaderImpl downloader;
	private Call call;

	@Before
	public void setUp() throws Exception {
		final OkHttpClient seedClient = mock(OkHttpClient.class);
		final OkHttpClient.Builder builder = mock(OkHttpClient.Builder.class);
		final OkHttpClient configuredClient = mock(OkHttpClient.class);
		call = mock(Call.class);

		when(seedClient.newBuilder()).thenReturn(builder);
		when(builder.readTimeout(anyLong(), any(TimeUnit.class))).thenReturn(builder);
		when(builder.connectTimeout(anyLong(), any(TimeUnit.class))).thenReturn(builder);
		when(builder.build()).thenReturn(configuredClient);
		when(configuredClient.newCall(any(Request.class))).thenReturn(call);
		when(call.execute()).thenAnswer(invocation -> mockResponse());

		downloader = new DownloaderImpl(seedClient);
	}

	@Test
	public void buildRequestContextFingerprint_changesWhenCookieStateChanges() {
		final String first = downloader.buildRequestContextFingerprint(WATCH_URL, "VISITOR_INFO1_LIVE=alpha");
		final String second = downloader.buildRequestContextFingerprint(WATCH_URL, "VISITOR_INFO1_LIVE=beta");

		assertNotEquals(first, second);
	}

	@Test
	public void buildRequestContextFingerprint_changesWhenRestrictedModeChanges() {
		final String withInjectedRestrictedMode = downloader.buildRequestContextFingerprint(WATCH_URL, "SID=session");
		final String withExistingPrefCookie = downloader.buildRequestContextFingerprint(
						WATCH_URL,
						"SID=session; PREF=f1=50000000");

		assertNotEquals(withInjectedRestrictedMode, withExistingPrefCookie);
	}

	@Test
	public void authSensitiveHeaderOverrides_markExtractionSessionNonCacheable() throws Exception {
		final ExtractionSession normalSession = new ExtractionSession();

		downloader.withExtractionSession(() -> {
			downloader.markPlaybackCachePopulationIneligibleIfNeeded(Map.of("Accept-Language", List.of("en-US")));
			return null;
		}, normalSession);

		assertTrue(downloader.canPopulatePlaybackMemoryCache(normalSession));

		final ExtractionSession authSensitiveSession = new ExtractionSession();
		downloader.withExtractionSession(() -> {
			downloader.markPlaybackCachePopulationIneligibleIfNeeded(Map.of("Cookie", List.of("SID=override")));
			return null;
		}, authSensitiveSession);

		assertFalse(downloader.canPopulatePlaybackMemoryCache(authSensitiveSession));
	}

	@Test
	public void standardPlaybackExtractionPath_isCacheEligibleBeforeLookup() {
		assertTrue(downloader.canUsePlaybackMemoryCache(WATCH_URL));
		assertFalse(downloader.canUsePlaybackMemoryCache("https://www.youtube.com/api/stats/watchtime"));
		assertFalse(downloader.canUsePlaybackMemoryCache("https://example.com/watch?v=mAdodMaERp0"));
	}

	private static Response mockResponse() throws Exception {
		final Response response = mock(Response.class);
		final Headers headers = mock(Headers.class);
		final ResponseBody responseBody = mock(ResponseBody.class);

		when(response.code()).thenReturn(200);
		when(response.message()).thenReturn("OK");
		when(response.headers()).thenReturn(headers);
		when(headers.toMultimap()).thenReturn(Collections.emptyMap());
		when(response.body()).thenReturn(responseBody);
		when(responseBody.string()).thenReturn("");
		return response;
	}
}
