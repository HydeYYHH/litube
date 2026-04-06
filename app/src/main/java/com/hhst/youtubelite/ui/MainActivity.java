package com.hhst.youtubelite.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.browser.YoutubeWebview;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.downloader.ui.DownloadActivity;
import com.hhst.youtubelite.downloader.ui.DownloadDialog;
import com.hhst.youtubelite.downloader.ui.PlaylistDownloadDialog;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.VideoDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.common.PlayerLoopMode;
import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.player.queue.QueueItem;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.queue.QueueWarmer;
import com.hhst.youtubelite.ui.queue.QueueAdapter;
import com.hhst.youtubelite.ui.queue.QueueTouch;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.ViewUtils;

import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	@Inject ExtensionManager extensionManager;
	@Inject TabManager tabManager;
	@Inject LitePlayer player;
	@Inject Engine engine;
	@Inject YoutubeExtractor youtubeExtractor;
	@Inject QueueRepository queueRepository;
	@Inject QueueWarmer queueWarmer;

	@Nullable private PlaybackService playbackService;
	@Nullable private DownloadService downloadService;

	private View queueContainer;
	private View expandedQueueContainer;
	private QueueAdapter queueAdapter;
	private NavigationBar navBar;
	private View navBarDivider;
	private int navigationBarHeight = 0;
	private long lastBackTime = 0;
	private boolean suppressNextUserLeaveHintPictureInPicture;

	private final ServiceConnection playbackConnection = new ServiceConnection() {
		@Override public void onServiceConnected(ComponentName n, IBinder s) {
			playbackService = ((PlaybackService.PlaybackBinder) s).getService();
			if (player != null) player.attachPlaybackService(playbackService);
		}
		@Override public void onServiceDisconnected(ComponentName n) { playbackService = null; }
	};

	private final ServiceConnection downloadConnection = new ServiceConnection() {
		@Override public void onServiceConnected(ComponentName n, IBinder s) {
			downloadService = ((DownloadService.DownloadBinder) s).getService();
		}
		@Override public void onServiceDisconnected(ComponentName n) { downloadService = null; }
	};

	@Override
	protected void onCreate(@Nullable final Bundle savedInstanceState) {
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_main);
		super.onCreate(savedInstanceState);
		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

		final View mainView = findViewById(R.id.main);
		ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
			final Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			final Insets navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
			navigationBarHeight = navInsets.bottom;
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
			updateQueueBarPosition();
			return insets;
		});

		navBar = findViewById(R.id.custom_nav_bar);
		navBar.setup(extensionManager, tabManager);
		navBarDivider = findViewById(R.id.nav_bar_divider);

		setupQueueUI();
		setupNativeContextMenu();
		requestPermissions();

		bindService(new Intent(this, PlaybackService.class), playbackConnection, BIND_AUTO_CREATE);
		bindService(new Intent(this, DownloadService.class), downloadConnection, BIND_AUTO_CREATE);

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
        
        final View fragmentContainer = findViewById(R.id.fragment_container);
        if (isInPictureInPictureMode) {
            if (fragmentContainer != null) fragmentContainer.setVisibility(View.GONE);
            if (navBar != null) navBar.setVisibility(View.GONE);
            if (navBarDivider != null) navBarDivider.setVisibility(View.GONE);
        } else {
            if (fragmentContainer != null) fragmentContainer.setVisibility(View.VISIBLE);
            updateNavBarVisibility();
        }
	}

	@Override
	public void onConfigurationChanged(@NonNull final Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (player != null) {
			player.syncRotation(DeviceUtils.isRotateOn(this), newConfig.orientation);
		}

		if (extensionManager.isEnabled(Constant.ENABLE_ORIENTATION_FULLSCREEN) && player != null) {
			if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
				if (!player.isFullscreen()) player.enterFullscreen();
			} else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
				if (player.isFullscreen()) player.exitFullscreen();
			}
		}
	}

	static boolean shouldEnterPictureInPictureOnUserLeaveHint(
			@Nullable final LitePlayer player,
			@NonNull final ExtensionManager extensionManager,
			final boolean isInPictureInPictureMode,
			final boolean suppressAutoEnterPictureInPicture) {
		return player != null
				&& !isInPictureInPictureMode
				&& !suppressAutoEnterPictureInPicture
				&& extensionManager.isEnabled(Constant.ENABLE_PIP)
				&& player.shouldAutoEnterPictureInPicture();
	}

	static boolean shouldSuppressPictureInPictureForStartedActivity(@Nullable final Intent intent, @NonNull final String packageName) {
		if (intent == null) return false;
		final ComponentName component = intent.getComponent();
		return component != null && Objects.equals(packageName, component.getPackageName());
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

	static boolean shouldRestoreMiniPlayerOnResume(final boolean hasMiniPlayerSession, final boolean isInPictureInPictureMode) {
		return hasMiniPlayerSession && !isInPictureInPictureMode;
	}

	static boolean shouldSuspendMiniPlayerOnStop(final boolean hasMiniPlayerSession, final boolean isChangingConfigurations, final boolean isInPictureInPictureMode) {
		return hasMiniPlayerSession && !isChangingConfigurations && !isInPictureInPictureMode;
	}

	@SuppressWarnings("SameParameterValue")
	static int sheetMax(final int displayHeight, final int topInset, final int playerBottom, final boolean isMiniPlayer) {
		if (isMiniPlayer) return displayHeight - topInset;
		if (playerBottom <= 0 || playerBottom >= displayHeight) return displayHeight;
		return displayHeight - playerBottom;
	}

	@SuppressWarnings("SameParameterValue")
	static int sheetPad(final int systemBarInset, final int additionalPad) {
		return systemBarInset + additionalPad;
	}

	@SuppressWarnings("SameParameterValue")
	static int listPad(final int systemBarInset, final int additionalPad, final int trailingSpace) {
		return systemBarInset + Math.max(additionalPad, trailingSpace);
	}

	@SuppressWarnings("SameParameterValue")
	static int queueAnchor(final int displayHeight, final int topInset) {
		if (displayHeight <= 0) return 0;
		return (displayHeight / 2) - topInset - (displayHeight / 10);
	}

	private void setupQueueUI() {
		queueContainer = findViewById(R.id.queue_container);
		expandedQueueContainer = findViewById(R.id.expanded_queue_container);

		findViewById(R.id.btn_queue_close).setOnClickListener(v -> hideExpandedQueue());

		View.OnClickListener toggleExpand = v -> showExpandedQueue();
		findViewById(R.id.queue_header).setOnClickListener(toggleExpand);
		findViewById(R.id.btn_expand_queue).setOnClickListener(toggleExpand);

		final RecyclerView recyclerView = findViewById(R.id.queue_items_recycler);
		final TextView emptyView = findViewById(R.id.queue_empty);

		queueAdapter = new QueueAdapter(new QueueAdapter.Actions() {
			@Override
			public void onPlayRequested(@NonNull final QueueItem item) {
				if (item.getUrl() != null) player.play(item.getUrl());
			}

			@Override
			public void onDeleteRequested(@NonNull final QueueItem item) {
				confirmRemove(() -> {
					final String videoId = item.getVideoId();
					if (videoId != null && queueRepository.remove(videoId)) {
						player.refreshQueueNavigationAvailability();
						syncQueueExpandedUI(recyclerView, emptyView);
					}
				});
			}
		});

		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.setAdapter(queueAdapter);

		final AtomicBoolean dirty = new AtomicBoolean(false);
		new ItemTouchHelper(new QueueTouch((from, to) -> {
			final boolean moved = queueAdapter.moveItem(from, to);
			if (moved) dirty.set(true);
			return moved;
		}, new QueueTouch.DragStateCallback() {
			@Override public void onDragStateChanged(final boolean dragging) {}
			@Override
			public void onDragFinished() {
				if (dirty.getAndSet(false)) {
					saveQueueOrder(queueAdapter.snapshotItems());
					player.refreshQueueNavigationAvailability();
				}
				syncQueueExpandedUI(recyclerView, emptyView);
			}
		})).attachToRecyclerView(recyclerView);

		final ImageButton orderButton = findViewById(R.id.btn_queue_order);
		if (orderButton != null) {
			renderLoop(orderButton, player.getLoopMode());
			orderButton.setOnClickListener(v -> {
				final PlayerLoopMode newMode = player.getLoopMode().next();
				player.setLoopMode(newMode);
				renderLoop(orderButton, newMode);
			});
		}

		final ImageButton clearButton = findViewById(R.id.btn_queue_clear);
		if (clearButton != null) {
			clearButton.setOnClickListener(v -> confirmClear(() -> {
				queueRepository.clear();
				player.refreshQueueNavigationAvailability();
				syncQueueExpandedUI(recyclerView, emptyView);
			}));
		}

		engine.addListener(new Player.Listener() {
			@Override
			public void onPlaybackStateChanged(final int state) {
				mainHandler.post(() -> {
					if (expandedQueueContainer != null && expandedQueueContainer.getVisibility() == View.VISIBLE) {
						syncQueueExpandedUI(recyclerView, emptyView);
					}
				});
			}
		});

		updateQueueUI();
	}

	private void showExpandedQueue() {
		if (expandedQueueContainer != null) {
			expandedQueueContainer.setVisibility(View.VISIBLE);
			queueContainer.setVisibility(View.GONE);
			syncQueueExpandedUI(findViewById(R.id.queue_items_recycler), findViewById(R.id.queue_empty));
		}
	}

	private void hideExpandedQueue() {
		if (expandedQueueContainer != null) {
			expandedQueueContainer.setVisibility(View.GONE);
			updateQueueUI();
		}
	}

	private void syncQueueExpandedUI(@NonNull final RecyclerView recyclerView,
								@NonNull final TextView emptyView) {
		if (queueAdapter == null) return;
		final List<QueueItem> items = queueRepository.getItems();
		queueAdapter.replaceItems(items, player.getLoadedVideoId());
		emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
		recyclerView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
	}

	private void syncQueueUiVisibility(final boolean isInPictureInPictureMode) {
		if (shouldShowQueueUi(isInPictureInPictureMode)) {
			updateQueueUI();
		} else {
			if (queueContainer != null) queueContainer.setVisibility(View.GONE);
			if (expandedQueueContainer != null) expandedQueueContainer.setVisibility(View.GONE);
		}
	}

	private void saveQueueOrder(@NonNull final List<QueueItem> order) {
		final List<QueueItem> current = queueRepository.getItems();
		for (int to = 0; to < order.size(); to++) {
			final String videoId = order.get(to).getVideoId();
			int from = -1;
			for (int i = 0; i < current.size(); i++) {
				if (Objects.equals(videoId, current.get(i).getVideoId())) {
					from = i;
					break;
				}
			}
			if (from >= 0 && from != to && queueRepository.move(from, to)) {
				current.add(to, current.remove(from));
			}
		}
	}

	private void confirmClear(@NonNull final Runnable onConfirmed) {
		new MaterialAlertDialogBuilder(this)
				.setMessage(R.string.clear_queue_confirmation)
				.setPositiveButton(R.string.confirm, (d, which) -> onConfirmed.run())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void confirmRemove(@NonNull final Runnable onConfirmed) {
		new MaterialAlertDialogBuilder(this)
				.setMessage(R.string.remove_queue_item_confirmation)
				.setPositiveButton(R.string.confirm, (d, which) -> onConfirmed.run())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void renderLoop(@NonNull final ImageButton button, @NonNull final PlayerLoopMode mode) {
		switch (mode) {
			case PLAYLIST_NEXT -> button.setImageResource(R.drawable.ic_playback_end_next);
			case LOOP_ONE -> button.setImageResource(R.drawable.ic_playback_end_loop);
			case PAUSE_AT_END -> button.setImageResource(R.drawable.ic_playback_end_pause);
			case PLAYLIST_RANDOM -> button.setImageResource(R.drawable.ic_playback_end_shuffle);
		}
	}

	private void updateQueueBarPosition() {
		if (queueContainer == null) return;

		ViewGroup.LayoutParams params = queueContainer.getLayoutParams();
		if (params instanceof ViewGroup.MarginLayoutParams marginParams) {
			boolean isWatch = isWatchPage();
			if (isWatch) {
				marginParams.bottomMargin = navigationBarHeight;
				marginParams.leftMargin = 0;
				marginParams.rightMargin = 0;
			} else {
				marginParams.bottomMargin = navigationBarHeight + ViewUtils.dpToPx(this, 16);
				marginParams.leftMargin = ViewUtils.dpToPx(this, 8);
				marginParams.rightMargin = ViewUtils.dpToPx(this, 8);
			}
		}
		queueContainer.setLayoutParams(params);
	}

	private void updateQueueUI() {
		if (queueContainer == null) return;
		if (expandedQueueContainer != null && expandedQueueContainer.getVisibility() == View.VISIBLE) return;

		List<QueueItem> queue = queueRepository.getItems();
		if (queue.isEmpty()) {
			queueContainer.setVisibility(View.GONE);
			return;
		}

		queueContainer.setVisibility(View.VISIBLE);
		updateQueueBarPosition();

		TextView titleText = findViewById(R.id.queue_title);
		if (titleText != null && !queue.isEmpty()) {
			titleText.setText(queue.get(0).getTitle());
		}
	}

	private boolean isWatchPage() {
		YoutubeWebview webview = getWebview();
		if (webview == null) return false;
		String url = webview.getUrl();
		return url != null && (url.contains("/watch") || url.contains("/shorts/"));
	}

	public void setUiVisibility(boolean visible) {
        if (DeviceUtils.isInPictureInPictureMode(this)) return;
		if (navBar != null) navBar.setVisibility(visible ? View.VISIBLE : View.GONE);
		if (navBarDivider != null) navBarDivider.setVisibility(visible ? View.VISIBLE : View.GONE);
		if (visible) updateNavBarVisibility();
	}

	@Override
	protected void onResume() {
		super.onResume();
		updateNavBarVisibility();
		updateQueueUI();
		suppressNextUserLeaveHintPictureInPicture = false;
		if (player != null && shouldRestoreMiniPlayerOnResume(player.isInAppMiniPlayer(), DeviceUtils.isInPictureInPictureMode(this))) {
			player.restoreInAppMiniPlayerUiIfNeeded();
		}
	}

	@Override
	public void startActivity(@Nullable final Intent intent) {
		if (shouldSuppressPictureInPictureForStartedActivity(intent, getPackageName())) {
			suppressNextUserLeaveHintPictureInPicture = true;
		}
		super.startActivity(intent);
	}

	private void updateNavBarVisibility() {
		if (navBar != null) {
			navBar.update();
			navBarDivider.setVisibility(navBar.getVisibility());
		}
	}

	private void handleIntent(@Nullable Intent intent) {
		if (intent == null) return;
		if ("OPEN_DOWNLOADS".equals(intent.getAction())) {
			startActivity(new Intent(this, DownloadActivity.class));
			return;
		}
		if ("PLAY_VIDEO".equals(intent.getAction())) {
			String url = intent.getStringExtra("url");
			if (url != null) player.play(url);
			return;
		}
		String url = null;
		if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
			url = intent.getData().toString();
		} else if (Intent.ACTION_SEND.equals(intent.getAction())) {
			String text = intent.getStringExtra(Intent.EXTRA_TEXT);
			if (text != null) url = extractUrlFromText(text);
		}
		if (url != null) {
			final String clean = url.replace(YOUTUBE_WWW_HOST, Constant.YOUTUBE_MOBILE_HOST);
			if (tabManager != null) tabManager.openTab(clean, UrlUtils.getPageClass(clean));
		} else if (tabManager != null && tabManager.getWebview() == null) {
			tabManager.openTab(Constant.HOME_URL, UrlUtils.getPageClass(Constant.HOME_URL));
		}
	}

	private String extractUrlFromText(String text) {
		Matcher m = Pattern.compile("https?://[\\w./?=&%#-]+", Pattern.CASE_INSENSITIVE).matcher(text);
		return m.find() ? m.group() : null;
	}

	private void setupNativeContextMenu() {
		findViewById(R.id.main).postDelayed(() -> {
			final YoutubeWebview webview = getWebview();
			if (webview != null) {
				webview.setOnLongClickListener(v -> {
					final WebView.HitTestResult r = webview.getHitTestResult();
					String url = r.getExtra();
					if (url == null) return false;
					if (url.startsWith("/")) url = "https://m.youtube.com" + url;
					if (url.contains("/shorts/")) return false;
					if (url.contains("/watch") || url.contains("list=") || url.contains("video_id=")) {
						showVideoOptionsDialog(url, null);
						return true;
					}
					return false;
				});
				webview.evaluateJavascript("document.addEventListener('contextmenu', e => { const a = e.target.closest('a'); if (a && a.href && !a.href.includes('/shorts/') && (a.href.includes('/watch') || a.href.includes('list='))) { e.preventDefault(); let title = ''; const titleEl = a.querySelector('h3, span#video-title'); if (titleEl) title = titleEl.innerText; android.showVideoOptions(a.href, title); } }, true);", null);
				webview.setOnPageFinishedListener(u -> {
					updateNavBarVisibility();
					runOnUiThread(this::updateQueueUI);
				});
			}
		}, 1500);
	}

	public void showVideoOptionsDialog(String url, @Nullable String title) {
		boolean hasVideoId = url.contains("v=") || url.contains("/watch")
				|| url.contains("video_id=") || url.contains("/live/") || url.contains("youtu.be/");
		boolean hasPlaylistId = url.contains("list=") || url.contains("/playlist");
		boolean isMix = url.contains("list=RD");

		boolean isPlaylist = hasPlaylistId && !hasVideoId && !isMix;

		@SuppressLint("InflateParams") View view = LayoutInflater.from(this).inflate(R.layout.dialog_video_options, null);
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setView(view)
				.create();

		if (title != null) {
			TextView dialogTitle = view.findViewById(R.id.dialog_title);
			if (dialogTitle != null) dialogTitle.setText(title);
		}

		View optionEnqueue = view.findViewById(R.id.option_enqueue);
		View optionDownload = view.findViewById(R.id.option_download);
		View optionPlaylist = view.findViewById(R.id.option_playlist);

		TextView enqueueText = view.findViewById(R.id.text_enqueue);
		final String videoId = YoutubeExtractor.getVideoId(url);
		boolean initiallyInQueue = false;
		if (videoId != null) {
			for (QueueItem item : queueRepository.getItems()) {
				if (videoId.equals(item.getVideoId())) {
					initiallyInQueue = true;
					break;
				}
			}
		}

		if (enqueueText != null) {
			enqueueText.setText(initiallyInQueue ? "Remove from Queue" : "Add to Queue");
		}

		if (isPlaylist) {
			optionEnqueue.setVisibility(View.GONE);
			optionPlaylist.setVisibility(View.VISIBLE);
			optionDownload.setVisibility(View.GONE);
		} else {
			optionEnqueue.setVisibility(View.VISIBLE);
			optionPlaylist.setVisibility(View.GONE);
			optionDownload.setVisibility(View.VISIBLE);
		}

		final boolean inQueue = initiallyInQueue;
		optionEnqueue.setOnClickListener(v -> {
			dialog.dismiss();
			if (inQueue) {
				queueRepository.remove(videoId);
				Toast.makeText(this, "Removed from queue", Toast.LENGTH_SHORT).show();
			} else {
				fetchAndEnqueue(url);
			}
			updateQueueUI();
		});

		optionDownload.setOnClickListener(v -> {
			dialog.dismiss();
			triggerDownload(url);
		});

		optionPlaylist.setOnClickListener(v -> {
			dialog.dismiss();
			triggerPlaylistDownload(url);
		});

		view.findViewById(R.id.option_share).setOnClickListener(v -> {
			dialog.dismiss();
			shareUrl(url);
		});

		view.findViewById(R.id.btn_close).setOnClickListener(v -> dialog.dismiss());

		dialog.show();
	}

	private void fetchAndEnqueue(String url) {
		executor.execute(() -> {
			try {
				VideoDetails details = youtubeExtractor.getVideoInfo(url);
				QueueItem item = new QueueItem();
				item.setVideoId(details.getId());
				item.setTitle(details.getTitle());
				item.setAuthor(details.getAuthor());
				item.setThumbnailUrl(details.getThumbnail());
				item.setUrl(url);
				queueRepository.add(item);
				runOnUiThread(() -> {
					player.refreshQueueNavigationAvailability();
					updateQueueUI();
					Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show();
				});
			} catch (Exception ignored) {}
		});
	}

	public void triggerDownload(String url) {
		String clean = url.replace(Constant.YOUTUBE_MOBILE_HOST, YOUTUBE_WWW_HOST);
		Toast.makeText(this, "Fetching details...", Toast.LENGTH_SHORT).show();
		mainHandler.postDelayed(() -> new DownloadDialog(clean, this, youtubeExtractor).show(), 600);
	}

	private void triggerPlaylistDownload(String url) {
		String clean = url.replace(Constant.YOUTUBE_MOBILE_HOST, YOUTUBE_WWW_HOST);
		Toast.makeText(this, "Fetching playlist...", Toast.LENGTH_SHORT).show();
		executor.execute(() -> {
			try {
				PlaylistExtractor ex = NewPipe.getService(0).getPlaylistExtractor(clean);
				ex.fetchPage();
				String playlistName = ex.getName();
				List<StreamInfoItem> items = new ArrayList<>();
				InfoItemsPage<StreamInfoItem> p = ex.getInitialPage();
				while (p != null) {
					items.addAll(p.getItems());
					if (!Page.isValid(p.getNextPage())) break;
					p = ex.getPage(p.getNextPage());
				}
				mainHandler.post(() -> new PlaylistDownloadDialog(this, items, playlistName, youtubeExtractor, downloadService).show());
			} catch (Exception e) {
				mainHandler.post(() -> Toast.makeText(this, "Failed to load playlist", Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void shareUrl(String url) {
		Intent i = new Intent(Intent.ACTION_SEND);
		i.putExtra(Intent.EXTRA_TEXT, url);
		i.setType("text/plain");
		startActivity(Intent.createChooser(i, "Share Video"));
	}

	private void setupBackNavigation() {
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override public void handleOnBackPressed() {
				if (DeviceUtils.isInPictureInPictureMode(MainActivity.this)) {
					setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); return;
				}
				if (player != null && player.isFullscreen()) { player.exitFullscreen(); return; }
                if (expandedQueueContainer != null && expandedQueueContainer.getVisibility() == View.VISIBLE) {
                    hideExpandedQueue();
                    return;
                }
				final YoutubeWebview web = getWebview();
				if (web != null && tabManager != null) {
					tabManager.evaluateJavascript("window.dispatchEvent(new Event('onGoBack'));", null);
					if (web.canGoBack()) {
						goBack();
						return;
					}
				}
				goBack();
			}
		});
	}

	private void goBack() {
		if (tabManager != null && !tabManager.goBack()) {
			if (System.currentTimeMillis() - lastBackTime < DOUBLE_TAP_EXIT_INTERVAL_MS) finish();
			else { lastBackTime = System.currentTimeMillis(); Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show(); }
		} else {
			updateNavBarVisibility();
			updateQueueUI();
		}
	}

	@Nullable private YoutubeWebview getWebview() { return tabManager != null ? tabManager.getWebview() : null; }

	private void requestPermissions() {
		if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_CODE);
	}

	@Override protected void onStop() {
		if (player != null && shouldSuspendMiniPlayerOnStop(player.isInAppMiniPlayer(), isChangingConfigurations(), DeviceUtils.isInPictureInPictureMode(this))) {
			player.suspendInAppMiniPlayerUiIfNeeded();
		}
		super.onStop();
	}

	@Override protected void onDestroy() {
		super.onDestroy();
		if (playbackConnection != null) unbindService(playbackConnection);
		if (downloadConnection != null) unbindService(downloadConnection);
		if (player != null && shouldReleasePlayerOnDestroy(isChangingConfigurations())) player.release();
		executor.shutdown();
	}
}
