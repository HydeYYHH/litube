package com.hhst.youtubelite.webview;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.hhst.youtubelite.MainActivity;
import com.hhst.youtubelite.downloader.DownloadDialog;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extension.ExtensionDialog;
import com.hhst.youtubelite.image.FullScreenImageActivity;

import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public class JavascriptInterface {
	private final Context context;
	private final Gson gson = new Gson();
	private final Handler handler = new Handler(Looper.getMainLooper());

	public JavascriptInterface(Context context) {
		this.context = context;
	}


	@android.webkit.JavascriptInterface
	public void finishRefresh() {
		handler.post(() -> ((MainActivity) context).getSwipeRefreshLayout().setRefreshing(false));
	}

	@android.webkit.JavascriptInterface
	public void setRefreshLayoutEnabled(boolean enabled) {
		handler.post(() -> ((MainActivity) context).getSwipeRefreshLayout().setEnabled(enabled));
	}

	@android.webkit.JavascriptInterface
	public void download(String url) {
		handler.post(() -> new DownloadDialog(url, context).show());
	}

	@android.webkit.JavascriptInterface
	public void extension() {
		handler.post(() -> new ExtensionDialog(context).build());
	}

	@android.webkit.JavascriptInterface
	public void shareLink(String url) {
		handler.post(() -> ((MainActivity) context).shareLink(url));
	}

	@android.webkit.JavascriptInterface
	public void play(String url) {
		handler.post(() -> ((MainActivity) context).play(url));
	}

	@android.webkit.JavascriptInterface
	public void hidePlayer() {
		handler.post(() -> ((MainActivity) context).hidePlayer());
	}

	@android.webkit.JavascriptInterface
	public void setPlayerHeight(int height) {
		handler.post(() -> ((MainActivity) context).setPlayerHeight(height));
	}

	@android.webkit.JavascriptInterface
	public void setPoToken(String poToken, String visitorData) {
		((MainActivity) context).getPoTokenProvider().setPoToken(new PoTokenResult(visitorData, poToken, poToken));
	}

	@android.webkit.JavascriptInterface
	public void onPosterLongPress(String urlsJson) {
		handler.post(() -> {
			List<String> urls = gson.fromJson(urlsJson, new TypeToken<List<String>>() {
			}.getType());
			Intent intent = new Intent(context, FullScreenImageActivity.class);
			intent.putStringArrayListExtra("thumbnails", new ArrayList<>(urls));
			intent.putExtra("filename", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
			context.startActivity(intent);
		});
	}

	@android.webkit.JavascriptInterface
	public void canSkipToNext(boolean can) {
		handler.post(() -> ((MainActivity) context).getPlayerController().canSkipToNext(can));
	}

	@android.webkit.JavascriptInterface
	public void canSkipToPrevious(boolean can) {
		handler.post(() -> ((MainActivity) context).getPlayerController().canSkipToPrevious(can));
	}

	@android.webkit.JavascriptInterface
	public String getPreferences() {
		ExtensionManager extensionManager = ((MainActivity) context).getExtensionManager();
		return gson.toJson(extensionManager.getAllPreferences());
	}

	@android.webkit.JavascriptInterface
	public void setPreference(String key, boolean value) {
		ExtensionManager extensionManager = ((MainActivity) context).getExtensionManager();
		extensionManager.setEnabled(key, value);
	}

}
