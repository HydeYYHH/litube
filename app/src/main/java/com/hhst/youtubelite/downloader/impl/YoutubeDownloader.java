package com.hhst.youtubelite.downloader.impl;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.exception.DownloadConnectionException;
import com.hhst.youtubelite.downloader.exception.DownloadMuxingException;
import com.hhst.youtubelite.downloader.core.DownloadProcessor;
import com.hhst.youtubelite.downloader.exception.DownloadStorageException;
import com.hhst.youtubelite.downloader.core.DownloadTask;
import com.hhst.youtubelite.downloader.core.ProgressCallback;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of DownloadProcessor for YouTube streams.
 * Handles the orchestration of video, audio, and subtitle streams.
 */
public final class YoutubeDownloader implements DownloadProcessor {

	private final AdvancedFileDownloader downloader = new MultiThreadFileDownloader();
	private final Map<String, Boolean> cancelledTasks = new ConcurrentHashMap<>();

	@Override
	public void process(@NonNull DownloadTask task, @NonNull String tag, @NonNull ProgressCallback callback, @NonNull Context context) {
		download(tag, task.getVideoStream(), task.getAudioStream(), task.getSubtitlesStream(), task.getOutput(), callback, context, task.getThreadCount());
	}

	@Override
	public void cancel(@NonNull String tag) {
		cancelledTasks.put(tag, true);
		downloader.cancel(tag);
	}

	private void download(@NonNull final String tag, @Nullable final VideoStream videoStream, @NonNull final AudioStream audioStream, @Nullable final SubtitlesStream subtitlesStream, @NonNull final File output, @NonNull final ProgressCallback callback, @NonNull final Context context, int threadCount) {

		// Download the video and audio streams
		final String baseName = FilenameUtils.getBaseName(output.getPath());
		final File tempDir = new File(context.getCacheDir(), baseName);
		try {
			FileUtils.forceMkdir(tempDir);
		} catch (final IOException e) {
			callback.onError(new DownloadStorageException("Failed to create temporary directory", e));
			return;
		}
		final File videoFile = new File(tempDir, baseName + ".mp4");
		final File audioFile = new File(tempDir, baseName + ".m4a");

		if (videoStream != null) {
			downloader.download(videoStream.getContent(), videoFile, new ProgressCallback() {
				@Override
				public void onProgress(final int progress, @Nullable final String message) {
					callback.onProgress(progress, context.getString(R.string.downloading_video));
				}

				@Override
				public void onComplete(@NonNull final File file) {
					if (Boolean.TRUE.equals(cancelledTasks.getOrDefault(tag, false))) return;
					// Download the audio stream
					downloader.download(audioStream.getContent(), audioFile, new ProgressCallback() {
						@Override
						public void onProgress(final int progress, @Nullable final String message) {
							callback.onProgress(progress, context.getString(R.string.downloading_audio));
						}

						@Override
						public void onComplete(@NonNull final File file) {
							if (subtitlesStream != null) {
								final String subExt = subtitlesStream.getFormat().getSuffix();
								final File subtitleFile = new File(output.getParentFile(), baseName + "." + subExt);
								downloader.download(subtitlesStream.getContent(), subtitleFile, new ProgressCallback() {
									@Override
									public void onProgress(int progress, @Nullable String message) {
										callback.onProgress(progress, context.getString(R.string.downloading_subtitle));
									}

									@Override
									public void onComplete(@NonNull File file) {
										mergeAndComplete(tag, videoFile, audioFile, output, callback, baseName, tempDir);
									}

									@Override
									public void onError(@NonNull Exception error) {
										// Even if subtitle download fails, we should still merge video and audio
										mergeAndComplete(tag, videoFile, audioFile, output, callback, baseName, tempDir);
									}

									@Override
									public void onCancel() {
										callback.onCancel();
									}

									@Override
									public void onMerge() {
									}
								}, tag, threadCount);
							} else {
								mergeAndComplete(tag, videoFile, audioFile, output, callback, baseName, tempDir);
							}
						}

						@Override
						public void onError(@NonNull final Exception error) {
							callback.onError(new DownloadConnectionException("Audio download failed", error));
						}

						@Override
						public void onCancel() {
							callback.onCancel();
						}

						@Override
						public void onMerge() {
						}
					}, tag, threadCount);
				}

				@Override
				public void onError(@NonNull final Exception error) {
					callback.onError(new DownloadConnectionException("Video download failed", error));
				}

				@Override
				public void onCancel() {
					callback.onCancel();
				}

				@Override
				public void onMerge() {
				}
			}, tag, threadCount);
		} else {
			downloader.download(audioStream.getContent(), audioFile, new ProgressCallback() {
				@Override
				public void onProgress(final int progress, @Nullable final String message) {
					callback.onProgress(progress, context.getString(R.string.downloading_audio));
				}

				@Override
				public void onComplete(@NonNull final File file) {
					if (subtitlesStream != null) {
						final String subExt = subtitlesStream.getFormat().getSuffix();
						final File subtitleFile = new File(output.getParentFile(), baseName + "." + subExt);
						downloader.download(subtitlesStream.getContent(), subtitleFile, new ProgressCallback() {
							@Override
							public void onProgress(int progress, @Nullable String message) {
								callback.onProgress(progress, context.getString(R.string.downloading_subtitle));
							}

							@Override
							public void onComplete(@NonNull File file) {
								moveAudioToOutput(tag, audioFile, output, callback);
							}

							@Override
							public void onError(@NonNull Exception error) {
								moveAudioToOutput(tag, audioFile, output, callback);
							}

							@Override
							public void onCancel() {
								callback.onCancel();
							}

							@Override
							public void onMerge() {
							}
						}, tag, threadCount);
					} else {
						moveAudioToOutput(tag, audioFile, output, callback);
					}
				}

				@Override
				public void onError(@NonNull final Exception error) {
					callback.onError(new DownloadConnectionException("Audio download failed", error));
				}

				@Override
				public void onCancel() {
					callback.onCancel();
				}

				@Override
				public void onMerge() {
				}
			}, tag, threadCount);
		}
	}

