package com.hhst.youtubelite.extractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import org.junit.Test;
import org.mockito.Answers;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public class YoutubeExtractorTest {
	private static final String VIDEO_ID = "mAdodMaERp0";
	private static final String WATCH_URL = "https://m.youtube.com/watch?v=" + VIDEO_ID;
	private static final String FINGERPRINT = "fp-a";

	@Test
	public void getVideoId_supportsCommonYoutubeUrlShapes() {
		assertEquals(VIDEO_ID, YoutubeExtractor.getVideoId("https://m.youtube.com/watch?v=" + VIDEO_ID));
		assertEquals(VIDEO_ID, YoutubeExtractor.getVideoId("https://youtu.be/" + VIDEO_ID));
		assertEquals(VIDEO_ID, YoutubeExtractor.getVideoId("https://www.youtube.com/shorts/" + VIDEO_ID));
	}

	@Test
	public void getVideoId_returnsNullForUnsupportedUrls() {
		assertNull(YoutubeExtractor.getVideoId("https://example.com/video"));
		assertNull(YoutubeExtractor.getVideoId("https://youtu.be/too-short"));
		assertNull(YoutubeExtractor.getVideoId(null));
	}

	@Test
	public void getPlaybackDetails_fetchesOnceAndCachesVideoDetailsWhenCacheMisses() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final AtomicInteger fetchCount = new AtomicInteger();
		final StreamInfo streamInfo = mockStreamInfo("fresh-title", "https://example.com/dash.mpd", StreamType.VIDEO_STREAM);
		when(cache.contains(VIDEO_ID)).thenReturn(false);

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 10_000L,
						(videoId, session) -> {
							fetchCount.incrementAndGet();
							return streamInfo;
						});

		final PlaybackDetails playbackDetails = extractor.getPlaybackDetails(WATCH_URL, new ExtractionSession());

		assertEquals(1, fetchCount.get());
		assertEquals("fresh-title", playbackDetails.getVideoDetails().getTitle());
		assertEquals("https://example.com/dash.mpd", playbackDetails.getStreamDetails().getDashUrl());
		verify(cache).encode(eq(VIDEO_ID), anyString(), eq(3600));
		assertEquals("https://example.com/dash.mpd", memoryCache.get(VIDEO_ID, FINGERPRINT, 10_001L).getDashUrl());
	}

	@Test
	public void getPlaybackDetails_usesCachedVideoDetailsAndStillFetchesFreshStreamInfo() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final AtomicInteger fetchCount = new AtomicInteger();
		final VideoDetails cachedVideoDetails = cachedVideoDetails("cached-title");
		final StreamInfo streamInfo = mockStreamInfo("fresh-title", "https://example.com/fresh-dash.mpd", StreamType.VIDEO_STREAM);
		when(cache.contains(VIDEO_ID)).thenReturn(true);
		when(cache.decodeString(VIDEO_ID, null)).thenReturn(gson.toJson(cachedVideoDetails));

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 20_000L,
						(videoId, session) -> {
							fetchCount.incrementAndGet();
							return streamInfo;
						});

		final PlaybackDetails playbackDetails = extractor.getPlaybackDetails(WATCH_URL, new ExtractionSession());

		assertEquals(1, fetchCount.get());
		assertSame(StreamType.VIDEO_STREAM, playbackDetails.getStreamDetails().getStreamType());
		assertEquals("cached-title", playbackDetails.getVideoDetails().getTitle());
		assertEquals("https://example.com/fresh-dash.mpd", playbackDetails.getStreamDetails().getDashUrl());
		verify(cache, never()).encode(eq(VIDEO_ID), anyString(), eq(3600));
		assertEquals("https://example.com/fresh-dash.mpd", memoryCache.get(VIDEO_ID, FINGERPRINT, 20_001L).getDashUrl());
	}

	@Test
	public void getPlaybackDetails_usesPersistentVideoCacheAndMemoryStreamCacheWithoutFetching() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final AtomicInteger fetchCount = new AtomicInteger();
		when(cache.contains(VIDEO_ID)).thenReturn(true);
		when(cache.decodeString(VIDEO_ID, null)).thenReturn(gson.toJson(cachedVideoDetails("cached-title")));
		memoryCache.put(VIDEO_ID, FINGERPRINT, streamDetails("https://example.com/cached-dash.mpd", StreamType.VIDEO_STREAM), 5_000L);

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 6_000L,
						(videoId, session) -> {
							fetchCount.incrementAndGet();
							return mockStreamInfo("fresh-title", "https://example.com/fresh-dash.mpd", StreamType.VIDEO_STREAM);
						});

		final PlaybackDetails playbackDetails = extractor.getPlaybackDetails(WATCH_URL, new ExtractionSession());

		assertEquals(0, fetchCount.get());
		assertEquals("cached-title", playbackDetails.getVideoDetails().getTitle());
		assertEquals("https://example.com/cached-dash.mpd", playbackDetails.getStreamDetails().getDashUrl());
		verify(cache, never()).encode(eq(VIDEO_ID), anyString(), eq(3600));
	}

	@Test
	public void getPlaybackDetails_videoMissStreamHit_forcesFreshExtraction() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final AtomicInteger fetchCount = new AtomicInteger();
		when(cache.contains(VIDEO_ID)).thenReturn(false);
		memoryCache.put(VIDEO_ID, FINGERPRINT, streamDetails("https://example.com/stale-dash.mpd", StreamType.VIDEO_STREAM), 8_000L);

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 9_000L,
						(videoId, session) -> {
							fetchCount.incrementAndGet();
							return mockStreamInfo("fresh-title", "https://example.com/fresh-dash.mpd", StreamType.VIDEO_STREAM);
						});

		final PlaybackDetails playbackDetails = extractor.getPlaybackDetails(WATCH_URL, new ExtractionSession());

		assertEquals(1, fetchCount.get());
		assertEquals("https://example.com/fresh-dash.mpd", playbackDetails.getStreamDetails().getDashUrl());
		verify(cache).encode(eq(VIDEO_ID), anyString(), eq(3600));
	}

	@Test
	public void getPlaybackDetails_fingerprintChange_skipsPreviousStreamCacheEntry() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, "fp-new");
		final AtomicInteger fetchCount = new AtomicInteger();
		when(cache.contains(VIDEO_ID)).thenReturn(true);
		when(cache.decodeString(VIDEO_ID, null)).thenReturn(gson.toJson(cachedVideoDetails("cached-title")));
		memoryCache.put(VIDEO_ID, "fp-old", streamDetails("https://example.com/stale-dash.mpd", StreamType.VIDEO_STREAM), 9_000L);

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 10_000L,
						(videoId, session) -> {
							fetchCount.incrementAndGet();
							return mockStreamInfo("fresh-title", "https://example.com/fresh-dash.mpd", StreamType.VIDEO_STREAM);
						});

		final PlaybackDetails playbackDetails = extractor.getPlaybackDetails(WATCH_URL, new ExtractionSession());

		assertEquals(1, fetchCount.get());
		assertEquals("https://example.com/fresh-dash.mpd", playbackDetails.getStreamDetails().getDashUrl());
	}

	@Test
	public void getPlaybackDetails_nonCacheableContext_bypassesMemoryCache() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(false, false, FINGERPRINT);
		final AtomicInteger fetchCount = new AtomicInteger();
		when(cache.contains(VIDEO_ID)).thenReturn(true);
		when(cache.decodeString(VIDEO_ID, null)).thenReturn(gson.toJson(cachedVideoDetails("cached-title")));
		memoryCache.put(VIDEO_ID, FINGERPRINT, streamDetails("https://example.com/stale-dash.mpd", StreamType.VIDEO_STREAM), 9_000L);

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 10_000L,
						(videoId, session) -> {
							fetchCount.incrementAndGet();
							return mockStreamInfo("fresh-title", "https://example.com/fresh-dash.mpd", StreamType.VIDEO_STREAM);
						});

		final PlaybackDetails playbackDetails = extractor.getPlaybackDetails(WATCH_URL, new ExtractionSession());

		assertEquals(1, fetchCount.get());
		assertEquals("https://example.com/fresh-dash.mpd", playbackDetails.getStreamDetails().getDashUrl());
		assertEquals("https://example.com/stale-dash.mpd", memoryCache.get(VIDEO_ID, FINGERPRINT, 10_001L).getDashUrl());
	}

	@Test
	public void getVideoInfo_returnsCachedDetailsWithoutFetching() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final AtomicInteger fetchCount = new AtomicInteger();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final VideoDetails cachedVideoDetails = cachedVideoDetails("cached-only");
		when(cache.contains(VIDEO_ID)).thenReturn(true);
		when(cache.decodeString(VIDEO_ID, null)).thenReturn(gson.toJson(cachedVideoDetails));

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 10_000L,
						(videoId, session) -> {
							fetchCount.incrementAndGet();
							return mockStreamInfo("unused", "https://example.com/unused.mpd", StreamType.VIDEO_STREAM);
						});

		final VideoDetails videoDetails = extractor.getVideoInfo(WATCH_URL);

		assertEquals(0, fetchCount.get());
		assertEquals("cached-only", videoDetails.getTitle());
	}

	@Test
	public void getPlaybackDetails_doesNotFetchWhenSessionAlreadyCancelled() {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final AtomicInteger fetchCount = new AtomicInteger();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final ExtractionSession session = new ExtractionSession();
		session.cancel();

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 10_000L,
						(videoId, ignoredSession) -> {
							fetchCount.incrementAndGet();
							return mockStreamInfo("unused", "https://example.com/unused.mpd", StreamType.VIDEO_STREAM);
						});

		assertThrows(InterruptedException.class, () -> extractor.getPlaybackDetails(WATCH_URL, session));
		assertEquals(0, fetchCount.get());
		assertNull(memoryCache.get(VIDEO_ID, FINGERPRINT, 10_001L));
		verify(cache, never()).encode(eq(VIDEO_ID), anyString(), eq(3600));
	}

	@Test
	public void getPlaybackDetails_cancelledAfterStreamCacheHitBeforeReturn_throws() {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final ExtractionSession session = new ExtractionSession();
		when(cache.contains(VIDEO_ID)).thenReturn(true);
		when(cache.decodeString(VIDEO_ID, null)).thenReturn(gson.toJson(cachedVideoDetails("cached-title")));
		memoryCache.put(VIDEO_ID, FINGERPRINT, streamDetails("https://example.com/cached-dash.mpd", StreamType.VIDEO_STREAM), 9_000L);

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						new LongSupplier() {
							private boolean first = true;

							@Override
							public long getAsLong() {
								if (first) {
									first = false;
									session.cancel();
								}
								return 10_000L;
							}
						},
						(videoId, activeSession) -> mockStreamInfo("unused", "https://example.com/unused.mpd", StreamType.VIDEO_STREAM));

		assertThrows(InterruptedException.class, () -> extractor.getPlaybackDetails(WATCH_URL, session));
	}

	@Test
	public void getPlaybackDetails_discardsFetchedResultWhenSessionCancelsMidFlight() {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final AtomicInteger fetchCount = new AtomicInteger();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final ExtractionSession session = new ExtractionSession();

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 10_000L,
						(videoId, activeSession) -> {
							fetchCount.incrementAndGet();
							activeSession.cancel();
							return mockStreamInfo("unused", "https://example.com/unused.mpd", StreamType.VIDEO_STREAM);
						});

		assertThrows(InterruptedException.class, () -> extractor.getPlaybackDetails(WATCH_URL, session));
		assertEquals(1, fetchCount.get());
		verify(cache, never()).encode(eq(VIDEO_ID), anyString(), eq(3600));
		assertNull(memoryCache.get(VIDEO_ID, FINGERPRINT, 10_001L));
	}

	@Test
	public void getPlaybackDetails_liveStreams_areNotInsertedIntoMemoryCache() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		when(cache.contains(VIDEO_ID)).thenReturn(false);

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 10_000L,
						(videoId, activeSession) -> mockStreamInfo("live-title", "https://example.com/live.m3u8", StreamType.LIVE_STREAM));

		final PlaybackDetails playbackDetails = extractor.getPlaybackDetails(WATCH_URL, new ExtractionSession());

		assertSame(StreamType.LIVE_STREAM, playbackDetails.getStreamDetails().getStreamType());
		assertNull(memoryCache.get(VIDEO_ID, FINGERPRINT, 10_001L));
	}

	@Test
	public void getPlaybackDetails_returnsMutableAudioListForPlaybackReordering() throws Exception {
		final MMKV cache = mock(MMKV.class);
		final Gson gson = new Gson();
		final PlaybackDetailsMemoryCache memoryCache = new PlaybackDetailsMemoryCache(16, 300_000L);
		final TestPlaybackCacheContextProvider contextProvider = new TestPlaybackCacheContextProvider(true, true, FINGERPRINT);
		final StreamInfo streamInfo = mockStreamInfo("fresh-title", "https://example.com/dash.mpd", StreamType.VIDEO_STREAM);
		final AudioStream audioStream = mock(AudioStream.class);
		when(cache.contains(VIDEO_ID)).thenReturn(false);
		when(audioStream.getFormat()).thenReturn(MediaFormat.M4A);
		when(audioStream.getAverageBitrate()).thenReturn(128);
		when(audioStream.getBitrate()).thenReturn(128);
		when(streamInfo.getAudioStreams()).thenReturn(List.of(audioStream));

		final YoutubeExtractor extractor = newExtractor(
						cache,
						gson,
						memoryCache,
						contextProvider,
						() -> 10_000L,
						(videoId, session) -> streamInfo);

		final PlaybackDetails playbackDetails = extractor.getPlaybackDetails(WATCH_URL, new ExtractionSession());

		playbackDetails.getStreamDetails().getAudioStreams().clear();
		assertEquals(0, playbackDetails.getStreamDetails().getAudioStreams().size());
	}

	private static YoutubeExtractor newExtractor(final MMKV cache,
	                                            final Gson gson,
	                                            final PlaybackDetailsMemoryCache memoryCache,
	                                            final TestPlaybackCacheContextProvider contextProvider,
	                                            final LongSupplier clock,
	                                            final StreamInfoFetcher fetcher) {
		return new YoutubeExtractor(cache, gson, memoryCache, fetcher, contextProvider, clock);
	}

	private static VideoDetails cachedVideoDetails(final String title) {
		final VideoDetails videoDetails = new VideoDetails();
		videoDetails.setId(VIDEO_ID);
		videoDetails.setTitle(title);
		videoDetails.setAuthor("cached-author");
		return videoDetails;
	}

	private static StreamDetails streamDetails(final String dashUrl, final StreamType streamType) {
		return new StreamDetails(
						new ArrayList<>(List.of(mock(VideoStream.class))),
						new ArrayList<>(List.of(mock(AudioStream.class))),
						new ArrayList<>(List.of(mock(SubtitlesStream.class))),
						dashUrl,
						dashUrl.replace("dash", "hls"),
						streamType);
	}

	private static StreamInfo mockStreamInfo(final String title,
	                                         final String dashUrl,
	                                         final StreamType streamType) {
		final StreamInfo streamInfo = mock(StreamInfo.class, Answers.RETURNS_DEEP_STUBS);
		when(streamInfo.getId()).thenReturn(VIDEO_ID);
		when(streamInfo.getName()).thenReturn(title);
		when(streamInfo.getUploaderName()).thenReturn("author");
		when(streamInfo.getDescription().getContent()).thenReturn("description");
		when(streamInfo.getDuration()).thenReturn(42L);
		when(streamInfo.getThumbnails()).thenReturn(Collections.emptyList());
		when(streamInfo.getLikeCount()).thenReturn(7L);
		when(streamInfo.getDislikeCount()).thenReturn(1L);
		when(streamInfo.getUploadDate().offsetDateTime().toInstant()).thenReturn(Instant.EPOCH);
		when(streamInfo.getUploaderUrl()).thenReturn("https://example.com/channel");
		when(streamInfo.getUploaderAvatars()).thenReturn(Collections.emptyList());
		when(streamInfo.getViewCount()).thenReturn(99L);
		when(streamInfo.getStreamSegments()).thenReturn(Collections.emptyList());
		when(streamInfo.getVideoOnlyStreams()).thenReturn(Collections.emptyList());
		when(streamInfo.getAudioStreams()).thenReturn(Collections.emptyList());
		when(streamInfo.getSubtitles()).thenReturn(Collections.emptyList());
		when(streamInfo.getDashMpdUrl()).thenReturn(dashUrl);
		when(streamInfo.getHlsUrl()).thenReturn("https://example.com/hls.m3u8");
		when(streamInfo.getStreamType()).thenReturn(streamType);
		return streamInfo;
	}
}

final class TestPlaybackCacheContextProvider implements PlaybackCacheContextProvider {
	private final boolean canUse;
	private final boolean canPopulate;
	private final String fingerprint;

	TestPlaybackCacheContextProvider(final boolean canUse,
	                                 final boolean canPopulate,
	                                 final String fingerprint) {
		this.canUse = canUse;
		this.canPopulate = canPopulate;
		this.fingerprint = fingerprint;
	}

	@Override
	public boolean canUsePlaybackMemoryCache(@NonNull final String url) {
		return canUse;
	}

	@Override
	@NonNull
	public String buildRequestContextFingerprint(@NonNull final String url) {
		return fingerprint;
	}

	@Override
	public boolean canPopulatePlaybackMemoryCache(@Nullable final ExtractionSession session) {
		return canPopulate;
	}

	@Override
	public void clearPlaybackMemoryCacheSession(@Nullable final ExtractionSession session) {
	}
}
