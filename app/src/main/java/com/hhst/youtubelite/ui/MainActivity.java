package com.hhst.youtubelite.ui;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Rational;
import android.view.View;
import android.webkit.WebView;
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
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.browser.YoutubeWebview;
import com.hhst.youtubelite.downloader.ui.DownloadActivity;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.LitePlayerView;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.UrlUtils;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

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

        // Initialize our Native Hold-to-Download Menu
        setupNativeContextMenu();

        requestPermissions();
        startPlaybackService();
        handleIntent(getIntent());
        setupBackNavigation();
    }

    private void setupNativeContextMenu() {
        findViewById(android.R.id.content).postDelayed(() -> {
            final YoutubeWebview webview = getWebview();
            if (webview != null) {
                webview.setOnLongClickListener(v -> {
                    final WebView.HitTestResult result = webview.getHitTestResult();
                    final String url = result.getExtra();

                    // If it's a video link
                    if (url != null && (url.contains("/watch?v=") || url.contains("/shorts/"))) {
                        showVideoOptionsDialog(url);
                        return true;
                    }
                    return false;
                });
            }
        }, 1500);
    }

    private void showVideoOptionsDialog(String videoUrl) {
        String[] options = {"Download", "Share Link", "Cancel"};

        new MaterialAlertDialogBuilder(this)
                .setTitle("Video Options")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // Download
                            triggerDownload(videoUrl);
                            break;
                        case 1: // Share
                            shareUrl(videoUrl);
                            break;
                        case 2: // Cancel
                            dialog.dismiss();
                            break;
                    }
                })
                .show();
    }

    /**
     * THE CORRECT COMMAND:
     * In this app, starting a download from an external URL requires
     * sending the URL to the PlaybackService or a dedicated DownloadDialog.
     */
    private void triggerDownload(String url) {
        Toast.makeText(this, "Resolving video info...", Toast.LENGTH_SHORT).show();

        // We use the same intent the 'Share to App' uses,
        // which triggers the downloader UI automatically.
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, url);
        intent.setType("text/plain");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // This tells the app: "Act as if I just shared this link from another app"
        handleIntent(intent);
    }

    private void shareUrl(String url) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, url);
        sendIntent.setType("text/plain");
        startActivity(Intent.createChooser(sendIntent, "Share Video"));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        final LitePlayerView playerView = findViewById(R.id.playerView);
        if (playerView != null) {
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                if (playerView.getVisibility() == View.VISIBLE && !playerView.isFs()) {
                    playerView.enterFullscreen(false);
                }
            } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                if (playerView.isFs()) {
                    playerView.exitFullscreen();
                }
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        }
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

                final LitePlayerView playerView = findViewById(R.id.playerView);
                if (playerView != null && playerView.isFs()) {
                    playerView.exitFullscreen();
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    return;
                }

                final YoutubeWebview webview = getWebview();
                if (webview != null && tabManager != null) {
                    String currentUrl = webview.getUrl();
                    if (currentUrl != null && currentUrl.contains("/watch?v=")) {
                        tabManager.loadUrl(Constant.HOME_URL);
                        return;
                    }

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

        // If the intent is a "Send" action (from our dialog or external share)
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null) {
                // This triggers the player to load the video,
                // which then makes the built-in download button available.
                tabManager.loadUrl(sharedText);
            }
            return;
        }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_CODE);

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