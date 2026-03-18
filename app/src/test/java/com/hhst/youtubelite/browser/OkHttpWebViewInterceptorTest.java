package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

public class OkHttpWebViewInterceptorTest {

	private File cacheRoot;

	@Before
	public void setUp() throws Exception {
		cacheRoot = Files.createTempDirectory("okhttp-webview-interceptor").toFile();
	}

	@After
	public void tearDown() {
		FileUtils.deleteQuietly(cacheRoot);
	}

	@Test
	public void derivedClients_splitDispatchersButReuseConnectionPool() throws Exception {
		final ConnectionPool pool = new ConnectionPool(8, 5, TimeUnit.MINUTES);
		final Cache cache = new Cache(new File(cacheRoot, "okhttp"), 1024L);
		final OkHttpClient baseClient = new OkHttpClient.Builder()
						.connectionPool(pool)
						.cache(cache)
						.build();

		final OkHttpClient resourceClient = OkHttpWebViewInterceptor.createResourceClient(baseClient);
		final OkHttpClient nativeClient = OkHttpWebViewInterceptor.createNativeRequestClient(baseClient);

		assertSame(baseClient.connectionPool(), resourceClient.connectionPool());
		assertSame(baseClient.connectionPool(), nativeClient.connectionPool());
		assertSame(baseClient.cache(), resourceClient.cache());
		assertNull(nativeClient.cache());
		assertNotSame(baseClient.dispatcher(), resourceClient.dispatcher());
		assertNotSame(baseClient.dispatcher(), nativeClient.dispatcher());
		assertNotSame(resourceClient.dispatcher(), nativeClient.dispatcher());
		assertEquals(48, getDispatcherMaxRequests(resourceClient.dispatcher()));
		assertEquals(12, getDispatcherMaxRequestsPerHost(resourceClient.dispatcher()));
		assertEquals(32, getDispatcherMaxRequests(nativeClient.dispatcher()));
		assertEquals(10, getDispatcherMaxRequestsPerHost(nativeClient.dispatcher()));
		assertEquals(6_000, resourceClient.connectTimeoutMillis());
		assertEquals(10_000, resourceClient.writeTimeoutMillis());
		assertEquals(12_000, resourceClient.readTimeoutMillis());
		assertEquals(18_000, resourceClient.callTimeoutMillis());
		assertEquals(6_000, nativeClient.connectTimeoutMillis());
		assertEquals(10_000, nativeClient.writeTimeoutMillis());
		assertEquals(12_000, nativeClient.readTimeoutMillis());
		assertEquals(20_000, nativeClient.callTimeoutMillis());
	}

	@Test
	public void shouldProxyRequest_allowsDynamicYoutubeGetRequests() {
		assertFalse(OkHttpWebViewInterceptor.shouldAttemptCacheLookup(
						false,
						"https://m.youtube.com/youtubei/v1/guide"));
	}

	@Test
	public void shouldProxyRequest_prefersWebViewForBodylessAllowedRequests() {
		assertTrue(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://m.youtube.com/youtubei/v1/guide"));
		assertTrue(OkHttpWebViewInterceptor.shouldProxyRequest(
						"HEAD",
						Map.of(),
						"https://m.youtube.com/youtubei/v1/guide"));
	}

	@Test
	public void bridgeSupport_coversRequestsWebViewCannotSafelyReplay() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"POST",
						Map.of("Content-Type", "application/json"),
						"https://m.youtube.com/youtubei/v1/next"));
		assertTrue(NativeHttpRequestExecutor.supportsBridgeMethod("POST"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"PUT",
						Map.of("Content-Type", "application/json"),
						"https://m.youtube.com/youtubei/v1/log_event"));
		assertTrue(NativeHttpRequestExecutor.supportsBridgeMethod("PUT"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of("Range", "bytes=0-1"),
						"https://m.youtube.com/api/stats/watchtime"));
		assertTrue(NativeHttpRequestExecutor.supportsBridgeMethod("GET"));
	}

	@Test
	public void unsupportedOrOffDomainRequests_doNotMatchProxyOrBridgeHotPath() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"TRACE",
						Map.of(),
						"https://m.youtube.com/youtubei/v1/guide"));
		assertFalse(NativeHttpRequestExecutor.supportsBridgeMethod("TRACE"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://example.com/script.js"));
	}

	@Test
	public void shouldProxyRequest_rejectsOffDomainAndRangeRequests() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://example.com/script.js"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of("Range", "bytes=0-1"),
						"https://m.youtube.com/watch?v=abc"));
	}

	private int getDispatcherMaxRequests(final Dispatcher dispatcher) throws Exception {
		return getField(Dispatcher.class, "maxRequests").getInt(dispatcher);
	}

	private int getDispatcherMaxRequestsPerHost(final Dispatcher dispatcher) throws Exception {
		return getField(Dispatcher.class, "maxRequestsPerHost").getInt(dispatcher);
	}

	private Field getField(final Class<?> type, final String name) throws Exception {
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
