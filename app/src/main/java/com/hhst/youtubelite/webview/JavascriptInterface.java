package com.hhst.youtubelite.webview;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.hhst.youtubelite.AboutActivity;
import com.hhst.youtubelite.MainActivity;
import com.hhst.youtubelite.downloader.ui.DownloadDialog;
import com.hhst.youtubelite.extension.ExtensionDialog;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.image.FullScreenImageActivity;

import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@OptIn(markerClass = UnstableApi.class)
public final class JavascriptInterface {
	@NonNull
	private final Context context;
	@NonNull
	private final YoutubeWebview webview;
	@NonNull
	private final Gson gson = new Gson();
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());

	public JavascriptInterface(@NonNull final YoutubeWebview webview) {
		this.context = webview.getContext();
		this.webview = webview;
	}


	@android.webkit.JavascriptInterface
	public void finishRefresh() {
		handler.post(() -> {
			if (webview.getParent() instanceof SwipeRefreshLayout)
				((SwipeRefreshLayout) webview.getParent()).setRefreshing(false);
		});
	}

	@android.webkit.JavascriptInterface
	public void setRefreshLayoutEnabled(final boolean enabled) {
		handler.post(() -> {
			if (webview.getParent() instanceof SwipeRefreshLayout)
				((SwipeRefreshLayout) webview.getParent()).setEnabled(enabled);
		});
	}

	@android.webkit.JavascriptInterface
	public void download(@Nullable final String url) {
		if (url != null) handler.post(() -> new DownloadDialog(url, context).show());
	}

	@android.webkit.JavascriptInterface
	public void extension() {
		handler.post(() -> new ExtensionDialog(context).build());
	}

	@android.webkit.JavascriptInterface
	public void about() {
		handler.post(() -> {
			Intent intent = new Intent(context, AboutActivity.class);
			context.startActivity(intent);
		});
	}
	@android.webkit.JavascriptInterface
	public void shareLink(@Nullable final String url) {
		if (url != null && context instanceof MainActivity activity)
			handler.post(() -> activity.shareLink(url));
	}

	@android.webkit.JavascriptInterface
	public void play(@Nullable final String url) {
		if (url != null && context instanceof MainActivity activity)
			handler.post(() -> activity.play(url));
	}

	@android.webkit.JavascriptInterface
	public void hidePlayer() {
		if (context instanceof MainActivity activity) handler.post(activity::hidePlayer);
	}

	@android.webkit.JavascriptInterface
	public void setPlayerHeight(final int height) {
		if (context instanceof MainActivity activity)
			handler.post(() -> activity.setPlayerHeight(height));
	}

	@android.webkit.JavascriptInterface
	public void setPoToken(@Nullable final String poToken, @Nullable final String visitorData) {
		if (poToken != null && visitorData != null && context instanceof MainActivity activity) {
			var poTokenProvider = activity.getPoTokenProvider();
			if (poTokenProvider != null)
				poTokenProvider.setPoToken(new PoTokenResult(visitorData, poToken, poToken));
		}

	}

	@android.webkit.JavascriptInterface
	public void onPosterLongPress(@Nullable final String urlsJson) {
		if (urlsJson != null) {
			handler.post(() -> {
				final List<String> urls = gson.fromJson(urlsJson, new TypeToken<List<String>>() {
				}.getType());
				final Intent intent = new Intent(context, FullScreenImageActivity.class);
				intent.putStringArrayListExtra("thumbnails", new ArrayList<>(urls));
				intent.putExtra("filename", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
				context.startActivity(intent);
			});
		}
	}

	@android.webkit.JavascriptInterface
	public void canSkipToNext(final boolean can) {
		if (context instanceof MainActivity activity) handler.post(() -> {
			var controller = activity.getPlayerController();
			if (controller != null) controller.canSkipToNext(can);
		});
	}

	@android.webkit.JavascriptInterface
	public void canSkipToPrevious(final boolean can) {
		if (context instanceof MainActivity activity) handler.post(() -> {
			var controller = activity.getPlayerController();
			if (controller != null) controller.canSkipToPrevious(can);
		});
	}

	@NonNull
	@android.webkit.JavascriptInterface
	public String getPreferences() {
		if (context instanceof MainActivity activity) {
			final ExtensionManager extensionManager = activity.getExtensionManager();
			if (extensionManager != null) return gson.toJson(extensionManager.getAllPreferences());
		}
		return "{}";
	}

	@android.webkit.JavascriptInterface
	public void setPreference(@Nullable final String key, final boolean value) {
		if (key != null && context instanceof MainActivity activity) {
			final ExtensionManager extensionManager = activity.getExtensionManager();
			if (extensionManager != null) extensionManager.setEnabled(key, value);
		}
	}

	@android.webkit.JavascriptInterface
	public void openTab(@Nullable final String url, @Nullable final String tag) {
		if (url != null && tag != null && context instanceof MainActivity activity)
			handler.post(() -> activity.openTab(url, tag));
	}

}
