package com.hhst.youtubelite.downloader.core.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.hhst.youtubelite.downloader.core.ProgressCallback2;
import com.hhst.youtubelite.downloader.core.StreamDownloader;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.extractor.YoutubeExtractor;

import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class LiteDownloaderImplTest {

	private StreamDownloader streamDownloader;
	private YoutubeExtractor extractor;
	private LiteDownloaderImpl downloader;
	private File cacheDir;
	private File destDir;

	@Before
	public void setUp() throws Exception {
		cacheDir = Files.createTempDirectory("lite-downloader-cache").toFile();
		destDir = Files.createTempDirectory("lite-downloader-output").toFile();
		Context context = mock(Context.class);
		streamDownloader = mock(StreamDownloader.class);
		extractor = mock(YoutubeExtractor.class);
		when(context.getCacheDir()).thenReturn(cacheDir);
		downloader = new LiteDownloaderImpl(context, streamDownloader, extractor, (videoFile, audioFile, outputFile) ->
						FileUtils.writeByteArrayToFile(outputFile, "merged".getBytes(StandardCharsets.UTF_8)));
	}

	@After
	public void tearDown() {
		FileUtils.deleteQuietly(cacheDir);
		FileUtils.deleteQuietly(destDir);
	}

	@Test
	public void downloadMergedMedia_cleansTemporaryFilesAndRemovesCallback() throws Exception {
		final VideoStream video = mock(VideoStream.class);
		final AudioStream audio = mock(AudioStream.class);
		when(video.getContent()).thenReturn("video");
		when(audio.getContent()).thenReturn("audio");
		doAnswer(invocation -> {
			final String content = invocation.getArgument(0);
			final File output = invocation.getArgument(1);
			FileUtils.writeByteArrayToFile(output, content.getBytes(StandardCharsets.UTF_8));
			return CompletableFuture.completedFuture(output);
		}).when(streamDownloader).download(anyString(), any(File.class), any(ProgressCallback.class));

		final String taskId = "video-id:v";
		final ProgressCallback2 callback = mock(ProgressCallback2.class);
		downloader.setCallback(taskId, callback);

		downloader.download(new Task(taskId, video, audio, null, null, "sample", destDir, 4));

		final File output = new File(destDir, "sample.mp4");
		assertTrue(output.exists());
		assertFalse(new File(cacheDir, "sample_v.tmp").exists());
		assertFalse(new File(cacheDir, "sample_a.tmp").exists());
		assertFalse(new File(cacheDir, "sample_m.tmp").exists());
		verify(callback).onMerge();
		verify(callback).onComplete(output);
		assertTrue(getCallbacks().isEmpty());
	}

	@Test
	public void cancel_removesCallback() throws Exception {
		final VideoStream video = mock(VideoStream.class);
		when(video.getContent()).thenReturn("video");
		when(streamDownloader.download(anyString(), any(File.class), any(ProgressCallback.class)))
						.thenReturn(new CompletableFuture<>());

		final String taskId = "video-id:v";
		final ProgressCallback2 callback = mock(ProgressCallback2.class);
		downloader.setCallback(taskId, callback);

		downloader.download(new Task(taskId, video, null, null, null, "sample-cancel", destDir, 4));
		downloader.cancel(taskId);

		verify(streamDownloader).cancel("video");
		verify(callback).onCancel();
		assertTrue(getCallbacks().isEmpty());
	}

	@Test
	public void staleStreamFailure_invalidatesPlaybackCacheByVideoId() {
		final VideoStream video = mock(VideoStream.class);
		when(video.getContent()).thenReturn("video");
		when(streamDownloader.download(anyString(), any(File.class), any(ProgressCallback.class)))
						.thenReturn(CompletableFuture.failedFuture(new IOException("GET 403")));

		final String taskId = "video-id:v";
		final ProgressCallback2 callback = mock(ProgressCallback2.class);
		downloader.setCallback(taskId, callback);

		downloader.download(new Task(taskId, video, null, null, null, "sample-stale", destDir, 4));

		verify(extractor, timeout(2_000)).invalidatePlaybackCacheByVideoId("video-id");
		verify(callback, timeout(2_000)).onError(any(IOException.class));
	}

	@Test
	public void clean_subtitleTaskDoesNotDeleteUnrelatedMediaArtifactsWithSameFileName() throws Exception {
		final VideoStream mediaVideo = mock(VideoStream.class);
		final AudioStream mediaAudio = mock(AudioStream.class);
		final Task mediaTask = new Task("shared-id:v", mediaVideo, mediaAudio, null, null, "shared", destDir, 4);
		final File mediaOutput = new File(destDir, "shared.mp4");
		final File mediaVideoTmp = getTmpFile(mediaTask, "_v");
		final File mediaAudioTmp = getTmpFile(mediaTask, "_a");
		final File mediaMergeTmp = getTmpFile(mediaTask, "_m");
		FileUtils.writeByteArrayToFile(mediaOutput, "media".getBytes(StandardCharsets.UTF_8));
		FileUtils.writeByteArrayToFile(mediaVideoTmp, "video-tmp".getBytes(StandardCharsets.UTF_8));
		FileUtils.writeByteArrayToFile(mediaAudioTmp, "audio-tmp".getBytes(StandardCharsets.UTF_8));
		FileUtils.writeByteArrayToFile(mediaMergeTmp, "merge-tmp".getBytes(StandardCharsets.UTF_8));

		final SubtitlesStream subtitle = mock(SubtitlesStream.class);
		when(subtitle.getExtension()).thenReturn("srt");
		final Task subtitleTask = new Task("shared-id:s", null, null, subtitle, null, "shared", destDir, 4);

		invokeClean(subtitleTask);

		assertTrue(mediaOutput.exists());
		assertTrue(mediaVideoTmp.exists());
		assertTrue(mediaAudioTmp.exists());
		assertTrue(mediaMergeTmp.exists());
	}

	@Test
	public void tmpFiles_areIsolatedByTaskIdWhenFileNamesMatch() throws Exception {
		final VideoStream firstVideo = mock(VideoStream.class);
		final VideoStream secondVideo = mock(VideoStream.class);
		final Task firstTask = new Task("video-id:v", firstVideo, null, null, null, "same-name", destDir, 4);
		final Task secondTask = new Task("video-id:s", secondVideo, null, null, null, "same-name", destDir, 4);

		assertNotEquals(getTmpFile(firstTask, "_v").getAbsolutePath(), getTmpFile(secondTask, "_v").getAbsolutePath());
	}

	@SuppressWarnings("unchecked")
	private Map<String, ProgressCallback2> getCallbacks() throws Exception {
		final Field field = LiteDownloaderImpl.class.getDeclaredField("cbs");
		field.setAccessible(true);
		return (Map<String, ProgressCallback2>) field.get(downloader);
	}

	private File getTmpFile(final Task task, final String suffix) throws Exception {
		final var method = LiteDownloaderImpl.class.getDeclaredMethod("tmp", Task.class, String.class);
		method.setAccessible(true);
		return (File) method.invoke(downloader, task, suffix);
	}

	private void invokeClean(final Task task) throws Exception {
		final var method = LiteDownloaderImpl.class.getDeclaredMethod("clean", Task.class);
		method.setAccessible(true);
		method.invoke(downloader, task);
	}
}
