package com.hhst.youtubelite.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
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
import com.hhst.youtubelite.downloader.ui.DownloadPermissionHost;
import com.hhst.youtubelite.downloader.ui.PlaylistDownloadDialog;
import com.hhst.youtubelite.downloader.ui.PlaylistDownloadItem;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.extractor.potoken.PoTokenHost;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.ui.queue.QueueAdapter;
import com.hhst.youtubelite.ui.queue.QueueTouch;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.PermissionUtils;
import com.hhst.youtubelite.util.ToastUtils;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.ViewUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Primary screen that wires playback, queue, and download entry points.
 */
@AndroidEntryPoint
@UnstableApi
public final class MainActivity extends AppCompatActivity implements LifecycleEventObserver, DownloadPermissionHost {
	private final Handler handler = new Handler(Looper.getMainLooper());
	@Inject
	ExtensionManager extensionManager;
	@Inject
	TabManager tabManager;
	@Inject
	LitePlayer player;
	@Inject
	YoutubeExtractor youtubeExtractor;
	@Inject
	QueueRepository queueRepository;
	@Inject
	PoTokenHost poTokenHost;
	@Nullable
	private PlaybackService playbackService;
	@Nullable
	private ServiceConnection serviceConnection;
	@Nullable
	private TextView hintText;
	@NonNull
	private final Runnable hideHintRunnable = this::hideHint;
	private MainActivityViewModel viewModel;
	@Nullable
	private QueueSheet queueSheet;
	private long lastBackTime;
	private boolean bootstrapped;
	private boolean suppressPiP;
	@Nullable
	private Runnable pendingPermissionAction;

	static boolean shouldEnterPictureInPicture(@Nullable LitePlayer player,
	                                           @Nullable ExtensionManager extensionManager,
	                                           final boolean isInPictureInPictureMode) {
		return !isInPictureInPictureMode
						&& extensionManager != null
						&& extensionManager.isEnabled(Constant.ENABLE_PIP)
						&& player != null
						&& player.shouldAutoEnterPictureInPicture();
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_main);
		super.onCreate(savedInstanceState);
		viewModel = new ViewModelProvider(this).get(MainActivityViewModel.class);
		viewModel.getState().observe(this, this::renderQueueSheet);

		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

