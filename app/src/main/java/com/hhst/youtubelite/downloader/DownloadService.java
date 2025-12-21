package com.hhst.youtubelite.downloader;

import android.app.Service;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.hhst.youtubelite.R;
import com.liulishuo.filedownloader.FileDownloader;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadService extends Service {

	private final AtomicInteger taskIdCounter = new AtomicInteger(1);
	private ConcurrentHashMap<Integer, DownloadTask> download_tasks;
	private ExecutorService download_executor;

	@Override
	public void onCreate() {
		super.onCreate();
		download_tasks = new ConcurrentHashMap<>();
		download_executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		FileDownloader.setup(this);
		FileDownloader.getImpl().setMaxNetworkThreadCount(4);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null) {
			return START_NOT_STICKY;
		}

		String action = intent.getAction();
		int taskId = intent.getIntExtra("taskId", -1);
		if ("CANCEL_DOWNLOAD".equals(action)) {
			cancelDownload(taskId);
		} else if ("DELETE_DOWNLOAD".equals(action)) {
			deleteDownload(taskId);
		} else if ("DOWNLOAD_THUMBNAIL".equals(action)) {
			String url = intent.getStringExtra("thumbnail");
			String filename = intent.getStringExtra("filename");
			File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), getString(R.string.app_name));
			File outputFile = new File(outputDir, filename + ".jpg");
			downloadThumbnail(url, outputFile);
		}
		return super.onStartCommand(intent, flags, startId);
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return new DownloadBinder();
	}

	private void showToast(String content) {
		new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this, content, Toast.LENGTH_SHORT).show());
	}

	private void downloadThumbnail(String thumbnail, File outputFile) {
		if (thumbnail != null) {
			download_executor.submit(() -> {
				try {
					FileUtils.copyURLToFile(new URL(thumbnail), outputFile);
					// notify to scan
					MediaScannerConnection.scanFile(this, new String[]{outputFile.getAbsolutePath()}, null, null);
					showToast(getString(R.string.thumbnail_has_been_saved_to) + outputFile);
				} catch (Exception e) {
					Log.e(getString(R.string.failed_to_download_thumbnail), Log.getStackTraceString(e));
					showToast(getString(R.string.failed_to_download_thumbnail));
				}
			});
		}
	}

	public void initiateDownload(DownloadTask task) {
		task.setState(DownloaderState.RUNNING);

		download_executor.submit(() -> {
			// Check and create output directory
			File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), getString(R.string.app_name));
			if (!outputDir.exists()) {
				boolean ignored = outputDir.mkdirs();
			}
			if (task.getVideoStream() != null) {
				DownloadTask videoTask = task.clone();
				videoTask.setFileName(String.format("%s(%s)", task.getFileName(), task.getVideoStream().getResolution()));
				videoTask.setOutput(new File(outputDir, task.getFileName() + ".mp4"));
				videoTask.setIsAudio(false);
				executeDownload(videoTask);
			}
			if (task.getIsAudio()) {
				DownloadTask audioTask = task.clone();
				audioTask.setFileName(String.format("(audio only) %s", task.getFileName()));
				audioTask.setOutput(new File(outputDir, audioTask.getFileName() + ".m4a"));
				audioTask.setVideoStream(null);
				executeDownload(audioTask);
			}
		});
	}

	private void executeDownload(DownloadTask task) {
		int taskId = taskIdCounter.getAndIncrement();

		task.setState(DownloaderState.RUNNING);
		download_tasks.put(taskId, task);

		DownloadNotification notification = new DownloadNotification(this, taskId);
		task.setNotification(notification);

		String fileName = task.getFileName();
		// Show initial notification
		String initialContent = task.getIsAudio() ? getString(R.string.downloading_audio) + ": " + fileName : getString(R.string.downloading_video) + ": " + fileName;

		startForeground(taskId, task.getNotification().showNotification(initialContent, 0));

		File output = task.getOutput();
		task.setOutput(output);

		// Download using YoutubeDownloader
		YoutubeDownloader.download("DownloadTask#" + taskId, task.getVideoStream(), task.getAudioStream(), output, new ProgressCallback() {
			@Override
			public void onProgress(int progress, String message) {
				if (task.getState() == DownloaderState.CANCELLED) return;

				task.setState(DownloaderState.DOWNLOADING);
				task.getNotification().updateProgress(progress, message);
			}

			@Override
			public void onComplete(File file) {
				if (task.getState() == DownloaderState.CANCELLED) return;
				task.setState(DownloaderState.FINISHED);

				showToast(String.format(getString(R.string.download_finished), fileName, file.getPath()));
				task.setOutput(file);
				// notify to scan
				MediaScannerConnection.scanFile(DownloadService.this, new String[]{file.getAbsolutePath()}, null, null);

				task.getNotification().completeDownload(String.format(getString(R.string.download_finished), fileName, file.getPath()), file, task.getIsAudio() ? "audio/*" : "video/*");

				onTaskTerminated();
			}

			@Override
			public void onError(Exception error) {
				if (task.getState() == DownloaderState.CANCELLED) return;
				task.setState(DownloaderState.STOPPED);

				Log.e(getString(R.string.failed_to_download), Log.getStackTraceString(error));
				showToast(getString(R.string.failed_to_download) + ": " + error.getMessage());
				task.getNotification().cancelDownload(getString(R.string.failed_to_download));

				onTaskTerminated();
			}

			@Override
			public void onCancel() {
				task.setState(DownloaderState.CANCELLED);

				Log.e(getString(R.string.failed_to_download), "Download canceled by user");
				showToast(getString(R.string.download_canceled));
				task.getNotification().cancelDownload(getString(R.string.download_canceled));
				onTaskTerminated();
			}

			@Override
			public void onMerge() {
				if (task.getState() == DownloaderState.CANCELLED) return;
				task.setState(DownloaderState.Merging);
				task.getNotification().startMuxing(getString(R.string.merging_audio_video));
			}
		}, this);
	}

	private void cancelDownload(int taskId) {
		DownloadTask task = download_tasks.get(taskId);
		if (task != null) {
			task.setState(DownloaderState.CANCELLED);
			// Cancel download
			YoutubeDownloader.cancel("DownloadTask#" + taskId);

			if (task.getNotification() != null) {
				task.getNotification().cancelDownload(getString(R.string.download_canceled));
			}
			onTaskTerminated();
		}
	}

	private void deleteDownload(int taskId) {
		DownloadTask task = download_tasks.get(taskId);
		if (task != null) {
			task.setState(DownloaderState.CANCELLED);

			if (task.getOutput() != null && task.getOutput().exists()) {
				try {
					FileUtils.forceDelete(task.getOutput());
					showToast(getString(R.string.file_deleted));
				} catch (IOException e) {
					Log.e(getString(R.string.failed_to_delete), Log.getStackTraceString(e));
					showToast(getString(R.string.failed_to_delete));
				}
			}
			if (task.getNotification() != null) {
				task.getNotification().clearDownload();
			}

			onTaskTerminated();
		}
	}

	private synchronized void onTaskTerminated() {
		boolean hasActiveTasks = false;
		for (DownloadTask task : download_tasks.values()) {
			DownloaderState state = task.getState();
			if (state == DownloaderState.RUNNING || state == DownloaderState.DOWNLOADING || state == DownloaderState.Merging) {
				hasActiveTasks = true;
				break;
			}
		}

		if (!hasActiveTasks) {
			stopForeground(false);
		}
	}

	@Override
	public boolean onUnbind(Intent intent) {
		return super.onUnbind(intent);
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
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

		// Shutdown the executor service
		download_executor.shutdown();
		try {
			if (!download_executor.awaitTermination(5, TimeUnit.SECONDS)) {
				download_executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			download_executor.shutdownNow();
		}
	}

	private void cancelAllDownloads() {
		if (download_tasks != null) {
			for (Integer taskId : download_tasks.keySet()) {
				DownloadTask task = download_tasks.get(taskId);
				if (task != null && (task.getState() == DownloaderState.RUNNING || task.getState() == DownloaderState.DOWNLOADING || task.getState() == DownloaderState.Merging)) {

					task.setState(DownloaderState.CANCELLED);

					YoutubeDownloader.cancel("DownloadTask#" + taskId);

					if (task.getNotification() != null) {
						task.getNotification().cancelDownload(getString(R.string.download_canceled));
					}
				}
			}

			download_tasks.clear();
		}
	}

	public class DownloadBinder extends Binder {
		public DownloadService getService() {
			return DownloadService.this;
		}
	}
}
