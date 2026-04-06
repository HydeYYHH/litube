package com.hhst.youtubelite.di;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.SimpleCache;

import com.hhst.youtubelite.util.WebResourceUtils;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class CommonModuleTest {

	private CommonModule commonModule;
	private Context context;
	private File cacheRoot;

	@Before
	public void setUp() throws Exception {
		commonModule = new CommonModule();
		cacheRoot = Files.createTempDirectory("common-module-cache").toFile();
		context = mock(Context.class);
		when(context.getCacheDir()).thenReturn(cacheRoot);
		when(context.getApplicationContext()).thenReturn(context);
		when(context.getDatabasePath(anyString())).thenAnswer(invocation -> new File(cacheRoot, invocation.getArgument(0)));
	}

	@After
	public void tearDown() {
		FileUtils.deleteQuietly(cacheRoot);
	}

	@Test
	public void provideOkHttpClient_usesReusableConnectionPool() throws Exception {
		final AtomicReference<List<?>> connectionPoolCtorArgs = new AtomicReference<>();
		try (MockedConstruction<ConnectionPool> connectionPoolConstruction = mockConstruction(
						ConnectionPool.class,
						(mock, invocation) -> connectionPoolCtorArgs.set(invocation.arguments()))) {
			final OkHttpClient client = commonModule.provideOkHttpClient(context);

			assertSame(connectionPoolConstruction.constructed().get(0), client.connectionPool());
			final List<?> args = connectionPoolCtorArgs.get();
			assertNotNull(args);
			assertEquals(3, args.size());
			assertEquals(CommonModule.OKHTTP_MAX_IDLE_CONNECTIONS, args.get(0));
			assertEquals(CommonModule.OKHTTP_KEEP_ALIVE_MINUTES, args.get(1));
			assertSame(TimeUnit.MINUTES, args.get(2));
		}
	}

	@Test
	public void provideOkHttpClient_usesModerateWebConcurrencyLimits() throws Exception {
		final OkHttpClient client = commonModule.provideOkHttpClient(context);
		final Dispatcher dispatcher = client.dispatcher();

		assertEquals(64, dispatcher.getMaxRequests());
		assertEquals(12, dispatcher.getMaxRequestsPerHost());
	}

	@Test
	public void provideOkHttpClient_prefersBalancedInteractiveTimeouts() {
		final OkHttpClient client = commonModule.provideOkHttpClient(context);

		assertEquals(10_000, client.connectTimeoutMillis());
		assertEquals(12_000, client.writeTimeoutMillis());
		assertEquals(12_000, client.readTimeoutMillis());
		assertEquals(20_000, client.callTimeoutMillis());
	}

	@Test
	public void provideOkHttpClient_preservesDiskCacheBudget() {
		final OkHttpClient client = commonModule.provideOkHttpClient(context);
		final Cache cache = client.cache();

		assertNotNull(cache);
		assertEquals(256L * 1024L * 1024L, cache.maxSize());
	}

	@Test
	public void provideOkHttpClient_preservesForceCacheInterceptorForCacheableResources() throws Exception {
		final OkHttpClient client = commonModule.provideOkHttpClient(context);
		final Interceptor interceptor = client.networkInterceptors().get(0);
		final Request request = new Request.Builder()
						.url("https://example.com/assets/app.js")
						.build();
		final Response response = response(
						request,
						"Pragma", "no-cache",
						"Cache-Control", "no-cache");
		final Interceptor.Chain chain = mock(Interceptor.Chain.class);
		when(chain.request()).thenReturn(request);
		when(chain.proceed(request)).thenReturn(response);

		final Response intercepted = interceptor.intercept(chain);

		assertNull(intercepted.header("Pragma"));
		assertEquals(
						"public, max-age=" + WebResourceUtils.WEBVIEW_CACHE_MAX_AGE_SECONDS + ", immutable",
						intercepted.header("Cache-Control"));
		assertEquals("no-cache", intercepted.header(WebResourceUtils.ORIGINAL_CACHE_CONTROL_HEADER));
	}

	@Test
	public void provideOkHttpClient_preservesForceCacheInterceptorForNonCacheableResources() throws Exception {
		final OkHttpClient client = commonModule.provideOkHttpClient(context);
		final Interceptor interceptor = client.networkInterceptors().get(0);
		final Request request = new Request.Builder()
						.url("https://example.com/api/player")
						.build();
		final Response response = response(request, "Cache-Control", "no-store");
		final Interceptor.Chain chain = mock(Interceptor.Chain.class);
		when(chain.request()).thenReturn(request);
		when(chain.proceed(request)).thenReturn(response);

		final Response intercepted = interceptor.intercept(chain);

		assertSame(response, intercepted);
	}

	@Test
	public void provideOkHttpClient_enablesRedirectsAndConnectionRetry() {
		final OkHttpClient client = commonModule.provideOkHttpClient(context);

		assertTrue(client.followRedirects());
		assertTrue(client.followSslRedirects());
		assertTrue(client.retryOnConnectionFailure());
	}

	@Test
	public void provideExecutor_usesBoundedThreadPoolShape() {
		final Executor provided = commonModule.provideExecutor();
		assertTrue(provided instanceof ThreadPoolExecutor);

		final ThreadPoolExecutor executor = (ThreadPoolExecutor) provided;
		try {
			assertEquals(4, executor.getCorePoolSize());
			assertEquals(12, executor.getMaximumPoolSize());
			assertEquals(30L, executor.getKeepAliveTime(TimeUnit.SECONDS));
			assertEquals(32, executor.getQueue().remainingCapacity() + executor.getQueue().size());
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	public void createOkHttpCache_placesCacheUnderOkHttpDirectory() {
		final Cache cache = commonModule.createOkHttpCache(context);

		assertEquals(new File(cacheRoot, "okhttp").getAbsolutePath(), cache.directory().getAbsolutePath());
		assertEquals(CommonModule.OKHTTP_CACHE_BYTES, cache.maxSize());
	}

	@Test
	public void createDispatcher_appliesRequestedLimits() {
		final Dispatcher dispatcher = commonModule.createDispatcher(17, 5);

		assertEquals(17, dispatcher.getMaxRequests());
		assertEquals(5, dispatcher.getMaxRequestsPerHost());
	}

	@Test
	public void provideOkHttpClient_skipsForceCacheInterceptorForPostRequests() throws Exception {
		final OkHttpClient client = commonModule.provideOkHttpClient(context);
		final Interceptor interceptor = client.networkInterceptors().get(0);
		final Request request = new Request.Builder()
						.url("https://example.com/assets/app.js")
						.post(RequestBody.create(new byte[]{0x1}))
						.build();
		final Response response = response(request, "Cache-Control", "no-cache");
		final Interceptor.Chain chain = mock(Interceptor.Chain.class);
		when(chain.request()).thenReturn(request);
		when(chain.proceed(request)).thenReturn(response);

		final Response intercepted = interceptor.intercept(chain);

		assertSame(response, intercepted);
	}

	@Test
	public void provideSimpleCache_usesUpdatedCacheBudget() throws Exception {
		final AtomicReference<List<?>> simpleCacheCtorArgs = new AtomicReference<>();
		final AtomicReference<List<?>> databaseProviderCtorArgs = new AtomicReference<>();
		final AtomicReference<List<?>> evictorCtorArgs = new AtomicReference<>();
		try (MockedConstruction<StandaloneDatabaseProvider> dbProviderConstruction = mockConstruction(
						StandaloneDatabaseProvider.class,
						(mock, invocation) -> databaseProviderCtorArgs.set(invocation.arguments()));
		     MockedConstruction<LeastRecentlyUsedCacheEvictor> evictorConstruction = mockConstruction(
							LeastRecentlyUsedCacheEvictor.class,
							(mock, invocation) -> evictorCtorArgs.set(invocation.arguments()));
		     MockedConstruction<SimpleCache> simpleCacheConstruction = mockConstruction(
							SimpleCache.class,
							(mock, invocation) -> simpleCacheCtorArgs.set(invocation.arguments()))) {
			final SimpleCache provided = commonModule.provideSimpleCache(context);
			assertSame(simpleCacheConstruction.constructed().get(0), provided);

			final List<?> args = simpleCacheCtorArgs.get();
			assertNotNull(args);
			assertEquals(3, args.size());
			assertEquals(new File(cacheRoot, "player").getAbsolutePath(), ((File) args.get(0)).getAbsolutePath());
			assertSame(evictorConstruction.constructed().get(0), args.get(1));
			assertSame(dbProviderConstruction.constructed().get(0), args.get(2));

			final List<?> dbArgs = databaseProviderCtorArgs.get();
			assertNotNull(dbArgs);
			assertEquals(1, dbArgs.size());
			assertSame(context, dbArgs.get(0));

			final List<?> evictorArgs = evictorCtorArgs.get();
			assertNotNull(evictorArgs);
			assertEquals(1, evictorArgs.size());
			assertEquals(CommonModule.PLAYER_CACHE_BYTES, evictorArgs.get(0));
		}
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
