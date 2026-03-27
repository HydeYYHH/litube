package com.hhst.youtubelite.downloader.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.core.LiteDownloader;
import com.hhst.youtubelite.downloader.core.ProgressCallback2;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.downloader.core.history.DownloadHistoryRepository;
import com.hhst.youtubelite.downloader.core.history.DownloadRecord;
import com.hhst.youtubelite.downloader.core.history.DownloadStatus;
import com.hhst.youtubelite.downloader.core.history.DownloadType;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.ui.MainActivity;
import com.hhst.youtubelite.util.DownloadStorageUtils;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
@UnstableApi
public class DownloadService extends Service {
	public static final String ACTION_DOWNLOAD_RECORD_UPDATED = "com.hhst.youtubelite.action.DOWNLOAD_RECORD_UPDATED";
	public static final String EXTRA_TASK_ID = "extra_task_id";
	private static final String CHANNEL_ID = "download_channel";
	private static final int NOTIFICATION_ID = 1001;
	private final Set<String> activeTaskIds = ConcurrentHashMap.newKeySet();
	private final Map<String, String> activeTaskNames = new ConcurrentHashMap<>();
	@Inject
	LiteDownloader liteDL;
	@Inject
	DownloadHistoryRepository historyRepository;
	@Inject
	YoutubeExtractor youtubeExtractor;
	private NotificationManager notificationManager;
	private NotificationCompat.Builder notificationBuilder;

	private static DownloadType inferType(@NonNull final Task task) {
		if (task.thumbnail() != null) return DownloadType.THUMBNAIL;
		if (task.subtitle() != null) return DownloadType.SUBTITLE;
		if (task.video() != null) return DownloadType.VIDEO;
		return DownloadType.AUDIO;
	}

	private static File expectedOutputFile(@NonNull final Task task, @NonNull final DownloadType type) {
		return switch (type) {
			case THUMBNAIL -> new File(task.desDir(), task.fileName() + ".jpg");
			case SUBTITLE ->
							new File(task.desDir(), task.fileName() + "." + task.subtitle().getExtension());
			case VIDEO -> new File(task.desDir(), task.fileName() + ".mp4");
			case AUDIO -> new File(task.desDir(), task.fileName() + ".m4a");
		};
	}

	@Override
	public void onCreate() {
		super.onCreate();
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		createNotificationChannel();
	}

