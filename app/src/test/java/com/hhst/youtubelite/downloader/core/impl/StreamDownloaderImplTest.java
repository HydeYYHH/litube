package com.hhst.youtubelite.downloader.core.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.tencent.mmkv.MMKV;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
	private static final int MIN_CHUNK_SIZE_BYTES = 512 * 1024;
	private static final long TEST_CHUNK_TOTAL_BYTES = 4L * 512L * 1024L;
	private static final String TEST_URL = "https://example.com/video";

	private StreamDownloaderImpl downloader;
	private File cacheRoot;
	private MMKV mmkv;

	@Before
	public void setUp() throws Exception {
		cacheRoot = Files.createTempDirectory("stream-downloader-test").toFile();
		mmkv = mock(MMKV.class);
		downloader = new StreamDownloaderImpl(new OkHttpClient.Builder().build(), mmkv);
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
		assertEquals(12, dispatcher.getMaxRequests());
		assertEquals(12, dispatcher.getMaxRequestsPerHost());
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
	public void download_successfulMultiChunkFileRemovesMmkv() throws Exception {
		final NavigableMap<Long, Long> requestedRanges = new ConcurrentSkipListMap<>();
		final NavigableMap<Long, byte[]> chunkData = new ConcurrentSkipListMap<>();
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0],
								"Content-Length", String.valueOf(TEST_CHUNK_TOTAL_BYTES),
								"Accept-Ranges", "bytes");
			}
			final String range = request.header("Range");
			final long start = parseRangeStart(range);
			final long end = parseRangeEnd(range);
			requestedRanges.put(start, end);
			final byte[] chunk = chunkForRange(start, end);
			chunkData.put(start, chunk);
			return response(request, 206, chunk,
							"Content-Length", String.valueOf(chunk.length),
							"Content-Range", "bytes " + start + "-" + end + "/" + TEST_CHUNK_TOTAL_BYTES);
		});
		final File output = Files.createTempFile("stream-downloader-success", ".tmp").toFile();

		try {
			final File result = downloader.download(TEST_URL, output, null).get(5, TimeUnit.SECONDS);
			assertEquals(output.getAbsolutePath(), result.getAbsolutePath());
			final byte[] fileBytes = Files.readAllBytes(output.toPath());
			assertEquals(TEST_CHUNK_TOTAL_BYTES, fileBytes.length);
			assertExpectedRanges(requestedRanges, TEST_CHUNK_TOTAL_BYTES, 0);
			assertDistinctChunks(chunkData);

			for (final Map.Entry<Long, byte[]> entry : chunkData.entrySet()) {
				final int start = entry.getKey().intValue();
				final byte[] expected = entry.getValue();
				assertArrayEquals(expected, Arrays.copyOfRange(fileBytes, start, start + expected.length));
			}

			verify(mmkv).removeValueForKey(dlKey());
			verify(mmkv, atLeastOnce()).encode(eq(dlKey()), any(byte[].class));
		} finally {
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void download_reportsStrictlyIncreasingProgressBeforeCompletion() throws Exception {
		final CopyOnWriteArrayList<Integer> progressEvents = new CopyOnWriteArrayList<>();
		final ProgressCallback callback = new ProgressCallback() {
			@Override
			public void onProgress(final int progress) {
				progressEvents.add(progress);
			}

			@Override
			public void onComplete(final File file) {
			}

			@Override
			public void onError(final Exception error) {
			}

			@Override
			public void onCancel() {
			}
		};
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0],
								"Content-Length", String.valueOf(TEST_CHUNK_TOTAL_BYTES),
								"Accept-Ranges", "bytes");
			}
			final String range = request.header("Range");
			return response(
							request,
							206,
							chunkForRange(parseRangeStart(range), parseRangeEnd(range)),
							"Content-Length", String.valueOf(524288),
							"Content-Range", "bytes " + parseRangeStart(range) + "-" + parseRangeEnd(range) + "/" + TEST_CHUNK_TOTAL_BYTES);
		});
		final File output = Files.createTempFile("stream-downloader-progress", ".tmp").toFile();

		try {
			downloader.download(TEST_URL, output, callback).get(5, TimeUnit.SECONDS);

			assertFalse(progressEvents.isEmpty());
			for (int i = 1; i < progressEvents.size(); i++) {
				assertTrue("progress should increase strictly", progressEvents.get(i) > progressEvents.get(i - 1));
			}
			assertTrue(progressEvents.get(progressEvents.size() - 1) <= 99);
		} finally {
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void download_resumesFromSavedBitmap() throws Exception {
		final BitSet saved = new BitSet();
		saved.set(0);
		when(mmkv.decodeBytes(anyString())).thenReturn(saved.toByteArray());
		final NavigableMap<Long, Long> requestedRanges = new ConcurrentSkipListMap<>();
		final NavigableMap<Long, byte[]> requestedChunks = new ConcurrentSkipListMap<>();
		final NavigableMap<Long, byte[]> expectedChunks = new ConcurrentSkipListMap<>();
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0],
								"Content-Length", String.valueOf(TEST_CHUNK_TOTAL_BYTES),
								"Accept-Ranges", "bytes");
			}
			final String range = request.header("Range");
			final long start = parseRangeStart(range);
			final long end = parseRangeEnd(range);
			requestedRanges.put(start, end);
			final byte[] chunk = chunkForRange(start, end);
			requestedChunks.put(start, chunk);
			expectedChunks.put(start, chunk);
			return response(request, 206, chunk,
							"Content-Length", String.valueOf(chunk.length),
							"Content-Range", "bytes " + start + "-" + end + "/" + TEST_CHUNK_TOTAL_BYTES);
		});
		final File output = Files.createTempFile("stream-downloader-resume", ".tmp").toFile();
		final long partSize = TEST_CHUNK_TOTAL_BYTES / expectedChunkCount();
		final byte[] firstChunk = chunkForRange(0, partSize - 1);
		expectedChunks.put(0L, firstChunk);

		try {
			Files.write(output.toPath(), firstChunk);
			downloader.download(TEST_URL, output, null).get(5, TimeUnit.SECONDS);
			assertExpectedRanges(requestedRanges, TEST_CHUNK_TOTAL_BYTES, 1);
			assertFalse(requestedChunks.containsKey(0L));
			assertDistinctChunks(expectedChunks);
			final byte[] fileBytes = Files.readAllBytes(output.toPath());
			assertEquals(TEST_CHUNK_TOTAL_BYTES, fileBytes.length);
			for (final Map.Entry<Long, byte[]> entry : expectedChunks.entrySet()) {
				final int start = entry.getKey().intValue();
				final byte[] expected = entry.getValue();
				assertArrayEquals(expected, Arrays.copyOfRange(fileBytes, start, start + expected.length));
			}
			verify(mmkv).removeValueForKey(dlKey());
			verify(mmkv, atLeastOnce()).encode(eq(dlKey()), any(byte[].class));
		} finally {
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void download_withoutRangeSupport_ignoresSavedBitmapAndUsesSingleRequest() throws Exception {
		final BitSet saved = new BitSet();
		saved.set(0);
		when(mmkv.decodeBytes(anyString())).thenReturn(saved.toByteArray());
		final CopyOnWriteArrayList<String> rangeHeaders = new CopyOnWriteArrayList<>();
		final byte[] fullBody = chunkForRange(0, TEST_CHUNK_TOTAL_BYTES - 1);
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0],
								"Content-Length", String.valueOf(TEST_CHUNK_TOTAL_BYTES));
			}
			rangeHeaders.add(request.header("Range"));
			return response(request, 200, fullBody,
							"Content-Length", String.valueOf(fullBody.length));
		});
		final File output = Files.createTempFile("stream-downloader-no-range", ".tmp").toFile();
		Files.write(output.toPath(), "stale".getBytes(StandardCharsets.UTF_8));

		try {
			downloader.download(TEST_URL, output, null).get(5, TimeUnit.SECONDS);

			assertEquals(1, rangeHeaders.size());
			assertNull(rangeHeaders.get(0));
			assertArrayEquals(fullBody, Files.readAllBytes(output.toPath()));
			verify(mmkv, never()).encode(eq(dlKey()), any(byte[].class));
			verify(mmkv).removeValueForKey(dlKey());
		} finally {
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void download_unknownLength_overwritesExistingFileWithoutLeavingStaleTail() throws Exception {
		final byte[] freshBody = "fresh-body".getBytes(StandardCharsets.UTF_8);
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0]);
			}
			return response(request, 200, freshBody);
		});
		final File output = Files.createTempFile("stream-downloader-unknown-length", ".tmp").toFile();
		Files.write(output.toPath(), "stale-content-that-is-longer".getBytes(StandardCharsets.UTF_8));

		try {
			downloader.download(TEST_URL, output, null).get(5, TimeUnit.SECONDS);

			assertArrayEquals(freshBody, Files.readAllBytes(output.toPath()));
			verify(mmkv, never()).encode(eq(dlKey()), any(byte[].class));
			verify(mmkv).removeValueForKey(dlKey());
		} finally {
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void pause_thenResume_completesOriginalFutureWithoutCancelOrError() throws Exception {
		final ProgressCallback callback = mock(ProgressCallback.class);
		final CountDownLatch slowChunkStarted = new CountDownLatch(1);
		final CountDownLatch releaseSlowChunks = new CountDownLatch(1);
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0],
								"Content-Length", String.valueOf(TEST_CHUNK_TOTAL_BYTES),
								"Accept-Ranges", "bytes");
			}
			final String range = request.header("Range");
			final long start = parseRangeStart(range);
			final long end = parseRangeEnd(range);
			if (start != 0L) {
				slowChunkStarted.countDown();
				try {
					assertTrue("slow chunk should eventually be released", releaseSlowChunks.await(5, TimeUnit.SECONDS));
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException(e);
				}
			}
			final byte[] chunk = chunkForRange(start, end);
			return response(request, 206, chunk,
							"Content-Length", String.valueOf(chunk.length),
							"Content-Range", "bytes " + start + "-" + end + "/" + TEST_CHUNK_TOTAL_BYTES);
		});
		final File output = Files.createTempFile("stream-downloader-pause-resume", ".tmp").toFile();

		try {
			final CompletableFuture<File> future = downloader.download(TEST_URL, output, callback);

			assertTrue("expected at least one slow chunk to start", slowChunkStarted.await(5, TimeUnit.SECONDS));
			downloader.pause(TEST_URL);
			Thread.sleep(100);
			assertFalse("pause should not complete the original future", future.isDone());

			releaseSlowChunks.countDown();
			Thread.sleep(100);
			downloader.resume(TEST_URL);

			assertEquals(output.getAbsolutePath(), future.get(5, TimeUnit.SECONDS).getAbsolutePath());
			verify(callback, timeout(1_000)).onComplete(output);
			verify(callback, never()).onCancel();
			verify(callback, never()).onError(any(Exception.class));
		} finally {
			releaseSlowChunks.countDown();
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void download_successfulMultiChunkUnevenTailLocksLastRangeBoundary() throws Exception {
		final long totalBytes = TEST_CHUNK_TOTAL_BYTES + 123L;
		final NavigableMap<Long, Long> requestedRanges = new ConcurrentSkipListMap<>();
		final NavigableMap<Long, byte[]> chunkData = new ConcurrentSkipListMap<>();
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0],
								"Content-Length", String.valueOf(totalBytes),
								"Accept-Ranges", "bytes");
			}
			final String range = request.header("Range");
			final long start = parseRangeStart(range);
			final long end = parseRangeEnd(range);
			requestedRanges.put(start, end);
			final byte[] chunk = chunkForRange(start, end);
			chunkData.put(start, chunk);
			return response(request, 206, chunk,
							"Content-Length", String.valueOf(chunk.length),
							"Content-Range", "bytes " + start + "-" + end + "/" + totalBytes);
		});
		final File output = Files.createTempFile("stream-downloader-uneven-tail", ".tmp").toFile();

		try {
			downloader.download(TEST_URL, output, null).get(5, TimeUnit.SECONDS);
			final byte[] fileBytes = Files.readAllBytes(output.toPath());
			assertEquals(totalBytes, fileBytes.length);
			assertExpectedRanges(requestedRanges, totalBytes, 0);
			assertDistinctChunks(chunkData);

			final long partSize = totalBytes / expectedChunkCount(totalBytes);
			final Map.Entry<Long, Long> lastRange = requestedRanges.lastEntry();
			assertEquals(totalBytes - 1, lastRange.getValue().longValue());
			final long lastChunkLength = lastRange.getValue() - lastRange.getKey() + 1;
			assertTrue("last chunk should not match regular chunk length when total is uneven", lastChunkLength != partSize);

			for (final Map.Entry<Long, byte[]> entry : chunkData.entrySet()) {
				final int start = entry.getKey().intValue();
				final byte[] expected = entry.getValue();
				assertArrayEquals(expected, Arrays.copyOfRange(fileBytes, start, start + expected.length));
			}

			verify(mmkv).removeValueForKey(dlKey());
			verify(mmkv, atLeastOnce()).encode(eq(dlKey()), any(byte[].class));
		} finally {
			Files.deleteIfExists(output.toPath());
		}
	}

	@Test
	public void cancel_stopsDownloadAndCleansMmkv() throws Exception {
		replaceDownloader(chain -> {
			final Request request = chain.request();
			if ("HEAD".equals(request.method())) {
				return response(request, 200, new byte[0],
								"Content-Length", String.valueOf(TEST_CHUNK_TOTAL_BYTES),
								"Accept-Ranges", "bytes");
			}
			final String range = request.header("Range");
			final long start = parseRangeStart(range);
			final long end = parseRangeEnd(range);
			final byte[] chunk = chunkForRange(start, end);
			try {
				Thread.sleep(200);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
			return response(request, 206, chunk,
							"Content-Length", String.valueOf(chunk.length),
							"Content-Range", "bytes " + start + "-" + end + "/" + TEST_CHUNK_TOTAL_BYTES);
		});
		final File output = Files.createTempFile("stream-downloader-cancel", ".tmp").toFile();
		output.deleteOnExit();
		final CompletableFuture<File> future = downloader.download(TEST_URL, output, null);
		Thread.sleep(50);
		downloader.cancel(TEST_URL);

		assertThrows(CancellationException.class, () -> future.get(5, TimeUnit.SECONDS));
		verify(mmkv).removeValueForKey(dlKey());
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
			assertEquals(8, client.dispatcher().getMaxRequests());
			assertEquals(4, client.dispatcher().getMaxRequestsPerHost());
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

	private void replaceDownloader(final Interceptor interceptor) throws Exception {
		shutdown(downloader);
		downloader = new StreamDownloaderImpl(new OkHttpClient.Builder().addInterceptor(interceptor).build(), mmkv);
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

	private String dlKey() {
		return "dl_" + md5(TEST_URL);
	}

	private int expectedChunkCount() {
		return expectedChunkCount(TEST_CHUNK_TOTAL_BYTES);
	}

	private int expectedChunkCount(final long totalBytes) {
		final int candidate = (int) Math.min(128, Math.max(4, totalBytes / MIN_CHUNK_SIZE_BYTES));
		return (totalBytes / Math.max(candidate, 1)) > 0 ? candidate : 1;
	}

	private NavigableMap<Long, Long> expectedRanges(final long totalBytes) {
		final int chunkCount = expectedChunkCount(totalBytes);
		final long partSize = totalBytes / chunkCount;
		final NavigableMap<Long, Long> ranges = new ConcurrentSkipListMap<>();
		for (int i = 0; i < chunkCount; i++) {
			final long start = i * partSize;
			final long end = (i == chunkCount - 1) ? (totalBytes - 1) : (start + partSize - 1);
			ranges.put(start, end);
		}
		return ranges;
	}

	private void assertExpectedRanges(final NavigableMap<Long, Long> requestedRanges, final long totalBytes, final int firstChunkIndex) {
		final NavigableMap<Long, Long> expectedRanges = expectedRanges(totalBytes);
		for (int i = 0; i < firstChunkIndex && !expectedRanges.isEmpty(); i++) {
			expectedRanges.pollFirstEntry();
		}
		assertEquals(expectedRanges, requestedRanges);
	}

	private void assertDistinctChunks(final NavigableMap<Long, byte[]> chunkData) {
		for (final Map.Entry<Long, byte[]> left : chunkData.entrySet()) {
			for (final Map.Entry<Long, byte[]> right : chunkData.tailMap(left.getKey(), false).entrySet()) {
				assertFalse("fixture chunks should differ for offsets " + left.getKey() + " and " + right.getKey(),
								Arrays.equals(left.getValue(), right.getValue()));
			}
		}
	}

	private long parseRangeStart(final String range) {
		if (range == null || !range.contains("=")) return 0;
		final String[] parts = range.split("=");
		if (parts.length < 2 || !parts[1].contains("-")) return 0;
		return Long.parseLong(parts[1].split("-")[0]);
	}

	private long parseRangeEnd(final String range) {
		if (range == null || !range.contains("-")) return 0;
		final String[] pieces = range.split("-");
		return Long.parseLong(pieces[1]);
	}

	private byte[] chunkForRange(final long start, final long end) {
		final int length = (int) (end - start + 1);
		final byte[] chunk = new byte[length];
		for (int i = 0; i < length; i++) {
			chunk[i] = (byte) ((((start + i) * 1_315_423_911L) ^ Long.rotateLeft(start, i & 15) ^ end) & 0xFF);
		}
		writeLongPrefix(chunk, 0, start ^ 0x4041424344454647L);
		writeLongPrefix(chunk, Long.BYTES, end ^ 0x5152535455565758L);
		return chunk;
	}

	private void writeLongPrefix(final byte[] target, final int offset, final long value) {
		for (int i = 0; i < Long.BYTES && offset + i < target.length; i++) {
			target[offset + i] = (byte) ((value >>> (i * Byte.SIZE)) & 0xFF);
		}
	}

	private String md5(final String value) {
		try {
			final byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
			final StringBuilder builder = new StringBuilder();
			for (final byte b : digest) {
				builder.append(String.format("%02x", b));
			}
			return builder.toString();
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 unavailable", e);
		}
	}

	private Throwable rootCause(final Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

}
