package com.hhst.youtubelite.cache;

import android.app.Activity;
import android.content.Context;
import android.webkit.WebStorage;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.Cache;

/**
 * Utility that clears app cache directories.
 */
@Singleton
public final class AppCacheCleaner {

	@NonNull
	private final Context context;
	@NonNull
	private final Cache cache;

	@Inject
	public AppCacheCleaner(@ApplicationContext @NonNull Context context,
	                       @NonNull Cache cache) {
		this.context = context;
		this.cache = cache;
	}

	static void deleteContents(File dir) throws IOException {
		if (dir == null || !dir.exists() || !dir.isDirectory()) {
			return;
		}
		File[] children = dir.listFiles();
		if (children == null) return;
		for (File child : children) {
			deleteRecursively(child);
		}
	}

	private static void performClearWebViewData(@NonNull Context context) {
		WebView webView = new WebView(context);
		try {
			webView.clearCache(true);
			WebStorage.getInstance().deleteAllData();
		} finally {
			webView.destroy();
		}
	}

	private static void deleteRecursively(@NonNull File target) throws IOException {
		if (target.isDirectory()) {
			File[] children = target.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		if (!target.delete() && target.exists()) {
			throw new IOException("Failed to delete " + target.getAbsolutePath());
		}
	}

	public void clear(@NonNull Activity activity) throws IOException, InterruptedException {
		clearWebViewData(activity);
		clearCacheDirectories();
	}

	void clearWebViewData(@NonNull Activity activity) throws InterruptedException {
		if (activity.isFinishing()) {
			return;
		}
		if (Thread.currentThread() == activity.getMainLooper().getThread()) {
			performClearWebViewData(activity);
			return;
		}
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<RuntimeException> failure = new AtomicReference<>();
		activity.runOnUiThread(() -> {
			try {
				performClearWebViewData(activity);
			} catch (RuntimeException e) {
				failure.set(e);
			} finally {
				latch.countDown();
			}
		});
		latch.await();
		RuntimeException error = failure.get();
		if (error != null) throw error;
	}

	void clearCacheDirectories() throws IOException {
		cache.evictAll();
		deleteContents(context.getCacheDir());
		deleteContents(context.getExternalCacheDir());
	}
}