	private void createNotificationChannel() {
		NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_DEFAULT);
		channel.setDescription("Download progress notifications");
		channel.setSound(null, null);
		notificationManager.createNotificationChannel(channel);
	}

	@Nullable
	@Override
	public IBinder onBind(@NonNull final Intent intent) {
		return new DownloadBinder();
	}

	public void download(@NonNull List<Task> tasks) {
		if (tasks.isEmpty()) return;
		initNotification();
		for (Task task : tasks) {
			startTask(task);
		}
	}

	private void startTask(@NonNull Task task) {
		final String taskId = task.vid();
		final DownloadType type = inferType(task);
		final File expectedOut = expectedOutputFile(task, type);
		final long now = System.currentTimeMillis();

		DownloadRecord existing = historyRepository.findByTaskId(taskId);
		final long createdAt = existing != null ? existing.getCreatedAt() : now;

		DownloadRecord record = new DownloadRecord(taskId, taskId, type, DownloadStatus.RUNNING, 0,
						task.fileName(), expectedOut.getAbsolutePath(), createdAt, now, null, 0L, 0L);
		historyRepository.upsert(record);
		broadcastRecordUpdated(taskId);
		activeTaskIds.add(taskId);
		activeTaskNames.put(taskId, task.fileName());

		liteDL.setCallback(taskId, new ProgressCallback2() {
			@Override
			public void onProgress(int progress, long downloaded, long total) {
				updateRecordProgress(taskId, progress, downloaded, total, DownloadStatus.RUNNING);
				updateNotificationProgress(task.fileName(), progress);
			}

			@Override
			public void onComplete(File file) {
				final long fileSize = file.length();
				try {
					final String outputReference = DownloadStorageUtils.publishToDownloads(DownloadService.this, file, file.getName());
					markRecordCompleted(taskId, outputReference, fileSize);
					onTaskCompleted(taskId, file.getName(), true);
				} catch (Exception e) {
					updateRecordProgress(taskId, -1, -1, -1, DownloadStatus.FAILED);
					onTaskCompleted(taskId, task.fileName(), false);
				}
			}

			@Override
			public void onError(Exception error) {
				updateRecordProgress(taskId, -1, -1, -1, DownloadStatus.FAILED);
				onTaskCompleted(taskId, task.fileName(), false);
			}

			@Override
			public void onCancel() {
				updateRecordProgress(taskId, -1, -1, -1, DownloadStatus.CANCELED);
				onTaskCancelled(taskId);
			}

			@Override
			public void onMerge() {
				updateRecordProgress(taskId, -1, -1, -1, DownloadStatus.MERGING);
				updateNotificationMerging(task.fileName());
			}
		});
		liteDL.download(task);
	}

	public void cancel(@NonNull String vid) {
		liteDL.cancel(vid);
	}

	private void updateRecordProgress(String taskId, int p, long d, long t, DownloadStatus status) {
		DownloadRecord record = historyRepository.findByTaskId(taskId);
		if (record == null) return;
		if (p >= 0) record.setProgress(p);
		if (d >= 0) record.setDownloadedSize(d);
		if (t >= 0) record.setTotalSize(t);
		record.setStatus(status);
		record.setUpdatedAt(System.currentTimeMillis());
		historyRepository.upsert(record);
		broadcastRecordUpdated(taskId);
	}

	private void markRecordCompleted(@NonNull final String taskId, @NonNull final String outputReference, final long fileSize) {
		DownloadRecord record = historyRepository.findByTaskId(taskId);
		if (record == null) return;
		record.setProgress(100);
		record.setDownloadedSize(fileSize);
		record.setTotalSize(fileSize);
		record.setOutputPath(outputReference);
		record.setStatus(DownloadStatus.COMPLETED);
		record.setUpdatedAt(System.currentTimeMillis());
		historyRepository.upsert(record);
		broadcastRecordUpdated(taskId);
	}

	private synchronized void initNotification() {
		notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
						.setSmallIcon(R.drawable.ic_launcher_foreground)
						.setContentTitle("Initializing...")
						.setContentIntent(createContentIntent())
						.setPriority(NotificationCompat.PRIORITY_DEFAULT)
						.setOngoing(true);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			startForeground(NOTIFICATION_ID, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
		} else {
			startForeground(NOTIFICATION_ID, notificationBuilder.build());
		}
	}

	private synchronized void updateNotificationProgress(String fileName, int progress) {
		if (notificationBuilder != null) {
			notificationBuilder.setContentTitle("Downloading: " + fileName)
							.setContentText(progress + "%")
							.setOngoing(true)
							.setProgress(100, progress, false);
			notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
		}
	}

	private synchronized void updateNotificationMerging(String fileName) {
		if (notificationBuilder != null) {
			notificationBuilder.setContentTitle("Merging: " + fileName)
							.setContentText("Finalizing file...")
							.setOngoing(true)
							.setProgress(100, 0, true);
			notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
		}
	}

	private synchronized void updateNotificationForRemainingTasks() {
		if (notificationBuilder == null || activeTaskIds.isEmpty()) return;
		final int remaining = activeTaskIds.size();
		final String fileName = activeTaskNames.values().stream().findFirst().orElse(getString(R.string.download));
		notificationBuilder.setOngoing(true)
						.setAutoCancel(false)
						.setProgress(0, 0, remaining > 1)
						.setContentTitle(remaining == 1
										? getString(R.string.downloading_file, fileName)
										: getString(R.string.downloads_running, remaining))
						.setContentText(remaining == 1
										? getString(R.string.status_queued)
										: getString(R.string.downloads_running, remaining));
		notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
	}

	private synchronized void onTaskCompleted(@NonNull final String taskId, @NonNull final String fileName, final boolean success) {
		activeTaskIds.remove(taskId);
		activeTaskNames.remove(taskId);
		if (activeTaskIds.isEmpty()) {
			finalizeNotification(fileName, success);
		} else {
			updateNotificationForRemainingTasks();
		}
	}

	private synchronized void onTaskCancelled(@NonNull final String taskId) {
		activeTaskIds.remove(taskId);
		activeTaskNames.remove(taskId);
		if (activeTaskIds.isEmpty()) {
			stopForeground(STOP_FOREGROUND_REMOVE);
			notificationManager.cancel(NOTIFICATION_ID);
			notificationBuilder = null;
		} else {
			updateNotificationForRemainingTasks();
		}
	}

	private synchronized void finalizeNotification(String fileName, boolean success) {
		if (notificationBuilder != null) {
			notificationBuilder.setOngoing(false)
							.setAutoCancel(true)
							.setProgress(0, 0, false)
							.setContentTitle(success ? "Download Finished" : "Download Failed")
							.setContentText(fileName);
			notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());

			stopForeground(STOP_FOREGROUND_DETACH);
			notificationBuilder = null;
		}
	}

	private PendingIntent createContentIntent() {
		Intent intent = new Intent(this, MainActivity.class);
		intent.setAction("OPEN_DOWNLOADS");
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
	}

	private void broadcastRecordUpdated(@NonNull final String taskId) {
		final Intent intent = new Intent(ACTION_DOWNLOAD_RECORD_UPDATED);
		intent.setPackage(getPackageName());
		intent.putExtra(EXTRA_TASK_ID, taskId);
		sendBroadcast(intent);
	}

	public class DownloadBinder extends Binder {
		public DownloadService getService() {
			return DownloadService.this;
		}
	}
}
