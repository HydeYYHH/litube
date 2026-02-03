package com.hhst.youtubelite.ui;

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
import android.os.IBinder;
import android.util.Rational;
import android.view.View;
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

import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.browser.YoutubeWebview;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.DeviceUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;


import com.hhst.youtubelite.downloader.ui.DownloadActivity;

@AndroidEntryPoint
@UnstableApi
public final class MainActivity extends AppCompatActivity {

	private static final String YOUTUBE_WWW_HOST = "www.youtube.com";
	private static final int REQUEST_NOTIFICATION_CODE = 100;
	private static final int REQUEST_STORAGE_CODE = 101;
	private static final int DOUBLE_TAP_EXIT_INTERVAL_MS = 2_000;

	@Inject
	ExtensionManager extensionManager;
	@Inject
	TabManager tabManager;
	@Inject
	LitePlayer player;
	@Nullable
	private PlaybackService playbackService;
	@Nullable
	private ServiceConnection playbackServiceConnection;
	private long lastBackTime = 0;

	@Override
	protected void onCreate(@Nullable final Bundle savedInstanceState) {
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_main);
		super.onCreate(savedInstanceState);

		final View mainView = findViewById(R.id.main);
		ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
			final Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
			return insets;
		});

		if (savedInstanceState == null) {
			final String initialUrl = getInitialUrl();
			tabManager.openTab(initialUrl, UrlUtils.getPageClass(initialUrl));
		}

		requestPermissions();
		startPlaybackService();

		handleIntent(getIntent());

		setupBackNavigation();
	}

	// fixs: back button issue
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

	private void handleIntent(@Nullable Intent intent) {
		if (intent == null) return;
		if ("OPEN_DOWNLOADS".equals(intent.getAction())) {
			Intent downloadIntent = new Intent(this, DownloadActivity.class);
			startActivity(downloadIntent);
		}
	}

	@NonNull
	private String getInitialUrl() {
		final Intent intent = getIntent();
		final String action = intent.getAction();
		final String type = intent.getType();

		String initialUrl = Constant.HOME_URL;
		if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
			final String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
			if (sharedText != null && (sharedText.startsWith("http://") || sharedText.startsWith("https://")))
				initialUrl = sharedText.replace(YOUTUBE_WWW_HOST, Constant.YOUTUBE_MOBILE_HOST);
		} else {
			final Uri intentUri = intent.getData();
			if (intentUri != null)
				initialUrl = intentUri.toString().replace(YOUTUBE_WWW_HOST, Constant.YOUTUBE_MOBILE_HOST);
		}
		return initialUrl;
	}

	@Nullable
	private YoutubeWebview getWebview() {
		if (tabManager != null) return tabManager.getWebview();
		return null;
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

	@Override
	public void onUserLeaveHint() {
		if (player != null && extensionManager != null) {
			if (player.isPlaying() && extensionManager.isEnabled(Constant.ENABLE_PIP)) {
				final Rational aspectRatio = new Rational(16, 9);
				final PictureInPictureParams params = new PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build();
				enterPictureInPictureMode(params);
			}
		}
		super.onUserLeaveHint();
	}

	@Override
	protected void onPause() {
		// Pause player if background play is disabled
		if (player != null && extensionManager != null) {
			if (isInPictureInPictureMode()) {
				super.onPause();
				return;
			}
			if (player.isPlaying() && !extensionManager.isEnabled(Constant.ENABLE_BACKGROUND_PLAY))
				player.pause();
		}
		super.onPause();
	}

	@Override
	public void onPictureInPictureModeChanged(final boolean isInPictureInPictureMode, @NonNull final Configuration newConfig) {
		if (player != null) player.onPictureInPictureModeChanged(isInPictureInPictureMode);
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
	}

	private void startPlaybackService() {
		// bind
		playbackServiceConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(final ComponentName componentName, final IBinder binder) {
				playbackService = ((PlaybackService.PlaybackBinder) binder).getService();
				if (player != null && playbackService != null) {
					player.attachPlaybackService(playbackService);
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
		handleIntent(intent);
		final Uri uri = intent.getData();
		if (uri != null && tabManager != null) tabManager.loadUrl(uri.toString());
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
	protected void onDestroy() {
		super.onDestroy();
		if (playbackService != null) {
			playbackService.hideNotification();
			stopService(new Intent(this, PlaybackService.class));
		}
		if (playbackServiceConnection != null) {
			unbindService(playbackServiceConnection);
			playbackServiceConnection = null;
		}

		if (player != null) player.release();

		playbackService = null;
	}

}
