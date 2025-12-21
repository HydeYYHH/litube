package com.hhst.youtubelite;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.hhst.youtubelite.downloader.DownloadService;
import com.hhst.youtubelite.extension.Constant;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.DownloaderImpl;
import com.hhst.youtubelite.extractor.PoTokenProviderImpl;
import com.hhst.youtubelite.player.PlayerContext;
import com.hhst.youtubelite.player.interfaces.IController;
import com.hhst.youtubelite.player.interfaces.IEngine;
import com.hhst.youtubelite.player.interfaces.IPlayer;
import com.hhst.youtubelite.webview.YoutubeWebview;
import com.tencent.mmkv.MMKV;

import org.apache.commons.io.FilenameUtils;

import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Getter;

@UnstableApi
@Getter
public class MainActivity extends AppCompatActivity {

	private static final int REQUEST_NOTIFICATION_CODE = 100;
	private static final int REQUEST_STORAGE_CODE = 101;
	@Getter
	public DownloadService downloadService;
	private YoutubeWebview webview;
	private SwipeRefreshLayout swipeRefreshLayout;
	private ProgressBar progressBar;
	private IPlayer player;
	private IController playerController;
	private IEngine playerEngine;
	private PlaybackService playbackService;
	private PoTokenProviderImpl poTokenProvider;
	private ServiceConnection playbackServiceConnection;
	private ServiceConnection downloadServiceConnection;
	@Getter
	private ExtensionManager extensionManager;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		MMKV.initialize(this);
		poTokenProvider = PoTokenProviderImpl.init();
		DownloaderImpl.init(null);
		extensionManager = new ExtensionManager();
		EdgeToEdge.enable(this);
		setContentView(R.layout.activity_main);
		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
			Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
			v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
			return insets;
		});

		swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
		progressBar = findViewById(R.id.progressBar);
		webview = findViewById(R.id.webview);

		swipeRefreshLayout.setColorSchemeResources(R.color.light_blue, R.color.blue, R.color.dark_blue);
		swipeRefreshLayout.setOnRefreshListener(() -> webview.evaluateJavascript("window.dispatchEvent(new Event('onRefresh'));", value -> {
		}));
		swipeRefreshLayout.setProgressViewOffset(true, 86, 196);

		try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
			executorService.execute(() -> {
				loadScript();
				runOnUiThread(() -> {
					webview.build();
					PlayerContext playerContext = PlayerContext.getInstance(MainActivity.this, webview);
					player = playerContext.getPlayer();
					playerController = playerContext.getController();
					playerEngine = playerContext.getEngine();
					// Process the intent data if available
					Intent intent = getIntent();
					String action = intent.getAction();
					String type = intent.getType();

					String baseUrl = "https://m.youtube.com";
					if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
						String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
						if (sharedText != null && (sharedText.startsWith("http://") || sharedText.startsWith("https://"))) {
							Log.d("MainActivity", "Loading shared URL: " + sharedText);
							webview.loadUrl(sharedText);
						} else {
							webview.loadUrl(baseUrl);
						}
					} else {
						Uri intentUri = intent.getData();
						if (intentUri != null) {
							Log.d("MainActivity", "Loading URL from intent: " + intentUri);
							webview.loadUrl(intentUri.toString());
						} else {
							webview.loadUrl(baseUrl);
						}
					}
				});
			});
		}

		requestPermissions();
		startDownloadService();
		startPlaybackService();
	}

	@OptIn(markerClass = UnstableApi.class)
	public void play(String url) {
		player.play(url);
	}

	@OptIn(markerClass = UnstableApi.class)
	public void hidePlayer() {
		player.hide();
	}

	public void setPlayerHeight(int height) {
		if (playerController.isFullscreen() || isInPictureInPictureMode()) return;
		player.setPlayerHeight(height);
	}

	public void requestPermissions() {
		// check and require post-notification permission
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_CODE);
			}
		}

		// check storage permission
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_CODE);
			}
		}
	}

	private void loadScript() {
		AssetManager assetManager = getAssets();

		List<String> resourceDirs = Arrays.asList("style", "script");
		try {
			for (String dir : resourceDirs) {
				List<String> resources = new ArrayList<>(Arrays.asList(Objects.requireNonNull(assetManager.list(dir))));
				// inject init.js or init.min.js
				String initScript = resources.contains("init.js") ? "init.js" : resources.contains("init.min.js") ? "init.min.js" : null;
				if (initScript != null) {
					webview.injectJavaScript(assetManager.open(dir + "/" + initScript));
					resources.remove(initScript);
				}
				for (String script : resources) {
					InputStream stream = assetManager.open(Paths.get(dir, script).toString());
					if (FilenameUtils.getExtension(script).equals("js")) {
						webview.injectJavaScript(stream);
					} else if (FilenameUtils.getExtension(script).equals("css")) {
						webview.injectCss(stream);
					}
				}
			}
		} catch (Exception e) {
			Log.e("load scripts error", "Failed to load assets: " + Log.getStackTraceString(e));
		}
	}

	@Override
	public void onUserLeaveHint() {
		if (playerEngine.isPlaying() && extensionManager.isEnabled(Constant.enablePip)) {
			Rational aspectRatio = new Rational(16, 9);
			PictureInPictureParams params = new PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build();
			enterPictureInPictureMode(params);
		}
		super.onUserLeaveHint();
	}

	@Override
	protected void onPause() {
		// Pause player if background play is disabled
		if (playerEngine.isPlaying() && !extensionManager.isEnabled(Constant.enableBackgroundPlay))
			playerEngine.pause();
		super.onPause();
	}

	@Override
	public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
		playerController.onPictureInPictureModeChanged(isInPictureInPictureMode);
		if (isInPictureInPictureMode) {
			webview.setVisibility(View.GONE);
			swipeRefreshLayout.setEnabled(false);
		} else {
			webview.setVisibility(View.VISIBLE);
			swipeRefreshLayout.setEnabled(true);
		}
		super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
	}

	@Override
	@OptIn(markerClass = UnstableApi.class)
	public boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
		if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
			if (playerController != null && playerController.isFullscreen()) {
				playerController.exitFullscreen();
				return true;
			}
			webview.evaluateJavascript("window.dispatchEvent(new Event('onGoBack'));", value -> {
			});
			if (webview.fullscreen != null && webview.fullscreen.getVisibility() == View.VISIBLE) {
				webview.evaluateJavascript("document.exitFullscreen()", value -> {
				});
				return true;
			}
			if (webview.canGoBack()) {
				webview.goBack();
			} else {
				finish();
			}
			return true;
		}
		return false;
	}

	private void startDownloadService() {
		// bind the download service
		downloadServiceConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName componentName, IBinder binder) {
				downloadService = ((DownloadService.DownloadBinder) binder).getService();
			}

			@Override
			public void onServiceDisconnected(ComponentName componentName) {
			}
		};

		Intent intent = new Intent(this, DownloadService.class);
		bindService(intent, downloadServiceConnection, Context.BIND_AUTO_CREATE);
	}

	private void startPlaybackService() {
		// bind
		playbackServiceConnection = new ServiceConnection() {
			@Override
			public void onServiceConnected(ComponentName componentName, IBinder binder) {
				playbackService = ((PlaybackService.PlaybackBinder) binder).getService();
				player.attachPlaybackService(playbackService);
				playbackService.initialize(player, playerEngine);
			}

			@Override
			public void onServiceDisconnected(ComponentName componentName) {
			}
		};
		Intent intent = new Intent(this, PlaybackService.class);
		bindService(intent, playbackServiceConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onNewIntent(@NonNull Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		Uri uri = intent.getData();
		if (uri != null && webview != null) {
			webview.loadUrl(uri.toString());
		}
	}

	public void shareLink(String url) {
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setData(Uri.parse(url));
		startActivity(intent);
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
		if (webview != null) {
			webview.setWebChromeClient(null);
			webview.removeAllViews();
			webview.destroy();
			webview = null;
		}
		if (player != null) {
			player.release();
			player = null;
		}
		if (playerController != null) {
			playerController.release();
			playerController = null;
		}
		downloadService = null;
		playbackService = null;

		// Clear any pending tasks to prevent memory leaks
		if (swipeRefreshLayout != null) {
			swipeRefreshLayout.setOnRefreshListener(null);
			swipeRefreshLayout = null;
		}

		progressBar = null;

		// Force garbage collection to clean up memory
		System.gc();
	}

}
