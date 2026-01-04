package com.hhst.youtubelite.browser;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hhst.youtubelite.downloader.ui.DownloadActivity;
import com.hhst.youtubelite.downloader.ui.DownloadDialog;
import com.hhst.youtubelite.extension.ExtensionDialog;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.PoTokenProviderImpl;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.gallery.GalleryActivity;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.ui.AboutActivity;

import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@UnstableApi
public final class JavascriptInterface {
	@NonNull
	private final Context context;
	@NonNull
	private final YoutubeWebview webview;
	@NonNull
	private final YoutubeExtractor youtubeExtractor;
	@NonNull
	private final LitePlayer player;
	@NonNull
	private final ExtensionManager extensionManager;
	@NonNull
	private final TabManager tabManager;
	@NonNull
	private final PoTokenProviderImpl poTokenProvider;
	@NonNull
	private final Gson gson = new Gson();
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());

	public JavascriptInterface(@NonNull final YoutubeWebview webview, @NonNull final YoutubeExtractor youtubeExtractor, @NonNull final LitePlayer player, @NonNull final ExtensionManager extensionManager, @NonNull final TabManager tabManager, @NonNull final PoTokenProviderImpl poTokenProvider) {
		this.context = webview.getContext();
		this.webview = webview;
		this.youtubeExtractor = youtubeExtractor;
		this.player = player;
		this.extensionManager = extensionManager;
		this.tabManager = tabManager;
		this.poTokenProvider = poTokenProvider;
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
		if (url != null)
			handler.post(() -> new DownloadDialog(url, context, youtubeExtractor).show());
	}

	@android.webkit.JavascriptInterface
	public void extension() {
		handler.post(() -> new ExtensionDialog(context, extensionManager).build());
	}

	@android.webkit.JavascriptInterface
	public void download() {
		handler.post(() -> {
			Intent intent = new Intent(context, DownloadActivity.class);
			context.startActivity(intent);
		});
	}

	@android.webkit.JavascriptInterface
	public void about() {
		handler.post(() -> {
			Intent intent = new Intent(context, AboutActivity.class);
			context.startActivity(intent);
		});
	}


	@android.webkit.JavascriptInterface
	public void play(@Nullable final String url) {
		if (url != null) handler.post(() -> player.play(url));
	}

	@android.webkit.JavascriptInterface
	public void hidePlayer() {
		handler.post(player::hide);
	}

	@android.webkit.JavascriptInterface
	public void setPlayerHeight(final int height) {
		handler.post(() -> player.setHeight(height));
	}

	@android.webkit.JavascriptInterface
	public void setPoToken(@Nullable final String poToken, @Nullable final String visitorData) {
		if (poToken != null && visitorData != null) {
			poTokenProvider.setPoToken(new PoTokenResult(visitorData, poToken, poToken));
		}
	}

	@android.webkit.JavascriptInterface
	public void onPosterLongPress(@Nullable final String urlsJson) {
		if (urlsJson != null) {
			handler.post(() -> {
				final List<String> urls = gson.fromJson(urlsJson, new TypeToken<List<String>>() {
				}.getType());
				final Intent intent = new Intent(context, GalleryActivity.class);
				intent.putStringArrayListExtra("thumbnails", new ArrayList<>(urls));
				intent.putExtra("filename", DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
				context.startActivity(intent);
			});
		}
	}

	@NonNull
	@android.webkit.JavascriptInterface
	public String getPreferences() {
		return gson.toJson(extensionManager.getAllPreferences());
	}

	@android.webkit.JavascriptInterface
	public void openTab(@Nullable final String url, @Nullable final String tag) {
		if (url != null && tag != null) handler.post(() -> tabManager.openTab(url, tag));
	}

}
