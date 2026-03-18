package com.hhst.youtubelite.di;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;

import com.hhst.youtubelite.util.WebResourceUtils;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

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
		final OkHttpClient client = commonModule.provideOkHttpClient(context);
		final ConnectionPool pool = client.connectionPool();

		assertEquals(10, getConnectionPoolMaxIdleConnections(pool));
		assertEquals(TimeUnit.MINUTES.toNanos(5), getConnectionPoolKeepAliveDurationNs(pool));
	}

	@Test
	public void provideOkHttpClient_usesModerateWebConcurrencyLimits() throws Exception {
		final OkHttpClient client = commonModule.provideOkHttpClient(context);
		final Dispatcher dispatcher = client.dispatcher();

		assertEquals(64, getDispatcherMaxRequests(dispatcher));
		assertEquals(12, getDispatcherMaxRequestsPerHost(dispatcher));
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
		final LeastRecentlyUsedCacheEvictor evictor = commonModule.createPlayerCacheEvictor();

		assertEquals(CommonModule.PLAYER_CACHE_BYTES, getEvictorBudget(evictor));
	}

	private int getConnectionPoolMaxIdleConnections(final ConnectionPool pool) throws Exception {
		final Object delegate = getField(ConnectionPool.class, "delegate").get(pool);
		return getField(delegate.getClass(), "maxIdleConnections").getInt(delegate);
	}

	private long getConnectionPoolKeepAliveDurationNs(final ConnectionPool pool) throws Exception {
		final Object delegate = getField(ConnectionPool.class, "delegate").get(pool);
		return getField(delegate.getClass(), "keepAliveDurationNs").getLong(delegate);
	}

	private long getEvictorBudget(final LeastRecentlyUsedCacheEvictor evictor) throws Exception {
		return getField(LeastRecentlyUsedCacheEvictor.class, "maxBytes").getLong(evictor);
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
