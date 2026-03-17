package com.hhst.youtubelite.extractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import org.junit.Test;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;

public class PlaybackInfoMemoryCacheTest {

	@Test
	public void putAndGet_returnsDefensiveCopyForFreshEntry() {
		final PlaybackInfoMemoryCache cache = new PlaybackInfoMemoryCache(16, 300_000L);
		final VideoStream videoStream = mock(VideoStream.class);
		final AudioStream audioStream = mock(AudioStream.class);
		final SubtitlesStream subtitlesStream = mock(SubtitlesStream.class);
		final StreamDetails original = new StreamDetails(
				new ArrayList<>(List.of(videoStream)),
				new ArrayList<>(List.of(audioStream)),
				new ArrayList<>(List.of(subtitlesStream)),
				"https://example.com/dash-a.mpd",
				"https://example.com/hls-a.m3u8",
				StreamType.VIDEO_STREAM
		);

		cache.put("video-a", "fp-a", original, 1_000L);

		original.getVideoStreams().clear();
		original.getAudioStreams().clear();
		original.getSubtitles().clear();
		original.setDashUrl("https://example.com/mutated-dash.mpd");
		original.setHlsUrl("https://example.com/mutated-hls.m3u8");

		final StreamDetails firstRead = cache.get("video-a", "fp-a", 2_000L);

		assertNotNull(firstRead);
		assertNotSame(original, firstRead);
		assertEquals(1, firstRead.getVideoStreams().size());
		assertEquals(1, firstRead.getAudioStreams().size());
		assertEquals(1, firstRead.getSubtitles().size());
		assertSame(videoStream, firstRead.getVideoStreams().get(0));
		assertSame(audioStream, firstRead.getAudioStreams().get(0));
		assertSame(subtitlesStream, firstRead.getSubtitles().get(0));
		assertEquals("https://example.com/dash-a.mpd", firstRead.getDashUrl());
		assertEquals("https://example.com/hls-a.m3u8", firstRead.getHlsUrl());

		firstRead.getVideoStreams().clear();
		firstRead.getAudioStreams().clear();
		firstRead.getSubtitles().clear();
		firstRead.setDashUrl("https://example.com/changed-after-read.mpd");

		final StreamDetails secondRead = cache.get("video-a", "fp-a", 3_000L);

		assertNotNull(secondRead);
		assertNotSame(firstRead, secondRead);
		assertEquals(1, secondRead.getVideoStreams().size());
		assertEquals(1, secondRead.getAudioStreams().size());
		assertEquals(1, secondRead.getSubtitles().size());
		assertEquals("https://example.com/dash-a.mpd", secondRead.getDashUrl());
	}

	@Test
	public void expiredEntry_isNotReturned() {
		final PlaybackInfoMemoryCache cache = new PlaybackInfoMemoryCache(16, 1_000L);

		cache.put("video-a", "fp-a", streamDetails("https://example.com/dash-a.mpd", StreamType.VIDEO_STREAM), 1_000L);

		assertNotNull(cache.get("video-a", "fp-a", 1_999L));
		assertNull(cache.get("video-a", "fp-a", 2_001L));
	}

	@Test
	public void defaultConstructor_usesApprovedCapacity() {
		final PlaybackInfoMemoryCache cache = new PlaybackInfoMemoryCache();

		for (int index = 0; index < 17; index++) {
			cache.put("video-" + index, "fp-" + index,
					streamDetails("https://example.com/dash-" + index + ".mpd", StreamType.VIDEO_STREAM), 1_000L + index);
		}

		assertNull(cache.get("video-0", "fp-0", 2_000L));
		assertNotNull(cache.get("video-16", "fp-16", 2_000L));
	}

	@Test
	public void lruEviction_dropsLeastRecentlyUsedEntry() {
		final PlaybackInfoMemoryCache cache = new PlaybackInfoMemoryCache(2, 300_000L);

		cache.put("video-a", "fp-a", streamDetails("https://example.com/dash-a.mpd", StreamType.VIDEO_STREAM), 1_000L);
		cache.put("video-b", "fp-b", streamDetails("https://example.com/dash-b.mpd", StreamType.VIDEO_STREAM), 2_000L);

		assertNotNull(cache.get("video-a", "fp-a", 3_000L));

		cache.put("video-c", "fp-c", streamDetails("https://example.com/dash-c.mpd", StreamType.VIDEO_STREAM), 4_000L);

		assertNotNull(cache.get("video-a", "fp-a", 5_000L));
		assertNull(cache.get("video-b", "fp-b", 5_000L));
		assertNotNull(cache.get("video-c", "fp-c", 5_000L));
	}

	@Test
	public void invalidate_removesOnlyMatchingFingerprint() {
		final PlaybackInfoMemoryCache cache = new PlaybackInfoMemoryCache(16, 300_000L);

		cache.put("video-a", "fp-a", streamDetails("https://example.com/dash-a.mpd", StreamType.VIDEO_STREAM), 1_000L);
		cache.put("video-a", "fp-b", streamDetails("https://example.com/dash-b.mpd", StreamType.VIDEO_STREAM), 1_000L);

		cache.invalidate("video-a", "fp-a");

		assertNull(cache.get("video-a", "fp-a", 2_000L));
		assertEquals("https://example.com/dash-b.mpd", cache.get("video-a", "fp-b", 2_000L).getDashUrl());
	}

	@Test
	public void invalidateVideo_removesAllFingerprintsForVideo() {
		final PlaybackInfoMemoryCache cache = new PlaybackInfoMemoryCache(16, 300_000L);

		cache.put("video-a", "fp-a", streamDetails("https://example.com/dash-a.mpd", StreamType.VIDEO_STREAM), 1_000L);
		cache.put("video-a", "fp-b", streamDetails("https://example.com/dash-b.mpd", StreamType.VIDEO_STREAM), 1_000L);
		cache.put("video-b", "fp-a", streamDetails("https://example.com/dash-c.mpd", StreamType.VIDEO_STREAM), 1_000L);

		cache.invalidateVideo("video-a");

		assertNull(cache.get("video-a", "fp-a", 2_000L));
		assertNull(cache.get("video-a", "fp-b", 2_000L));
		assertEquals("https://example.com/dash-c.mpd", cache.get("video-b", "fp-a", 2_000L).getDashUrl());
	}

	@Test
	public void liveStreamEntry_isIgnored() {
		for (StreamType streamType : List.of(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM)) {
			final PlaybackInfoMemoryCache cache = new PlaybackInfoMemoryCache(1, 300_000L);

			cache.put("video-a", "fp-a", streamDetails("https://example.com/dash-a.mpd", StreamType.VIDEO_STREAM), 1_000L);
			cache.put("video-live-" + streamType.name(), "fp-live",
					streamDetails("https://example.com/live.mpd", streamType), 2_000L);

			assertNotNull(cache.get("video-a", "fp-a", 3_000L));
			assertNull(cache.get("video-live-" + streamType.name(), "fp-live", 3_000L));
		}
	}

	private static StreamDetails streamDetails(final String dashUrl, final StreamType streamType) {
		return new StreamDetails(
				new ArrayList<>(List.of(mock(VideoStream.class))),
				new ArrayList<>(List.of(mock(AudioStream.class))),
				new ArrayList<>(List.of(mock(SubtitlesStream.class))),
				dashUrl,
				dashUrl.replace("dash", "hls"),
				streamType
		);
	}
}
