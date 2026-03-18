package com.hhst.youtubelite.downloader.core.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.tencent.mmkv.MMKV;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class StreamDownloaderImplTest {
	private static final long TEST_CHUNK_TOTAL_BYTES = 4L * 512L * 1024L;
	private static final String TEST_URL = "https://example.com/video";

	private StreamDownloaderImpl downloader;
	private File cacheRoot;

	@Before
	public void setUp() throws Exception {
		cacheRoot = Files.createTempDirectory("stream-downloader-test").toFile();
		downloader = new StreamDownloaderImpl(new OkHttpClient.Builder().build(), mock(MMKV.class));
	}

	@After
	public void tearDown() throws Exception {
		shutdown(downloader);
		FileUtils.deleteQuietly(cacheRoot);
	}

	@Test
	public void setMaxThreadCount_canIncreaseAboveInitialMaximum() throws Exception {
		downloader.setMaxThreadCount(16);

		final ThreadPoolExecutor executor = getExecutor();
		assertEquals(16, executor.getCorePoolSize());
		assertEquals(16, executor.getMaximumPoolSize());
	}

	@Test
	public void setMaxThreadCount_canShrinkAfterGrowing() throws Exception {
		downloader.setMaxThreadCount(16);
		downloader.setMaxThreadCount(2);

		final ThreadPoolExecutor executor = getExecutor();
		assertEquals(2, executor.getCorePoolSize());
		assertEquals(2, executor.getMaximumPoolSize());
	}

	@Test
	public void setMaxThreadCount_updatesDispatcherLimitsToMatchRequestedConcurrency() throws Exception {
		downloader.setMaxThreadCount(12);

		final Dispatcher dispatcher = getClient(downloader).dispatcher();
		assertEquals(12, getDispatcherMaxRequests(dispatcher));
		assertEquals(12, getDispatcherMaxRequestsPerHost(dispatcher));
	}

	@Test
	public void headFailure_completesExceptionally() throws Exception {
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 403, "forbidden".getBytes(StandardCharsets.UTF_8));
			}
			return response(request, 200, "ok".getBytes(StandardCharsets.UTF_8));
		});
		final File output = Files.createTempFile("stream-downloader-head", ".tmp").toFile();

		try {
			final ExecutionException failure = assertThrows(ExecutionException.class,
							() -> downloader.download(TEST_URL, output, null).get(5, TimeUnit.SECONDS));

			assertTrue(rootCause(failure).getMessage().contains("HEAD 403"));
		} finally {
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void chunkGetFailure_completesExceptionally() throws Exception {
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0], "Content-Length", String.valueOf(TEST_CHUNK_TOTAL_BYTES), "Accept-Ranges", "bytes");
			}
			if ("bytes=0-524287".equals(request.header("Range"))) {
				return response(request, 403, "stale".getBytes(StandardCharsets.UTF_8));
			}
			return response(request, 206, "x".getBytes(StandardCharsets.UTF_8));
		});
		final File output = Files.createTempFile("stream-downloader-get", ".tmp").toFile();

		try {
			final ExecutionException failure = assertThrows(ExecutionException.class,
							() -> downloader.download(TEST_URL, output, null).get(5, TimeUnit.SECONDS));

			assertTrue(rootCause(failure).getMessage().contains("GET 403"));
		} finally {
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void constructor_buildsDedicatedFiniteTimeoutClientWithoutCache() throws Exception {
		final ConnectionPool pool = new ConnectionPool(8, 5, TimeUnit.MINUTES);
		final Cache cache = new Cache(new File(cacheRoot, "okhttp"), 1024L);
		final OkHttpClient baseClient = new OkHttpClient.Builder()
						.connectionPool(pool)
						.cache(cache)
						.callTimeout(20, TimeUnit.SECONDS)
						.build();
		final StreamDownloaderImpl configured = new StreamDownloaderImpl(baseClient, mock(MMKV.class));

		try {
			final OkHttpClient client = getClient(configured);

			assertNull(client.cache());
			assertEquals(15_000, client.connectTimeoutMillis());
			assertEquals(15_000, client.writeTimeoutMillis());
			assertEquals(30_000, client.readTimeoutMillis());
			assertEquals(0, client.callTimeoutMillis());
			assertNotSame(baseClient.dispatcher(), client.dispatcher());
			assertEquals(8, getDispatcherMaxRequests(client.dispatcher()));
			assertEquals(4, getDispatcherMaxRequestsPerHost(client.dispatcher()));
			assertEquals(baseClient.connectionPool(), client.connectionPool());
		} finally {
			shutdown(configured);
		}
	}

	private ThreadPoolExecutor getExecutor() throws Exception {
		return getExecutor(downloader);
	}

	private OkHttpClient getClient(final StreamDownloaderImpl target) throws Exception {
		final Field field = StreamDownloaderImpl.class.getDeclaredField("client");
		field.setAccessible(true);
		return (OkHttpClient) field.get(target);
	}

	private ThreadPoolExecutor getExecutor(final StreamDownloaderImpl target) throws Exception {
		final Field field = StreamDownloaderImpl.class.getDeclaredField("executor");
		field.setAccessible(true);
		return (ThreadPoolExecutor) field.get(target);
	}

	private int getDispatcherMaxRequests(final Dispatcher dispatcher) throws Exception {
		final Field field = Dispatcher.class.getDeclaredField("maxRequests");
		field.setAccessible(true);
		return field.getInt(dispatcher);
	}

	private int getDispatcherMaxRequestsPerHost(final Dispatcher dispatcher) throws Exception {
		final Field field = Dispatcher.class.getDeclaredField("maxRequestsPerHost");
		field.setAccessible(true);
		return field.getInt(dispatcher);
	}

	private void replaceDownloader(final Interceptor interceptor) throws Exception {
		shutdown(downloader);
		downloader = new StreamDownloaderImpl(new OkHttpClient.Builder().addInterceptor(interceptor).build(), mock(MMKV.class));
	}

	private void shutdown(final StreamDownloaderImpl target) throws Exception {
		if (target != null) getExecutor(target).shutdownNow();
	}

	private Response response(final Request request, final int code, final byte[] body, final String... headers) {
		final Response.Builder builder = new Response.Builder()
						.request(request)
						.protocol(Protocol.HTTP_1_1)
						.code(code)
						.message("HTTP " + code)
						.body(ResponseBody.create(body, null));
		for (int i = 0; i < headers.length; i += 2) {
			builder.header(headers[i], headers[i + 1]);
		}
		return builder.build();
	}

	private Throwable rootCause(final Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}
}
