package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hhst.youtubelite.util.WebResourceUtils;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
	public void resourceClient_usesDedicatedDispatcherAndReusesBaseCacheAndPool() {
		final ConnectionPool pool = new ConnectionPool(8, 5, TimeUnit.MINUTES);
		final Cache cache = new Cache(new File(cacheRoot, "okhttp"), 1024L);
		final OkHttpClient baseClient = new OkHttpClient.Builder()
						.connectionPool(pool)
						.cache(cache)
						.build();

		final OkHttpClient resourceClient = OkHttpWebViewInterceptor.createResourceClient(baseClient);

		assertSame(baseClient.connectionPool(), resourceClient.connectionPool());
		assertSame(baseClient.cache(), resourceClient.cache());
		assertNotSame(baseClient.dispatcher(), resourceClient.dispatcher());
		assertEquals(48, resourceClient.dispatcher().getMaxRequests());
		assertEquals(12, resourceClient.dispatcher().getMaxRequestsPerHost());
		assertEquals(6_000, resourceClient.connectTimeoutMillis());
		assertEquals(10_000, resourceClient.writeTimeoutMillis());
		assertEquals(12_000, resourceClient.readTimeoutMillis());
		assertEquals(18_000, resourceClient.callTimeoutMillis());
	}

	@Test
	public void resourceClient_doesNotRewriteStaticResourceResponses() throws Exception {
		final OkHttpClient resourceClient = OkHttpWebViewInterceptor.createResourceClient(new OkHttpClient.Builder().build());
		final Interceptor interceptor = resourceClient.networkInterceptors().get(0);
		final Request request = requestWithCacheTag(
						"https://www.youtube.com/s/player/12345/base.js",
						true,
						true,
						"text/html");
		final Response response = response(
						request,
						"Cache-Control", "max-age=60",
						"Pragma", "no-cache");
		final Interceptor.Chain chain = mock(Interceptor.Chain.class);
		when(chain.request()).thenReturn(request);
		when(chain.proceed(request)).thenReturn(response);

		assertSame(response, interceptor.intercept(chain));
	}

	@Test
	public void resourceClient_doesNotRewriteSubframeHtmlResponses() throws Exception {
		final OkHttpClient resourceClient = OkHttpWebViewInterceptor.createResourceClient(new OkHttpClient.Builder().build());
		final Interceptor interceptor = resourceClient.networkInterceptors().get(0);
		final Request request = requestWithCacheTag(
						"https://www.youtube.com/embed/abc123",
						false,
						false,
						"text/html");
		final Response response = response(
						request,
						"Cache-Control", "max-age=60",
						"Pragma", "no-cache");
		final Interceptor.Chain chain = mock(Interceptor.Chain.class);
		when(chain.request()).thenReturn(request);
		when(chain.proceed(request)).thenReturn(response);

		assertSame(response, interceptor.intercept(chain));
	}

	@Test
	public void resourceClient_skipsRewriteWhenResponseAlreadyTracksOriginalCacheControl() throws Exception {
		final OkHttpClient resourceClient = OkHttpWebViewInterceptor.createResourceClient(new OkHttpClient.Builder().build());
		final Interceptor interceptor = resourceClient.networkInterceptors().get(0);
		final Request request = requestWithCacheTag(
						"https://www.youtube.com/watch?v=abc123",
						true,
						false,
						"text/html");
		final Response response = response(
						request,
						"Cache-Control", "public, max-age=31536000, immutable",
						WebResourceUtils.ORIGINAL_CACHE_CONTROL_HEADER, "max-age=60");
		final Interceptor.Chain chain = mock(Interceptor.Chain.class);
		when(chain.request()).thenReturn(request);
		when(chain.proceed(request)).thenReturn(response);

		assertSame(response, interceptor.intercept(chain));
	}

	@Test
	public void shouldAttemptCacheLookup_skipsDynamicYoutubeApiRequests() {
		assertFalse(OkHttpWebViewInterceptor.shouldAttemptCacheLookup(
						false,
						"https://m.youtube.com/youtubei/v1/guide"));
	}

	@Test
	public void shouldAttemptCacheLookup_usesStaticResourceRules() {
		assertTrue(OkHttpWebViewInterceptor.shouldAttemptCacheLookup(
						false,
						"https://i.ytimg.com/vi/abc123/hqdefault.webp"));
		assertTrue(OkHttpWebViewInterceptor.shouldAttemptCacheLookup(
						false,
						"https://www.youtube.com/s/player/12345/player_ias.vflset/en_US/base.js"));
		assertFalse(OkHttpWebViewInterceptor.shouldAttemptCacheLookup(
						false,
						"https://www.youtube.com/watch?v=abc123"));
	}

	@Test
	public void resourceClient_doesNotRewriteUntaggedHtmlResponses() throws Exception {
		final OkHttpClient resourceClient = OkHttpWebViewInterceptor.createResourceClient(new OkHttpClient.Builder().build());
		final Interceptor interceptor = resourceClient.networkInterceptors().get(0);
		final Request request = new Request.Builder()
						.url("https://www.youtube.com/watch?v=abc123")
						.header("Accept", "text/html")
						.build();
		final Response response = response(
						request,
						"Cache-Control", "max-age=60",
						"Pragma", "no-cache");
		final Interceptor.Chain chain = mock(Interceptor.Chain.class);
		when(chain.request()).thenReturn(request);
		when(chain.proceed(request)).thenReturn(response);

		assertSame(response, interceptor.intercept(chain));
	}

	@Test
	public void shouldAttemptCacheLookup_rejectsAccountsYoutubeAndMalformedUrls() {
		assertFalse(OkHttpWebViewInterceptor.shouldAttemptCacheLookup(
						true,
						"https://accounts.youtube.com/ServiceLogin"));
		assertFalse(OkHttpWebViewInterceptor.shouldAttemptCacheLookup(
						true,
						"https://www.youtube.com/watch?v=%zz"));
		assertFalse(OkHttpWebViewInterceptor.shouldAttemptCacheLookup(
						false,
						"not-a-url"));
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
		assertTrue(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						null,
						"https://m.youtube.com/youtubei/v1/guide"));
	}

	@Test
	public void shouldProxyRequest_rejectsAuthenticationHosts() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://accounts.google.com/signin/v2/identifier"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://accounts.youtube.com/accounts/CheckConnection"));
	}

	@Test
	public void shouldProxyRequest_rejectsMalformedAndUnsupportedSchemes() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"ftp://www.youtube.com/watch?v=abc"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of(),
						"https://www.youtube.com/watch?v=%zz"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						null,
						Map.of(),
						"https://m.youtube.com/watch?v=abc"));
	}

	@Test
	public void shouldProxyRequest_rejectsNonBodylessAndRangeRequests() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"POST",
						Map.of("Content-Type", "application/json"),
						"https://m.youtube.com/youtubei/v1/next"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"PUT",
						Map.of("Content-Type", "application/json"),
						"https://m.youtube.com/youtubei/v1/log_event"));
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of("Range", "bytes=0-1"),
						"https://m.youtube.com/api/stats/watchtime"));
	}

	@Test
	public void unsupportedOrOffDomainRequests_doNotMatchProxyPath() {
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"TRACE",
						Map.of(),
						"https://m.youtube.com/youtubei/v1/guide"));
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
		assertFalse(OkHttpWebViewInterceptor.shouldProxyRequest(
						"GET",
						Map.of("rAnGe", "bytes=0-1"),
						"https://m.youtube.com/watch?v=abc"));
	}

	private Request requestWithCacheTag(final String url,
	                                    final boolean mainFrame,
	                                    final boolean staticResource,
	                                    final String acceptHeader) throws Exception {
		final Class<?> tagType = Class.forName("com.hhst.youtubelite.browser.OkHttpWebViewInterceptor$CacheRequestInfo");
		final Constructor<?> ctor = tagType.getDeclaredConstructor(boolean.class, boolean.class);
		ctor.setAccessible(true);
		final Object tag = ctor.newInstance(mainFrame, staticResource);

		final Request.Builder builder = new Request.Builder().url(url);
		if (acceptHeader != null) {
			builder.header("Accept", acceptHeader);
		}
		@SuppressWarnings("unchecked")
		final Class<Object> typedTagType = (Class<Object>) tagType;
		return builder.tag(typedTagType, tag).build();
	}

	private Response response(final Request request, final String... headers) {
		final Response.Builder builder = new Response.Builder()
						.request(request)
						.protocol(Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.body(ResponseBody.create(new byte[0], null));
		for (int i = 0; i < headers.length; i += 2) {
			builder.header(headers[i], headers[i + 1]);
		}
		return builder.build();
	}

}
