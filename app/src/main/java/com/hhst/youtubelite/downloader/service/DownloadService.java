package com.hhst.youtubelite.downloader.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
import com.hhst.youtubelite.ui.MainActivity;

import java.io.File;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
@UnstableApi
public class DownloadService extends Service {

	public static final String ACTION_DOWNLOAD_RECORD_UPDATED = "com.hhst.youtubelite.action.DOWNLOAD_RECORD_UPDATED";
	public static final String EXTRA_TASK_ID = "extra_task_id";
	private static final String TAG = "DownloadService";
	private static final String CHANNEL_ID = "download_channel";
	private static final int NOTIFICATION_ID = 1001;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	@Inject
	LiteDownloader liteDL;
	@Inject
	DownloadHistoryRepository historyRepository;

	@NonNull
	private static DownloadType inferType(@NonNull final Task task) {
		if (task.thumbnail() != null) return DownloadType.THUMBNAIL;
		if (task.subtitle() != null) return DownloadType.SUBTITLE;
		if (task.video() != null) return DownloadType.VIDEO;
		return DownloadType.AUDIO;
	}

	@NonNull
	private static String extractBaseVid(@NonNull final String taskId) {
		final int idx = taskId.indexOf(':');
		return idx > 0 ? taskId.substring(0, idx) : taskId;
	}

	@NonNull
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
	}

	@Override
	public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
		return START_NOT_STICKY;
	}

	@Nullable
	@Override
	public IBinder onBind(@NonNull final Intent intent) {
		return new DownloadBinder();
	}

	public void download(@NonNull List<Task> tasks) {
		for (Task task : tasks) {
			final String taskId = task.vid();
			final DownloadType type = inferType(task);
			final String baseVid = extractBaseVid(taskId);
			final File expectedOut = expectedOutputFile(task, type);

			final long now = System.currentTimeMillis();
			final DownloadRecord existing = historyRepository.findByTaskId(taskId);
			final long createdAt = existing != null ? existing.getCreatedAt() : now;
			historyRepository.upsert(new DownloadRecord(taskId, baseVid, type, DownloadStatus.RUNNING, 0, task.fileName(), expectedOut.getAbsolutePath(), createdAt, now, null));
			broadcastRecordUpdated(taskId);

			liteDL.setCallback(taskId, new ProgressCallback2() {
				@Override
				public void onProgress(int progress) {
					updateRecordProgress(taskId, progress, DownloadStatus.RUNNING, null, null);
				}

				@Override
				public void onComplete(File file) {
					updateRecordProgress(taskId, 100, DownloadStatus.COMPLETED, file.getAbsolutePath(), null);
					showToast(getString(R.string.download_finished, file.getName(), file.getParent()));
					MediaScannerConnection.scanFile(DownloadService.this, new String[]{file.getAbsolutePath()}, null, null);
				}

				@Override
				public void onError(Exception error) {
					Log.e(TAG, "Download error", error);
					updateRecordProgress(taskId, -1, DownloadStatus.FAILED, null, error.getMessage());
					showToast(getString(R.string.failed_to_download) + ": " + error.getMessage());
				}

				@Override
				public void onCancel() {
					updateRecordProgress(taskId, -1, DownloadStatus.CANCELED, null, null);
					showToast(getString(R.string.download_canceled));
				}

				@Override
				public void onMerge() {
					updateRecordProgress(taskId, -1, DownloadStatus.MERGING, null, null);
				}
			});
			liteDL.download(task);
		}

		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
		notificationManager.createNotificationChannel(channel);

		Intent clickIntent = new Intent(this, MainActivity.class);
		clickIntent.setAction("OPEN_DOWNLOADS");
		clickIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, clickIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(getString(R.string.download_tasks_added)).setContentText(getString(R.string.download_tasks_added_count, tasks.size())).setContentIntent(pendingIntent).setOngoing(false).setAutoCancel(true);

		notificationManager.notify(NOTIFICATION_ID, builder.build());
	}

	public void cancel(@NonNull String vid) {
		liteDL.cancel(vid);
	}

	private void showToast(@NonNull final String content) {
		mainHandler.post(() -> Toast.makeText(this, content, Toast.LENGTH_SHORT).show());
	}

	private void updateRecordProgress(@NonNull final String taskId, final int progress, @NonNull final DownloadStatus status, @Nullable final String outputPath, @Nullable final String errorMessage) {
		final DownloadRecord record = historyRepository.findByTaskId(taskId);
		if (record == null) return;
		final long now = System.currentTimeMillis();
		if (progress >= 0) record.setProgress(progress);
		record.setStatus(status);
		record.setUpdatedAt(now);
		if (outputPath != null) record.setOutputPath(outputPath);
		record.setErrorMessage(errorMessage);
		historyRepository.upsert(record);
		broadcastRecordUpdated(taskId);
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
