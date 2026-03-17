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
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.ui.MainActivity;
import com.hhst.youtubelite.ui.widget.LoadingProgressBar;
import com.hhst.youtubelite.util.StreamIOUtils;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.ViewUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;

import lombok.Setter;
import okhttp3.OkHttpClient;
import okhttp3.Response;

@UnstableApi
public class YoutubeWebview extends WebView {

	private final ArrayList<String> scripts = new ArrayList<>();
	@Nullable
	public View fullscreen = null;
	@Nullable
	private OkHttpWebViewInterceptor okHttpWebViewInterceptor;
	@Setter
	@Nullable
	private Consumer<String> updateVisitedHistory;
	@Setter
	@Nullable
	private Consumer<String> onPageFinishedListener;
	@Setter
	private YoutubeExtractor youtubeExtractor;
	@Setter
	private LitePlayer player;
	@Setter
	private ExtensionManager extensionManager;
	@Setter
	private TabManager tabManager;
	@Nullable
	private LoadingProgressBar progressBar;

	public YoutubeWebview(@NonNull final Context context) {
		this(context, null);
	}

	public YoutubeWebview(@NonNull final Context context, @Nullable final AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public YoutubeWebview(@NonNull final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	public void setOkHttpClient(@NonNull final OkHttpClient okHttpClient) {
		okHttpWebViewInterceptor = new OkHttpWebViewInterceptor(okHttpClient);
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();
		progressBar = findViewById(R.id.progressBar);
	}

	@Override
	public void loadUrl(@NonNull final String url) {
		if (UrlUtils.isAllowedDomain(Uri.parse(url))) {
			super.loadUrl(url);
		} else {
			final String currentUrl = getUrl();
			if (currentUrl != null && UrlUtils.isAllowedDomain(Uri.parse(currentUrl))) {
				super.loadUrl(url);
			} else {
				Log.w("YoutubeWebview", "Blocked attempt to load unauthorized URL: " + url);
			}
		}
	}

	@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
	public void init() {
		setFocusable(true);
		setFocusableInTouchMode(true);
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
		settings.setMediaPlaybackRequiresUserGesture(false);
		settings.setUserAgentString("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

		final JavascriptInterface jsInterface = new JavascriptInterface(this, youtubeExtractor, player, extensionManager, tabManager);
		addJavascriptInterface(jsInterface, "android");
		setTag(jsInterface);

		setWebViewClient(new WebViewClient() {

			@Override
			public boolean shouldOverrideUrlLoading(@NonNull final WebView view, @NonNull final WebResourceRequest request) {
				final Uri uri = request.getUrl();
				if (Objects.equals(uri.getScheme(), "intent")) {
					// open in other app
					try {
						final Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
						getContext().startActivity(intent);
					} catch (final ActivityNotFoundException | URISyntaxException e) {
						post(() -> Toast.makeText(getContext(), R.string.application_not_found, Toast.LENGTH_SHORT).show());
						Log.e(getContext().getString(R.string.application_not_found), e.toString());
					}
				} else {
					// restrict domain
					if (UrlUtils.isAllowedDomain(uri)) return false;
					// open in browser
					getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri));
				}
				return true;
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
				if (progressBar != null) progressBar.beginLoading();
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
				if (request.isForMainFrame()) {
					final int errorCode = error.getErrorCode();
					final String failingUrl = request.getUrl().toString();
					final String description = error.getDescription().toString();

					final String encodedDescription = URLEncoder.encode(description, StandardCharsets.UTF_8);
					final String encodedUrl = URLEncoder.encode(failingUrl, StandardCharsets.UTF_8);
					final String url = "file:///android_asset/page/error.html?description=" + encodedDescription + "&errorCode=" + errorCode + "&url=" + encodedUrl;
					post(() -> view.loadUrl(url));
				}
			}

			@Override
			public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
				if (request.isForMainFrame()) {
					final int statusCode = errorResponse.getStatusCode();
					final String failingUrl = request.getUrl().toString();
					final String reason = errorResponse.getReasonPhrase();

					final String encodedDescription = URLEncoder.encode("HTTP Error " + statusCode + ": " + reason, StandardCharsets.UTF_8);
					final String encodedUrl = URLEncoder.encode(failingUrl, StandardCharsets.UTF_8);
					final String url = "file:///android_asset/page/error.html?description=" + encodedDescription + "&errorCode=" + statusCode + "&url=" + encodedUrl;
					post(() -> view.loadUrl(url));
				}
			}

			@Nullable
			@Override
			public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
				final Uri uri = request.getUrl();
				final String path = uri.getPath();
				if (path != null && path.equals("/live_chat") && okHttpWebViewInterceptor != null && okHttpWebViewInterceptor.canExecute(request)) {
					final String url = uri.toString();
					Response response = null;
					try {
						response = okHttpWebViewInterceptor.execute(request);
						if (response == null) return super.shouldInterceptRequest(view, request);

						final InputStream sourceStream = response.body().byteStream();
						final String injectedScript = "<script>(function(){ " +
										"document.addEventListener('tap', (e) => { " +
										"const msg = e.target.closest('yt-live-chat-text-message-renderer'); " +
										"if (!msg) return; " +
										"e.preventDefault(); " +
										"e.stopImmediatePropagation(); " +
										"}, true); " +
										"})();</script>";
						final InputStream injectedStream = new ByteArrayInputStream(injectedScript.getBytes(StandardCharsets.UTF_8));
						final Enumeration<InputStream> streams = Collections.enumeration(Arrays.asList(injectedStream, sourceStream));
						final SequenceInputStream sequenceInputStream = new SequenceInputStream(streams);
						return okHttpWebViewInterceptor.toWebResourceResponse(url, response, sequenceInputStream);
					} catch (final Exception ignored) {
						if (response != null) response.close();
					}
				}
				if (okHttpWebViewInterceptor != null) {
					final WebResourceResponse response = okHttpWebViewInterceptor.intercept(request);
					if (response != null) return response;
				}
				return super.shouldInterceptRequest(view, request);
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
				if (progressBar == null) {
					super.onProgressChanged(view, progress);
					return;
				}
				if (progress >= 100) {
					progressBar.finishLoading();
					evaluateJavascript("window.dispatchEvent(new Event('onProgressChangeFinish'));", null);
				} else {
					progressBar.setLoadingProgress(progress);
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

				if (getContext() instanceof MainActivity mainActivity) {
					if (fullscreen != null)
						((FrameLayout) mainActivity.getWindow().getDecorView()).removeView(fullscreen);

					fullscreen = new FrameLayout(getContext());
					((FrameLayout) fullscreen).addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
					ViewUtils.setFullscreen(fullscreen, true);

					((FrameLayout) mainActivity.getWindow().getDecorView()).addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
					fullscreen.setVisibility(View.VISIBLE);
					// keep screen going on
					fullscreen.setKeepScreenOn(true);
					evaluateJavascript("window.dispatchEvent(new Event('onFullScreen'));", null);
				}
			}

			@Override
			public void onHideCustomView() {
				if (fullscreen == null) return;
				ViewUtils.setFullscreen(fullscreen, false);
				fullscreen.setVisibility(View.GONE);
				fullscreen.setKeepScreenOn(false);
				setVisibility(View.VISIBLE);
				if (getContext() instanceof MainActivity) {
					evaluateJavascript("window.dispatchEvent(new Event('exitFullScreen'));", null);
				}
			}
		});
	}

	private void doInjectJavaScript() {
		for (final String js : scripts) evaluateJavascript(js, null);
	}

	public void injectJavaScript(@NonNull final InputStream jsInputStream) {
		final String js = StreamIOUtils.readInputStream(jsInputStream);
		if (js != null) post(() -> scripts.add(js));
	}

	public void injectCss(@NonNull final InputStream cssInputStream) {
		final String css = StreamIOUtils.readInputStream(cssInputStream);
		if (css != null) {
			final String encodedCss = Base64.getEncoder().encodeToString(css.getBytes());
			final String js = String.format("""
							(function(){
							let style = document.createElement('style');
							style.type = 'text/css';
							style.textContent = window.atob('%s');
							let target = document.head || document.documentElement;
							if (target) target.appendChild(style);
							})()
							""", encodedCss);
			post(() -> scripts.add(js));
		}
	}

}

