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
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.gallery.GalleryActivity;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.queue.QueueWarmer;
import com.hhst.youtubelite.ui.AboutActivity;
import com.hhst.youtubelite.R;

import android.widget.Toast;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
	private final QueueRepository queueRepository;
	@NonNull
	private final QueueWarmer queueWarmer;
	@NonNull
	private final Gson gson = new Gson();
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());

	public JavascriptInterface(@NonNull final YoutubeWebview webview,
	                           @NonNull final YoutubeExtractor youtubeExtractor,
	                           @NonNull final LitePlayer player,
	                           @NonNull final ExtensionManager extensionManager,
	                           @NonNull final TabManager tabManager,
	                           @NonNull final QueueRepository queueRepository,
	                           @NonNull final QueueWarmer queueWarmer) {
		this.context = webview.getContext();
		this.webview = webview;
		this.youtubeExtractor = youtubeExtractor;
		this.player = player;
		this.extensionManager = extensionManager;
		this.tabManager = tabManager;
		this.queueRepository = queueRepository;
		this.queueWarmer = queueWarmer;
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
	public void addToQueue(@Nullable final String itemJson) {
		if (itemJson == null) return;
		handler.post(() -> {
			try {
				final QueueItem item = gson.fromJson(itemJson, QueueItem.class);
				if (item == null || item.getUrl() == null) return;
				final String vid = item.getVideoId();
				if (vid == null || vid.isBlank()
						|| item.getTitle() == null || item.getTitle().isBlank()
						|| item.getAuthor() == null || item.getAuthor().isBlank()) {
					Toast.makeText(context, R.string.queue_item_unavailable, Toast.LENGTH_SHORT).show();
					return;
				}
				item.setVideoId(vid);
				queueRepository.add(item);
				queueWarmer.warmItem(item);
				player.refreshQueueNavigationAvailability();
				Toast.makeText(context, R.string.queue_item_added, Toast.LENGTH_SHORT).show();
			} catch (final Exception ignored) {
			}
		});
	}

	@android.webkit.JavascriptInterface
	public void showQueueItemUnavailable() {
		handler.post(() -> Toast.makeText(context, R.string.queue_item_unavailable, Toast.LENGTH_SHORT).show());
	}

	@android.webkit.JavascriptInterface
	public boolean isQueueEnabled() {
		return queueRepository.isEnabled();
	}

	@Nullable
	private String extractVideoId(@Nullable final String url) {
		if (url == null) return null;
		final String extractedByParser = YoutubeExtractor.getVideoId(url);
		if (extractedByParser != null && !extractedByParser.isBlank()) {
			return extractedByParser;
		}
		final Matcher matcher = Pattern.compile("[?&]v=([\\w-]{6,})", Pattern.CASE_INSENSITIVE).matcher(url);
		return matcher.find() ? matcher.group(1) : null;
	}

	@android.webkit.JavascriptInterface
	public void hidePlayer() {
		handler.post(tabManager::hidePlayer);
	}

	@android.webkit.JavascriptInterface
	public void setPlayerHeight(final int height) {
		handler.post(() -> player.setHeight(height));
	}

	@android.webkit.JavascriptInterface
	public boolean seekLoadedVideo(@Nullable final String url, final long positionMs) {
		return player.seekLoadedVideo(url, positionMs);
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
