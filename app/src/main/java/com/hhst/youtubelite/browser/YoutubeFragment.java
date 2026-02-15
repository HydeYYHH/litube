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

import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.extractor.PoTokenProviderImpl;
import com.hhst.youtubelite.extractor.YoutubeExtractor;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.controller.Controller;

import java.util.Objects;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import lombok.Getter;

@Getter
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
	Controller controller;
	@Inject
	ExtensionManager extensionManager;
	@Inject
	TabManager tabManager;
	@Inject
	PoTokenProviderImpl poTokenProvider;

	@Nullable
	private String url;
	@Nullable
	private String mTag;
	@Nullable
	private YoutubeWebview webview;
	@Nullable
	private SwipeRefreshLayout swipeRefreshLayout;
	@Nullable
	private WebBackForwardList historySnapshot;

	@NonNull
	public static YoutubeFragment newInstance(@NonNull final String url, @NonNull final String tag) {
		final YoutubeFragment fragment = new YoutubeFragment();
		final Bundle args = new Bundle();
		args.putString(ARG_URL, url);
		args.putString(ARG_TAG, tag);
		fragment.setArguments(args);
		return fragment;
	}

	public void loadUrl(@Nullable final String url) {
		if (webview != null && url != null && !Objects.equals(webview.getUrl(), url))
			webview.loadUrl(url);
	}

	private void takeHistorySnapshot() {
		if (webview != null) historySnapshot = webview.copyBackForwardList();
	}

	@Override
	public void onCreate(@Nullable final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		final Bundle args = getArguments();
		if (args != null) {
			url = args.getString(ARG_URL);
			mTag = args.getString(ARG_TAG);
		}
	}

	@NonNull
	@Override
	public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container, @Nullable final Bundle savedInstanceState) {
		final View view = inflater.inflate(R.layout.fragment_webview, container, false);
		webview = view.findViewById(R.id.webview);
		swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);

		swipeRefreshLayout.setColorSchemeResources(R.color.yt_red);
		swipeRefreshLayout.setOnRefreshListener(() -> webview.evaluateJavascript("window.dispatchEvent(new Event('onRefresh'));", value -> {
		}));
		swipeRefreshLayout.setProgressViewOffset(true, 86, 196);

		webview.setYoutubeExtractor(youtubeExtractor);
		webview.setPlayer(player);
		webview.setExtensionManager(extensionManager);
		webview.setTabManager(tabManager);
		webview.setPoTokenProvider(poTokenProvider);
		webview.setUpdateVisitedHistory(url -> {
			YoutubeFragment.this.url = url;
			tabManager.onUrlChanged(this, url);
		});
		webview.setOnPageFinishedListener(url -> takeHistorySnapshot());
		webview.init();
		if (savedInstanceState != null) webview.restoreState(savedInstanceState);
		else if (url != null) loadUrl(url);

		// Load scripts in background
		new Thread(() -> {
			if (tabManager != null) {
				tabManager.injectScripts(webview);
			}
		}).start();

		return view;
	}

	@Override
	public void onResume() {
		super.onResume();
		if (webview != null && !isHidden()) {
			webview.onResume();
			webview.resumeTimers();
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		if (webview != null && !isHidden()) {
			if (getActivity() != null && getActivity().isInPictureInPictureMode()) return;
			webview.onPause();
			webview.pauseTimers();
		}
	}

	@Override
	public void onHiddenChanged(final boolean hidden) {
		super.onHiddenChanged(hidden);
		if (webview != null) {
			if (hidden) {
				webview.onPause();
				webview.pauseTimers();
			} else {
				webview.onResume();
				webview.resumeTimers();
			}
		}
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (webview != null) {
			webview.stopLoading();
			webview.clearHistory();
			webview.removeAllViews();
			webview.destroy();
			webview = null;
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull final Bundle outState) {
		super.onSaveInstanceState(outState);
		if (webview != null) webview.saveState(outState);
	}

}
