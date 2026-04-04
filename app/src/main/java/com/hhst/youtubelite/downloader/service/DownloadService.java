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
import com.hhst.youtubelite.downloader.core.DownloadTaskIdHelper;
import com.hhst.youtubelite.downloader.core.LiteDownloader;
import com.hhst.youtubelite.downloader.core.ProgressCallback2;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.downloader.core.history.DownloadHistoryRepository;
import com.hhst.youtubelite.downloader.core.history.DownloadRecord;
import com.hhst.youtubelite.downloader.core.history.DownloadStatus;
import com.hhst.youtubelite.downloader.core.history.DownloadType;
import com.hhst.youtubelite.ui.MainActivity;
import com.hhst.youtubelite.util.DownloadStorageUtils;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Foreground service for download jobs and records.
 */
@AndroidEntryPoint
@UnstableApi
public class DownloadService extends Service {
	public static final String ACTION_DOWNLOAD_RECORD_UPDATED = "com.hhst.youtubelite.action.DOWNLOAD_RECORD_UPDATED";
	public static final String EXTRA_TASK_ID = "extra_task_id";
	private static final String CHANNEL_ID = "download_channel";
	private static final int NOTIFICATION_ID = 1001;
	private final Set<String> activeIds = ConcurrentHashMap.newKeySet();
	private final Map<String, String> activeNames = new ConcurrentHashMap<>();
	@Inject
	LiteDownloader downloader;
	@Inject
	DownloadHistoryRepository historyRepository;
	private NotificationManager notificationManager;
	private NotificationCompat.Builder notificationBuilder;

	private static DownloadType inferType(@NonNull Task task) {
		if (task.thumbnail() != null) return DownloadType.THUMBNAIL;
		if (task.subtitle() != null) return DownloadType.SUBTITLE;
		if (task.video() != null) return DownloadType.VIDEO;
		return DownloadType.AUDIO;
	}

