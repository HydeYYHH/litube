package com.hhst.youtubelite;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;


import com.hhst.youtubelite.downloader.DownloadService;
import com.hhst.youtubelite.webview.YoutubeWebview;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public YoutubeWebview webview;
    public SwipeRefreshLayout swipeRefreshLayout;
    public ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        swipeRefreshLayout.setOnRefreshListener(
                () -> webview.evaluateJavascript(
                        "window.dispatchEvent(new Event('onRefresh'));",
                        value -> {}
                )
        );
        swipeRefreshLayout.setProgressViewOffset(true, 80,180);

        Executors.newSingleThreadExecutor().execute(() -> {
            loadScript();
            runOnUiThread(() -> {
                webview.build();
                webview.loadUrl(getString(R.string.base_url));
            });
        });

        requestPermissions();
        startDownloadService();
        initializeDownloader();

    }

    private static final int REQUEST_NOTIFICATION_CODE = 100;
    private static final int REQUEST_STORAGE_CODE = 101;

    public void requestPermissions() {

        // check and require post-notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_CODE
                );
            }
        }

        // check storage permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_CODE
                );
            }
        }
    }

    private void loadScript(){
        AssetManager assetManager = getAssets();

        List<String> res_pths = Arrays.asList("css", "js");
        try{
            for (String dir_path : res_pths) {
                ArrayList<String> resources = new ArrayList<>(
                        Arrays.asList(Objects.requireNonNull(assetManager.list(dir_path)))
                );
                String init_res = resources.contains("init.js") ? "init.js" :
                        resources.contains("init.min.js") ? "init.min.js" : null;
                if (init_res != null){
                    webview.injectJavaScript(
                            assetManager.open(dir_path + File.separator + init_res));
                    resources.remove(init_res);
                }
                for (String res : resources) {
                    InputStream stream = assetManager.open(dir_path + File.separator
                            + res);
                    if (res.endsWith(".js")) {
                        webview.injectJavaScript(stream);
                    } else if (res.endsWith(".css")) {
                        webview.injectCSS(stream);
                    }
                }
            }
        }catch (IOException e){
            Log.e("IOException", e.toString());
        }

    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent KeyEvent) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            webview.evaluateJavascript(
                    "window.dispatchEvent(new Event('onGoBack'));",
                    value -> {}
            );
            if (webview.fullscreen != null && webview.fullscreen.getVisibility() == View.VISIBLE){
                webview.evaluateJavascript(
                        "document.exitFullscreen()",
                        value -> {}
                );
                return true;
            }
            if (webview.canGoBack()){
                webview.goBack();
            } else {
                finish();
            }
            return true;
        }
        return false;
    }


    public DownloadService downloadService;

    private void startDownloadService() {
        // bind the download service
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                downloadService = ((DownloadService.DownloadBinder) iBinder).getService();
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
            }
        };

        Intent intent = new Intent(this, DownloadService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void initializeDownloader() {
        Executors.newSingleThreadExecutor().execute(() -> {
            // Initialize the downloader
            try {
                YoutubeDL.getInstance().init(this);
                FFmpeg.getInstance().init(this);
            } catch (YoutubeDLException e) {
                Toast.makeText(this, getString(R.string.downloader_initialize_error), Toast.LENGTH_SHORT)
                        .show();
            }
            // try to update yt-dlp
            try {
                YoutubeDL.getInstance().updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
            } catch (YoutubeDLException e) {
                Log.e("unable to update yt-dlp", Log.getStackTraceString(e));
            }

        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}