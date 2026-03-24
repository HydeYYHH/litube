package com.hhst.youtubelite.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.browser.YoutubeWebview;
import com.hhst.youtubelite.downloader.ui.DownloadActivity;
import com.hhst.youtubelite.downloader.ui.DownloadDialog;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.queue.LocalQueueRepository;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.ViewUtils;
import com.squareup.picasso.Picasso;

import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
@UnstableApi
public final class MainActivity extends AppCompatActivity {
	private static final String YOUTUBE_WWW_HOST = "www.youtube.com";
	private static final int REQUEST_NOTIFICATION_CODE = 100;
	private static final int DOUBLE_TAP_EXIT_INTERVAL_MS = 2_000;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	@Inject
	ExtensionManager extensionManager;
	@Inject
	TabManager tabManager;
	@Inject
	LitePlayer player;
	@Inject
	YoutubeExtractor youtubeExtractor;
	@Inject
	LocalQueueRepository localQueueRepository;
	@Nullable
	private PlaybackService playbackService;
	@Nullable
	private ServiceConnection playbackServiceConnection;
	@Nullable
	private BottomSheetDialog queueBottomSheetDialog;
	private long lastBackTime = 0;

	@Override
	protected void onCreate(@Nullable final Bundle savedInstanceState) {
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_main);
		super.onCreate(savedInstanceState);

		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

