package com.hhst.youtubelite.browser;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebBackForwardList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.util.UnstableApi;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.cache.WebViewCachePolicy;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.extractor.potoken.PoTokenContextStore;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.queue.QueueRepository;

import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import okhttp3.OkHttpClient;

/**
 * Fragment that hosts the YouTube WebView.
 */
@AndroidEntryPoint
@UnstableApi
public final class YoutubeFragment extends Fragment {

	private static final String ARG_URL = "url";
	private static final String ARG_TAG = "tag";

	@Inject
	YoutubeExtractor youtubeExtractor;
	@Inject
	LitePlayer player;
	@Inject
	ExtensionManager extensionManager;
	@Inject
	TabManager tabManager;
	@Inject
	QueueRepository queueRepository;
	@Inject
	OkHttpClient okHttpClient;
	@Inject
	WebViewCachePolicy webViewCachePolicy;
	@Inject
	PoTokenContextStore poTokenContextStore;

	@Nullable
	private String url;
	@Nullable
	private String tag;
	@Nullable
	private YoutubeWebview webView;
	@Nullable
	private WebBackForwardList historySnapshot;

	@NonNull
	public static YoutubeFragment newInstance(@NonNull String url, @NonNull String tag) {
		YoutubeFragment fragment = new YoutubeFragment();
		Bundle args = new Bundle();
		args.putString(ARG_URL, url);
		args.putString(ARG_TAG, tag);
		fragment.setArguments(args);
		fragment.url = url;
		fragment.tag = tag;
		return fragment;
	}

	public void loadUrl(@Nullable String url) {
		this.url = url;
		YoutubeWebview webView = this.webView;
		if (webView != null && url != null && !Objects.equals(webView.getUrl(), url)) {
			webView.loadUrl(url);
		}
	}

	private void takeHistorySnapshot() {
		YoutubeWebview webView = this.webView;
		if (webView != null) historySnapshot = webView.copyBackForwardList();
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle args = getArguments();
		if (args != null) {
			url = args.getString(ARG_URL);
			tag = args.getString(ARG_TAG);
		}
	}

	@NonNull
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_webview, container, false);
		YoutubeWebview webView = view.findViewById(R.id.webview);
		this.webView = webView;
		SwipeRefreshLayout swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);

		swipeRefreshLayout.setColorSchemeResources(R.color.yt_red);
		swipeRefreshLayout.setOnRefreshListener(() -> webView.evaluateJavascript("window.dispatchEvent(new Event('onRefresh'));", value -> {
		}));
		swipeRefreshLayout.setProgressViewOffset(true, 86, 196);

		webView.setYoutubeExtractor(youtubeExtractor);
		webView.setPlayer(player);
		webView.setExtensionManager(extensionManager);
		webView.setTabManager(tabManager);
		webView.setQueueRepository(queueRepository);
		webView.setOkHttpClient(okHttpClient, webViewCachePolicy);
		webView.setPoTokenContextStore(poTokenContextStore);
		tabManager.injectScripts(webView);
		webView.setUpdateVisitedHistory(url -> {
			YoutubeFragment.this.url = url;
			tabManager.onUrlChanged(this, url);
		});
		webView.setOnPageFinishedListener(url -> takeHistorySnapshot());
		webView.init();
		webView.setScriptActive(!isHidden());
		if (savedInstanceState != null) webView.restoreState(savedInstanceState);
		else if (url != null) loadUrl(url);

		return view;
	}

	@Override
	public void onResume() {
		super.onResume();
		YoutubeWebview webView = this.webView;
		if (webView == null || isHidden()) return;
		webView.setScriptActive(true);
		webView.syncPreferences();
		webView.onResume();
		webView.resumeTimers();
		webView.refreshPoTokenContext();
	}

	@Override
	public void onPause() {
		super.onPause();
		YoutubeWebview webView = this.webView;
		if (webView == null || isHidden()) return;
		if (Constant.PAGE_WATCH.equals(tag)) {
			return;
		}
		if (getActivity() != null && getActivity().isInPictureInPictureMode()) return;
		webView.setScriptActive(false);
		webView.onPause();
		webView.pauseTimers();
	}

	@Override
	public void onHiddenChanged(boolean hidden) {
		super.onHiddenChanged(hidden);
		YoutubeWebview webView = this.webView;
		if (webView == null) return;
		if (hidden) {
			if (Constant.PAGE_WATCH.equals(tag)) {
				return;
			}
			webView.setScriptActive(false);
			webView.onPause();
			webView.pauseTimers();
		} else {
			webView.setScriptActive(true);
			webView.syncPreferences();
			webView.onResume();
			webView.resumeTimers();
			webView.refreshPoTokenContext();
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		YoutubeWebview webView = this.webView;
		if (webView == null) return;
		this.webView = null;
		webView.stopLoading();
		webView.clearHistory();
		webView.removeAllViews();
		webView.destroy();
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		YoutubeWebview webView = this.webView;
		if (webView != null) webView.saveState(outState);
	}

	@Nullable
	public String getUrl() {
		return url;
	}

	@Nullable
	public String getTabTag() {
		return tag;
	}

	@Nullable
	public YoutubeWebview getWebView() {
		return webView;
	}

	@Nullable
	public WebBackForwardList getHistorySnapshot() {
		return historySnapshot;
	}

}
