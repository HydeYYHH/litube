package com.hhst.youtubelite.browser;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.PoTokenProviderImpl;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.ui.MainActivity;
import com.hhst.youtubelite.util.StreamIOUtils;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.ViewUtils;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Objects;
import lombok.Setter;

@UnstableApi
public class YoutubeWebview extends WebView {

    private final ArrayList<String> scripts = new ArrayList<>();
    @Nullable public FrameLayout fullscreen = null;
    @Setter @Nullable private Consumer<String> updateVisitedHistory;
    @Setter @Nullable private Consumer<String> onPageFinishedListener;
    @Setter private YoutubeExtractor youtubeExtractor;
    @Setter private LitePlayer player;
    @Setter private ExtensionManager extensionManager;
    @Setter private TabManager tabManager;
    @Setter private PoTokenProviderImpl poTokenProvider;
    private boolean isDestroyed = false;

    public YoutubeWebview(@NonNull final Context context) { this(context, null); }
    public YoutubeWebview(@NonNull final Context context, @Nullable final AttributeSet attrs) { this(context, attrs, 0); }
    public YoutubeWebview(@NonNull final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override
    public void loadUrl(@NonNull final String url) {
        if (isDestroyed) return;
        try {
            if (UrlUtils.isAllowedDomain(Uri.parse(url))) {
                super.loadUrl(url);
            } else {
                final String currentUrl = getUrl();
                if (currentUrl != null && UrlUtils.isAllowedDomain(Uri.parse(currentUrl))) {
                    super.loadUrl(url);
                }
            }
        } catch (Exception e) {
            Log.e("YoutubeWebview", "Error loading url", e);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    public void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        setLayerType(LAYER_TYPE_HARDWARE, null);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true);

        final WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");

        final JavascriptInterface jsInterface = new JavascriptInterface(this, youtubeExtractor, player, extensionManager, tabManager, poTokenProvider);
        addJavascriptInterface(jsInterface, "android");
        setTag(jsInterface);

        setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(@NonNull final WebView view, @NonNull final WebResourceRequest request) {
                if (isDestroyed) return true;
                final Uri uri = request.getUrl();
                final String host = uri.getHost();
                final String scheme = uri.getScheme();
                
                if (Objects.equals(scheme, "intent")) {
                    try {
                        final Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                        getContext().startActivity(intent);
                    } catch (final ActivityNotFoundException | URISyntaxException e) {
                        Log.e("WebView", e.toString());
                    }
                    return true;
                }

                if (Objects.equals(scheme, "vnd.youtube") || (host != null && host.equals("m.youtube.com") && uri.getPath() != null && uri.getPath().startsWith("/app"))) {
                    return true;
                }

                if (host != null && (host.contains("accounts.google.com") || host.contains("google.com/accounts") || host.contains("accounts.youtube.com"))) {
                    return false;
                }

                if (UrlUtils.isAllowedDomain(uri)) {
                    return false;
                }
                
                try {
                    getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Log.e("WebView", "Error starting activity", e);
                }
                return true;
            }

            @Override
            public void doUpdateVisitedHistory(@NonNull final WebView view, @NonNull final String url, final boolean isReload) {
                if (isDestroyed) return;
                super.doUpdateVisitedHistory(view, url, isReload);
                evaluateJavascript("window.dispatchEvent(new Event('doUpdateVisitedHistory'));", null);
                if (updateVisitedHistory != null) updateVisitedHistory.accept(url);
            }

            @Override
            public void onPageStarted(@NonNull final WebView view, @NonNull final String url, @Nullable final Bitmap favicon) {
                if (isDestroyed) return;
                super.onPageStarted(view, url, favicon);
                evaluateJavascript("window.dispatchEvent(new Event('onPageStarted'));", null);
                doInjectJavaScript();
            }

            @Override
            public void onPageFinished(@NonNull final WebView view, @NonNull final String url) {
                if (isDestroyed) return;
                super.onPageFinished(view, url);
                evaluateJavascript("window.dispatchEvent(new Event('onPageFinished'));", null);
                doInjectJavaScript();
                if (onPageFinishedListener != null) onPageFinishedListener.accept(url);
            }
        });

        setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(@NonNull final WebView view, final int progress) {
                if (isDestroyed) return;
                final ProgressBar progressBar = findViewById(R.id.progressBar);
                if (progressBar != null) {
                    if (progress >= 100) {
                        progressBar.setVisibility(GONE);
                    } else {
                        progressBar.setVisibility(VISIBLE);
                        progressBar.setProgress(progress, true);
                    }
                }
                super.onProgressChanged(view, progress);
            }

            @Override
            public void onShowCustomView(@NonNull final View view, @NonNull final CustomViewCallback callback) {
                if (isDestroyed) return;
                setVisibility(View.GONE);
                if (getContext() instanceof MainActivity mainActivity) {
                    ViewGroup decorView = (ViewGroup) mainActivity.getWindow().getDecorView();
                    if (fullscreen != null) decorView.removeView(fullscreen);
                    fullscreen = new FrameLayout(getContext());
                    fullscreen.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    ViewUtils.setFullscreen(fullscreen, true);
                    decorView.addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
                    fullscreen.setVisibility(View.VISIBLE);
                    fullscreen.setKeepScreenOn(true);
                }
            }

            @Override
            public void onHideCustomView() {
                if (isDestroyed || fullscreen == null) return;
                ViewUtils.setFullscreen(fullscreen, false);
                fullscreen.setVisibility(View.GONE);
                fullscreen.setKeepScreenOn(false);
                setVisibility(View.VISIBLE);
            }
        });
    }

    private void doInjectJavaScript() {
        if (isDestroyed) return;
        for (final String js : scripts) evaluateJavascript(js, null);
    }

    public void injectJavaScript(@NonNull final InputStream is) {
        String js = StreamIOUtils.readInputStream(is);
        if (js != null) post(() -> {
            if (!isDestroyed) scripts.add(js);
        });
    }

    public void injectCss(@NonNull final InputStream is) {
        String css = StreamIOUtils.readInputStream(is);
        if (css != null) {
            String encoded = Base64.getEncoder().encodeToString(css.getBytes());
            String js = "(function(){let s=document.createElement('style');s.type='text/css';s.textContent=window.atob('" + encoded + "');document.head.appendChild(s);})()";
            post(() -> {
                if (!isDestroyed) scripts.add(js);
            });
        }
    }

    @Override
    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        if (isDestroyed) return;
        try {
            super.evaluateJavascript(script, resultCallback);
        } catch (Exception e) {
            Log.e("YoutubeWebview", "Error evaluating javascript", e);
        }
    }

    @Override
    public void destroy() {
        isDestroyed = true;
        setWebViewClient(null);
        setWebChromeClient(null);
        removeJavascriptInterface("android");
        super.destroy();
    }
}
