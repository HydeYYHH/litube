package com.hhst.youtubelite.downloader.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.ui.MainActivity;
import com.hhst.youtubelite.util.DownloadStorageUtils;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@UnstableApi
@AndroidEntryPoint
public class DownloadService extends Service {

	public static final String ACTION_DOWNLOAD_RECORD_UPDATED = "com.hhst.youtubelite.action.DOWNLOAD_RECORD_UPDATED";
	public static final String EXTRA_TASK_ID = "extra_task_id";

	private static final String CHANNEL_ID = "download_channel";
	private static final int NOTIFICATION_ID = 1001;
	private static final long FAILED_TEMP_CLEANUP_THRESHOLD = TimeUnit.DAYS.toMillis(1);

	private final Set<String> activeTaskIds = ConcurrentHashMap.newKeySet();
	private final Map<String, String> activeTaskNames = new ConcurrentHashMap<>();
	private final Map<String, Task> activeTasks = new ConcurrentHashMap<>();
	private SharedPreferences itagPrefs;

	@Inject
	LiteDownloader liteDL;
	@Inject
	DownloadHistoryRepository historyRepository;
	@Inject
	YoutubeExtractor youtubeExtractor;

	private NotificationManager notificationManager;
	private NotificationCompat.Builder notificationBuilder;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	@Override
	public void onCreate() {
		super.onCreate();
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		itagPrefs = getSharedPreferences("download_itags", Context.MODE_PRIVATE);
		createNotificationChannel();
		cleanupOldFailedTemps();
	}

	private void cleanupOldFailedTemps() {
		new Thread(() -> {
			long now = System.currentTimeMillis();
			List<DownloadRecord> all = historyRepository.getAllSorted();
			for (DownloadRecord r : all) {
				if (r.getStatus() == DownloadStatus.FAILED && (now - r.getUpdatedAt() > FAILED_TEMP_CLEANUP_THRESHOLD)) {
					liteDL.cancel(r.getTaskId());
					String baseName = r.getFileName();
					File cacheDir = getCacheDir();
					deleteFile(new File(cacheDir, baseName + "_v.tmp"));
					deleteFile(new File(cacheDir, baseName + "_a.tmp"));
					deleteFile(new File(cacheDir, baseName + "_m.tmp"));
				}
			}
		}).start();
	}

