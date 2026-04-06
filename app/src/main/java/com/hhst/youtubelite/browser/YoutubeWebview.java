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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ConsoleMessage;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.queue.QueueRepository;
import com.hhst.youtubelite.player.queue.QueueWarmer;
import com.hhst.youtubelite.ui.MainActivity;
import com.hhst.youtubelite.util.StreamIOUtils;
import com.hhst.youtubelite.util.UrlUtils;
import com.hhst.youtubelite.util.ViewUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;

import okhttp3.OkHttpClient;
import okhttp3.Response;

@UnstableApi
public class YoutubeWebview extends WebView {

	private final ArrayList<String> scripts = new ArrayList<>();
	@Nullable public View fullscreen = null;
	@Nullable private OkHttpWebViewInterceptor okHttpWebViewInterceptor;
	@Nullable private Consumer<String> updateVisitedHistory;
	@Nullable private Consumer<String> onPageFinishedListener;
	private YoutubeExtractor youtubeExtractor;
	private LitePlayer player;
	private ExtensionManager extensionManager;
	private TabManager tabManager;
	private PoTokenProviderImpl poTokenProvider;
	private QueueRepository queueRepository;
	private QueueWarmer queueWarmer;
	private boolean isDestroyed = false;

	public YoutubeWebview(@NonNull final Context context) { this(context, null); }
	public YoutubeWebview(@NonNull final Context context, @Nullable final AttributeSet attrs) { this(context, attrs, 0); }
	public YoutubeWebview(@NonNull final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) { super(context, attrs, defStyleAttr); }

	public void setOkHttpClient(@NonNull final OkHttpClient okHttpClient) {
		okHttpWebViewInterceptor = new OkHttpWebViewInterceptor(okHttpClient);
	}

	public void setUpdateVisitedHistory(@Nullable final Consumer<String> updateVisitedHistory) {
		this.updateVisitedHistory = updateVisitedHistory;
	}

	public void setOnPageFinishedListener(@Nullable final Consumer<String> onPageFinishedListener) {
		this.onPageFinishedListener = onPageFinishedListener;
	}

	public void setYoutubeExtractor(@NonNull final YoutubeExtractor youtubeExtractor) {
		this.youtubeExtractor = youtubeExtractor;
	}

	public void setPlayer(@NonNull final LitePlayer player) {
		this.player = player;
	}

	public void setExtensionManager(@NonNull final ExtensionManager extensionManager) {
		this.extensionManager = extensionManager;
	}

	public void setTabManager(@NonNull final TabManager tabManager) {
		this.tabManager = tabManager;
	}

	public void setPoTokenProvider(@NonNull final PoTokenProviderImpl poTokenProvider) {
		this.poTokenProvider = poTokenProvider;
	}

	public void setQueueRepository(@NonNull final QueueRepository queueRepository) {
		this.queueRepository = queueRepository;
	}

	public void setQueueWarmer(@NonNull final QueueWarmer queueWarmer) {
		this.queueWarmer = queueWarmer;
	}

	@Override
	public void loadUrl(@NonNull final String url) {
		if (isDestroyed) return;
		final String resolvedUrl = sanitizeLoadUrl(url);
		if (canLoad(resolvedUrl)) {
			super.loadUrl(resolvedUrl);
			return;
		}
		if (canOpenExternal(resolvedUrl)) {
			openExternal(Uri.parse(resolvedUrl));
			return;
		}
		Log.w("YoutubeWebview", "Blocked unauthorized URL: " + resolvedUrl);
	}

	static boolean canLoad(@NonNull final String url) {
		if (UrlUtils.isAllowedUrl(url)) return true;
		final String scheme = scheme(url);
		return isScheme(scheme, "file")
				|| isScheme(scheme, "about")
				|| isScheme(scheme, "data")
				|| isScheme(scheme, "javascript");
	}

	static boolean canOpenExternal(@NonNull final String url) {
		if (UrlUtils.isAllowedUrl(url)) return false;
		final String scheme = scheme(url);
		return isScheme(scheme, "http") || isScheme(scheme, "https");
	}

	@Nullable
	private static String scheme(@NonNull final String url) {
		try {
			return URI.create(url).getScheme();
		} catch (final IllegalArgumentException ignored) {
			return null;
		}
	}

	private static boolean isScheme(@Nullable final String actual, @NonNull final String expected) {
		return expected.equalsIgnoreCase(actual);
	}

	private void openExternal(@NonNull final Uri uri) {
		try {
			getContext().startActivity(new Intent(Intent.ACTION_VIEW, uri));
		} catch (final ActivityNotFoundException e) {
			post(() -> Toast.makeText(getContext(), R.string.application_not_found, Toast.LENGTH_SHORT).show());
		}
	}

