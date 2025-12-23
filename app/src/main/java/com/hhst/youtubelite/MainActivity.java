package com.hhst.youtubelite;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.extension.Constant;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.DownloaderImpl;
import com.hhst.youtubelite.extractor.PoTokenProviderImpl;
import com.hhst.youtubelite.player.PlayerContext;
import com.hhst.youtubelite.player.interfaces.IController;
import com.hhst.youtubelite.player.interfaces.IPlayer;
import com.hhst.youtubelite.utils.UrlUtils;
import com.hhst.youtubelite.webview.YoutubeWebview;
import com.tencent.mmkv.MMKV;

import lombok.Getter;

@UnstableApi
@Getter
public final class MainActivity extends AppCompatActivity {

	private static final int REQUEST_NOTIFICATION_CODE = 100;
	private static final int REQUEST_STORAGE_CODE = 101;
	private static final String INITIAL_URL = "https://m.youtube.com";
	private static final String MIME_TEXT_PLAIN = "text/plain";
	private static final String SCHEME_HTTP = "http://";
	private static final String SCHEME_HTTPS = "https://";
	private static final int DOUBLE_TAP_EXIT_INTERVAL_MS = 2_000;
	private static final int PIP_ASPECT_RATIO_NUMERATOR = 16;
	private static final int PIP_ASPECT_RATIO_DENOMINATOR = 9;
	@Getter
	private static MainActivity instance;
	@NonNull
	private final Handler handler = new Handler(Looper.getMainLooper());

	@Nullable
	@Getter
	private DownloadService downloadService;

	@Nullable
	private PlaybackService playbackService;
	@Nullable
	private PoTokenProviderImpl poTokenProvider;
	@Nullable
	private ServiceConnection playbackServiceConnection;
	@Nullable
	private ServiceConnection downloadServiceConnection;

	@Nullable
	@Getter
	private ExtensionManager extensionManager;

	@Nullable
	@Getter
	private TabManager tabManager;

	private long lastBackTime = 0;

	@Override
	protected void onCreate(@Nullable final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		instance = this;
		MMKV.initialize(this);
		poTokenProvider = PoTokenProviderImpl.init();
		DownloaderImpl.init(null);
		extensionManager = new ExtensionManager();
		tabManager = new TabManager(this);
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_main);