	private void deleteFile(File file) {
		if (file.exists()) file.delete();
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
			channel.setSound(null, null);
			if (notificationManager != null) notificationManager.createNotificationChannel(channel);
		}
	}

	@Nullable
	@Override
	public IBinder onBind(@NonNull final Intent intent) {
		return new DownloadBinder();
	}

	public void download(@NonNull List<Task> tasks) {
		if (tasks.isEmpty()) return;
		ensureForeground();
		for (Task task : tasks) {
			startTask(task);
		}
	}

	private void startTask(@NonNull Task task) {
		final String taskId = task.vid();
		activeTasks.put(taskId, task);
		activeTaskIds.add(taskId);
		activeTaskNames.put(taskId, task.fileName());
		saveItags(task);

		DownloadRecord record = historyRepository.findByTaskId(taskId);
		long now = System.currentTimeMillis();

		DownloadType type;
		String ext;
		if (task.video() != null) {
			type = DownloadType.VIDEO;
			ext = ".mp4";
		} else if (task.audio() != null) {
			type = DownloadType.AUDIO;
			ext = ".m4a";
		} else if (task.subtitle() != null) {
			type = DownloadType.SUBTITLE;
			ext = "." + task.subtitle().getExtension();
		} else {
			type = DownloadType.THUMBNAIL;
			ext = ".jpg";
		}

		String outPath = new File(task.desDir(), task.fileName() + ext).getAbsolutePath();

		if (record == null) {
			record = new DownloadRecord(taskId, taskId, type, DownloadStatus.RUNNING, 0,
							task.fileName(), outPath, now, now, null, 0L, 0L, task.quality());
		} else {
			record.setStatus(DownloadStatus.RUNNING);
			record.setUpdatedAt(now);
			record.setFileName(task.fileName());
			record.setOutputPath(outPath);
			record.setType(type);
			record.setQuality(task.quality());
		}
		historyRepository.upsert(record);
		broadcastRecordUpdated(taskId);

		attachCallback(taskId);
		liteDL.download(task);
	}

	private void attachCallback(final String taskId) {
		liteDL.setCallback(taskId, new ProgressCallback2() {
			@Override
			public void onProgress(int progress, long d, long t) {
				updateRecordProgress(taskId, progress, d, t, DownloadStatus.RUNNING);
				updateNotificationProgress(getTaskFileName(taskId), progress);
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
					onTaskCompleted(taskId, getTaskFileName(taskId), false);
				}
			}

			@Override
			public void onError(Exception error) {
				updateRecordProgress(taskId, -1, -1, -1, DownloadStatus.FAILED);
				onTaskCompleted(taskId, getTaskFileName(taskId), false);
			}

			@Override
			public void onCancel() {
				updateRecordProgress(taskId, -1, -1, -1, DownloadStatus.CANCELED);
				onTaskCancelled(taskId);
			}

			@Override
			public void onMerge() {
				updateRecordStatus(taskId, DownloadStatus.MERGING);
				updateNotificationMerging(getTaskFileName(taskId));
			}
		});
	}

	private void updateRecordProgress(String taskId, int p, long d, long t, DownloadStatus status) {
		DownloadRecord record = historyRepository.findByTaskId(taskId);
		if (record != null) {
			if (p >= 0) record.setProgress(p);
			if (d >= 0) record.setDownloadedSize(d);
			if (t >= 0) record.setTotalSize(t);
			record.setStatus(status);
			record.setUpdatedAt(System.currentTimeMillis());
			historyRepository.upsert(record);
			broadcastRecordUpdated(taskId);
		}
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

	private void updateRecordStatus(String taskId, DownloadStatus status) {
		DownloadRecord record = historyRepository.findByTaskId(taskId);
		if (record != null) {
			record.setStatus(status);
			record.setUpdatedAt(System.currentTimeMillis());
			historyRepository.upsert(record);
			broadcastRecordUpdated(taskId);
		}
	}

	public void pause(@NonNull String vid) {
		liteDL.pause(vid);
		updateRecordStatus(vid, DownloadStatus.PAUSED);
		updateNotificationPaused(vid);
	}

	public void resume(@NonNull String vid) {
		ensureForeground();
		updateRecordStatus(vid, DownloadStatus.QUEUED);
		DownloadRecord record = historyRepository.findByTaskId(vid);
		if (record != null) {
			new Thread(() -> reExtractAndResume(record)).start();
		}
	}

	private void reExtractAndResume(DownloadRecord record) {
		try {
			StreamDetails si = youtubeExtractor.getStreamInfo("https://www.youtube.com/watch?v=" + record.getVid().split(":")[0]);
			int vItag = itagPrefs.getInt(record.getTaskId() + "_v_itag", -1);
			int aItag = itagPrefs.getInt(record.getTaskId() + "_a_itag", -1);

			VideoStream video = si.getVideoStreams().stream()
							.filter(s -> s.getItag() == vItag)
							.findFirst()
							.orElse(si.getVideoStreams().isEmpty() ? null : si.getVideoStreams().get(0));

			AudioStream audio = si.getAudioStreams().stream()
							.filter(s -> s.getItag() == aItag)
							.findFirst()
							.orElse(si.getAudioStreams().isEmpty() ? null : si.getAudioStreams().get(0));

			Task newTask = new Task(record.getTaskId(), video, audio, null, null, record.getFileName(),
							new File(record.getOutputPath()).getParentFile(), 4, record.getQuality());

			mainHandler.post(() -> startTask(newTask));
		} catch (Exception e) {
			mainHandler.post(() -> updateRecordStatus(record.getTaskId(), DownloadStatus.FAILED));
		}
	}

	public void cancel(@NonNull String vid) {
		liteDL.cancel(vid);
		onTaskCancelled(vid);
		broadcastRecordUpdated(vid);
	}

	public void cancelByPrefix(String prefix) {
		for (String vid : activeTasks.keySet()) {
			Task t = activeTasks.get(vid);
			if (t != null && t.fileName().startsWith(prefix)) {
				cancel(vid);
			}
		}
	}

	private synchronized void ensureForeground() {
		if (notificationBuilder == null) {
			notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
							.setSmallIcon(R.drawable.ic_download)
							.setContentTitle("LitePipe Downloader")
							.setContentIntent(createContentIntent())
							.setPriority(NotificationCompat.PRIORITY_LOW)
							.setOngoing(true);
		}
		startForeground(NOTIFICATION_ID, notificationBuilder.build());
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
							.setContentText("Please wait...")
							.setOngoing(true)
							.setProgress(100, 0, true);
			notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
		}
	}

	private synchronized void updateNotificationPaused(String vid) {
		if (notificationBuilder != null) {
			notificationBuilder.setContentTitle("Paused: " + getTaskFileName(vid))
							.setContentText("Tap to view")
							.setProgress(0, 0, false)
							.setOngoing(false);
			notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
			stopForeground(STOP_FOREGROUND_DETACH);
		}
	}

	private synchronized void updateNotificationForRemainingTasks() {
		if (notificationBuilder == null || activeTaskIds.isEmpty()) return;
		final int remaining = activeTaskIds.size();
		final String fileName = activeTaskNames.values().stream().findFirst().orElse("Download");
		notificationBuilder.setOngoing(true)
						.setAutoCancel(false)
						.setProgress(0, 0, remaining > 1)
						.setContentTitle(remaining == 1
										? "Downloading: " + fileName
										: remaining + " downloads running")
						.setContentText(remaining == 1
										? "Queued"
										: "Tap to view all");
		notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
	}

	private synchronized void onTaskCompleted(@NonNull final String taskId, @NonNull final String fileName, final boolean success) {
		activeTaskIds.remove(taskId);
		activeTaskNames.remove(taskId);
		activeTasks.remove(taskId);
		if (activeTaskIds.isEmpty()) {
			finalizeNotification(fileName, success);
		} else {
			updateNotificationForRemainingTasks();
		}
	}

	private synchronized void onTaskCancelled(@NonNull final String taskId) {
		activeTaskIds.remove(taskId);
		activeTaskNames.remove(taskId);
		activeTasks.remove(taskId);
		if (activeTaskIds.isEmpty()) {
			stopForeground(STOP_FOREGROUND_REMOVE);
			if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
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

	private void saveItags(Task t) {
		SharedPreferences.Editor e = itagPrefs.edit();
		if (t.video() != null) e.putInt(t.vid() + "_v_itag", t.video().getItag());
		if (t.audio() != null) e.putInt(t.vid() + "_a_itag", t.audio().getItag());
		e.apply();
	}

	private String getTaskFileName(String id) {
		String name = activeTaskNames.get(id);
		if (name != null) return name;
		DownloadRecord record = historyRepository.findByTaskId(id);
		return record != null ? record.getFileName() : "Download";
	}

	private void broadcastRecordUpdated(String tid) {
		Intent intent = new Intent(ACTION_DOWNLOAD_RECORD_UPDATED);
		intent.putExtra(EXTRA_TASK_ID, tid);
		intent.setPackage(getPackageName());
		sendBroadcast(intent);
	}

	private PendingIntent createContentIntent() {
		Intent intent = new Intent(this, MainActivity.class);
		intent.setAction("OPEN_DOWNLOADS");
		return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
	}

	public class DownloadBinder extends Binder {
		public DownloadService getService() {
			return DownloadService.this;
		}
	}
}
