package com.hhst.youtubelite.browser;

import android.app.Activity;
import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.ValueCallback;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.util.UrlUtils;

import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import dagger.Lazy;
import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;

/**
 * Manages the tabs (fragments) of the application.
 */

@ActivityScoped
@UnstableApi
public class TabManager {

	private static final String TAG = "TabManager";
	private static final String SCRIPT_INIT = "init.js";
	private static final String SCRIPT_INIT_MIN = "init.min.js";
	private static final Set<String> NAV_TAGS = Set.of(Constant.PAGE_HOME, Constant.PAGE_SUBSCRIPTIONS, Constant.PAGE_LIBRARY);
	private final Activity activity;
	private final Lazy<LitePlayer> player;
	private final Deque<YoutubeFragment> tabs = new LinkedList<>();
	@Getter
	@Nullable
	private YoutubeFragment tab;

	@Inject
	public TabManager(@NonNull final Activity activity, @NonNull final Lazy<LitePlayer> player) {
		this.activity = activity;
		this.player = player;
	}


	public void onUrlChanged(@NonNull final YoutubeFragment fragment, @NonNull final String url) {
		if (fragment == tab) {
			if (Constant.PAGE_WATCH.equals(UrlUtils.getPageClass(url))) player.get().play(url);
			else player.get().hide();
		}
	}

	private void onTabChanged() {
		if (tab == null) return;
		onUrlChanged(tab, tab.getUrl());
	}

	@NonNull
	private FragmentManager getFm() {
		return ((FragmentActivity) activity).getSupportFragmentManager();
	}

	public void openTab(@NonNull final String url, @Nullable String tag) {
		if (tag == null) tag = UrlUtils.getPageClass(url);
		// Open in current tab
		if (tab != null && (tag.equals(tab.getMTag()) && NAV_TAGS.contains(tag) || tag.equals(Constant.PAGE_SHORTS))) {
			if (!url.equals(tab.getUrl())) tab.loadUrl(url);
			return;
		}
		final String homeTag = Constant.PAGE_HOME;
		final FragmentTransaction ft = getFm().beginTransaction();
		ft.setCustomAnimations(R.anim.tab_enter, R.anim.tab_exit, R.anim.tab_pop_enter, R.anim.tab_pop_exit);
		if (tab != null) ft.hide(tab);
		if (!NAV_TAGS.contains(tag)) {
			// Open tab directly and ensure home is on the bottom
			final var first = tabs.peekFirst();
			if (first == null || !Constant.PAGE_HOME.equals(first.getMTag())) {
				final YoutubeFragment home = YoutubeFragment.newInstance(Constant.HOME_URL, Constant.PAGE_HOME);
				tabs.offerFirst(home);
				ft.add(R.id.fragment_container, home, Constant.PAGE_HOME);
				ft.hide(home);
			}
			tab = YoutubeFragment.newInstance(url, tag);
			tabs.offer(tab);
			ft.add(R.id.fragment_container, tab, tag);
		} else {
			// Clear tabs, only keep home and target tab and ensure home is on bottom
			YoutubeFragment home = null;
			YoutubeFragment nav = null;
			for (final YoutubeFragment t : tabs) {
				final String tTag = t.getMTag();
				if (homeTag.equals(tTag)) home = t;
				else if (tag.equals(tTag)) nav = t;
				else ft.remove(t);
			}
			tabs.clear();
			if (home == null) {
				home = YoutubeFragment.newInstance(Constant.HOME_URL, homeTag);
				ft.add(R.id.fragment_container, home, homeTag);
			}
			tabs.offer(home);
			if (homeTag.equals(tag)) tab = home;
			else {
				if (nav == null) {
					nav = YoutubeFragment.newInstance(url, tag);
					ft.add(R.id.fragment_container, nav, tag);
				}
				tabs.offer(nav);
				tab = nav;
			}
		}
		ft.show(tab);
		ft.commit();
		onTabChanged();
	}

	public void injectScripts(@NonNull final YoutubeWebview webview) {
		final AssetManager assetManager = activity.getAssets();
		final List<String> resourceDirs = Arrays.asList("style", "script");
		try {
			for (final String dir : resourceDirs) {
				final String[] list = assetManager.list(dir);
				if (list == null) continue;
				final List<String> resources = new ArrayList<>(Arrays.asList(list));
				final String initScript = resources.contains(SCRIPT_INIT) ? SCRIPT_INIT : resources.contains(SCRIPT_INIT_MIN) ? SCRIPT_INIT_MIN : null;
				if (initScript != null) {
					try (final InputStream is = assetManager.open(dir + "/" + initScript)) {
						webview.injectJavaScript(is);
					}
					resources.remove(initScript);
				}
				for (final String resourceName : resources) {
					try (final InputStream stream = assetManager.open(dir + "/" + resourceName)) {
						final String extension = FilenameUtils.getExtension(resourceName);
						if ("js".equals(extension)) webview.injectJavaScript(stream);
						else if ("css".equals(extension)) webview.injectCss(stream);
					}
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "Failed to load assets", e);
		}
	}

	@Nullable
	public YoutubeWebview getWebview() {
		return tab != null ? tab.getWebview() : null;
	}

	public void evaluateJavascript(@NonNull final String script, @Nullable final ValueCallback<String> callback) {
		final YoutubeWebview webview = getWebview();
		if (webview != null) webview.evaluateJavascript(script, callback);
	}

	public void loadUrl(@NonNull final String url) {
		final YoutubeWebview webview = getWebview();
		if (webview != null) webview.loadUrl(url);
	}

	public boolean goBack() {
		if (tab == null) return false;
		final YoutubeWebview webview = getWebview();
		final boolean hasBackStack = tabs.size() > 1;
		if (webview != null && webview.canGoBack()) {
			// Webview back
			webview.goBack();
			return true;
		} else if (hasBackStack) {
			// Tabs back
			final FragmentTransaction ft = getFm().beginTransaction();
			ft.setCustomAnimations(R.anim.tab_pop_enter, R.anim.tab_exit);
			ft.remove(tabs.pollLast());
			tab = tabs.peekLast();
			if (tab != null) ft.show(tab);
			ft.commit();
			onTabChanged();
			return true;
		}
		return false;
	}

}
