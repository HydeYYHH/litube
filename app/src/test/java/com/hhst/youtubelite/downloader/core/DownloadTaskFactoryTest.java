package com.hhst.youtubelite.downloader.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hhst.youtubelite.extractor.StreamCatalog;
import com.hhst.youtubelite.extractor.StreamCandidate;
import com.hhst.youtubelite.extractor.VideoDetails;

import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.util.List;

/**
 * Verifies that download tasks carry a muxed MPEG-4 fallback stream: YouTube serves
 * resolutions above 360p to some clients in the SABR format, which cannot be downloaded
 * as a plain progressive file, so the downloader falls back to the best muxed MPEG-4
 * stream (e.g. 360p) that YouTube keeps outside SABR.
 */
public class DownloadTaskFactoryTest {

	private DownloadTaskFactory factory;
	private File dir;
	private VideoDetails videoDetails;
	private AudioStream audio;
	private DownloadSelectionConfig config;

	@Before
	public void setUp() {
		factory = new DownloadTaskFactory();
		dir = new File("/tmp");
		videoDetails = new VideoDetails();
		videoDetails.setId("abc123");
		videoDetails.setTitle("Test Video");
		videoDetails.setThumbnailUrl("https://img.youtube.com/vi/abc123/hqdefault.jpg");
		audio = audio("en", AudioTrackType.ORIGINAL);
		config = new DownloadSelectionConfig(
						DownloadSelectionConfig.PrimaryMediaMode.VIDEO, false, false, 4);
	}

	@Test
	public void muxedFallback_prefersSameOrLowerResolution() {
		StreamCatalog catalog = catalogWithMuxed(video("360p", 360, 18), video("720p", 720, 22),
						video("1080p", 1080, 37));
		VideoStream selected = video("1080p", 1080, 137);

		List<Task> tasks = factory.buildSingleVideoTasks(
						videoDetails, catalog, config, selected, audio, null, "file", dir);

		assertEquals(1, tasks.size());
		Task task = tasks.get(0);
		assertSame(selected, task.video());
		assertSame(1080, task.muxedFallback().getHeight());
	}

	@Test
	public void muxedFallback_capsAtBestAvailableWhenRequestedIsHigher() {
		StreamCatalog catalog = catalogWithMuxed(video("360p", 360, 18), video("1080p", 1080, 37));
		VideoStream selected = video("2160p", 2160, 401);

		List<Task> tasks = factory.buildSingleVideoTasks(
						videoDetails, catalog, config, selected, audio, null, "file", dir);

		Task task = tasks.get(0);
		assertSame(1080, task.muxedFallback().getHeight());
	}

	@Test
	public void muxedFallback_picksHighestWhenTargetBelowLowest() {
		StreamCatalog catalog = catalogWithMuxed(video("360p", 360, 18), video("720p", 720, 22));
		VideoStream selected = video("144p", 144, 160);

		List<Task> tasks = factory.buildSingleVideoTasks(
						videoDetails, catalog, config, selected, audio, null, "file", dir);

		Task task = tasks.get(0);
		// No muxed stream at or below 144p exists, so the best available muxed MPEG-4 wins.
		assertSame(360, task.muxedFallback().getHeight());
	}

	@Test
	public void muxedFallback_nullWhenNoMuxedStreams() {
		StreamCatalog catalog = new StreamCatalog();
		VideoStream selected = video("720p", 720, 136);

		List<Task> tasks = factory.buildSingleVideoTasks(
						videoDetails, catalog, config, selected, audio, null, "file", dir);

		assertNull(tasks.get(0).muxedFallback());
	}

	@Test
	public void muxedFallback_nullWhenOnlyNonMpeg4MuxedStreams() {
		StreamCatalog catalog = new StreamCatalog();
		VideoStream webm = video("720p", 720, 248);
		when(webm.getFormat()).thenReturn(MediaFormat.WEBM);
		catalog.getMuxedCandidates().add(StreamCandidate.muxed(webm, null, false, false, false));
		VideoStream selected = video("720p", 720, 136);

		List<Task> tasks = factory.buildSingleVideoTasks(
						videoDetails, catalog, config, selected, audio, null, "file", dir);

		assertNull(tasks.get(0).muxedFallback());
	}

	private StreamCatalog catalogWithMuxed(VideoStream... muxed) {
		StreamCatalog catalog = new StreamCatalog();
		for (VideoStream stream : muxed) {
			catalog.getMuxedCandidates().add(StreamCandidate.muxed(stream, null, false, false, false));
		}
		return catalog;
	}

	private static VideoStream video(String resolution, int height, int itag) {
		VideoStream stream = mock(VideoStream.class);
		when(stream.getFormat()).thenReturn(MediaFormat.MPEG_4);
		when(stream.getResolution()).thenReturn(resolution);
		when(stream.getHeight()).thenReturn(height);
		when(stream.getFps()).thenReturn(30);
		when(stream.getBitrate()).thenReturn(1_000_000);
		when(stream.getItag()).thenReturn(itag);
		return stream;
	}

	private static AudioStream audio(String id, AudioTrackType type) {
		AudioStream stream = mock(AudioStream.class);
		when(stream.getFormat()).thenReturn(MediaFormat.M4A);
		when(stream.getAudioTrackId()).thenReturn(id);
		when(stream.getAudioTrackName()).thenReturn("English");
		when(stream.getAudioTrackType()).thenReturn(type);
		when(stream.getAverageBitrate()).thenReturn(128);
		return stream;
	}
}