	private static File expectedOutputFile(@NonNull Task task, @NonNull DownloadType type) {
		return switch (type) {
			case PLAYLIST -> new File(task.desDir(), task.fileName());
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
	public IBinder onBind(@NonNull Intent intent) {
		return new DownloadBinder();
	}

	public void download(@NonNull List<Task> tasks) {
		if (tasks.isEmpty()) return;
		initNotification();
		for (Task task : tasks) {
			startTask(task);
		}
	}

	public void upsertPlaylistRecord(@NonNull DownloadRecord record) {
		historyRepository.upsert(record);
		broadcastRecordUpdated(record.getTaskId());
	}

	public void refreshPlaylistRecord(@NonNull String taskId) {
		updateParentRecord(taskId);
	}

	private void startTask(@NonNull Task task) {
		String taskId = task.videoId();
		DownloadType type = inferType(task);
		File expectedOut = expectedOutputFile(task, type);
		long now = System.currentTimeMillis();

		// Keep the record in sync before the download callbacks start.
		DownloadRecord prev = historyRepository.findByTaskId(taskId);
		long createdAt = prev != null ? prev.getCreatedAt() : now;
		DownloadRecord record = new DownloadRecord();
		record.setTaskId(taskId);
		record.setVideoId(DownloadTaskIdHelper.extractVidId(taskId));
		record.setType(type);
		record.setStatus(DownloadStatus.RUNNING);
		record.setProgress(0);
		record.setFileName(task.fileName());
		record.setOutputPath(expectedOut.getAbsolutePath());
		record.setCreatedAt(createdAt);
		record.setUpdatedAt(now);
		record.setDownloadedSize(0L);
		record.setTotalSize(0L);
		record.setParentId(task.parentId());
		record.setTitle(task.title() == null || task.title().isBlank() ? task.fileName() : task.title());
		record.setThumbnailUrl(task.thumbUrl());
		historyRepository.upsert(record);
		broadcastRecordUpdated(taskId);
		updateParentRecord(record.getParentId());
		activeIds.add(taskId);
		activeNames.put(taskId, task.fileName());

		downloader.setCallback(taskId, new ProgressCallback2() {
			@Override
			public void onProgress(int progress, long downloaded, long total) {
				updateRecordProgress(taskId, progress, downloaded, total, DownloadStatus.RUNNING);
				updateNotificationProgress(task.fileName(), progress);
			}

			@Override
			public void onComplete(File file) {
				long fileSize = file.length();
				try {
					String outputReference = DownloadStorageUtils.publishToDownloads(DownloadService.this, file, file.getName());
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
		downloader.download(task);
	}

	public void cancel(@NonNull String taskId) {
		settleCanceledRecord(taskId);
		downloader.cancel(taskId);
	}

	private void settleCanceledRecord(@NonNull String taskId) {
		DownloadRecord record = historyRepository.findByTaskId(taskId);
		if (record == null) {
			onTaskCancelled(taskId);
			return;
		}
		if (record.getStatus() != DownloadStatus.COMPLETED
						&& record.getStatus() != DownloadStatus.CANCELED) {
			record.setStatus(DownloadStatus.CANCELED);
			record.setUpdatedAt(System.currentTimeMillis());
			historyRepository.upsert(record);
			broadcastRecordUpdated(taskId);
			updateParentRecord(record.getParentId());
		}
		onTaskCancelled(taskId);
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
		updateParentRecord(record.getParentId());
	}

	private void markRecordCompleted(@NonNull String taskId, @NonNull String outputReference, long fileSize) {
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
		updateParentRecord(record.getParentId());
	}

	private void updateParentRecord(@Nullable String parentId) {
		if (parentId == null || parentId.isBlank()) return;
		DownloadRecord parent = historyRepository.findByTaskId(parentId);
		if (parent == null) return;
		List<DownloadRecord> children = historyRepository.getChildrenSorted(parentId);
		Map<String, DownloadStatus> itemStates = new LinkedHashMap<>();
		for (DownloadRecord child : children) {
			itemStates.merge(
							DownloadTaskIdHelper.extractItemKey(child.getTaskId()),
							child.getStatus(),
							(left, right) -> {
								if (left == DownloadStatus.RUNNING
												|| left == DownloadStatus.MERGING
												|| left == DownloadStatus.QUEUED
												|| left == DownloadStatus.PAUSED
												|| right == DownloadStatus.RUNNING
												|| right == DownloadStatus.MERGING
												|| right == DownloadStatus.QUEUED
												|| right == DownloadStatus.PAUSED) {
									return DownloadStatus.RUNNING;
								}
								if (left == DownloadStatus.FAILED || right == DownloadStatus.FAILED)
									return DownloadStatus.FAILED;
								if (left == DownloadStatus.CANCELED || right == DownloadStatus.CANCELED)
									return DownloadStatus.CANCELED;
								if (left == DownloadStatus.COMPLETED && right == DownloadStatus.COMPLETED)
									return DownloadStatus.COMPLETED;
								return right;
							});
		}

		int itemCount = Math.max(parent.getItemCount(), itemStates.size());
		int missingCount = Math.max(0, itemCount - itemStates.size());
		int done = 0;
		int failed = 0;
		int canceled = 0;
		int running = 0;
		for (DownloadStatus status : itemStates.values()) {
			switch (status) {
				case COMPLETED -> done++;
				case FAILED -> failed++;
				case CANCELED -> canceled++;
				default -> running++;
			}
		}
		if (parent.isSealed()) failed += missingCount;
		else running += missingCount;

		parent.setItemCount(itemCount);
		parent.setDoneCount(done);
		parent.setFailedCount(failed + canceled);
		parent.setRunningCount(running);
		parent.setProgress(itemCount == 0 ? 0 : Math.min(100, (done * 100) / itemCount));
		parent.setUpdatedAt(System.currentTimeMillis());
		if (running > 0 || itemStates.size() < itemCount) {
			parent.setStatus(DownloadStatus.RUNNING);
		} else if (done >= itemCount && itemCount > 0) {
			parent.setStatus(DownloadStatus.COMPLETED);
		} else if (failed == 0 && canceled > 0 && done + canceled >= itemCount) {
			parent.setStatus(DownloadStatus.CANCELED);
		} else if (failed > 0) {
			parent.setStatus(DownloadStatus.FAILED);
		} else {
			parent.setStatus(DownloadStatus.QUEUED);
		}
		historyRepository.upsert(parent);
		broadcastRecordUpdated(parentId);
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

	private synchronized void updateRemainingNotification() {
		if (notificationBuilder == null || activeIds.isEmpty()) return;
		int remaining = activeIds.size();
		String fileName = activeNames.values().stream().findFirst().orElse(getString(R.string.download));
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

	private synchronized void onTaskCompleted(@NonNull String taskId, @NonNull String fileName, boolean success) {
		activeIds.remove(taskId);
		activeNames.remove(taskId);
		if (activeIds.isEmpty()) {
			finalizeNotification(fileName, success);
		} else {
			updateRemainingNotification();
		}
	}

	private synchronized void onTaskCancelled(@NonNull String taskId) {
		activeIds.remove(taskId);
		activeNames.remove(taskId);
		if (activeIds.isEmpty()) {
			stopForeground(STOP_FOREGROUND_REMOVE);
			notificationManager.cancel(NOTIFICATION_ID);
			notificationBuilder = null;
		} else {
			updateRemainingNotification();
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

	private void broadcastRecordUpdated(@NonNull String taskId) {
		Intent intent = new Intent(ACTION_DOWNLOAD_RECORD_UPDATED);
		intent.setPackage(getPackageName());
		intent.putExtra(EXTRA_TASK_ID, taskId);
		sendBroadcast(intent);
	}

/**
 * Binder that exposes the foreground download service.
 */
	public class DownloadBinder extends Binder {
		public DownloadService getService() {
			return DownloadService.this;
		}
	}
}
