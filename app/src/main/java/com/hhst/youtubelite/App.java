package com.hhst.youtubelite;

import android.app.Application;
import android.os.Build;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.tencent.mmkv.MMKV;

import dagger.hilt.android.HiltAndroidApp;

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
	}

}