		final View mainView = findViewById(R.id.main);
		if (mainView != null) ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
			final Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
			return insets;
		});

		PlayerContext.init(this, tabManager);
		final String initialUrl = getInitialUrl();
		openTab(initialUrl, UrlUtils.getPageClass(initialUrl));

		requestPermissions();
		startDownloadService();
		startPlaybackService();
	}

	@NonNull
	private String getInitialUrl() {
		final Intent intent = getIntent();
		final String action = intent.getAction();
		final String type = intent.getType();

		String initialUrl = INITIAL_URL;
		if (Intent.ACTION_SEND.equals(action) && MIME_TEXT_PLAIN.equals(type)) {
			final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
			if (sharedText != null && (sharedText.startsWith(SCHEME_HTTP) || sharedText.startsWith(SCHEME_HTTPS)))
				initialUrl = sharedText;
		} else {
			final Uri intentUri = intent.getData();
			if (intentUri != null) initialUrl = intentUri.toString();
		}
		return initialUrl;
	}

	@Nullable
	private YoutubeWebview getWebview() {
		if (tabManager != null) return tabManager.getWebview();
		return null;
	}

	@Nullable
	public SwipeRefreshLayout getSwipeRefreshLayout() {
		if (tabManager != null && tabManager.getTab() != null)
			return tabManager.getTab().getSwipeRefreshLayout();
		return null;
	}

	public void openTab(@NonNull final String url, @Nullable final String tag) {
		if (tabManager != null) tabManager.openTab(url, tag);
	}

	private void goBack() {
		if (tabManager != null && !tabManager.goBack()) {
			// Handle double-tap to exit
			final long time = System.currentTimeMillis();
			if (time - lastBackTime < DOUBLE_TAP_EXIT_INTERVAL_MS) finish();
			else {
				lastBackTime = time;
				Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
			}
		}
	}

	public void play(@NonNull final String url) {
		if (PlayerContext.getInstance() != null && PlayerContext.getInstance().getPlayer() != null)
			PlayerContext.getInstance().getPlayer().play(url);
	}

	public void hidePlayer() {
		if (PlayerContext.getInstance() == null || PlayerContext.getInstance().getPlayer() == null)
			return;
		PlayerContext.getInstance().getPlayer().hide();
	}

	public void setPlayerHeight(final int height) {
		if (PlayerContext.getInstance() == null || PlayerContext.getInstance().getController() == null || PlayerContext.getInstance().getPlayer() == null)
			return;
		if (PlayerContext.getInstance().getController().isFullscreen() || isInPictureInPictureMode())
			return;
		PlayerContext.getInstance().getPlayer().setPlayerHeight(height);
	}

	private void requestPermissions() {
		// check and require post-notification permission
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_CODE);

		// check storage permission
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
			ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_CODE);
	}

	@Override
	public void onUserLeaveHint() {
		final PlayerContext playerContext = PlayerContext.getInstance();
		if (playerContext != null && playerContext.getEngine() != null && extensionManager != null) {
			if (playerContext.getEngine().isPlaying() && extensionManager.isEnabled(Constant.enablePip)) {
				final Rational aspectRatio = new Rational(PIP_ASPECT_RATIO_NUMERATOR, PIP_ASPECT_RATIO_DENOMINATOR);
				final PictureInPictureParams params = new PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build();
				enterPictureInPictureMode(params);
			}
		}
		super.onUserLeaveHint();
	}

	@Override
	protected void onPause() {
		// Pause player if background play is disabled
		final PlayerContext playerContext = PlayerContext.getInstance();
		if (playerContext != null && playerContext.getEngine() != null && extensionManager != null) {
			if (isInPictureInPictureMode()) {
				super.onPause();
				return;
			}
			if (playerContext.getEngine().isPlaying() && !extensionManager.isEnabled(Constant.enableBackgroundPlay))
				playerContext.getEngine().pause();
		}
		super.onPause();
	}

	@Override
	public void onPictureInPictureModeChanged(final boolean isInPictureInPictureMode, @NonNull final Configuration newConfig) {
		final PlayerContext playerContext = PlayerContext.getInstance();
		if (playerContext != null && playerContext.getController() != null)
			playerContext.getController().onPictureInPictureModeChanged(isInPictureInPictureMode);
		final YoutubeWebview webview = getWebview();
		final SwipeRefreshLayout swipeRefreshLayout = getSwipeRefreshLayout();
		final int visibility = isInPictureInPictureMode ? View.GONE : View.VISIBLE;
		if (webview != null) webview.setVisibility(visibility);
		if (swipeRefreshLayout != null) swipeRefreshLayout.setEnabled(!isInPictureInPictureMode);
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
	}

	@Override
	public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
		if (keyCode == KeyEvent.KEYCODE_BACK) {
			final PlayerContext playerContext = PlayerContext.getInstance();
			if (playerContext != null) {
				final IController controller = playerContext.getController();
				if (controller != null && controller.isFullscreen()) {
					controller.exitFullscreen();
					return true;
				}
			}

			final YoutubeWebview webview = getWebview();
			if (webview != null && tabManager != null) {
				tabManager.evaluateJavascript("window.dispatchEvent(new Event('onGoBack'));", null);
				if (webview.fullscreen != null && webview.fullscreen.getVisibility() == View.VISIBLE) {
					tabManager.evaluateJavascript("document.exitFullscreen()", null);
					return true;
				}
			}
			goBack();
			return true;
		}
		return false;
	}

	private void startDownloadService() {
		// bind the download service
		downloadServiceConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(final ComponentName componentName, final IBinder binder) {
				downloadService = ((DownloadService.DownloadBinder) binder).getService();
			}

			@Override
			public void onServiceDisconnected(final ComponentName componentName) {
				downloadService = null;
			}
		};

		final Intent intent = new Intent(this, DownloadService.class);
		bindService(intent, downloadServiceConnection, Context.BIND_AUTO_CREATE);
	}

	private void startPlaybackService() {
		// bind
		playbackServiceConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(final ComponentName componentName, final IBinder binder) {
				playbackService = ((PlaybackService.PlaybackBinder) binder).getService();
				final PlayerContext playerContext = PlayerContext.getInstance();
				if (playerContext != null) {
					final IPlayer player = playerContext.getPlayer();
					if (player != null && playbackService != null) {
						player.attachPlaybackService(playbackService);
						playbackService.initialize(playerContext.getEngine());
					}
				}
			}

			@Override
			public void onServiceDisconnected(final ComponentName componentName) {
				playbackService = null;
			}
		};
		final Intent intent = new Intent(this, PlaybackService.class);
		bindService(intent, playbackServiceConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onNewIntent(@NonNull final Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		final Uri uri = intent.getData();
		if (uri != null && tabManager != null) tabManager.loadUrl(uri.toString());
	}

	public void shareLink(@NonNull final String url) {
		final Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setData(Uri.parse(url));
		startActivity(intent);
	}

	@Nullable
	public IController getPlayerController() {
		final PlayerContext playerContext = PlayerContext.getInstance();
		return playerContext != null ? playerContext.getController() : null;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (playbackService != null) {
			playbackService.hideNotification();
			stopService(new Intent(this, PlaybackService.class));
		}
		if (downloadServiceConnection != null) {
			unbindService(downloadServiceConnection);
			downloadServiceConnection = null;
		}
		if (playbackServiceConnection != null) {
			unbindService(playbackServiceConnection);
			playbackServiceConnection = null;
		}

		final PlayerContext context = PlayerContext.getInstance();
		if (context != null) {
			if (context.getPlayer() != null) context.getPlayer().release();
			if (context.getController() != null) context.getController().release();
		}
		downloadService = null;
		playbackService = null;
	}

}
