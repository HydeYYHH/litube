package com.hhst.youtubelite.downloader.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.ui.MainActivity;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

@AndroidEntryPoint
@UnstableApi
public class DownloadService extends Service {

    public static final String ACTION_DOWNLOAD_RECORD_UPDATED = "com.hhst.youtubelite.action.DOWNLOAD_RECORD_UPDATED";
    public static final String EXTRA_TASK_ID = "extra_task_id";
    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 1001;

    @Inject LiteDownloader liteDL;
    @Inject DownloadHistoryRepository historyRepository;
    @Inject YoutubeExtractor youtubeExtractor;

    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Task> activeTasks = new ConcurrentHashMap<>();
    private final Set<String> blockedPrefixes = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private SharedPreferences itagPrefs;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        itagPrefs = getSharedPreferences("download_itags", Context.MODE_PRIVATE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Nullable @Override public IBinder onBind(@NonNull final Intent intent) { return new DownloadBinder(); }

    public void download(@NonNull List<Task> tasks) {
        ensureForeground();
        for (Task task : tasks) startTask(task);
    }

    private void startTask(@NonNull Task task) {
        final String taskId = task.vid();

        if (blockedPrefixes.stream().anyMatch(p -> task.fileName().startsWith(p))) {
            return;
        }

        activeTasks.put(taskId, task);

        SharedPreferences.Editor editor = itagPrefs.edit();
        if (task.video() != null) editor.putInt(taskId + "_v_itag", task.video().getItag());
        if (task.audio() != null) editor.putInt(taskId + "_a_itag", task.audio().getItag());
        editor.apply();

        DownloadRecord existing = historyRepository.findByTaskId(taskId);
        long now = System.currentTimeMillis();
        if (existing == null) {
            DownloadType type = task.video() != null ? DownloadType.VIDEO : DownloadType.AUDIO;
            File out = new File(task.desDir(), task.fileName() + (task.video() != null ? ".mp4" : ".m4a"));
            historyRepository.upsert(new DownloadRecord(taskId, taskId, type, DownloadStatus.RUNNING, 0,
                    task.fileName(), out.getAbsolutePath(), now, now, null, 0L, 0L));
        } else {
            updateRecordStatus(taskId, DownloadStatus.RUNNING);
        }

        broadcastRecordUpdated(taskId);
        attachCallback(taskId);
        liteDL.download(task);
    }

    private void attachCallback(final String taskId) {
        liteDL.setCallback(taskId, new ProgressCallback2() {
            @Override public void onProgress(int progress, long d, long t) {
                updateRecordProgress(taskId, progress, d, t, DownloadStatus.RUNNING);
                updateNotificationProgress(getTaskFileName(taskId), progress);
            }
            @Override public void onComplete(File file) {
                activeTasks.remove(taskId);
                updateRecordProgress(taskId, 100, -1, -1, DownloadStatus.COMPLETED);
                finalizeNotification(file.getName(), true);
                MediaScannerConnection.scanFile(DownloadService.this, new String[]{file.getAbsolutePath()}, null, null);
            }
            @Override public void onError(Exception e) {
                DownloadRecord record = historyRepository.findByTaskId(taskId);
                if (record != null && record.getStatus() != DownloadStatus.PAUSED) {
                    updateRecordStatus(taskId, DownloadStatus.FAILED);
                    finalizeNotification(getTaskFileName(taskId), false);
                }
            }
            @Override public void onCancel() {
                activeTasks.remove(taskId);
                updateRecordStatus(taskId, DownloadStatus.CANCELED);
                killNotification();
            }
            @Override public void onMerge() {
                updateRecordStatus(taskId, DownloadStatus.MERGING);
                updateNotificationMerging(getTaskFileName(taskId));
            }
        });
    }

    private String getTaskFileName(String taskId) {
        Task t = activeTasks.get(taskId);
        if (t != null) return t.fileName();
        DownloadRecord record = historyRepository.findByTaskId(taskId);
        return record != null ? record.getFileName() : "Download";
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
            String videoUrl = "https://www.youtube.com/watch?v=" + record.getVid().split(":")[0];
            new Thread(() -> reExtractAndResume(videoUrl, record)).start();
        }
    }

