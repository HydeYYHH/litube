package com.hhst.youtubelite.downloader.core.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class LiteDownloaderImplTest {

	private Context context;
	private StreamDownloader streamDownloader;
	private YoutubeExtractor extractor;
	private LiteDownloaderImpl downloader;
	private File cacheDir;
	private File destDir;

	@Before
	public void setUp() throws Exception {
		cacheDir = Files.createTempDirectory("lite-downloader-cache").toFile();
		destDir = Files.createTempDirectory("lite-downloader-output").toFile();
		context = mock(Context.class);
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
	public void downloadMergedMedia_cleansTemporaryFilesAndReportsCompletion() throws Exception {
		final VideoStream video = mock(VideoStream.class);
		final AudioStream audio = mock(AudioStream.class);
		final List<File> tempFiles = new ArrayList<>();
		final AtomicReference<File> mergeTempFile = new AtomicReference<>();
		when(video.getContent()).thenReturn("video");
		when(audio.getContent()).thenReturn("audio");
		downloader = new LiteDownloaderImpl(context, streamDownloader, extractor, (videoFile, audioFile, outputFile) -> {
			mergeTempFile.set(outputFile);
			FileUtils.writeByteArrayToFile(outputFile, "merged".getBytes(StandardCharsets.UTF_8));
		});
		stubStreamDownloadsWriteRequestedContent(tempFiles);

		final String taskId = "video-id:v";
		final ProgressCallback2 callback = registerCallback(taskId);

		downloader.download(task(taskId, video, audio, null, null, "sample"));

		final File output = new File(destDir, "sample.mp4");
		assertTrue(output.exists());
		assertEquals(2, tempFiles.size());
		for (final File tempFile : tempFiles) {
			assertFalse(tempFile.exists());
		}
		assertFalse(mergeTempFile.get().exists());
		verify(callback).onMerge();
		verify(callback).onComplete(output);
	}

	@Test
	public void cancel_activeMergedDownload_cancelsBothStreamsAndDeletesTemps() throws Exception {
		final VideoStream video = mock(VideoStream.class);
		final AudioStream audio = mock(AudioStream.class);
		final AtomicReference<File> videoTemp = new AtomicReference<>();
		final AtomicReference<File> audioTemp = new AtomicReference<>();
		when(video.getContent()).thenReturn("video");
		when(audio.getContent()).thenReturn("audio");
		when(streamDownloader.download(anyString(), any(File.class), any(ProgressCallback.class)))
						.thenAnswer(invocation -> {
							final String content = invocation.getArgument(0);
							final File output = invocation.getArgument(1);
							if ("video".equals(content)) videoTemp.set(output);
							if ("audio".equals(content)) audioTemp.set(output);
							FileUtils.writeByteArrayToFile(output, content.getBytes(StandardCharsets.UTF_8));
							return new CompletableFuture<>();
						});

		final String taskId = "video-id:v";
		final ProgressCallback2 callback = registerCallback(taskId);

		downloader.download(task(taskId, video, audio, null, null, "sample-cancel"));
		downloader.cancel(taskId);

		verify(streamDownloader).cancel("video");
		verify(streamDownloader).cancel("audio");
		verify(callback).onCancel();
		assertFalse(videoTemp.get().exists());
		assertFalse(audioTemp.get().exists());
		assertFalse(new File(destDir, "sample-cancel.mp4").exists());
	}

	@Test
	public void staleStreamFailure_invalidatesPlaybackCacheByVideoId() {
		final VideoStream video = mock(VideoStream.class);
		when(video.getContent()).thenReturn("video");
		when(streamDownloader.download(anyString(), any(File.class), any(ProgressCallback.class)))
						.thenReturn(CompletableFuture.failedFuture(new IOException("GET 403")));

		final String taskId = "video-id:v";
		final ProgressCallback2 callback = registerCallback(taskId);

		downloader.download(task(taskId, video, null, null, null, "sample-stale"));

		verify(extractor, timeout(2_000)).invalidatePlaybackCacheByVideoId("video-id");
		verify(callback, timeout(2_000)).onError(any(IOException.class));
	}

	@Test
	public void genericDownloadFailure_doesNotInvalidatePlaybackCache() {
		final VideoStream video = mock(VideoStream.class);
		when(video.getContent()).thenReturn("video");
		when(streamDownloader.download(anyString(), any(File.class), any(ProgressCallback.class)))
						.thenReturn(CompletableFuture.failedFuture(new IOException("socket timeout")));

		final String taskId = "video-id:v";
		final ProgressCallback2 callback = registerCallback(taskId);

		downloader.download(task(taskId, video, null, null, null, "sample-generic-fail"));

		verify(callback, timeout(2_000)).onError(any(IOException.class));
		verify(extractor, never()).invalidatePlaybackCacheByVideoId(anyString());
	}

	@Test
	public void subtitleDownload_copiesFileWithSubtitleExtension() throws Exception {
		final File subtitleSource = createSourceFile("subtitle-source", "subtitle-content");
		final SubtitlesStream subtitle = mock(SubtitlesStream.class);
		when(subtitle.getContent()).thenReturn(subtitleSource.toURI().toURL().toString());
		when(subtitle.getExtension()).thenReturn("vtt");

		final String taskId = "video-id:s";
		final ProgressCallback2 callback = registerCallback(taskId);
		final Task task = task(taskId, null, null, subtitle, null, "subtitle-track");

		downloader.download(task);

		final File output = new File(destDir, "subtitle-track.vtt");
		verify(callback, timeout(2_000)).onComplete(output);
		assertTrue(output.exists());
		assertEquals("subtitle-content", FileUtils.readFileToString(output, StandardCharsets.UTF_8));
		verifyNoInteractions(streamDownloader);
	}

	@Test
	public void thumbnailDownload_copiesFileAsJpg() throws Exception {
		final File thumbnailSource = createSourceFile("thumbnail-source", "thumb-content");
		final String thumbnailUrl = thumbnailSource.toURI().toURL().toString();

		final String taskId = "video-id:t";
		final ProgressCallback2 callback = registerCallback(taskId);
		final Task task = task(taskId, null, null, null, thumbnailUrl, "thumbnail-track");

		downloader.download(task);

		final File output = new File(destDir, "thumbnail-track.jpg");
		verify(callback, timeout(2_000)).onComplete(output);
		assertTrue(output.exists());
		assertEquals("thumb-content", FileUtils.readFileToString(output, StandardCharsets.UTF_8));
		verifyNoInteractions(streamDownloader);
	}

	@Test
	public void videoOnlyDownload_movesTempFileToMp4Output() throws Exception {
		final List<File> tempFiles = new ArrayList<>();
		stubStreamDownloadsWriteRequestedContent(tempFiles);
		final VideoStream video = mock(VideoStream.class);
		when(video.getContent()).thenReturn("video-only-content");

		final String taskId = "video-id:v";
		final ProgressCallback2 callback = registerCallback(taskId);

		downloader.download(task(taskId, video, null, null, null, "video-only"));

		final File output = new File(destDir, "video-only.mp4");
		verify(callback, timeout(2_000)).onComplete(output);
		verify(callback, never()).onMerge();
		assertTrue(output.exists());
		assertEquals("video-only-content", FileUtils.readFileToString(output, StandardCharsets.UTF_8));
		assertEquals(1, tempFiles.size());
		assertFalse(tempFiles.get(0).exists());
	}

	@Test
	public void audioOnlyDownload_movesTempFileToM4aOutput() throws Exception {
		final List<File> tempFiles = new ArrayList<>();
		stubStreamDownloadsWriteRequestedContent(tempFiles);
		final AudioStream audio = mock(AudioStream.class);
		when(audio.getContent()).thenReturn("audio-only-content");

		final String taskId = "video-id:a";
		final ProgressCallback2 callback = registerCallback(taskId);

		downloader.download(task(taskId, null, audio, null, null, "audio-only"));

		final File output = new File(destDir, "audio-only.m4a");
		verify(callback, timeout(2_000)).onComplete(output);
		verify(callback, never()).onMerge();
		assertTrue(output.exists());
		assertEquals("audio-only-content", FileUtils.readFileToString(output, StandardCharsets.UTF_8));
		assertEquals(1, tempFiles.size());
		assertFalse(tempFiles.get(0).exists());
	}

	@Test
	public void mergeFailure_cleansTemporaryFilesAndDeletesOutput() throws Exception {
		final List<File> tempFiles = new ArrayList<>();
		final AtomicReference<File> mergeTempFile = new AtomicReference<>();
		downloader = new LiteDownloaderImpl(context, streamDownloader, extractor, (videoFile, audioFile, outputFile) -> {
			mergeTempFile.set(outputFile);
			FileUtils.writeByteArrayToFile(outputFile, "partial".getBytes(StandardCharsets.UTF_8));
			throw new IOException("merge failed");
		});

		stubStreamDownloadsWriteRequestedContent(tempFiles);
		final VideoStream video = mock(VideoStream.class);
		final AudioStream audio = mock(AudioStream.class);
		when(video.getContent()).thenReturn("video-merge");
		when(audio.getContent()).thenReturn("audio-merge");

		final String taskId = "video-id:m";
		final ProgressCallback2 callback = registerCallback(taskId);

		downloader.download(task(taskId, video, audio, null, null, "merge-fail"));

		verify(callback, timeout(2_000)).onMerge();
		verify(callback, timeout(2_000)).onError(any(IOException.class));
		verify(callback, never()).onComplete(any(File.class));
		assertEquals(2, tempFiles.size());
		for (final File tempFile : tempFiles) {
			assertFalse(tempFile.exists());
		}
		assertFalse(mergeTempFile.get().exists());
		assertFalse(new File(destDir, "merge-fail.mp4").exists());
	}

	@Test
	public void setCallback_nullRemovesCompletionNotification() throws Exception {
		stubStreamDownloadsWriteRequestedContent(new ArrayList<>());
		final VideoStream video = mock(VideoStream.class);
		when(video.getContent()).thenReturn("video-only-content");
		final String taskId = "video-id:v";
		final ProgressCallback2 callback = registerCallback(taskId);
		downloader.setCallback(taskId, null);

		downloader.download(task(taskId, video, null, null, null, "video-no-callback"));

		assertTrue(new File(destDir, "video-no-callback.mp4").exists());
		verifyNoInteractions(callback);
	}

	@Test
	public void subtitleFailure_keepsExistingMediaOutputWithSameBaseName() throws Exception {
		final File mediaOutput = new File(destDir, "shared.mp4");
		FileUtils.writeByteArrayToFile(mediaOutput, "media".getBytes(StandardCharsets.UTF_8));
		final SubtitlesStream subtitle = mock(SubtitlesStream.class);
		when(subtitle.getContent()).thenReturn(new File(cacheDir, "missing-subtitle.vtt").toURI().toURL().toString());
		when(subtitle.getExtension()).thenReturn("srt");
		final ProgressCallback2 callback = registerCallback("shared-id:s");
		final Task subtitleTask = task("shared-id:s", null, null, subtitle, null, "shared");

		downloader.download(subtitleTask);

		verify(callback, timeout(2_000)).onError(any(IOException.class));
		verify(extractor, never()).invalidatePlaybackCacheByVideoId(anyString());
		assertTrue(mediaOutput.exists());
		assertFalse(new File(destDir, "shared.srt").exists());
		verifyNoInteractions(streamDownloader);
	}

	@Test
	public void tmpFiles_areIsolatedByTaskIdWhenFileNamesMatch() throws Exception {
		final List<File> tempFiles = new ArrayList<>();
		when(streamDownloader.download(anyString(), any(File.class), any(ProgressCallback.class)))
						.thenAnswer(invocation -> {
							tempFiles.add(invocation.getArgument(1));
							return new CompletableFuture<>();
						});
		final VideoStream firstVideo = mock(VideoStream.class);
		final VideoStream secondVideo = mock(VideoStream.class);
		when(firstVideo.getContent()).thenReturn("video-one");
		when(secondVideo.getContent()).thenReturn("video-two");
		final Task firstTask = task("video-id:v", firstVideo, null, null, null, "same-name");
		final Task secondTask = task("video-id:s", secondVideo, null, null, null, "same-name");

		downloader.download(firstTask);
		downloader.download(secondTask);

		assertEquals(2, tempFiles.size());
		assertNotEquals(tempFiles.get(0).getAbsolutePath(), tempFiles.get(1).getAbsolutePath());
	}

	@Test
	public void invalidatePlaybackCacheIfLikelyExpiredStream_audioOnly403InvalidatesByRawVideoId() {
		final AudioStream audio = mock(AudioStream.class);
		final Task task = task("video-id:a", null, audio, null, null, "audio-only");

		downloader.invalidatePlaybackCacheIfLikelyExpiredStream(task, new IOException("GET 403"));

		verify(extractor).invalidatePlaybackCacheByVideoId("video-id");
	}

	@Test
	public void isLikelyExpiredStreamError_checksNestedStatusCodesOnly() {
		assertTrue(LiteDownloaderImpl.isLikelyExpiredStreamError(
						new RuntimeException("wrapper", new IOException("GET 410"))));
		assertFalse(LiteDownloaderImpl.isLikelyExpiredStreamError(new IOException("socket timeout")));
		assertFalse(LiteDownloaderImpl.isLikelyExpiredStreamError(null));
	}

	@Test
	public void rawVideoId_stripsOnlyTrailingTaskSuffix() {
		assertEquals("video-id", LiteDownloaderImpl.rawVideoId("video-id:v"));
		assertEquals("video:id", LiteDownloaderImpl.rawVideoId("video:id:a"));
		assertEquals("plain-video-id", LiteDownloaderImpl.rawVideoId("plain-video-id"));
	}

	private Task task(final String taskId,
	                  final VideoStream video,
	                  final AudioStream audio,
	                  final SubtitlesStream subtitle,
	                  final String thumbnail,
	                  final String fileName) {
		return new Task(taskId, video, audio, subtitle, thumbnail, fileName, destDir, 4);
	}

	private ProgressCallback2 registerCallback(final String taskId) {
		final ProgressCallback2 callback = mock(ProgressCallback2.class);
		downloader.setCallback(taskId, callback);
		return callback;
	}

	private void stubStreamDownloadsWriteRequestedContent(final List<File> tempFiles) throws Exception {
		doAnswer(invocation -> {
			final String content = invocation.getArgument(0);
			final File output = invocation.getArgument(1);
			tempFiles.add(output);
			FileUtils.writeByteArrayToFile(output, content.getBytes(StandardCharsets.UTF_8));
			return CompletableFuture.completedFuture(output);
		}).when(streamDownloader).download(anyString(), any(File.class), any(ProgressCallback.class));
	}

	private File createSourceFile(final String prefix, final String content) throws IOException {
		final File file = Files.createTempFile(cacheDir.toPath(), prefix, ".tmp").toFile();
		FileUtils.writeByteArrayToFile(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}
}
