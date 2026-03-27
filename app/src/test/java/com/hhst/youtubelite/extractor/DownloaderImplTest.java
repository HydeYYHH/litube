package com.hhst.youtubelite.extractor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import android.webkit.CookieManager;

public class DownloaderImplTest {

	private static final String WATCH_URL = "https://www.youtube.com/watch?v=mAdodMaERp0";

	private DownloaderImpl downloader;
	private Call call;
	private OkHttpClient configuredClient;

	@Before
	public void setUp() throws Exception {
		final OkHttpClient seedClient = mock(OkHttpClient.class);
		final OkHttpClient.Builder builder = mock(OkHttpClient.Builder.class);
		configuredClient = mock(OkHttpClient.class);
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
	public void mergeCookiesForUrl_injectsRestrictedModeWhenYoutubeCookiesLackPref() {
		final String merged = downloader.mergeCookiesForUrl(WATCH_URL, "SID=session");
		assertEquals("SID=session; PREF=f2=8000000", merged);
	}

	@Test
	public void mergeCookiesForUrl_doesNotInjectRestrictedModeWhenPrefAlreadyProvided() {
		final String merged = downloader.mergeCookiesForUrl(WATCH_URL, "SID=session; PREF=f1=50000000");
		assertEquals("SID=session; PREF=f1=50000000", merged);
	}

	@Test
	public void mergeCookiesForUrl_nonYoutubeUrlDoesNotInjectRestrictedModeCookie() {
		final String merged = downloader.mergeCookiesForUrl("https://example.com/watch?v=mAdodMaERp0", "SID=session");
		assertEquals("SID=session", merged);
	}

	@Test
	public void mergeCookiesForUrl_invalidUrlKeepsProvidedCookiesWithoutYoutubeDefaults() {
		final String merged = downloader.mergeCookiesForUrl("::::invalid-url::::", "SID=session");
		assertEquals("SID=session", merged);
	}

	@Test
	public void buildRequestContextFingerprint_invalidUrlIsDeterministicAndCookieDriven() {
		final String first = downloader.buildRequestContextFingerprint("::::invalid-url::::", "SID=session");
		final String second = downloader.buildRequestContextFingerprint("::::invalid-url::::", "SID=session");
		final String changed = downloader.buildRequestContextFingerprint("::::invalid-url::::", "SID=other");

		assertEquals(first, second);
		assertNotEquals(first, changed);
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
	public void authSensitiveHeaderOverrides_areMatchedCaseInsensitively() throws Exception {
		final ExtractionSession session = new ExtractionSession();

		downloader.withExtractionSession(() -> {
			downloader.markPlaybackCachePopulationIneligibleIfNeeded(Map.of("AUTHORIZATION", List.of("Bearer token")));
			return null;
		}, session);

		assertFalse(downloader.canPopulatePlaybackMemoryCache(session));
	}

	@Test
	public void nonSensitiveOrNullHeaderNames_doNotMarkSessionIneligible() throws Exception {
		final ExtractionSession session = new ExtractionSession();
		final Map<String, List<String>> headers = new HashMap<>();
		headers.put(null, List.of("ignored"));
		headers.put("Accept-Language", List.of("en-US"));

		downloader.withExtractionSession(() -> {
			downloader.markPlaybackCachePopulationIneligibleIfNeeded(headers);
			return null;
		}, session);

		assertTrue(downloader.canPopulatePlaybackMemoryCache(session));
	}

	@Test
	public void withExtractionSession_nestedSessionsKeepEligibilityIsolated() throws Exception {
		final ExtractionSession outerSession = new ExtractionSession();
		final ExtractionSession innerSession = new ExtractionSession();

		downloader.withExtractionSession(() -> {
			downloader.withExtractionSession(() -> {
				downloader.markPlaybackCachePopulationIneligibleIfNeeded(Map.of("Authorization", List.of("Bearer inner")));
				return null;
			}, innerSession);
			downloader.markPlaybackCachePopulationIneligibleIfNeeded(Map.of("Accept-Language", List.of("en-US")));
			return null;
		}, outerSession);

		assertTrue(downloader.canPopulatePlaybackMemoryCache(outerSession));
		assertFalse(downloader.canPopulatePlaybackMemoryCache(innerSession));
	}

	@Test
	public void clearPlaybackMemoryCacheSession_resetsSessionEligibilityToDefault() throws Exception {
		final ExtractionSession session = new ExtractionSession();
		downloader.withExtractionSession(() -> {
			downloader.markPlaybackCachePopulationIneligibleIfNeeded(Map.of("Cookie", List.of("SID=override")));
			return null;
		}, session);
		assertFalse(downloader.canPopulatePlaybackMemoryCache(session));

		downloader.clearPlaybackMemoryCacheSession(session);

		assertTrue(downloader.canPopulatePlaybackMemoryCache(session));
	}

	@Test
	public void standardPlaybackExtractionPath_isCacheEligibleBeforeLookup() {
		assertTrue(downloader.canUsePlaybackMemoryCache(WATCH_URL));
		assertTrue(downloader.canUsePlaybackMemoryCache("https://youtu.be/mAdodMaERp0"));
		assertTrue(downloader.canUsePlaybackMemoryCache("https://music.youtube.com/watch?v=mAdodMaERp0"));
		assertFalse(downloader.canUsePlaybackMemoryCache("https://www.youtube.com/api/stats/watchtime"));
		assertFalse(downloader.canUsePlaybackMemoryCache("https://example.com/watch?v=mAdodMaERp0"));
	}

	@Test
	public void execute_includesCookiesAndOverridesHeaders() throws Exception {
		final ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
		when(configuredClient.newCall(requestCaptor.capture())).thenReturn(call);

		try (MockedStatic<CookieManager> cookieManagerStatic = mockStatic(CookieManager.class)) {
			final CookieManager cookieManager = mock(CookieManager.class);
			cookieManagerStatic.when(CookieManager::getInstance).thenReturn(cookieManager);
			when(cookieManager.getCookie(WATCH_URL)).thenReturn("SID=session");

			final Map<String, List<String>> requestHeaders = new HashMap<>();
			requestHeaders.put("Authorization", List.of("Bearer token"));
			requestHeaders.put("User-Agent", List.of("CustomAgent/1.0"));

			final org.schabi.newpipe.extractor.downloader.Request newpipeRequest =
							new org.schabi.newpipe.extractor.downloader.Request(
											"POST",
											WATCH_URL,
											requestHeaders,
											"payload".getBytes(StandardCharsets.UTF_8),
											null,
											false);

			downloader.execute(newpipeRequest);

			final Request captured = requestCaptor.getValue();
			assertEquals("Bearer token", captured.header("Authorization"));
			assertEquals("CustomAgent/1.0", captured.header("User-Agent"));
			final String cookieHeader = captured.header("Cookie");
			assertTrue(cookieHeader.contains("SID=session"));
			assertTrue(cookieHeader.contains("PREF=f2=8000000"));
		}
	}

	@Test
	public void mergeCookiesForUrl_deduplicatesRepeatedCookies() {
		final String merged = downloader.mergeCookiesForUrl(
						WATCH_URL,
						"SID=session; PREF=f2=8000000; SID=session");

		assertEquals("SID=session; PREF=f2=8000000", merged);
	}

	@Test
	public void execute_throwsReCaptchaExceptionOn429() throws Exception {
		final Response response429 = mock(Response.class);
		final Headers headers = mock(Headers.class);
		final ResponseBody body = mock(ResponseBody.class);
		when(response429.code()).thenReturn(429);
		when(response429.message()).thenReturn("Too Many Requests");
		when(response429.headers()).thenReturn(headers);
		when(headers.toMultimap()).thenReturn(Collections.emptyMap());
		when(response429.body()).thenReturn(body);
		when(body.string()).thenReturn("");
		when(call.execute()).thenReturn(response429);

		try (MockedStatic<CookieManager> cookieManagerStatic = mockStatic(CookieManager.class)) {
			final CookieManager cookieManager = mock(CookieManager.class);
			cookieManagerStatic.when(CookieManager::getInstance).thenReturn(cookieManager);
			when(cookieManager.getCookie(WATCH_URL)).thenReturn("");

			final org.schabi.newpipe.extractor.downloader.Request request =
							new org.schabi.newpipe.extractor.downloader.Request(
											"GET",
											WATCH_URL,
											Collections.emptyMap(),
											null,
											null,
											false);

			assertThrows(org.schabi.newpipe.extractor.exceptions.ReCaptchaException.class,
							() -> downloader.execute(request));
		}
	}

	@Test
	public void execute_handlesNullResponseBodyAsEmptyString() throws Exception {
		final Response response = mock(Response.class);
		final Headers headers = mock(Headers.class);
		when(response.code()).thenReturn(204);
		when(response.message()).thenReturn("No Content");
		when(response.headers()).thenReturn(headers);
		when(headers.toMultimap()).thenReturn(Collections.emptyMap());
		when(response.body()).thenReturn(null);
		when(call.execute()).thenReturn(response);

		try (MockedStatic<CookieManager> cookieManagerStatic = mockStatic(CookieManager.class)) {
			final CookieManager cookieManager = mock(CookieManager.class);
			cookieManagerStatic.when(CookieManager::getInstance).thenReturn(cookieManager);
			when(cookieManager.getCookie(WATCH_URL)).thenReturn("");

			final org.schabi.newpipe.extractor.downloader.Request request =
							new org.schabi.newpipe.extractor.downloader.Request(
											"GET",
											WATCH_URL,
											Collections.emptyMap(),
											null,
											null,
											false);

			final org.schabi.newpipe.extractor.downloader.Response result = downloader.execute(request);

			assertEquals(204, result.responseCode());
			assertEquals("No Content", result.responseMessage());
			assertEquals("", result.responseBody());
			assertEquals(WATCH_URL, result.latestUrl());
		}
	}

	@Test
	public void execute_wrapsCancelledSessionException() throws Exception {
		final IOException cause = new IOException("connection dropped");
		when(call.execute()).thenThrow(cause);

		final ExtractionSession session = new ExtractionSession();
		session.cancel();
		try (MockedStatic<CookieManager> cookieManagerStatic = mockStatic(CookieManager.class)) {
			final CookieManager cookieManager = mock(CookieManager.class);
			cookieManagerStatic.when(CookieManager::getInstance).thenReturn(cookieManager);
			when(cookieManager.getCookie(WATCH_URL)).thenReturn("");

			final org.schabi.newpipe.extractor.downloader.Request request =
							new org.schabi.newpipe.extractor.downloader.Request(
											"GET",
											WATCH_URL,
											Collections.emptyMap(),
											null,
											null,
											false);

			final InterruptedIOException interrupted = assertThrows(InterruptedIOException.class,
							() -> downloader.withExtractionSession(() -> downloader.execute(request), session));
			assertEquals("Extraction canceled", interrupted.getMessage());
			assertEquals(cause, interrupted.getCause());
		}
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
