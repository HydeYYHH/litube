package com.hhst.youtubelite.downloader.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hhst.youtubelite.downloader.exception.DownloadConnectionException;
import com.hhst.youtubelite.downloader.core.ProgressCallback;
import com.liulishuo.filedownloader.BaseDownloadTask;
import com.liulishuo.filedownloader.FileDownloadListener;
import com.liulishuo.filedownloader.FileDownloader;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

public final class MultiThreadFileDownloader implements AdvancedFileDownloader {

	private final Map<String, List<Integer>> tasks = new ConcurrentHashMap<>();

	@Override
	public void download(@NonNull final String url, @NonNull final File output, @NonNull final ProgressCallback callback, @Nullable final String tag, int threadCount) {
		final BaseDownloadTask task = FileDownloader.getImpl().create(url).setPath(output.getPath()).setAutoRetryTimes(3).setCallbackProgressMinInterval(1000).setWifiRequired(false).setSyncCallback(false).setForceReDownload(false).addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36").setListener(new FileDownloadListener() {
			@Override
			protected void pending(@NonNull final BaseDownloadTask task, final int soFarBytes, final int totalBytes) {
			}

			@Override
			protected void progress(@NonNull final BaseDownloadTask task, final int soFarBytes, final int totalBytes) {
				if (totalBytes > 0)
					callback.onProgress((int) Math.floor((100f * soFarBytes) / totalBytes), null);
			}

			@Override
			protected void completed(@NonNull final BaseDownloadTask task) {
				MultiThreadConnectionCountAdapter.removeThreadCount(task.getId());
				callback.onComplete(new File(task.getTargetFilePath()));
			}

			@Override
			protected void paused(@NonNull final BaseDownloadTask task, final int soFarBytes, final int totalBytes) {
				MultiThreadConnectionCountAdapter.removeThreadCount(task.getId());
				callback.onCancel();
			}

			@Override
			protected void error(@NonNull final BaseDownloadTask task, @NonNull final Throwable e) {
				MultiThreadConnectionCountAdapter.removeThreadCount(task.getId());
				callback.onError(new DownloadConnectionException("File download error", e));
			}

			@Override
			protected void warn(@NonNull final BaseDownloadTask task) {
			}
		});

		final int id = task.getId();
		MultiThreadConnectionCountAdapter.setThreadCount(id, threadCount);
		task.start();

		if (tag != null) tasks.computeIfAbsent(tag, k -> new Vector<>()).add(id);
	}

	@Override
	public void cancel(@Nullable final String tag) {
		if (tag == null) return;

		final List<Integer> taskList = tasks.get(tag);
		if (taskList != null) taskList.forEach(id -> FileDownloader.getImpl().pause(id));
	}
}
