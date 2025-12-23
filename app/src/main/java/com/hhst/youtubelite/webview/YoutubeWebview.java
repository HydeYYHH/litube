package com.hhst.youtubelite.webview;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.MainActivity;
import com.hhst.youtubelite.R;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import lombok.Setter;

@OptIn(markerClass = UnstableApi.class)
public class YoutubeWebview extends WebView {

	private static final List<String> ALLOWED_DOMAINS = Arrays.asList("youtube.com", "youtube.googleapis.com", "googlevideo.com", "ytimg.com", "accounts.google", "googleusercontent.com", "apis.google.com");
	private final ArrayList<String> scripts = new ArrayList<>();
	@Nullable
	public View fullscreen = null;
	@Setter
	@Nullable
	private Consumer<String> updateVisitedHistory;
	@Setter
	@Nullable
	private Consumer<String> onPageFinishedListener;

	public YoutubeWebview(@NonNull final Context context) {
		super(context);
	}

	public YoutubeWebview(@NonNull final Context context, @Nullable final AttributeSet attrs) {
		super(context, attrs);
	}

	public YoutubeWebview(@NonNull final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@SuppressLint("SetJavaScriptEnabled")
	public void build() {
		setLayerType(LAYER_TYPE_HARDWARE, null);

		CookieManager.getInstance().setAcceptCookie(true);

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

		final JavascriptInterface jsInterface = new JavascriptInterface(this);
		addJavascriptInterface(jsInterface, "android");
		setTag(jsInterface);

		setWebViewClient(new WebViewClient() {

			@Override
			public boolean shouldOverrideUrlLoading(@NonNull final WebView view, @NonNull final WebResourceRequest request) {
				if (Objects.equals(request.getUrl().getScheme(), "intent")) {
					// open in other app
					try {
						final Intent intent = Intent.parseUri(request.getUrl().toString(), Intent.URI_INTENT_SCHEME);
						getContext().startActivity(intent);
					} catch (final ActivityNotFoundException | URISyntaxException e) {
						post(() -> Toast.makeText(getContext(), R.string.application_not_found, Toast.LENGTH_SHORT).show());
						Log.e(getContext().getString(R.string.application_not_found), e.toString());
					}
				} else {
					// restrict domain
					if (isAllowedDomain(request.getUrl())) return false;
					// open in browser
					getContext().startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
				}
				return true;
			}

			public boolean isAllowedDomain(@Nullable final Uri uri) {
				if (uri == null) return false;
				final String host = uri.getHost();
				if (host == null) return false;
				for (final String domain : ALLOWED_DOMAINS)
					if (host.endsWith(domain) || host.startsWith(domain)) return true;
				return false;
			}

			@Override
			public void doUpdateVisitedHistory(@NonNull final WebView view, @NonNull final String url, final boolean isReload) {
				super.doUpdateVisitedHistory(view, url, isReload);
				evaluateJavascript("window.dispatchEvent(new Event('doUpdateVisitedHistory'));", null);
				if (updateVisitedHistory != null) updateVisitedHistory.accept(url);
			}

			@Override
			public void onPageStarted(@NonNull final WebView view, @NonNull final String url, @Nullable final Bitmap favicon) {
				super.onPageStarted(view, url, favicon);
				evaluateJavascript("window.dispatchEvent(new Event('onPageStarted'));", null);
				doInjectJavaScript();
			}

			@Override
			public void onPageFinished(@NonNull final WebView view, @NonNull final String url) {
				super.onPageFinished(view, url);
				evaluateJavascript("window.dispatchEvent(new Event('onPageFinished'));", null);
				doInjectJavaScript();
				if (onPageFinishedListener != null) onPageFinishedListener.accept(url);
			}

			@Override
			public void onReceivedError(@NonNull final WebView view, @NonNull final WebResourceRequest request, @NonNull final WebResourceError error) {
				final int errorCode = error.getErrorCode();
				final String failingUrl = request.getUrl().toString();
				final String description = error.getDescription().toString();
				if (description.contains("TIMED_OUT") || description.contains("CONNECTION_ABORTED") || description.contains("CONNECTION_CLOSED")) {
					final String encodedDescription = URLEncoder.encode(description, StandardCharsets.UTF_8);
					final String encodedUrl = URLEncoder.encode(failingUrl, StandardCharsets.UTF_8);
					final String url = "file:///android_asset/page/error.html?description=" + encodedDescription + "&errorCode=" + errorCode + "&url=" + encodedUrl;
					view.loadUrl(url);
				}
			}

		});

		setWebChromeClient(new WebChromeClient() {

			@Override
			public boolean onConsoleMessage(@NonNull final ConsoleMessage consoleMessage) {
				Log.d("js-log", consoleMessage.message() + " -- From line " + consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
				return super.onConsoleMessage(consoleMessage);
			}

			@Override
			public void onProgressChanged(@NonNull final WebView view, final int progress) {
				final ProgressBar progressBar = findViewById(R.id.progressBar);
				if (progress >= 100) {
					progressBar.setVisibility(GONE);
					progressBar.setProgress(0);
					evaluateJavascript("window.dispatchEvent(new Event('onProgressChangeFinish'));", null);
				} else {
					progressBar.setVisibility(VISIBLE);
					progressBar.setProgress(progress, true);
				}
				super.onProgressChanged(view, progress);
			}

			// replace video default poster
			@Override
			public Bitmap getDefaultVideoPoster() {
				return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			}

			@Override
			public void onShowCustomView(@NonNull final View view, @NonNull final CustomViewCallback callback) {
				setVisibility(View.GONE);

				final MainActivity mainActivity = (MainActivity) getContext();
				if (fullscreen != null)
					((FrameLayout) mainActivity.getWindow().getDecorView()).removeView(fullscreen);

				fullscreen = new FrameLayout(getContext());
				((FrameLayout) fullscreen).addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
				fullscreen.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

				((FrameLayout) mainActivity.getWindow().getDecorView()).addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
				fullscreen.setVisibility(View.VISIBLE);
				// keep screen going on
				fullscreen.setKeepScreenOn(true);
				mainActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
				evaluateJavascript("window.dispatchEvent(new Event('onFullScreen'));", null);
			}

			@Override
			public void onHideCustomView() {
				if (fullscreen == null) return;
				fullscreen.setVisibility(View.GONE);
				fullscreen.setKeepScreenOn(false);
				setVisibility(View.VISIBLE);
				final MainActivity mainActivity = (MainActivity) getContext();
				mainActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
				evaluateJavascript("window.dispatchEvent(new Event('exitFullScreen'));", null);
			}
		});
	}

	private void doInjectJavaScript() {
		for (final String js : scripts) evaluateJavascript(js, null);
	}

	public void injectJavaScript(@NonNull final InputStream jsInputStream) {
		final String js = readInputStream(jsInputStream);
		if (js != null) scripts.add(js);
	}

	public void injectCss(@NonNull final InputStream cssInputStream) {
		final String css = readInputStream(cssInputStream);
		if (css != null) {
			final String encodedCss = Base64.getEncoder().encodeToString(css.getBytes());
			final String js = String.format("""
							(function(){
							let style = document.createElement('style');
							style.type = 'text/css';
							style.textContent = window.atob('%s');
							document.head.appendChild(style);
							})()
							""", encodedCss);
			scripts.add(js);
		}
	}

	@Nullable
	private String readInputStream(@NonNull final InputStream inputStream) {
		try {
			return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
		} catch (final IOException e) {
			Log.e("InputStreamError", "Error reading input stream", e);
			return null;
		}
	}

}