    private void reExtractAndResume(String videoUrl, DownloadRecord record) {
        try {
            StreamDetails si = youtubeExtractor.getStreamInfo(videoUrl);
            int vItag = itagPrefs.getInt(record.getTaskId() + "_v_itag", -1);
            int aItag = itagPrefs.getInt(record.getTaskId() + "_a_itag", -1);

            VideoStream video = si.getVideoStreams().stream().filter(s -> s.getItag() == vItag).findFirst().orElse(null);
            AudioStream audio = si.getAudioStreams().stream().filter(s -> s.getItag() == aItag).findFirst().orElse(null);

            if (video == null && vItag != -1) video = si.getVideoStreams().get(0);
            if (audio == null && aItag != -1) audio = si.getAudioStreams().get(0);

            Task newTask = new Task(record.getTaskId(), video, audio, null, null,
                    record.getFileName(), new File(record.getOutputPath()).getParentFile(), 4);

            mainHandler.post(() -> {
                activeTasks.put(record.getTaskId(), newTask);
                startTask(newTask);
            });
        } catch (Exception e) {
            mainHandler.post(() -> updateRecordStatus(record.getTaskId(), DownloadStatus.FAILED));
        }
    }

    public void cancel(@NonNull String vid) {
        liteDL.cancel(vid);
        activeTasks.remove(vid);
        killNotification();
    }

    public void cancelByPrefix(String prefix) {
        blockedPrefixes.add(prefix);
        activeTasks.values().forEach(t -> {
            if (t.fileName().startsWith(prefix)) {
                liteDL.cancel(t.vid());
            }
        });
        mainHandler.postDelayed(() -> blockedPrefixes.remove(prefix), 60000);
    }

    private void updateRecordProgress(String taskId, int p, long d, long t, DownloadStatus status) {
        DownloadRecord record = historyRepository.findByTaskId(taskId);
        if (record == null || record.getStatus() == DownloadStatus.PAUSED) return;
        if (p >= 0) record.setProgress(p);
        if (d >= 0) record.setDownloadedSize(d);
        if (t >= 0) record.setTotalSize(t);
        record.setStatus(status);
        record.setUpdatedAt(System.currentTimeMillis());
        historyRepository.upsert(record);
        broadcastRecordUpdated(taskId);
    }

    private void updateRecordStatus(String taskId, DownloadStatus status) {
        DownloadRecord record = historyRepository.findByTaskId(taskId);
        if (record != null) {
            record.setStatus(status);
            historyRepository.upsert(record);
            broadcastRecordUpdated(taskId);
        }
    }

    private void ensureForeground() {
        if (notificationBuilder == null) {
            notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("YouTube Lite Downloader")
                    .setContentIntent(createContentIntent())
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notificationBuilder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void updateNotificationProgress(String fileName, int progress) {
        if (notificationBuilder != null) {
            notificationBuilder.setContentTitle("Downloading: " + fileName)
                    .setContentText(progress + "%")
                    .setProgress(100, progress, false)
                    .setOngoing(true);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void updateNotificationPaused(String vid) {
        if (notificationBuilder != null) {
            String fileName = getTaskFileName(vid);
            notificationBuilder.setContentTitle("Paused: " + fileName)
                    .setContentText("Tap to resume")
                    .setProgress(0, 0, false)
                    .setOngoing(false);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
            stopForeground(STOP_FOREGROUND_DETACH);
        }
    }

    private void killNotification() {
        boolean hasActive = activeTasks.values().stream().anyMatch(t -> {
            DownloadRecord r = historyRepository.findByTaskId(t.vid());
            return r != null && (r.getStatus() == DownloadStatus.RUNNING || r.getStatus() == DownloadStatus.QUEUED);
        });
        if (!hasActive) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    private void updateNotificationMerging(String fileName) {
        if (notificationBuilder != null) {
            notificationBuilder.setContentTitle("Merging: " + fileName)
                    .setContentText("Please wait...")
                    .setProgress(100, 0, true);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    private void finalizeNotification(String fileName, boolean success) {
        if (notificationBuilder != null) {
            notificationBuilder.setOngoing(false).setAutoCancel(true).setProgress(0, 0, false)
                    .setContentTitle(success ? "Download Finished" : "Download Failed")
                    .setContentText(fileName);
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
            killNotification();
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

    public class DownloadBinder extends Binder { public DownloadService getService() { return DownloadService.this; } }
}