		View mainView = findViewById(R.id.main);
		ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
			Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			Insets tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, tappable.bottom);
			return insets;
		});

		hintText = findViewById(R.id.activity_hint_text);
		if (hintText != null) {
			int pad = ViewUtils.dpToPx(this, 16);
			hintText.setPadding(pad, pad / 2, pad, pad / 2);
		}

		View playerRoot = findViewById(R.id.playerView);
		playerRoot.post(() -> {
			findViewById(R.id.btn_queue).setOnClickListener(v -> showQueueBottomSheet());
			findViewById(R.id.btn_mini_queue).setOnClickListener(v -> showQueueBottomSheet());
		});
		if (PermissionUtils.needsPostNotificationsPermission()
						&& !PermissionUtils.hasPostNotificationsPermission(this)) {
			ActivityCompat.requestPermissions(
							this,
							PermissionUtils.postNotificationsPermission(),
							PermissionUtils.REQUEST_POST_NOTIFICATIONS);
		}
		serviceConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName name, IBinder binder) {
				playbackService = ((PlaybackService.PlaybackBinder) binder).getService();
				if (player != null && playbackService != null) {
					player.attachPlaybackService(playbackService);
				}
			}

			@Override
			public void onServiceDisconnected(ComponentName name) {
				playbackService = null;
			}
		};
		bindService(new Intent(this, PlaybackService.class), serviceConnection, Context.BIND_AUTO_CREATE);
		ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
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
				YoutubeWebview webview = getWebView();
				if (webview != null && tabManager != null) {
					tabManager.evaluateJavascript("window.dispatchEvent(new Event('onGoBack'));", null);
					if (webview.fullscreen != null && webview.fullscreen.getVisibility() == View.VISIBLE) {
						tabManager.evaluateJavascript("document.exitFullscreen()", null);
						return;
					}
				}
				if (tabManager != null && !tabManager.goBack()) {
					long time = System.currentTimeMillis();
					if (time - lastBackTime < 2_000L) finish();
					else {
						lastBackTime = time;
						ToastUtils.show(MainActivity.this, R.string.press_back_again_to_exit);
					}
				}
			}
		});

		// Initialize potoken dependency and open home page.
		long startupDeadlineMs = SystemClock.uptimeMillis() + 4_000L;

		mainView.post(new Runnable() {
			@Override
			public void run() {
				if (bootstrapped) {
					handleIntent(getIntent());
					return;
				}
				poTokenHost.prewarm();
				if (tabManager.getWebView() == null) {
					tabManager.openTab(Constant.HOME_URL, UrlUtils.getPageClass(Constant.HOME_URL));
				}
				if (!poTokenHost.isReady() && SystemClock.uptimeMillis() < startupDeadlineMs) {
					handler.postDelayed(this, 100L);
					return;
				}
				bootstrapped = true;
				handleIntent(getIntent());
			}
		});
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
		boolean suppressAutoEnterPiP = suppressPiP;
		suppressPiP = false;
		if (!suppressAutoEnterPiP
						&& shouldEnterPictureInPicture(player, extensionManager, DeviceUtils.isInPictureInPictureMode(this))) {
			player.enterPictureInPicture();
		}
	}

	@Override
	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
		player.onPictureInPictureModeChanged(isInPictureInPictureMode);
	}

	@Override
	public void onStateChanged(@NonNull androidx.lifecycle.LifecycleOwner source,
	                           @NonNull Lifecycle.Event event) {
		if (event == Lifecycle.Event.ON_STOP
						&& shouldEnterPictureInPicture(player, extensionManager, isInPictureInPictureMode())) {
			player.enterPictureInPicture();
		}
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		player.syncRotation(DeviceUtils.isRotateOn(this), newConfig.orientation);
	}

	private void handleIntent(@Nullable Intent intent) {
		if (intent == null) return;
		String action = intent.getAction();
		boolean isDownloadAction = "TRIGGER_DOWNLOAD_FROM_SHARE".equals(action);

		if ("OPEN_DOWNLOADS".equals(action)) {
			startActivity(new Intent(this, DownloadActivity.class));
			return;
		}

		String url = null;
		if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
			url = intent.getData().toString();
		} else if (Intent.ACTION_SEND.equals(action) || isDownloadAction) {
			// Extract a shared YouTube URL from the incoming text.
			String text = intent.getStringExtra(Intent.EXTRA_TEXT);
			if (text != null) {
				Pattern pat = Pattern.compile("https?://[\\w./?=&%#-]+", Pattern.CASE_INSENSITIVE);
				Matcher m = pat.matcher(text);
				url = m.find() ? m.group() : null;
			}
		}

		if (url != null) {
			if (isDownloadAction) {
				String loadUrl = url.replace(Constant.YOUTUBE_MOBILE_HOST, "www.youtube.com");
				long fetchToast = ToastUtils.show(this, "Fetching download links...");
				handler.postDelayed(() -> ToastUtils.cancel(fetchToast), 1000);
				handler.postDelayed(() -> new DownloadDialog(loadUrl, this, youtubeExtractor).show(), 600);
			} else {
				String loadUrl = url.replace("www.youtube.com", Constant.YOUTUBE_MOBILE_HOST);
				if (tabManager != null) {
					tabManager.openTab(loadUrl, UrlUtils.getPageClass(loadUrl));
				}
			}
		} else if (tabManager.getWebView() == null) {
			tabManager.openTab(Constant.HOME_URL, UrlUtils.getPageClass(Constant.HOME_URL));
		}
	}

	private void showQueueBottomSheet() {
		if (DeviceUtils.isInPictureInPictureMode(this)) return;
		BottomSheetDialog dialog = new BottomSheetDialog(this);
		View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_queue, new android.widget.FrameLayout(this), false);
		dialog.setContentView(sheetView);

		ImageButton closeButton = sheetView.findViewById(R.id.btn_queue_close);
		SwitchMaterial enabledSwitch = sheetView.findViewById(R.id.switch_queue_enabled);
		ImageButton downloadButton = sheetView.findViewById(R.id.btn_queue_download);
		ImageButton orderButton = sheetView.findViewById(R.id.btn_queue_order);
		ImageButton clearButton = sheetView.findViewById(R.id.btn_queue_clear);
		TextView emptyView = sheetView.findViewById(R.id.queue_empty);
		RecyclerView recyclerView = sheetView.findViewById(R.id.queue_items_recycler);
		QueueAdapter adapter = new QueueAdapter(new QueueAdapter.Actions() {
			@Override
			public void onPlayRequested(@NonNull QueueItem item) {
				dialog.dismiss();
				if (item.getVideoUrl() != null) {
					tabManager.playInWatch(item.getVideoUrl());
				}
			}

			@Override
			public void onDeleteRequested(@NonNull QueueItem item) {
				new MaterialAlertDialogBuilder(MainActivity.this)
								.setMessage(R.string.remove_queue_item_confirmation)
								.setPositiveButton(R.string.confirm, (d, which) -> {
									String videoId = item.getVideoId();
									if (videoId == null) return;
									viewModel.removeQueueItem(videoId);
								})
								.setNegativeButton(R.string.cancel, null)
								.show();
			}
		});
		QueueSheet sheet = new QueueSheet(enabledSwitch, orderButton, emptyView, recyclerView, adapter);
		queueSheet = sheet;
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.setAdapter(adapter);
		recyclerView.setNestedScrollingEnabled(true);
		recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
		new ItemTouchHelper(new QueueTouch(adapter::moveItem, new QueueTouch.DragStateCallback() {
			@Override
			public void onDragStateChanged(boolean dragging) {
				if (sheet.behavior != null) sheet.behavior.setDraggable(!dragging);
			}

			@Override
			public void onDragFinished() {
				viewModel.moveQueue(adapter.snapshotItems());
				if (sheet.behavior != null) sheet.behavior.setDraggable(true);
			}
		})).attachToRecyclerView(recyclerView);

		closeButton.setOnClickListener(v -> dialog.dismiss());
		enabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (!buttonView.isPressed()) return;
			viewModel.setQueueEnabled(isChecked);
			ToastUtils.show(this, isChecked ? R.string.queue_enabled_on : R.string.queue_enabled_off);
		});
		downloadButton.setOnClickListener(v -> {
			dialog.dismiss();
			List<QueueItem> items = uiState().items();
			if (items.isEmpty()) {
				ToastUtils.show(this, R.string.queue_download_unavailable);
				return;
			}
			List<PlaylistDownloadItem> dialogItems = new java.util.ArrayList<>();
			for (int i = 0; i < items.size(); i++) {
				QueueItem queueItem = items.get(i);
				String videoId = queueItem.getVideoId() != null
								? queueItem.getVideoId()
								: YoutubeExtractor.getVideoId(queueItem.getVideoUrl());
				String itemUrl = queueItem.getVideoUrl() != null && !queueItem.getVideoUrl().isBlank()
								? queueItem.getVideoUrl()
								: videoId == null || videoId.isBlank()
								? null
								: "https://www.youtube.com/watch?v=" + videoId;
				PlaylistDownloadItem item = new PlaylistDownloadItem(
								i,
								videoId == null ? "unknown" : videoId,
								itemUrl == null ? "" : itemUrl);
				item.setTitle(queueItem.getTitle());
				item.setAuthor(queueItem.getAuthor());
				item.setThumbnailUrl(queueItem.getThumbnailUrl());
				if (videoId == null || itemUrl == null || itemUrl.isBlank()) {
					item.setAvailabilityStatus(PlaylistDownloadItem.AvailabilityStatus.LOAD_FAILED);
					item.setFailureReason(getString(R.string.playlist_download_status_failed));
					item.setSelected(false);
				} else {
					item.setAvailabilityStatus(PlaylistDownloadItem.AvailabilityStatus.READY);
					item.setSelected(true);
				}
				dialogItems.add(item);
			}
			new PlaylistDownloadDialog(
							getString(R.string.queue),
							dialogItems,
							null,
							null,
							this,
							youtubeExtractor,
							null).show();
		});
		orderButton.setOnClickListener(v -> {
			PlayerLoopMode newMode = uiState().loopMode().next();
			player.setLoopMode(newMode);
		});
		clearButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
						.setMessage(R.string.clear_queue_confirmation)
						.setPositiveButton(R.string.confirm, (d, which) -> viewModel.clearQueue())
						.setNegativeButton(R.string.cancel, null)
						.show());
		dialog.setOnShowListener(ignored -> {
			final android.widget.FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
			if (bottomSheet == null) return;
			BottomSheetBehavior<android.widget.FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
			sheet.behavior = behavior;
			int sheetBasePaddingBottom = sheetView.getPaddingBottom();
			int recyclerBasePaddingBottom = recyclerView.getPaddingBottom();
			int recyclerTrailingSpace = Math.round(getResources().getDisplayMetrics().density * 24);
			View mainView = findViewById(R.id.main);
			WindowInsetsCompat rootInsets = mainView != null
							? ViewCompat.getRootWindowInsets(mainView)
							: ViewCompat.getRootWindowInsets(bottomSheet);
			int bottomInset = rootInsets != null
							? rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
							: 0;
			sheetView.setPadding(
							sheetView.getPaddingLeft(),
							sheetView.getPaddingTop(),
							sheetView.getPaddingRight(),
							sheetBasePaddingBottom + Math.max(0, bottomInset));
			// Keep last row visible.
			recyclerView.setPadding(
							recyclerView.getPaddingLeft(),
							recyclerView.getPaddingTop(),
							recyclerView.getPaddingRight(),
							recyclerBasePaddingBottom + Math.max(Math.max(0, bottomInset), Math.max(0, recyclerTrailingSpace)));
			View playerRoot = findViewById(R.id.playerView);
			int mainHeight = mainView != null ? mainView.getHeight() : 0;
			int topInset = mainView != null ? mainView.getPaddingTop() : 0;
			int playerBottom = playerRoot != null ? playerRoot.getBottom() : 0;
			final int maxSheetHeight;
			if (mainHeight <= 0) {
				maxSheetHeight = 0;
			} else if (uiState().miniPlayer()) {
				maxSheetHeight = Math.max(0, mainHeight - Math.max(0, topInset));
			} else if (playerBottom <= 0 || playerBottom >= mainHeight) {
				maxSheetHeight = mainHeight;
			} else {
				maxSheetHeight = mainHeight - playerBottom;
			}
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
			sheet.scrollPending = true;
			renderQueueSheet(uiState());
		});
		dialog.setOnDismissListener(d -> {
			if (queueSheet == sheet) {
				queueSheet = null;
			}
		});
		renderQueueSheet(uiState());
		dialog.show();
	}

	@NonNull
	private MainActivityViewModel.UiState uiState() {
		final MainActivityViewModel.UiState state = viewModel.getState().getValue();
		if (state != null) return state;
		return new MainActivityViewModel.UiState(
						queueRepository.isEnabled(),
						queueRepository.getItems(),
						player.getVideoId(),
						player.getLoopMode(),
						player.isInMiniPlayer());
	}

	private void renderQueueSheet(@NonNull MainActivityViewModel.UiState state) {
		QueueSheet sheet = queueSheet;
		if (sheet == null) return;
		if (sheet.enabledSwitch.isChecked() != state.queueEnabled()) {
			sheet.enabledSwitch.setChecked(state.queueEnabled());
		}
		renderLoop(sheet.orderButton, state.loopMode());
		sheet.adapter.replaceItems(state.items(), state.videoId());
		boolean empty = state.items().isEmpty();
		sheet.emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
		sheet.recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
		if (sheet.scrollPending) {
			scrollQueueToPlaying(sheet.recyclerView, state.items(), state.videoId());
			sheet.scrollPending = false;
		}
	}

	private void scrollQueueToPlaying(@NonNull RecyclerView recyclerView,
	                                  @NonNull List<QueueItem> items,
	                                  @Nullable String playingId) {
		if (playingId == null) return;
		int playingPosition = -1;
		for (int i = 0; i < items.size(); i++) {
			if (playingId.equals(items.get(i).getVideoId())) {
				playingPosition = i;
				break;
			}
		}
		if (playingPosition < 0) return;
		int target = playingPosition;
		recyclerView.post(() -> {
			final RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
			if (layoutManager instanceof LinearLayoutManager linearLayoutManager) {
				linearLayoutManager.scrollToPositionWithOffset(
								target,
								Math.max(0, recyclerView.getPaddingTop()) + Math.max(0, recyclerView.getHeight()) / 3);
				return;
			}
			recyclerView.scrollToPosition(target);
		});
	}

	private void renderLoop(@NonNull ImageButton button, @NonNull PlayerLoopMode mode) {
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

	@Nullable
	private YoutubeWebview getWebView() {
		return tabManager != null ? tabManager.getWebView() : null;
	}

	public void showHint(@NonNull String text, long durationMs) {
		if (hintText == null || DeviceUtils.isInPictureInPictureMode(this)) return;
		hintText.setText(text);
		hintText.setVisibility(View.VISIBLE);
		hintText.bringToFront();
		hintText.setTranslationZ(1000f);
		hintText.setAlpha(1.0f);
		ViewUtils.animateViewAlpha(hintText, 1.0f, View.GONE);
		handler.removeCallbacks(hideHintRunnable);
		if (durationMs > 0) {
			handler.postDelayed(hideHintRunnable, durationMs);
		}
	}

	public void hideHint() {
		if (hintText != null) {
			ViewUtils.animateViewAlpha(hintText, 0.0f, View.GONE);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		suppressPiP = false;
		if (player != null && player.isInMiniPlayer() && !DeviceUtils.isInPictureInPictureMode(this)) {
			player.restoreInAppMiniPlayerUiIfNeeded();
		}
	}

	@Override
	public void startActivity(@Nullable Intent intent) {
		suppressPiP = shouldSuppressPiPForStartedActivity(intent);
		super.startActivity(intent);
	}

	@Override
	public void startActivity(@Nullable Intent intent, @Nullable Bundle options) {
		suppressPiP = shouldSuppressPiPForStartedActivity(intent);
		super.startActivity(intent, options);
	}

	@Override
	protected void onStop() {
		if (player != null && player.isInMiniPlayer() && !isChangingConfigurations() && !DeviceUtils.isInPictureInPictureMode(this)) {
			player.suspendInAppMiniPlayerUiIfNeeded();
		}
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		ProcessLifecycleOwner.get().getLifecycle().removeObserver(this);
		if (serviceConnection != null) unbindService(serviceConnection);
		if (!isChangingConfigurations() && player != null) player.release();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
	                                       @NonNull String[] permissions,
	                                       @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode != PermissionUtils.REQUEST_STORAGE_PERMISSION) return;
		Runnable action = pendingPermissionAction;
		pendingPermissionAction = null;
		if (grantResults.length == 0) return;
		for (int result : grantResults) {
			if (result != PackageManager.PERMISSION_GRANTED) return;
		}
		if (action != null) action.run();
	}

	@Override
	public void requestDownloadStoragePermission(@NonNull Runnable onGranted) {
		if (!PermissionUtils.needsLegacyStoragePermission()
						|| PermissionUtils.hasDownloadStoragePermission(this)) {
			onGranted.run();
			return;
		}
		pendingPermissionAction = onGranted;
		ActivityCompat.requestPermissions(
						this,
						PermissionUtils.downloadStoragePermissions(),
						PermissionUtils.REQUEST_STORAGE_PERMISSION);
	}

	private boolean shouldSuppressPiPForStartedActivity(@Nullable Intent intent) {
		if (intent == null || intent.getComponent() == null) return false;
		return getPackageName().equals(intent.getComponent().getPackageName());
	}

/**
 * Helper that owns the queue bottom sheet widgets and transient state.
 */
	private static final class QueueSheet {
		@NonNull
		private final SwitchMaterial enabledSwitch;
		@NonNull
		private final ImageButton orderButton;
		@NonNull
		private final TextView emptyView;
		@NonNull
		private final RecyclerView recyclerView;
		@NonNull
		private final QueueAdapter adapter;
		@Nullable
		private BottomSheetBehavior<android.widget.FrameLayout> behavior;
		private boolean scrollPending;

		private QueueSheet(@NonNull SwitchMaterial enabledSwitch,
		                   @NonNull ImageButton orderButton,
		                   @NonNull TextView emptyView,
		                   @NonNull RecyclerView recyclerView,
		                   @NonNull QueueAdapter adapter) {
			this.enabledSwitch = enabledSwitch;
			this.orderButton = orderButton;
			this.emptyView = emptyView;
			this.recyclerView = recyclerView;
			this.adapter = adapter;
		}
	}

}
