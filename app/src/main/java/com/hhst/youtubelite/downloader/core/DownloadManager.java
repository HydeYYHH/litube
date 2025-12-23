package com.hhst.youtubelite.downloader.core;

import android.content.Context;
import android.os.Environment;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.downloader.impl.AdvancedFileDownloader;
import com.hhst.youtubelite.downloader.impl.MultiThreadFileDownloader;
import com.hhst.youtubelite.downloader.impl.YoutubeDownloader;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manager for handling all download tasks.
 * Acts as the core layer between the UI/Service and the actual downloaders.
 */
public final class DownloadManager {

	private static volatile DownloadManager instance;
	private final AtomicInteger taskIdCounter = new AtomicInteger(1);
	private final ConcurrentHashMap<Integer, DownloadTask> downloadTasks = new ConcurrentHashMap<>();
	private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

	// Processors
	private final DownloadProcessor youtubeProcessor = new YoutubeDownloader();

	private DownloadManager() {
	}

	public static DownloadManager getInstance() {
		if (instance == null) {
			synchronized (DownloadManager.class) {
				if (instance == null) instance = new DownloadManager();
			}
		}
		return instance;
	}

	public int generateTaskId() {
		return taskIdCounter.getAndIncrement();
	}

	public void addTask(int taskId, DownloadTask task) {
		downloadTasks.put(taskId, task);
	}

	public DownloadTask getTask(int taskId) {
		return downloadTasks.get(taskId);
	}

	public ConcurrentHashMap<Integer, DownloadTask> getTasks() {
		return downloadTasks;
	}

	public ExecutorService getExecutor() {
		return downloadExecutor;
	}

	public File getDownloadDirectory(Context context) {
		File outputDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), context.getString(R.string.app_name));
		if (!outputDir.exists()) {
			boolean ignored = outputDir.mkdirs();
		}
		return outputDir;
	}

	public void startTask(int taskId, DownloadTask task, ProgressCallback callback, Context context) {
		String tag = "DownloadTask#" + taskId;
		if (!task.isComplexDownload()) {
			// Simple file download for subtitles
			AdvancedFileDownloader downloader = new MultiThreadFileDownloader();
			downloader.download(task.getDownloadUrl(), task.getOutput(), callback, tag, task.getThreadCount());
		} else
			// Complex download (video+audio+subtitle)
			youtubeProcessor.process(task, tag, callback, context);
	}

	public void cancelTask(int taskId) {
		DownloadTask task = downloadTasks.get(taskId);
		if (task != null) {
			task.setState(DownloaderState.CANCELLED);
			if (task.isSubtitle() && task.getAudioStream() == null) {
				new MultiThreadFileDownloader().cancel("DownloadTask#" + taskId);
			} else {
				youtubeProcessor.cancel("DownloadTask#" + taskId);
			}
		}
	}
}
