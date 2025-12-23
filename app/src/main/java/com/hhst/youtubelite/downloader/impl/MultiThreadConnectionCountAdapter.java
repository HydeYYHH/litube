package com.hhst.youtubelite.downloader.impl;

import com.liulishuo.filedownloader.util.FileDownloadHelper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom adapter to determine the number of connections for a download task.
 * This allows per-task thread count configuration.
 */
public class MultiThreadConnectionCountAdapter implements FileDownloadHelper.ConnectionCountAdapter {

	private static final Map<Integer, Integer> taskThreadCounts = new ConcurrentHashMap<>();

	public static void setThreadCount(int downloadId, int threadCount) {
		taskThreadCounts.put(downloadId, threadCount);
	}

	public static void removeThreadCount(int downloadId) {
		taskThreadCounts.remove(downloadId);
	}

	@Override
	public int determineConnectionCount(int downloadId, String url, String path, long totalLength) {
		Integer threadCount = taskThreadCounts.get(downloadId);
		if (threadCount != null && threadCount > 0) {
			return threadCount;
		}

		// Default logic if no specific thread count is set
		if (totalLength < 1024 * 1024) { // 1MB
			return 1;
		}
		if (totalLength < 5 * 1024 * 1024) { // 5MB
			return 2;
		}
		if (totalLength < 50 * 1024 * 1024) { // 50MB
			return 3;
		}
		if (totalLength < 100 * 1024 * 1024) { // 100MB
			return 4;
		}
		return 5;
	}
}