	@NonNull
	String sanitizeLoadUrl(@NonNull final String url) {
		return sanitizeLoadUrl(url, queueRepository != null && queueRepository.isEnabled());
	}

	@NonNull
	static String sanitizeLoadUrl(@NonNull final String url, final boolean queueEnabled) {
		if (!queueEnabled || !com.hhst.youtubelite.Constant.PAGE_WATCH.equals(UrlUtils.getPageClass(url))) {
			return url;
		}
		try {
			final URI uri = URI.create(url);
			final String rawQuery = uri.getRawQuery();
			if (rawQuery == null || rawQuery.isEmpty()) return url;
			boolean removed = false;
			final StringBuilder filteredQuery = new StringBuilder();
			for (final String part : rawQuery.split("&")) {
				if (part.isEmpty()) continue;
				final int separatorIndex = part.indexOf('=');
				final String key = separatorIndex >= 0 ? part.substring(0, separatorIndex) : part;
				if ("list".equalsIgnoreCase(key)) {
					removed = true;
					continue;
				}
				if (filteredQuery.length() > 0) filteredQuery.append('&');
				filteredQuery.append(part);
			}
			if (!removed) return url;
			return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getRawPath(),
					filteredQuery.length() > 0 ? filteredQuery.toString() : null, uri.getRawFragment()).toString();
		} catch (final Exception ignored) { return url; }
	}

	@SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
	public void init() {
		if (isDestroyed) return;
		setFocusable(true);
		setFocusableInTouchMode(true);
		setLayerType(View.LAYER_TYPE_HARDWARE, null);

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
		settings.setUserAgentString(com.hhst.youtubelite.Constant.USER_AGENT);

		final JavascriptInterface jsInterface = new JavascriptInterface(this, youtubeExtractor, player, extensionManager, tabManager, poTokenProvider, queueRepository, queueWarmer);
		addJavascriptInterface(jsInterface, "android");
		addJavascriptInterface(jsInterface, "lite");
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

				if (UrlUtils.isAllowedDomain(uri)) return false;
				openExternal(uri);
				return true;
			}

			@Nullable
			@Override
			public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
				if (isDestroyed) return null;
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
				doInjectJavaScript();
				evaluateJavascript("window.dispatchEvent(new Event('onPageStarted'));", null);
			}

			@Override
			public void onPageFinished(@NonNull final WebView view, @NonNull final String url) {
				if (isDestroyed) return;
				super.onPageFinished(view, url);
				doInjectJavaScript();
				evaluateJavascript("window.dispatchEvent(new Event('onPageFinished'));", null);
				evaluateJavascript("window.dispatchEvent(new Event('onProgressChangeFinish'));", null);
				if (onPageFinishedListener != null) onPageFinishedListener.accept(url);
				postDelayed(() -> {
					if (getParent() instanceof SwipeRefreshLayout) {
						((SwipeRefreshLayout) getParent()).setRefreshing(false);
					}
				}, 500);
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
				if (isDestroyed) return;
				if (progress >= 100) {
					evaluateJavascript("window.dispatchEvent(new Event('onProgressChangeFinish'));", null);
				}
				super.onProgressChanged(view, progress);
			}

			@Override
			public Bitmap getDefaultVideoPoster() {
				return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
			}

			@Override
			public void onShowCustomView(@NonNull final View view, @NonNull final CustomViewCallback callback) {
				if (isDestroyed) return;
				setVisibility(View.GONE);
				if (getContext() instanceof MainActivity mainActivity) {
					ViewGroup decorView = (ViewGroup) mainActivity.getWindow().getDecorView();
					if (fullscreen != null) decorView.removeView(fullscreen);
					fullscreen = new FrameLayout(getContext());
					((FrameLayout) fullscreen).addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
					ViewUtils.setFullscreen(fullscreen, true);
					decorView.addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
					fullscreen.setVisibility(View.VISIBLE);
					fullscreen.setKeepScreenOn(true);
					evaluateJavascript("window.dispatchEvent(new Event('onFullScreen'));", null);
				}
			}

			@Override
			public void onHideCustomView() {
				if (isDestroyed || fullscreen == null) return;
				ViewUtils.setFullscreen(fullscreen, false);
				fullscreen.setVisibility(View.GONE);
				fullscreen.setKeepScreenOn(false);
				setVisibility(View.VISIBLE);
				evaluateJavascript("window.dispatchEvent(new Event('exitFullScreen'));", null);
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
			String js = String.format("""
							(function(){
							let style = document.createElement('style');
							style.type = 'text/css';
							style.textContent = window.atob('%s');
							let target = document.head || document.documentElement;
							if (target) target.appendChild(style);
							})()
							""", encoded);
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
		removeJavascriptInterface("lite");
		super.destroy();
	}
}
