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
import android.widget.ImageButton;
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
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.player.queue.QueueWarmer;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.ui.queue.QueueAdapter;
import com.hhst.youtubelite.ui.queue.QueueTouch;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.UrlUtils;

import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
	Engine engine;
	@Inject
	YoutubeExtractor youtubeExtractor;
	@Inject
	QueueRepository queueRepository;
	@Inject
	QueueWarmer queueWarmer;
	@Nullable
	private PlaybackService playbackService;
	@Nullable
	private ServiceConnection playbackServiceConnection;
	@Nullable
	private BottomSheetDialog queueBottomSheetDialog;
	private long lastBackTime = 0;
	private boolean suppressNextUserLeaveHintPictureInPicture;

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
		queueWarmer.warmItems(queueRepository.getItems());

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
		final boolean suppressAutoEnterPictureInPicture = suppressNextUserLeaveHintPictureInPicture;
		suppressNextUserLeaveHintPictureInPicture = false;
		if (shouldEnterPictureInPictureOnUserLeaveHint(
						player,
						extensionManager,
						DeviceUtils.isInPictureInPictureMode(this),
						suppressAutoEnterPictureInPicture)) {
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
	                                                          final boolean isInPictureInPictureMode,
	                                                          final boolean suppressAutoEnterPictureInPicture) {
		return !isInPictureInPictureMode
						&& !suppressAutoEnterPictureInPicture
						&& player != null
						&& extensionManager != null
						&& extensionManager.isEnabled(Constant.ENABLE_PIP)
						&& player.shouldAutoEnterPictureInPicture();
	}

	static boolean shouldSuppressPictureInPictureForStartedActivity(@Nullable final Intent intent,
	                                                                @NonNull final String appPackageName) {
		if (intent == null || intent.getComponent() == null) return false;
		return appPackageName.equals(intent.getComponent().getPackageName());
	}

	static void dispatchPictureInPictureModeChanged(@Nullable final LitePlayer player, final boolean isInPictureInPictureMode) {
		if (player != null) {
			player.onPictureInPictureModeChanged(isInPictureInPictureMode);
		}
	}

	static boolean shouldShowQueueUi(final boolean isInPictureInPictureMode) {
		return !isInPictureInPictureMode;
	}

	static boolean shouldReleasePlayerOnDestroy(final boolean isChangingConfigurations) {
		return !isChangingConfigurations;
	}

	static boolean shouldRestoreMiniPlayerOnResume(final boolean isInAppMiniPlayer,
	                                               final boolean isInPictureInPictureMode) {
		return isInAppMiniPlayer && !isInPictureInPictureMode;
	}

	static boolean shouldSuspendMiniPlayerOnStop(final boolean isInAppMiniPlayer,
	                                             final boolean isChangingConfigurations,
	                                             final boolean isInPictureInPictureMode) {
		return isInAppMiniPlayer && !isChangingConfigurations && !isInPictureInPictureMode;
	}

	static int resolveQueueBottomSheetMaxHeight(final int mainHeight,
	                                            final int topInset,
	                                            final int playerBottom,
	                                            final boolean isInAppMiniPlayer) {
		if (mainHeight <= 0) return 0;
		if (isInAppMiniPlayer) return Math.max(0, mainHeight - Math.max(0, topInset));
		if (playerBottom <= 0 || playerBottom >= mainHeight) return mainHeight;
		return mainHeight - playerBottom;
	}

	static int resolveQueueBottomSheetBottomPadding(final int baseBottomPadding, final int bottomInset) {
		return baseBottomPadding + Math.max(0, bottomInset);
	}

	static int resolveQueueRecyclerBottomPadding(final int baseBottomPadding,
	                                             final int bottomInset,
	                                             final int minimumTrailingSpace) {
		return baseBottomPadding + Math.max(Math.max(0, bottomInset), Math.max(0, minimumTrailingSpace));
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
		final List<QueueItem> items = queueRepository.getItems();
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
		final AtomicReference<BottomSheetBehavior<android.widget.FrameLayout>> sheetRef = new AtomicReference<>();
		final AtomicBoolean dirty = new AtomicBoolean(false);

		final ImageButton closeButton = sheetView.findViewById(R.id.btn_queue_close);
		final SwitchMaterial enabledSwitch = sheetView.findViewById(R.id.switch_queue_enabled);
		final ImageButton downloadButton = sheetView.findViewById(R.id.btn_queue_download);
		final ImageButton orderButton = sheetView.findViewById(R.id.btn_queue_order);
		final ImageButton clearButton = sheetView.findViewById(R.id.btn_queue_clear);
		final TextView emptyView = sheetView.findViewById(R.id.queue_empty);
		final RecyclerView recyclerView = sheetView.findViewById(R.id.queue_items_recycler);
		final QueueAdapter adapter = getQueueAdapter(dialog, recyclerView, emptyView);
		final Player.Listener queuePlaybackListener = new Player.Listener() {
			@Override
			public void onPlaybackStateChanged(final int state) {
				mainHandler.post(() -> {
					if (queueBottomSheetDialog == dialog) {
						refreshQueueBottomSheet(adapter, recyclerView, emptyView);
					}
				});
			}
		};
		engine.addListener(queuePlaybackListener);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.setAdapter(adapter);
		recyclerView.setNestedScrollingEnabled(true);
		recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
		new ItemTouchHelper(new QueueTouch((from, to) -> {
			final boolean moved = adapter.moveItem(from, to);
			if (moved) {
				dirty.set(true);
			}
			return moved;
		}, new QueueTouch.DragStateCallback() {
			@Override
			public void onDragStateChanged(final boolean dragging) {
				final BottomSheetBehavior<android.widget.FrameLayout> behavior = sheetRef.get();
				if (behavior != null) {
					// Avoid gesture fights.
					behavior.setDraggable(!dragging);
				}
			}

			@Override
			public void onDragFinished() {
				if (dirty.getAndSet(false)) {
					persistQueueOrder(adapter.snapshotItems());
					player.refreshQueueNavigationAvailability();
				}
				refreshQueueBottomSheet(adapter, recyclerView, emptyView);
				final BottomSheetBehavior<android.widget.FrameLayout> behavior = sheetRef.get();
				if (behavior != null) {
					behavior.setDraggable(true);
				}
			}
		})).attachToRecyclerView(recyclerView);

		if (closeButton != null) {
			closeButton.setOnClickListener(v -> dialog.dismiss());
		}
		if (enabledSwitch != null) {
			enabledSwitch.setChecked(queueRepository.isEnabled());
			enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
				queueRepository.setEnabled(isChecked);
				player.refreshQueueNavigationAvailability();
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
			clearButton.setOnClickListener(v -> confirmClearQueue(() -> {
				queueRepository.clear();
				player.refreshQueueNavigationAvailability();
				refreshQueueBottomSheet(adapter, recyclerView, emptyView);
			}));
		}
		dialog.setOnShowListener(ignored -> {
			final android.widget.FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
			if (bottomSheet == null) return;
			final BottomSheetBehavior<android.widget.FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
			sheetRef.set(behavior);
			final int sheetBasePaddingBottom = sheetView.getPaddingBottom();
			final int recyclerBasePaddingBottom = recyclerView.getPaddingBottom();
			final int recyclerTrailingSpace = Math.round(getResources().getDisplayMetrics().density * 24);
			final View mainView = findViewById(R.id.main);
			final WindowInsetsCompat rootInsets = mainView != null
					? ViewCompat.getRootWindowInsets(mainView)
					: ViewCompat.getRootWindowInsets(bottomSheet);
			final int bottomInset = rootInsets != null
					? rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
					: 0;
			sheetView.setPadding(
					sheetView.getPaddingLeft(),
					sheetView.getPaddingTop(),
					sheetView.getPaddingRight(),
					resolveQueueBottomSheetBottomPadding(sheetBasePaddingBottom, bottomInset));
			// Keep last row visible.
			recyclerView.setPadding(
					recyclerView.getPaddingLeft(),
					recyclerView.getPaddingTop(),
					recyclerView.getPaddingRight(),
					resolveQueueRecyclerBottomPadding(recyclerBasePaddingBottom, bottomInset, recyclerTrailingSpace));
			final View playerRoot = findViewById(R.id.playerView);
			final int maxSheetHeight = resolveQueueBottomSheetMaxHeight(
					mainView != null ? mainView.getHeight() : 0,
					mainView != null ? mainView.getPaddingTop() : 0,
					playerRoot != null ? playerRoot.getBottom() : 0,
					player != null && player.isInAppMiniPlayer());
			final android.view.ViewGroup.LayoutParams bottomSheetLayoutParams = bottomSheet.getLayoutParams();
			if (bottomSheetLayoutParams != null && maxSheetHeight > 0) {
				bottomSheetLayoutParams.height = maxSheetHeight;
				bottomSheet.setLayoutParams(bottomSheetLayoutParams);
			}
			final android.view.ViewGroup.LayoutParams sheetLayoutParams = sheetView.getLayoutParams();
			if (sheetLayoutParams != null && maxSheetHeight > 0) {
				sheetLayoutParams.height = maxSheetHeight;
				sheetView.setLayoutParams(sheetLayoutParams);
			}
			behavior.setPeekHeight(maxSheetHeight > 0 ? maxSheetHeight : sheetView.getMeasuredHeight());
			behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
		});
		dialog.setOnDismissListener(d -> {
			engine.removeListener(queuePlaybackListener);
			if (queueBottomSheetDialog == dialog) {
				queueBottomSheetDialog = null;
			}
		});
		refreshQueueBottomSheet(adapter, recyclerView, emptyView);
		dialog.show();
	}

	@NonNull
	private QueueAdapter getQueueAdapter(BottomSheetDialog dialog, RecyclerView recyclerView, TextView emptyView) {
		final AtomicReference<QueueAdapter> adapterRef = new AtomicReference<>();
		final QueueAdapter adapter = new QueueAdapter(new QueueAdapter.Actions() {
			@Override
			public void onPlayRequested(@NonNull final QueueItem item) {
				dialog.dismiss();
				if (item.getUrl() != null) {
					tabManager.playInPlaybackSession(item.getUrl());
				}
			}

			@Override
			public void onDeleteRequested(@NonNull final QueueItem item) {
				confirmRemoveQueueItem(item, () -> {
					final String videoId = item.getVideoId();
					if (videoId == null) return;
					if (queueRepository.remove(videoId)) {
						player.refreshQueueNavigationAvailability();
						final QueueAdapter a = adapterRef.get();
						if (a != null) {
							refreshQueueBottomSheet(a, recyclerView, emptyView);
						}
					}
				});
			}
		});
		adapterRef.set(adapter);
		return adapter;
	}

	private void confirmClearQueue(@NonNull final Runnable onConfirmed) {
		new MaterialAlertDialogBuilder(this)
						.setMessage(R.string.clear_queue_confirmation)
						.setPositiveButton(R.string.confirm, (d, which) -> onConfirmed.run())
						.setNegativeButton(R.string.cancel, null)
						.show();
	}

	private void confirmRemoveQueueItem(@NonNull final QueueItem item,
	                                    @NonNull final Runnable onConfirmed) {
		new MaterialAlertDialogBuilder(this)
						.setMessage(R.string.remove_queue_item_confirmation)
						.setPositiveButton(R.string.confirm, (d, which) -> onConfirmed.run())
						.setNegativeButton(R.string.cancel, null)
						.show();
	}

	private void refreshQueueBottomSheet(@NonNull final QueueAdapter adapter,
	                                     @NonNull final RecyclerView recyclerView,
	                                     @NonNull final TextView emptyView) {
		final List<QueueItem> items = queueRepository.getItems();
		adapter.replaceItems(items, player.getLoadedVideoId());
		emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
		recyclerView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
	}

	private void persistQueueOrder(@NonNull final List<QueueItem> order) {
		final List<QueueItem> items = queueRepository.getItems();
		for (int to = 0; to < order.size(); to++) {
			final String videoId = order.get(to).getVideoId();
			final int from = indexOfQueueItem(items, videoId);
			if (from < 0 || from == to) continue;
			if (queueRepository.move(from, to)) {
				final QueueItem item = items.remove(from);
				items.add(to, item);
			}
		}
	}

	private int indexOfQueueItem(@NonNull final List<QueueItem> items,
	                             @Nullable final String videoId) {
		if (videoId == null) return -1;
		for (int i = 0; i < items.size(); i++) {
			if (videoId.equals(items.get(i).getVideoId())) {
				return i;
			}
		}
		return -1;
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
	protected void onResume() {
		super.onResume();
		suppressNextUserLeaveHintPictureInPicture = false;
		if (player != null && shouldRestoreMiniPlayerOnResume(player.isInAppMiniPlayer(), DeviceUtils.isInPictureInPictureMode(this))) {
			player.restoreInAppMiniPlayerUiIfNeeded();
		}
	}

	@Override
	public void startActivity(@Nullable final Intent intent) {
		suppressNextUserLeaveHintPictureInPicture =
						shouldSuppressPictureInPictureForStartedActivity(intent, getPackageName());
		super.startActivity(intent);
	}

	@Override
	public void startActivity(@Nullable final Intent intent, @Nullable final Bundle options) {
		suppressNextUserLeaveHintPictureInPicture =
						shouldSuppressPictureInPictureForStartedActivity(intent, getPackageName());
		super.startActivity(intent, options);
	}

	@Override
	protected void onStop() {
		if (player != null
						&& shouldSuspendMiniPlayerOnStop(
						player.isInAppMiniPlayer(),
						isChangingConfigurations(),
						DeviceUtils.isInPictureInPictureMode(this))) {
			player.suspendInAppMiniPlayerUiIfNeeded();
		}
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (playbackServiceConnection != null) unbindService(playbackServiceConnection);
		if (player != null && shouldReleasePlayerOnDestroy(isChangingConfigurations())) player.release();
	}
}

