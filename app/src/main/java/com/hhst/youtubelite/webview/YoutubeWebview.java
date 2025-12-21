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

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.MainActivity;
import com.hhst.youtubelite.R;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

@OptIn(markerClass = UnstableApi.class)
public class YoutubeWebview extends WebView {

	public final List<String> allowed_domain = java.util.Arrays.asList("youtube.com", "youtube.googleapis.com", "googlevideo.com", "ytimg.com", "accounts.google", "googleusercontent.com", "apis.google.com");
	private final ArrayList<String> scripts = new ArrayList<>();
	public View fullscreen = null;

	public YoutubeWebview(Context context) {
		super(context);
	}

	public YoutubeWebview(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public YoutubeWebview(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@SuppressLint("SetJavaScriptEnabled")
	public void build() {
		setLayerType(LAYER_TYPE_HARDWARE, null);

		CookieManager.getInstance().setAcceptCookie(true);


		WebSettings settings = getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDatabaseEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setCacheMode(WebSettings.LOAD_DEFAULT);
		settings.setLoadWithOverviewMode(true);
		settings.setUseWideViewPort(true);
		settings.setLoadsImagesAutomatically(true);
		settings.setSupportZoom(false);
		settings.setBuiltInZoomControls(false);

		JavascriptInterface jsInterface = new JavascriptInterface(getContext());
		addJavascriptInterface(jsInterface, "android");
		setTag(jsInterface);

		setWebViewClient(new WebViewClient() {

			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				if (Objects.equals(request.getUrl().getScheme(), "intent")) {
					// open in other app
					try {
						Intent intent = Intent.parseUri(request.getUrl().toString(), Intent.URI_INTENT_SCHEME);
						getContext().startActivity(intent);
					} catch (ActivityNotFoundException | URISyntaxException e) {
						post(() -> Toast.makeText(getContext(), R.string.application_not_found, Toast.LENGTH_SHORT).show());
						Log.e(getContext().getString(R.string.application_not_found), e.toString());
					}
				} else {
					// restrict domain
					if (isAllowedDomain(request.getUrl())) {
						return false;
					}
					// open in browser
					getContext().startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
				}
				return true;
			}

			public boolean isAllowedDomain(Uri uri) {
				String host = uri.getHost();
				if (host == null) {
					return false;
				}
				for (String domain : allowed_domain) {
					if (host.endsWith(domain) || host.startsWith(domain)) {
						return true;
					}
				}
				return false;
			}

			@Override
			@OptIn(markerClass = UnstableApi.class)
			public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
				super.doUpdateVisitedHistory(view, url, isReload);
				evaluateJavascript("window.dispatchEvent(new Event('doUpdateVisitedHistory'));", null);
			}

			@Override
			public void onPageStarted(WebView view, String url, Bitmap favicon) {
				super.onPageStarted(view, url, favicon);
				evaluateJavascript("window.dispatchEvent(new Event('onPageStarted'));", null);
				doInjectJavaScript();
			}

			@Override
			public void onPageFinished(WebView view, String url) {
				super.onPageFinished(view, url);
				evaluateJavascript("window.dispatchEvent(new Event('onPageFinished'));", null);
				doInjectJavaScript();
			}

			@Override
			public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
				int errorCode = error.getErrorCode();
				String failingUrl = request.getUrl().toString();
				String description = error.getDescription().toString();
				if (description.contains("Webpage not available")) {
					String encodedDescription = java.net.URLEncoder.encode(description, StandardCharsets.UTF_8);
					String encodedUrl = java.net.URLEncoder.encode(failingUrl, StandardCharsets.UTF_8);
					String url = "file:///android_asset/page/error.html?description=" + encodedDescription + "&errorCode=" + errorCode + "&url=" + encodedUrl;
					view.loadUrl(url);
				}
			}

		});

		setWebChromeClient(new WebChromeClient() {

			@Override
			public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
				Log.d("js-log", consoleMessage.message() + " -- From line " + consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
				return super.onConsoleMessage(consoleMessage);
			}

			@Override
			public void onProgressChanged(WebView view, int progress) {
				ProgressBar progressBar = findViewById(R.id.progressBar);
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
			public void onShowCustomView(View view, CustomViewCallback callback) {
				setVisibility(View.GONE);

				MainActivity mainActivity = (MainActivity) getContext();
				if (fullscreen != null) {
					((FrameLayout) mainActivity.getWindow().getDecorView()).removeView(fullscreen);
				}

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
				if (fullscreen == null) {
					return;
				}
				fullscreen.setVisibility(View.GONE);
				fullscreen.setKeepScreenOn(false);
				setVisibility(View.VISIBLE);
				MainActivity mainActivity = (MainActivity) getContext();
				mainActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
				evaluateJavascript("window.dispatchEvent(new Event('exitFullScreen'));", null);
			}
		});
	}

	private void doInjectJavaScript() {
		for (String js : scripts) {
			evaluateJavascript(js, null);
		}
	}

	public void injectJavaScript(InputStream jsInputStream) {
		String js = readInputStream(jsInputStream);
		if (js != null) scripts.add(js);
	}

	public void injectCss(InputStream cssInputStream) {
		String css = readInputStream(cssInputStream);
		if (css != null) {
			String encodedCss = Base64.getEncoder().encodeToString(css.getBytes());
			String js = String.format("""
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

	private String readInputStream(InputStream inputStream) {
		try {
			return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
		} catch (IOException e) {
			Log.e("InputStreamError", "Error reading input stream", e);
			return null;
		}
	}

}