	private void mergeAndComplete(String tag, File videoFile, File audioFile, File output, ProgressCallback callback, String baseName, File tempDir) {
		// Merge the video and audio files
		try {
			// Create a temporary output file for better speed
			final File tempOutput = new File(tempDir, baseName + "_merged.mp4");
			callback.onMerge();
			if (Boolean.TRUE.equals(cancelledTasks.getOrDefault(tag, false))) return;
			new MediaMuxerImpl().merge(videoFile, audioFile, tempOutput);
			if (Boolean.TRUE.equals(cancelledTasks.getOrDefault(tag, false))) return;
			// Move merged file to output
			if (output.exists()) {
				final File availableFile = getAvailableFile(output);
				FileUtils.moveFile(tempOutput, availableFile);
				callback.onComplete(availableFile);
			} else {
				FileUtils.moveFile(tempOutput, output);
				callback.onComplete(output);
			}

		} catch (final IOException e) {
			callback.onError(new DownloadStorageException("Failed to move merged file", e));
		} catch (final Exception e) {
			callback.onError(new DownloadMuxingException("Failed to merge video and audio", e));
		}
	}

	private void moveAudioToOutput(String tag, File audioFile, File output, ProgressCallback callback) {
		// Move audio file to output
		try {
			if (Boolean.TRUE.equals(cancelledTasks.getOrDefault(tag, false))) return;
			if (output.exists()) {
				final File availableFile = getAvailableFile(output);
				FileUtils.moveFile(audioFile, availableFile);
				callback.onComplete(availableFile);
			} else {
				FileUtils.moveFile(audioFile, output);
				callback.onComplete(output);
			}

		} catch (final IOException e) {
			callback.onError(new DownloadStorageException("Failed to move audio file", e));
		}
	}

	private File getAvailableFile(@NonNull final File output) {
		final String baseName = FilenameUtils.getBaseName(output.getPath());
		final String extension = FilenameUtils.getExtension(output.getPath());
		int i = 1;
		File file;
		do {
			file = new File(output.getParent(), baseName + "(" + i + ")." + extension);
			++i;
		} while (file.exists());
		return file;
	}
}
