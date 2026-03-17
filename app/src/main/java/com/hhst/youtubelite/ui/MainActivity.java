package com.hhst.youtubelite.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
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
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.PlaybackService;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.browser.TabManager;
import com.hhst.youtubelite.browser.YoutubeWebview;
import com.hhst.youtubelite.downloader.core.Task;
import com.hhst.youtubelite.downloader.service.DownloadService;
import com.hhst.youtubelite.downloader.ui.DownloadActivity;
import com.hhst.youtubelite.downloader.ui.DownloadDialog;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.util.DeviceUtils;
import com.hhst.youtubelite.util.UrlUtils;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final MMKV kv = MMKV.defaultMMKV();

    @Inject ExtensionManager extensionManager;
    @Inject TabManager tabManager;
    @Inject LitePlayer player;
    @Inject YoutubeExtractor youtubeExtractor;

    @Nullable private PlaybackService playbackService;
    @Nullable private DownloadService downloadService;

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
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        setupNativeContextMenu();
        requestPermissions();
        bindService(new Intent(this, PlaybackService.class), playbackConnection, BIND_AUTO_CREATE);
        bindService(new Intent(this, DownloadService.class), downloadConnection, BIND_AUTO_CREATE);
        setupBackNavigation();
        mainView.post(() -> handleIntent(getIntent()));
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) return;
        if ("OPEN_DOWNLOADS".equals(intent.getAction())) {
            startActivity(new Intent(this, DownloadActivity.class));
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
        } else if (tabManager.getWebview() == null) {
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
                    if (url.contains("/watch") || url.contains("/shorts/") || url.contains("list=") || url.contains("video_id=")) {
                        showVideoOptionsDialog(url);
                        return true;
                    }
                    return false;
                });
                webview.evaluateJavascript("if(!window.location.pathname.startsWith('/shorts/')){var s=document.createElement('style');s.innerHTML=':root{--safe-area-inset-bottom:0px!important}body{padding-bottom:0px!important;margin-bottom:0px!important}ytm-pivot-bar-renderer{height:48px!important;padding-bottom:0px!important;bottom:0px!important;margin-bottom:0px!important;min-height:48px!important}a{text-decoration:none!important}a.yt-simple-endpoint{text-decoration:none!important;color:inherit!important}';document.head.appendChild(s)}", null);
                webview.evaluateJavascript("document.addEventListener('contextmenu', e => { const a = e.target.closest('a'); if (a && a.href && (a.href.includes('/watch') || a.href.includes('/shorts/'))) { e.preventDefault(); android.showVideoOptions(a.href); } }, true);", null);
            }
        }, 1500);
    }

    public void showVideoOptionsDialog(String url) {
        boolean isPl = url.contains("list=");
        String[] opts = isPl ? new String[]{"Download Video", "Download Playlist", "Share Link", "Open Downloads", "Cancel"}
                : new String[]{"Download Video", "Share Link", "Open Downloads", "Cancel"};
        new MaterialAlertDialogBuilder(this).setTitle("Video Options").setItems(opts, (d, w) -> {
            if (isPl) {
                if (w == 0) triggerDownload(url);
                else if (w == 1) triggerPlaylistDownload(url);
                else if (w == 2) shareUrl(url);
                else if (w == 3) startActivity(new Intent(this, DownloadActivity.class));
            } else {
                if (w == 0) triggerDownload(url);
                else if (w == 1) shareUrl(url);
                else if (w == 2) startActivity(new Intent(this, DownloadActivity.class));
            }
        }).show();
    }

    public void triggerDownload(String url) {
        String clean = url.replace(Constant.YOUTUBE_MOBILE_HOST, YOUTUBE_WWW_HOST);
        Toast.makeText(this, "Fetching details...", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(() -> new DownloadDialog(clean, this, youtubeExtractor).show(), 600);
    }

    private void triggerPlaylistDownload(String url) {
        String clean = url.replace(Constant.YOUTUBE_MOBILE_HOST, YOUTUBE_WWW_HOST);
        Toast.makeText(this, "Fetching playlist...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<StreamInfoItem> items = new ArrayList<>();
            try {
                PlaylistExtractor ex = NewPipe.getService(0).getPlaylistExtractor(clean);
                ex.fetchPage();
                InfoItemsPage<StreamInfoItem> p = ex.getInitialPage();
                while (p != null) {
                    items.addAll(p.getItems());
                    if (!Page.isValid(p.getNextPage())) break;
                    p = ex.getPage(p.getNextPage());
                }
                mainHandler.post(() -> showPlaylistSelectionDialog(items));
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "Failed to load playlist", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showPlaylistSelectionDialog(List<StreamInfoItem> items) {
        String[] titles = new String[items.size()];
        boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            titles[i] = items.get(i).getName();
            checked[i] = true;
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(60, 30, 60, 0);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText("Select Videos");
        tv.setTextSize(16);
        tv.setTextColor(Color.WHITE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));

        CheckBox allCb = new CheckBox(this);
        allCb.setChecked(true);
        allCb.setText("All");
        allCb.setTextColor(Color.WHITE);
        header.addView(tv);
        header.addView(allCb);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setCustomTitle(header)
                .setMultiChoiceItems(titles, checked, (d, i, b) -> checked[i] = b)
                .setPositiveButton("Download", null)
                .setNegativeButton("Cancel", null)
                .create();

        allCb.setOnCheckedChangeListener((v, is) -> {
            for (int i = 0; i < checked.length; i++) {
                checked[i] = is;
                dialog.getListView().setItemChecked(i, is);
            }
        });

        dialog.setOnShowListener(d -> {
            Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            styleButton(pos, true);
            styleButton(neg, false);

            pos.setOnClickListener(v -> {
                List<PlaylistDownloadItem> selected = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    if (checked[i]) selected.add(new PlaylistDownloadItem(items.get(i).getUrl(), items.get(i).getName()));
                }
                if (!selected.isEmpty()) {
                    processBulkDownload(selected);
                    dialog.dismiss();
                }
            });
            neg.setOnClickListener(v -> dialog.dismiss());
        });
        dialog.show();
    }

    private void styleButton(Button b, boolean primary) {
        b.setAllCaps(false);
        b.setTextSize(15);
        if (primary) {
            b.setBackgroundColor(Color.WHITE);
            b.setTextColor(Color.BLACK);
        } else {
            b.setTextColor(Color.WHITE);
        }
    }

    private void processBulkDownload(List<PlaylistDownloadItem> items) {
        Toast.makeText(this, "Enqueuing " + items.size() + " items...", Toast.LENGTH_LONG).show();
        String lastRes = kv.decodeString("last_download_res", "720p");
        int lastBitrate = kv.decodeInt("last_download_audio_bitrate", -1);
        int threads = kv.decodeInt("download_thread_count", 4);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (PlaylistDownloadItem item : items) {
            pool.execute(() -> {
                try {
                    StreamDetails si = youtubeExtractor.getStreamInfo(item.url);
                    VideoStream vs = si.getVideoStreams().stream()
                            .filter(s -> s.getFormat() == MediaFormat.MPEG_4 && s.getResolution().equals(lastRes))
                            .findFirst().orElse(si.getVideoStreams().get(0));
                    AudioStream as = si.getAudioStreams().stream()
                            .filter(s -> s.getFormat() == MediaFormat.M4A && (lastBitrate == -1 || s.getAverageBitrate() == lastBitrate))
                            .findFirst().orElse(si.getAudioStreams().get(0));

                    File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), getString(R.string.app_name));
                    if (!dir.exists()) dir.mkdirs();

                    String sanitized = item.title.replaceAll("[<>:\"/|?*]", "_");
                    Task t = new Task(YoutubeExtractor.getVideoId(item.url) + ":v", vs, as, null, null, sanitized, dir, threads);

                    mainHandler.post(() -> { if (downloadService != null) downloadService.download(List.of(t)); });
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
        }
        pool.shutdown();
    }

    private record PlaylistDownloadItem(String url, String title) {
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
                final YoutubeWebview web = getWebview();
                if (web != null && tabManager != null) {
                    tabManager.evaluateJavascript("window.dispatchEvent(new Event('onGoBack'));", null);
                    if (web.fullscreen != null && web.fullscreen.getVisibility() == View.VISIBLE) {
                        tabManager.evaluateJavascript("document.exitFullscreen()", null); return;
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
        }
    }

    @Nullable private YoutubeWebview getWebview() { return tabManager != null ? tabManager.getWebview() : null; }

    private void startPlaybackService() {}
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (playbackConnection != null) unbindService(playbackConnection);
        if (downloadConnection != null) unbindService(downloadConnection);
        if (player != null) player.release();
    }
}