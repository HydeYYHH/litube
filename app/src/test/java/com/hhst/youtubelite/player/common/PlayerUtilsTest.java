package com.hhst.youtubelite.player.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;

/**
 * Streamlined tests for PlayerUtils utility methods.
 */
public class PlayerUtilsTest {

	@Test
	public void getCodecPriority_returnsCorrectScores() {
		assertEquals(4, PlayerUtils.getCodecPriority("avc1"));
		assertEquals(4, PlayerUtils.getCodecPriority("H264"));
		assertEquals(3, PlayerUtils.getCodecPriority("vp9"));
		assertEquals(3, PlayerUtils.getCodecPriority("VP8"));
		assertEquals(2, PlayerUtils.getCodecPriority("h265"));
		assertEquals(1, PlayerUtils.getCodecPriority("av01"));
		assertEquals(0, PlayerUtils.getCodecPriority(null));
		assertEquals(0, PlayerUtils.getCodecPriority("unknown"));
	}

	@Test
	public void isBetterStream_comparesStreamsCorrectly() {
		VideoStream s1 = createVideoStream("avc1", 1080, 30, 5000000);
		VideoStream s2 = createVideoStream("vp9", 1080, 30, 5000000);
		assertTrue(PlayerUtils.isBetterStream(s1, s2));

		s1 = createVideoStream("avc1", 1080, 60, 5000000);
		s2 = createVideoStream("avc1", 1080, 30, 5000000);
		assertTrue(PlayerUtils.isBetterStream(s1, s2));

		s1 = createVideoStream("avc1", 1080, 30, 8000000);
		s2 = createVideoStream("avc1", 1080, 30, 5000000);
		assertTrue(PlayerUtils.isBetterStream(s1, s2));
	}

	@Test
	public void filterBestStreams_returnsOptimalStreams() {
		List<VideoStream> streams = new ArrayList<>();
		streams.add(createVideoStream("avc1", 1080, 30, 5000000));
		streams.add(createVideoStream("vp9", 1080, 30, 5000000));
		streams.add(createVideoStream("avc1", 720, 30, 3000000));

		List<VideoStream> filtered = PlayerUtils.filterBestStreams(streams);
		assertNotNull(filtered);
		assertEquals(2, filtered.size());
		assertEquals(1080, filtered.get(0).getHeight());
		assertEquals("avc1", filtered.get(0).getCodec());
		assertEquals(720, filtered.get(1).getHeight());
	}

	@Test
	public void filterBestStreams_handlesNullAndEmptyInputs() {
		assertTrue(PlayerUtils.filterBestStreams(null).isEmpty());
		assertTrue(PlayerUtils.filterBestStreams(new ArrayList<>()).isEmpty());
	}

	@Test
	public void selectVideoStream_selectsCorrectStream() {
		List<VideoStream> streams = new ArrayList<>();
		streams.add(createVideoStream("avc1", 1080, 30, 5000000));
		streams.add(createVideoStream("avc1", 720, 30, 3000000));
		streams.add(createVideoStream("avc1", 480, 30, 1500000));

		VideoStream selected = PlayerUtils.selectVideoStream(streams, "720p");
		assertNotNull(selected);
		assertEquals(720, selected.getHeight());

		selected = PlayerUtils.selectVideoStream(streams, null);
		assertNotNull(selected);
		assertEquals(1080, selected.getHeight());

		selected = PlayerUtils.selectVideoStream(streams, "2160p");
		assertNotNull(selected);
		assertEquals(1080, selected.getHeight());
	}

	@Test
	public void selectAudioStream_selectsCorrectStream() {
		List<AudioStream> streams = new ArrayList<>();
		streams.add(createAudioStream("webm", "opus", 128));
		streams.add(createAudioStream("mp4", "aac", 256));

		AudioStream selected = PlayerUtils.selectAudioStream(streams, null);
		assertNotNull(selected);
		assertEquals(128, selected.getAverageBitrate());
	}

	private VideoStream createVideoStream(String codec, int height, int fps, int bitrate) {
		VideoStream stream = mock(VideoStream.class);
		when(stream.getCodec()).thenReturn(codec);
		when(stream.getHeight()).thenReturn(height);
		when(stream.getFps()).thenReturn(fps);
		when(stream.getBitrate()).thenReturn(bitrate);
		when(stream.getResolution()).thenReturn(height + "p");
		return stream;
	}

	private AudioStream createAudioStream(String format, String codec, int bitrate) {
		AudioStream stream = mock(AudioStream.class);
		when(stream.getFormat()).thenReturn("webm".equals(format) ? MediaFormat.WEBMA : MediaFormat.MPEG_4);
		when(stream.getCodec()).thenReturn(codec);
		when(stream.getAverageBitrate()).thenReturn(bitrate);
		return stream;
	}
}
