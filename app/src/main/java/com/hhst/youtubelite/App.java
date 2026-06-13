package com.hhst.youtubelite;

import android.app.Application;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.tencent.mmkv.MMKV;

import java.io.File;
import java.io.IOException;

import dagger.hilt.android.HiltAndroidApp;

/**
 * Application entry point that initializes shared runtime state and logging.
 */
@HiltAndroidApp
public class App extends Application {

	@Override
	public void onCreate() {
		super.onCreate();
		MMKV.initialize(this);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			String processName = getProcessName();
			if (!getPackageName().equals(processName)) {
				WebView.setDataDirectorySuffix(processName);
			}
		}
		Constant.USER_AGENT = WebSettings.getDefaultUserAgent(this);
		startLogging();
		// Warm up WebView renderer process as early as possible to reduce cold-start latency
		new Handler(Looper.getMainLooper()).post(() -> {
			WebView wv = new WebView(this);
			wv.destroy();
		});
	}

	private void startLogging() {
		File logFile = new File(getFilesDir(), Constant.LOGGING_FILENAME);
		try {
			String[] command = new String[]{"logcat", "-v", "threadtime", "*:E", "-f", logFile.getAbsolutePath(), "-n", "1", "-r", "1024"};
			Runtime.getRuntime().exec(command);
		} catch (IOException e) {
			Log.e("App", "Failed to start logging", e);
		}
	}

}
