package com.hhst.youtubelite.downloader.service;

import android.app.Service;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.ErrorDialog;
import com.hhst.youtubelite.MainActivity;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.DownloadManager;
import com.hhst.youtubelite.downloader.core.DownloadTask;
import com.hhst.youtubelite.downloader.core.DownloaderState;
import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.hhst.youtubelite.downloader.impl.MultiThreadConnectionCountAdapter;
import com.liulishuo.filedownloader.FileDownloader;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public class DownloadService extends Service {

	private DownloadManager downloadManager;

	@Override
	public void onCreate() {
		super.onCreate();
		downloadManager = DownloadManager.getInstance();
		FileDownloader.setupOnApplicationOnCreate(getApplication())
				.connectionCountAdapter(new MultiThreadConnectionCountAdapter())
				.commit();
		FileDownloader.getImpl().setMaxNetworkThreadCount(16);
	}

	@Override
	public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
		if (intent == null) return START_NOT_STICKY;

		final String action = intent.getAction();
		final int taskId = intent.getIntExtra("taskId", -1);
		if ("CANCEL_DOWNLOAD".equals(action)) cancelDownload(taskId);
		else if ("DELETE_DOWNLOAD".equals(action)) deleteDownload(taskId);
		else if ("DOWNLOAD_THUMBNAIL".equals(action)) {
			final String url = intent.getStringExtra("thumbnail");
			final String filename = intent.getStringExtra("filename");
			final File outputDir = downloadManager.getDownloadDirectory(this);
			final File outputFile = new File(outputDir, filename + ".jpg");
			downloadThumbnail(url, outputFile);
		}
		return super.onStartCommand(intent, flags, startId);
	}

	@Nullable
	@Override
	public IBinder onBind(@NonNull final Intent intent) {
		return new DownloadBinder();
	}

	private void showToast(@NonNull final String content) {
		new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this, content, Toast.LENGTH_SHORT).show());
	}

	private void downloadThumbnail(@Nullable final String thumbnail, @NonNull final File outputFile) {
		if (thumbnail != null) {
			downloadManager.getExecutor().submit(() -> {
				try {
					FileUtils.copyURLToFile(new URL(thumbnail), outputFile);
					// notify to scan
					MediaScannerConnection.scanFile(this, new String[]{outputFile.getAbsolutePath()}, null, null);
					showToast(getString(R.string.thumbnail_has_been_saved_to) + outputFile);
				} catch (final IOException e) {
					Log.e(getString(R.string.failed_to_download_thumbnail), Log.getStackTraceString(e));
					showToast(getString(R.string.failed_to_download_thumbnail));
				}
			});
		}
	}

	public void initiateDownload(@NonNull final DownloadTask task) {
		task.setState(DownloaderState.RUNNING);

		downloadManager.getExecutor().submit(() -> {
			// Check and create output directory
			final File outputDir = downloadManager.getDownloadDirectory(this);
			if (task.getVideoStream() != null) {
				final DownloadTask videoTask = task.clone();
				videoTask.setFileName(String.format("%s(%s)", task.getFileName(), task.getVideoStream().getResolution()));
				videoTask.setOutput(new File(outputDir, task.getFileName() + ".mp4"));
				videoTask.setAudio(false);
				executeDownload(videoTask);
			} else if (task.isAudio()) {
				final DownloadTask audioTask = task.clone();
				audioTask.setFileName(String.format("(audio only) %s", task.getFileName()));
				audioTask.setOutput(new File(outputDir, audioTask.getFileName() + ".m4a"));
				audioTask.setVideoStream(null);
				executeDownload(audioTask);
			} else if (task.isSubtitle()) {
				final DownloadTask subTask = task.clone();
				final String subExt = task.getSubtitlesStream().getFormat().getSuffix();
				subTask.setFileName(String.format("(subtitle) %s", task.getFileName()));
				subTask.setOutput(new File(outputDir, task.getFileName() + "." + subExt));
				executeDownload(subTask);
			}
		});
	}

	private void executeDownload(@NonNull final DownloadTask task) {
		final int taskId = downloadManager.generateTaskId();

		task.setState(DownloaderState.RUNNING);
		downloadManager.addTask(taskId, task);

		final DownloadNotification notification = new DownloadNotification(this, taskId);
		task.setNotification(notification);

		final String fileName = task.getFileName();
		final String initialContent;
		if (task.isSubtitle()) {
			initialContent = getString(R.string.downloading_subtitle) + ": " + fileName;
		} else {
			initialContent = task.isAudio() ? getString(R.string.downloading_audio) + ": " + fileName : getString(R.string.downloading_video) + ": " + fileName;
		}

		startForeground(taskId, task.getNotification().showNotification(initialContent, 0));

		downloadManager.startTask(taskId, task, new ProgressCallback() {
			@Override
			public void onProgress(int progress, @Nullable String message) {
				if (task.getState() == DownloaderState.CANCELLED) return;
				task.setState(DownloaderState.DOWNLOADING);
				task.getNotification().updateProgress(progress, message != null ? message : (task.isSubtitle() ? getString(R.string.downloading_subtitle) : (task.isAudio() ? getString(R.string.downloading_audio) : getString(R.string.downloading_video))));
			}

			@Override
			public void onComplete(@NonNull File file) {
				if (task.getState() == DownloaderState.CANCELLED) return;
				task.setState(DownloaderState.FINISHED);
				showToast(String.format(getString(R.string.download_finished), fileName, file.getPath()));
				task.setOutput(file);
				MediaScannerConnection.scanFile(DownloadService.this, new String[]{file.getAbsolutePath()}, null, null);
				task.getNotification().completeDownload(String.format(getString(R.string.download_finished), fileName, file.getPath()), file, task.isSubtitle() ? "text/plain" : (task.isAudio() ? "audio/*" : "video/*"));
				onTaskTerminated();
			}

			@Override
			public void onError(@NonNull Exception error) {
				if (task.getState() == DownloaderState.CANCELLED) return;
				task.setState(DownloaderState.STOPPED);
				Log.e(getString(R.string.failed_to_download), Log.getStackTraceString(error));
				showToast(getString(R.string.failed_to_download) + ": " + error.getMessage());
				task.getNotification().cancelDownload(getString(R.string.failed_to_download));
				
				// Show error dialog if MainActivity is available
				new Handler(Looper.getMainLooper()).post(() -> {
					MainActivity activity = MainActivity.getInstance();
					if (activity != null) {
						ErrorDialog.show(activity, getString(R.string.failed_to_download), Log.getStackTraceString(error));
					}
				});
				
				onTaskTerminated();
			}

			@Override
			public void onCancel() {
				task.setState(DownloaderState.CANCELLED);
				showToast(getString(R.string.download_canceled));
				task.getNotification().cancelDownload(getString(R.string.download_canceled));
				onTaskTerminated();
			}

			@Override
			public void onMerge() {
				if (task.getState() == DownloaderState.CANCELLED) return;
				task.setState(DownloaderState.MERGING);
				task.getNotification().startMuxing(getString(R.string.merging_audio_video));
			}
		}, this);
	}


	private void cancelDownload(final int taskId) {
		downloadManager.cancelTask(taskId);
		onTaskTerminated();
	}

	private void deleteDownload(final int taskId) {
		final DownloadTask task = downloadManager.getTask(taskId);
		if (task != null) {
			task.setState(DownloaderState.CANCELLED);

			if (task.getOutput() != null && task.getOutput().exists()) {
				try {
					FileUtils.forceDelete(task.getOutput());
					showToast(getString(R.string.file_deleted));
				} catch (final IOException e) {
					Log.e(getString(R.string.failed_to_delete), Log.getStackTraceString(e));
					showToast(getString(R.string.failed_to_delete));
				}
			}
			if (task.getNotification() != null) task.getNotification().clearDownload();

			onTaskTerminated();
		}
	}

	private synchronized void onTaskTerminated() {
		final boolean hasActiveTasks = downloadManager.getTasks().values().stream().anyMatch(task -> {
			final DownloaderState state = task.getState();
			return state == DownloaderState.RUNNING || state == DownloaderState.DOWNLOADING || state == DownloaderState.MERGING;
		});

		if (!hasActiveTasks) stopForeground(false);
	}

	@Override
	public boolean onUnbind(@NonNull final Intent intent) {
		return super.onUnbind(intent);
	}

	@Override
	public void onTaskRemoved(@NonNull final Intent rootIntent) {
		super.onTaskRemoved(rootIntent);
		stopForeground(true);
		stopSelf();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		// Stop the foreground service and remove the notification
		stopForeground(true);

		// Cancel all downloads
		cancelAllDownloads();
	}

	private void cancelAllDownloads() {
		downloadManager.getTasks().keySet().forEach(taskId -> {
			final DownloadTask task = downloadManager.getTask(taskId);
			if (task != null && (task.getState() == DownloaderState.RUNNING || task.getState() == DownloaderState.DOWNLOADING || task.getState() == DownloaderState.MERGING)) {
				task.setState(DownloaderState.CANCELLED);
				downloadManager.cancelTask(taskId);
				if (task.getNotification() != null)
					task.getNotification().cancelDownload(getString(R.string.download_canceled));
			}
		});
		downloadManager.getTasks().clear();
	}

	public class DownloadBinder extends Binder {
		public DownloadService getService() {
			return DownloadService.this;
		}
	}
}