		final View mainView = findViewById(R.id.main);
		ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
			final Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			// FIX: Set bottom padding to 0 to ensure the Nav Bar sticks to the bottom edge
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
			return insets;
		});

		setupNativeContextMenu();
		setupQueueUi();
		requestPermissions();
		startPlaybackService();
		setupBackNavigation();

		mainView.post(() -> handleIntent(getIntent()));
	}

	@Override
	protected void onNewIntent(@NonNull Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		handleIntent(intent);
	}

	@Override
	protected void onUserLeaveHint() {
		super.onUserLeaveHint();
		if (shouldEnterPictureInPictureOnUserLeaveHint(player, extensionManager, DeviceUtils.isInPictureInPictureMode(this))) {
			player.enterPictureInPicture();
		}
	}

	@Override
	public void onPictureInPictureModeChanged(final boolean isInPictureInPictureMode, @NonNull final Configuration newConfig) {
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
		dispatchPictureInPictureModeChanged(player, isInPictureInPictureMode);
		syncQueueUiVisibility(isInPictureInPictureMode);
	}

	private void handleIntent(@Nullable Intent intent) {
		if (intent == null) return;
		String action = intent.getAction();
		boolean isDownloadAction = "TRIGGER_DOWNLOAD_FROM_SHARE".equals(action);

		if ("OPEN_DOWNLOADS".equals(action)) {
			startActivity(new Intent(this, DownloadActivity.class));
			return;
		}

		String urlToLoad = null;
		if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
			urlToLoad = intent.getData().toString();
		} else if (Intent.ACTION_SEND.equals(action) || isDownloadAction) {
			String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
			if (sharedText != null) {
				urlToLoad = extractUrlFromText(sharedText);
			}
		}

		if (urlToLoad != null) {
			if (isDownloadAction) {
				triggerDownload(urlToLoad);
			} else {
				final String cleanUrl = urlToLoad.replace(YOUTUBE_WWW_HOST, Constant.YOUTUBE_MOBILE_HOST);
				if (tabManager != null) {
					tabManager.openTab(cleanUrl, UrlUtils.getPageClass(cleanUrl));
				}
			}
		} else if (tabManager.getWebview() == null) {
			tabManager.openTab(Constant.HOME_URL, UrlUtils.getPageClass(Constant.HOME_URL));
		}
	}

	static boolean shouldEnterPictureInPictureOnUserLeaveHint(@Nullable final LitePlayer player,
	                                                          @Nullable final ExtensionManager extensionManager,
	                                                          final boolean isInPictureInPictureMode) {
		return !isInPictureInPictureMode
						&& player != null
						&& extensionManager != null
						&& extensionManager.isEnabled(Constant.ENABLE_PIP)
						&& player.shouldAutoEnterPictureInPicture();
	}

	static void dispatchPictureInPictureModeChanged(@Nullable final LitePlayer player, final boolean isInPictureInPictureMode) {
		if (player != null) {
			player.onPictureInPictureModeChanged(isInPictureInPictureMode);
		}
	}

	static boolean shouldShowQueueUi(final boolean isInPictureInPictureMode) {
		return !isInPictureInPictureMode;
	}

	private String extractUrlFromText(String text) {
		Pattern pattern = Pattern.compile("https?://[\\w./?=&%#-]+", Pattern.CASE_INSENSITIVE);
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group() : null;
	}

	private void setupNativeContextMenu() {
		findViewById(R.id.main).postDelayed(() -> {
			final YoutubeWebview webview = getWebview();
			if (webview != null) {
				webview.setOnLongClickListener(v -> {
					final WebView.HitTestResult result = webview.getHitTestResult();
					String url = result.getExtra();
					if (url == null) return false;
					if (url.startsWith("/")) url = "https://m.youtube.com" + url;
					if (url.contains("/watch") || url.contains("/shorts/") || url.contains("list=") || url.contains("video_id=")) {
						showVideoOptionsDialog(url);
						return true;
					}
					return false;
				});

				final String scripts = "var style = document.createElement('style');" +
								"style.innerHTML = ' " +
								":root { --safe-area-inset-bottom: 0px !important; } " +
								"body { padding-bottom: 0px !important; margin-bottom: 0px !important; } " +
								"ytm-pivot-bar-renderer { " +
								"  height: 48px !important; " +
								"  padding-bottom: 0px !important; " +
								"  bottom: 0px !important; " +
								"  margin-bottom: 0px !important; " +
								"  min-height: 48px !important; " +
								"} " +
								"a { text-decoration: none !important; } " +
								"a.yt-simple-endpoint { text-decoration: none !important; color: inherit !important; } " +
								"'; document.head.appendChild(style);";
				webview.evaluateJavascript(scripts, null);
			}
		}, 1500);
	}

	private void showVideoOptionsDialog(String url) {
		boolean isPlaylist = url.contains("list=");
		String[] options = isPlaylist ?
						new String[]{"Download Video", "Download Playlist", "Share Link", "Cancel"} :
						new String[]{"Download Video", "Share Link", "Cancel"};

		new MaterialAlertDialogBuilder(this)
						.setTitle("Video Options")
						.setItems(options, (dialog, which) -> {
							if (isPlaylist) {
								if (which == 0) triggerDownload(url);
								else if (which == 1) triggerPlaylistDownload(url);
								else if (which == 2) shareUrl(url);
							} else {
								if (which == 0) triggerDownload(url);
								else if (which == 1) shareUrl(url);
							}
						})
						.show();
	}

	private void setupQueueUi() {
		final View playerRoot = findViewById(R.id.playerView);
		if (playerRoot == null) return;
		playerRoot.post(() -> {
			final View queueButton = findViewById(R.id.btn_queue);
			if (queueButton != null) {
				queueButton.setOnClickListener(v -> showQueueBottomSheet());
			}
			final View miniQueueButton = findViewById(R.id.btn_mini_queue);
			if (miniQueueButton != null) {
				miniQueueButton.setOnClickListener(v -> showQueueBottomSheet());
			}
			syncQueueUiVisibility(DeviceUtils.isInPictureInPictureMode(this));
		});
	}

	private void syncQueueUiVisibility(final boolean isInPictureInPictureMode) {
		final View queueButton = findViewById(R.id.btn_queue);
		final View miniQueueButton = findViewById(R.id.btn_mini_queue);
		if (shouldShowQueueUi(isInPictureInPictureMode)) {
			if (queueButton != null) queueButton.setVisibility(View.VISIBLE);
			return;
		}
		if (queueButton != null) queueButton.setVisibility(View.GONE);
		if (miniQueueButton != null) miniQueueButton.setVisibility(View.GONE);
		if (queueBottomSheetDialog != null && queueBottomSheetDialog.isShowing()) {
			queueBottomSheetDialog.dismiss();
		}
	}

	private void triggerDownload(String url) {
		String cleanUrl = url.replace(Constant.YOUTUBE_MOBILE_HOST, YOUTUBE_WWW_HOST);
		final Toast fetchToast = Toast.makeText(this, "Fetching download links...", Toast.LENGTH_SHORT);
		fetchToast.show();
		mainHandler.postDelayed(fetchToast::cancel, 1000);
		mainHandler.postDelayed(() -> {
			DownloadDialog dialog = new DownloadDialog(cleanUrl, this, youtubeExtractor);
			dialog.show();
		}, 600);
	}

	private void triggerPlaylistDownload(String url) {
		String cleanUrl = url.replace(Constant.YOUTUBE_MOBILE_HOST, YOUTUBE_WWW_HOST);
		final Toast fetchToast = Toast.makeText(this, "Fetching playlist...", Toast.LENGTH_SHORT);
		fetchToast.show();

		new Thread(() -> {
			List<String> videoUrls = new ArrayList<>();
			try {
				PlaylistExtractor extractor = NewPipe.getService(0).getPlaylistExtractor(cleanUrl);
				extractor.fetchPage();
				InfoItemsPage<StreamInfoItem> page = extractor.getInitialPage();

				while (page != null) {
					for (StreamInfoItem item : page.getItems()) {
						videoUrls.add(item.getUrl());
					}
					if (!Page.isValid(page.getNextPage())) break;
					page = extractor.getPage(page.getNextPage());
				}

				mainHandler.post(fetchToast::cancel);

				if (videoUrls.isEmpty()) {
					mainHandler.post(() -> Toast.makeText(this, "Playlist is empty", Toast.LENGTH_LONG).show());
					return;
				}

				mainHandler.post(() -> Toast.makeText(this, "Downloading " + videoUrls.size() + " videos...", Toast.LENGTH_LONG).show());

				for (String videoUrl : videoUrls) {
					mainHandler.post(() -> triggerDownload(videoUrl));
					Thread.sleep(250);
				}

			} catch (ExtractionException | IOException | InterruptedException e) {
				mainHandler.post(() -> Toast.makeText(this, "Failed to load playlist: " + e.getMessage(), Toast.LENGTH_LONG).show());
			}
		}).start();
	}

	private void triggerQueueDownload() {
		final String playbackUrl = tabManager != null ? tabManager.getPlaybackSessionUrl() : null;
		if (playbackUrl != null && playbackUrl.contains("list=")) {
			triggerPlaylistDownload(playbackUrl);
			return;
		}
		final List<QueueItem> items = localQueueRepository.getItems();
		if (items.isEmpty()) {
			Toast.makeText(this, R.string.queue_download_unavailable, Toast.LENGTH_SHORT).show();
			return;
		}
		Toast.makeText(this, getString(R.string.downloading_queue_count, items.size()), Toast.LENGTH_LONG).show();
		for (int i = 0; i < items.size(); i++) {
			final String itemUrl = items.get(i).getUrl();
			if (itemUrl == null || itemUrl.isBlank()) continue;
			mainHandler.postDelayed(() -> triggerDownload(itemUrl), i * 250L);
		}
	}

	private void showQueueBottomSheet() {
		if (!shouldShowQueueUi(DeviceUtils.isInPictureInPictureMode(this))) return;
		final BottomSheetDialog dialog = new BottomSheetDialog(this);
		queueBottomSheetDialog = dialog;
		final View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_queue, new android.widget.FrameLayout(this), false);
		dialog.setContentView(sheetView);

		final android.widget.FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
		if (bottomSheet != null) {
			final BottomSheetBehavior<android.widget.FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
			sheetView.measure(View.MeasureSpec.makeMeasureSpec(ViewUtils.getScreenWidth(this), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
			behavior.setPeekHeight(sheetView.getMeasuredHeight());
		}

		final ImageButton closeButton = sheetView.findViewById(R.id.btn_queue_close);
		final SwitchMaterial enabledSwitch = sheetView.findViewById(R.id.switch_queue_enabled);
		final ImageButton downloadButton = sheetView.findViewById(R.id.btn_queue_download);
		final ImageButton orderButton = sheetView.findViewById(R.id.btn_queue_order);
		final ImageButton clearButton = sheetView.findViewById(R.id.btn_queue_clear);
		final LinearLayout itemsContainer = sheetView.findViewById(R.id.queue_items_container);
		final TextView emptyView = sheetView.findViewById(R.id.queue_empty);

		if (closeButton != null) {
			closeButton.setOnClickListener(v -> dialog.dismiss());
		}
		if (enabledSwitch != null) {
			enabledSwitch.setChecked(localQueueRepository.isEnabled());
			enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
				localQueueRepository.setEnabled(isChecked);
				Toast.makeText(this, isChecked ? R.string.queue_enabled_on : R.string.queue_enabled_off, Toast.LENGTH_SHORT).show();
			});
		}
		if (downloadButton != null) {
			downloadButton.setOnClickListener(v -> {
				dialog.dismiss();
				triggerQueueDownload();
			});
		}
		if (orderButton != null) {
			renderLoopModeButton(orderButton, player.getLoopMode());
			orderButton.setOnClickListener(v -> {
				final PlayerLoopMode newMode = player.getLoopMode().next();
				player.setLoopMode(newMode);
				renderLoopModeButton(orderButton, newMode);
			});
		}
		if (clearButton != null) {
			clearButton.setOnClickListener(v -> confirmClearQueue(dialog, itemsContainer, emptyView));
		}
		dialog.setOnDismissListener(d -> {
			if (queueBottomSheetDialog == dialog) {
				queueBottomSheetDialog = null;
			}
		});
		populateQueueItems(dialog, itemsContainer, emptyView);
		dialog.show();
	}

	private void confirmClearQueue(@NonNull final BottomSheetDialog dialog,
	                               @NonNull final LinearLayout itemsContainer,
	                               @NonNull final TextView emptyView) {
		new MaterialAlertDialogBuilder(this)
						.setMessage(R.string.clear_queue_confirmation)
						.setPositiveButton(R.string.confirm, (d, which) -> {
							localQueueRepository.clear();
							populateQueueItems(dialog, itemsContainer, emptyView);
						})
						.setNegativeButton(R.string.cancel, null)
						.show();
	}

	private void populateQueueItems(@NonNull final BottomSheetDialog dialog,
	                                @NonNull final LinearLayout itemsContainer,
	                                @NonNull final TextView emptyView) {
		itemsContainer.removeAllViews();
		final List<QueueItem> items = localQueueRepository.getItems();
		final String currentVideoId = player.getLoadedVideoId();
		emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
		for (final QueueItem item : items) {
			final View itemView = getLayoutInflater().inflate(R.layout.item_queue_entry, itemsContainer, false);
			final ImageView thumbnailView = itemView.findViewById(R.id.queue_item_thumbnail);
			final TextView titleView = itemView.findViewById(R.id.queue_item_title);
			final TextView authorView = itemView.findViewById(R.id.queue_item_author);
			titleView.setText(item.getTitle() == null || item.getTitle().isBlank() ? item.getUrl() : item.getTitle());
			authorView.setText(item.getAuthor() == null || item.getAuthor().isBlank()
							? getString(R.string.queue_unknown_author)
							: item.getAuthor());
			if (item.getThumbnailUrl() != null && !item.getThumbnailUrl().isBlank()) {
				Picasso.get().load(item.getThumbnailUrl()).placeholder(R.drawable.ic_broken_image).error(R.drawable.ic_broken_image).into(thumbnailView);
			} else {
				thumbnailView.setImageResource(R.drawable.ic_broken_image);
			}
			itemView.setAlpha(item.getVideoId() != null && item.getVideoId().equals(currentVideoId) ? 1.0f : 0.88f);
			itemView.setOnClickListener(v -> {
				dialog.dismiss();
				if (item.getUrl() != null) {
					tabManager.playInPlaybackSession(item.getUrl());
				}
			});
			itemsContainer.addView(itemView);
		}
	}

	private void renderLoopModeButton(@NonNull final ImageButton button, @NonNull final PlayerLoopMode mode) {
		switch (mode) {
			case PLAYLIST_NEXT -> {
				button.setImageResource(R.drawable.ic_playback_end_next);
				button.setContentDescription(getString(R.string.playback_end_next));
			}
			case LOOP_ONE -> {
				button.setImageResource(R.drawable.ic_playback_end_loop);
				button.setContentDescription(getString(R.string.playback_end_loop));
			}
			case PAUSE_AT_END -> {
				button.setImageResource(R.drawable.ic_playback_end_pause);
				button.setContentDescription(getString(R.string.playback_end_pause));
			}
			case PLAYLIST_RANDOM -> {
				button.setImageResource(R.drawable.ic_playback_end_shuffle);
				button.setContentDescription(getString(R.string.playback_end_playlist_random));
			}
		}
	}

	private void shareUrl(String url) {
		Intent sendIntent = new Intent(Intent.ACTION_SEND);
		sendIntent.putExtra(Intent.EXTRA_TEXT, url);
		sendIntent.setType("text/plain");
		startActivity(Intent.createChooser(sendIntent, "Share Video"));
	}


	private void setupBackNavigation() {
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (DeviceUtils.isInPictureInPictureMode(MainActivity.this)) {
					setEnabled(false);
					getOnBackPressedDispatcher().onBackPressed();
					setEnabled(true);
					return;
				}
				if (player != null && player.isFullscreen()) {
					player.exitFullscreen();
					return;
				}
				final YoutubeWebview webview = getWebview();
				if (webview != null && tabManager != null) {
					tabManager.evaluateJavascript("window.dispatchEvent(new Event('onGoBack'));", null);
					if (webview.fullscreen != null && webview.fullscreen.getVisibility() == View.VISIBLE) {
						tabManager.evaluateJavascript("document.exitFullscreen()", null);
						return;
					}
				}
				goBack();
			}
		});
	}

	@NonNull
	private String getInitialUrl() {
		final Intent intent = getIntent();
		if (intent.getData() != null)
			return intent.getData().toString().replace(YOUTUBE_WWW_HOST, Constant.YOUTUBE_MOBILE_HOST);
		return Constant.HOME_URL;
	}

	@Nullable
	private YoutubeWebview getWebview() {
		return tabManager != null ? tabManager.getWebview() : null;
	}

	private void goBack() {
		if (tabManager != null && !tabManager.goBack()) {
			final long time = System.currentTimeMillis();
			if (time - lastBackTime < DOUBLE_TAP_EXIT_INTERVAL_MS) finish();
			else {
				lastBackTime = time;
				Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
			}
		}
	}

	private void startPlaybackService() {
		playbackServiceConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name, IBinder binder) {
				playbackService = ((PlaybackService.PlaybackBinder) binder).getService();
				if (player != null && playbackService != null)
					player.attachPlaybackService(playbackService);
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				playbackService = null;
			}
		};
		bindService(new Intent(this, PlaybackService.class), playbackServiceConnection, Context.BIND_AUTO_CREATE);
	}

	private void requestPermissions() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_CODE);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (playbackServiceConnection != null) unbindService(playbackServiceConnection);
		if (player != null) player.release();
	}
}

