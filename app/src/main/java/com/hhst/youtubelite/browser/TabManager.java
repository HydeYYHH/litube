package com.hhst.youtubelite.browser;

import android.app.Activity;
import android.util.Log;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.R;
import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.util.UrlUtils;

import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import dagger.Lazy;
import dagger.hilt.android.scopes.ActivityScoped;
import lombok.Getter;

/**
 * Owns tab state and watch-tab transitions.
 */
@ActivityScoped
@UnstableApi
public class TabManager {

	private static final String TAG = "TabManager";
	private static final Set<String> NAV_TAGS = Set.of(Constant.PAGE_HOME, Constant.PAGE_SUBSCRIPTIONS, Constant.PAGE_LIBRARY);
	private final Activity activity;
	private final Lazy<LitePlayer> player;
	private final ExtensionManager extensionManager;
	private final Deque<YoutubeFragment> tabs = new LinkedList<>();
	@Getter
	@Nullable
	private YoutubeFragment tab;
	@Nullable
	private YoutubeFragment suspendedTab;

	@Inject
	public TabManager(@NonNull Activity activity, @NonNull Lazy<LitePlayer> player, @NonNull ExtensionManager extensionManager) {
		this.activity = activity;
		this.player = player;
		this.extensionManager = extensionManager;
	}

	static boolean shouldSuspend(@Nullable String tag, @Nullable String targetTag, boolean miniPlayerOn, boolean canSuspend) {
		return Constant.PAGE_WATCH.equals(tag) && !Constant.PAGE_WATCH.equals(targetTag) && miniPlayerOn && canSuspend;
	}

	static boolean shouldSuspendBack(@Nullable String tag, boolean miniPlayerOn, boolean canSuspend) {
		return Constant.PAGE_WATCH.equals(tag) && miniPlayerOn && canSuspend;
	}

	public void onUrlChanged(@NonNull YoutubeFragment fragment, @NonNull String url) {
		if (fragment != tab) return;
		var litePlayer = player.get();
		if (Constant.PAGE_WATCH.equals(UrlUtils.getPageClass(url))) {
			if (litePlayer.isInMiniPlayer()) litePlayer.exitInAppMiniPlayer();
			litePlayer.play(url);
			return;
		}
		if (suspendedTab != null || litePlayer.isInMiniPlayer()) return;
		litePlayer.hide();
	}

	private void onTabChanged() {
		if (tab == null || tab.getUrl() == null) return;
		onUrlChanged(tab, tab.getUrl());
	}

	@NonNull
	private FragmentManager fm() {
		return ((FragmentActivity) activity).getSupportFragmentManager();
	}

	@NonNull
	protected YoutubeFragment createFragment(@NonNull String url, @NonNull String tag) {
		return YoutubeFragment.newInstance(url, tag);
	}

	public void openTab(@NonNull String url, @Nullable String tag) {
		if (tag == null) tag = UrlUtils.getPageClass(url);
		var targetTag = tag;
		if (Constant.PAGE_WATCH.equals(targetTag) && openWatchTab(url)) return;
		if (tab != null && (targetTag.equals(tab.getTabTag()) && NAV_TAGS.contains(targetTag) || targetTag.equals(Constant.PAGE_SHORTS))) {
			if (!url.equals(tab.getUrl())) tab.loadUrl(url);
			return;
		}
		var homeTag = Constant.PAGE_HOME;
		var ft = fm().beginTransaction();
		var suspendWatch = shouldSuspend(pageClass(tab), targetTag, extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER), player.get().canSuspendWatch());
		if (suspendWatch) suspendTab(ft);
		else if (tab != null) ft.hide(tab);
		if (!NAV_TAGS.contains(targetTag)) {
			var first = tabs.peekFirst();
			if (first == null || !Constant.PAGE_HOME.equals(first.getTabTag())) {
				var home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
				tabs.offerFirst(home);
				ft.add(R.id.fragment_container, home, Constant.PAGE_HOME);
				ft.hide(home);
			}
			tab = createFragment(url, targetTag);
			tabs.offer(tab);
			ft.add(R.id.fragment_container, tab, targetTag);
		} else {
			YoutubeFragment home = null;
			YoutubeFragment nav = null;
			for (var t : tabs) {
				var tabTag = t.getTabTag();
				if (homeTag.equals(tabTag)) home = t;
				else if (targetTag.equals(tabTag)) nav = t;
				else ft.remove(t);
			}
			tabs.clear();
			if (home == null) {
				home = createFragment(Constant.HOME_URL, homeTag);
				ft.add(R.id.fragment_container, home, homeTag);
			}
			tabs.offer(home);
			if (homeTag.equals(targetTag)) {
				tab = home;
			} else {
				if (nav == null) {
					nav = createFragment(url, targetTag);
					ft.add(R.id.fragment_container, nav, targetTag);
				}
				tabs.offer(nav);
				tab = nav;
			}
		}
		ft.show(tab);
		commitAndRun(ft, () -> {
			if (suspendWatch) enterMiniPlayer();
			onTabChanged();
		});
	}

	public void injectScripts(@NonNull YoutubeWebview webView) {
		var assetMgr = activity.getAssets();
		try {
			for (var dir : List.of("style", "script")) {
				var list = assetMgr.list(dir);
				if (list == null) continue;
				var resources = new ArrayList<>(List.of(list));
				var initScript = resources.contains("init.js") ? "init.js" : resources.contains("init.min.js") ? "init.min.js" : null;
				if (initScript != null) {
					try (var is = assetMgr.open(dir + "/" + initScript)) {
						webView.injectJavaScript(is);
					}
					resources.remove(initScript);
				}
				for (var resName : resources) {
					try (var stream = assetMgr.open(dir + "/" + resName)) {
						var ext = FilenameUtils.getExtension(resName);
						if ("js".equals(ext)) webView.injectJavaScript(stream);
						else if ("css".equals(ext)) webView.injectCss(stream);
					}
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "Failed to load assets", e);
		}
	}

	@Nullable
	public YoutubeWebview getWebView() {
		return tab != null ? tab.getWebView() : null;
	}

	public void evaluateJavascript(@NonNull String script, @Nullable ValueCallback<String> callback) {
		YoutubeWebview webView = getWebView();
		if (webView != null) webView.evaluateJavascript(script, callback);
	}

	public void evalWatchJs(@NonNull String script, @Nullable ValueCallback<String> callback) {
		var webView = watchTabWebView();
		if (webView != null) {
			webView.evaluateJavascript(script, callback);
			return;
		}
		if (callback != null) callback.onReceiveValue(null);
	}

	public void playInWatch(@NonNull String url) {
		player.get().play(url);
		var webView = watchTabWebView();
		if (webView != null) {
			webView.loadUrl(url);
			return;
		}
		openTab(url, UrlUtils.getPageClass(url));
	}

	public boolean canGoBackInWatch() {
		var webView = watchTabWebView();
		return webView != null && webView.canGoBack();
	}

	public void goBackInWatch() {
		var webView = watchTabWebView();
		if (webView == null || !webView.canGoBack()) return;
		webView.goBack();
	}

	public boolean watchHasPlaylist() {
		var url = getWatchUrl();
		return url != null && url.contains("list=");
	}

	@Nullable
	public String getWatchUrl() {
		if (isWatchTab(suspendedTab)) {
			return suspendedTab.getUrl();
		}
		return isWatchTab(tab) ? tab.getUrl() : null;
	}

	@Nullable
	private YoutubeWebview watchTabWebView() {
		if (isWatchTab(suspendedTab)) {
			var webView = suspendedTab.getWebView();
			if (webView != null) {
				return webView;
			}
		}
		if (!isWatchTab(tab)) {
			return null;
		}
		return tab != null ? tab.getWebView() : null;
	}

	private boolean isWatchTab(@Nullable YoutubeFragment fragment) {
		return Constant.PAGE_WATCH.equals(pageClass(fragment));
	}

	@Nullable
	private String pageClass(@Nullable YoutubeFragment fragment) {
		if (fragment == null) return null;
		var url = fragment.getUrl();
		if (url != null) {
			return UrlUtils.getPageClass(url);
		}
		return fragment.getTabTag();
	}

	private boolean openWatchTab(@NonNull String url) {
		var watch = findWatchTab();
		if (watch == null) return false;
		if (watch == tab) {
			if (!url.equals(watch.getUrl())) watch.loadUrl(url);
			onUrlChanged(watch, url);
			return true;
		}
		var restoring = watch == suspendedTab;
		var ft = fm().beginTransaction();
		if (tab != null) ft.hide(tab);
		tabs.remove(watch);
		tabs.offerLast(watch);
		tab = watch;
		if (restoring) {
			suspendedTab = null;
		}
		if (!url.equals(watch.getUrl())) watch.loadUrl(url);
		ft.show(watch);
		commitAndRun(ft, () -> {
			if (restoring) {
				var litePlayer = player.get();
				litePlayer.exitInAppMiniPlayer();
				litePlayer.setMiniPlayerCallbacks(null, null);
			}
			onUrlChanged(watch, url);
		});
		return true;
	}

	public void hidePlayer() {
		if (suspendedTab != null) return;
		player.get().hide();
	}

	public boolean goBack() {
		if (tab == null) return false;
		var webView = getWebView();
		var prev = prev(tab);
		var hasBack = tabs.size() > 1;
		if (shouldSuspendBack(pageClass(tab), extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER), player.get().canSuspendWatch())) {
			var prevTab = previousTab();
			if (prev != null && !Constant.PAGE_WATCH.equals(prev.tag()) && (prevTab == null || !prev.url().equals(prevTab.getUrl()))) {
				openTab(prev.url(), prev.tag());
				return true;
			}
			var ft = fm().beginTransaction();
			suspendTab(ft);
			tab = prevTab != null ? prevTab : home(ft);
			ft.show(tab);
			commitAndRun(ft, () -> {
				enterMiniPlayer();
				onTabChanged();
			});
			return true;
		}
		if (webView != null && webView.canGoBack()) {
			webView.goBack();
			return true;
		} else if (hasBack) {
			var ft = fm().beginTransaction();
			ft.remove(tabs.pollLast());
			tab = tabs.peekLast();
			if (tab != null) ft.show(tab);
			commitAndRun(ft, this::onTabChanged);
			return true;
		}
		return false;
	}

	private void suspendTab(@NonNull FragmentTransaction ft) {
		if (tab == null) return;
		suspendedTab = tab;
		tabs.pollLast();
		ft.hide(tab);
	}

	private void enterMiniPlayer() {
		var litePlayer = player.get();
		litePlayer.setMiniPlayerCallbacks(() -> {
			if (suspendedTab == null) return;
			var ft = fm().beginTransaction();
			if (tab != null) ft.hide(tab);
			tabs.offerLast(suspendedTab);
			tab = suspendedTab;
			suspendedTab = null;
			ft.show(tab);
			var activePlayer = player.get();
			commitAndRun(ft, () -> {
				activePlayer.exitInAppMiniPlayer();
				activePlayer.setMiniPlayerCallbacks(null, null);
				onTabChanged();
			});
		}, () -> {
			var activePlayer = player.get();
			if (suspendedTab != null) {
				var ft = fm().beginTransaction();
				ft.remove(suspendedTab);
				ft.commit();
				suspendedTab = null;
			}
			activePlayer.exitInAppMiniPlayer();
			activePlayer.setMiniPlayerCallbacks(null, null);
		});
		litePlayer.enterInAppMiniPlayer();
	}

	private void commitAndRun(@NonNull FragmentTransaction ft, @NonNull Runnable afterCommit) {
		ft.runOnCommit(afterCommit);
		ft.commit();
	}

	@Nullable
	private YoutubeFragment findWatchTab() {
		if (isWatchTab(tab)) {
			return tab;
		}
		if (isWatchTab(suspendedTab)) {
			return suspendedTab;
		}
		for (var fragment : tabs) {
			if (isWatchTab(fragment)) {
				return fragment;
			}
		}
		return null;
	}

	@Nullable
	private YoutubeFragment previousTab() {
		if (tabs.size() < 2) return null;
		var it = tabs.descendingIterator();
		it.next();
		return it.hasNext() ? it.next() : null;
	}

	@NonNull
	private YoutubeFragment home(@NonNull FragmentTransaction ft) {
		for (var frag : tabs) {
			if (Constant.PAGE_HOME.equals(frag.getTabTag())) {
				return frag;
			}
		}
		var home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		tabs.offerFirst(home);
		ft.add(R.id.fragment_container, home, Constant.PAGE_HOME);
		return home;
	}

	@Nullable
	private Page prev(@Nullable YoutubeFragment frag) {
		var hist = history(frag);
		if (hist == null) return null;
		var i = hist.getCurrentIndex() - 1;
		if (i < 0) return null;
		var item = hist.getItemAtIndex(i);
		return item != null ? page(item.getUrl()) : null;
	}

	@Nullable
	private WebBackForwardList history(@Nullable YoutubeFragment frag) {
		if (frag == null) return null;
		var webView = frag.getWebView();
		return webView != null ? webView.copyBackForwardList() : frag.getHistorySnapshot();
	}

	@Nullable
	private Page page(@Nullable String url) {
		if (url == null) return null;
		return new Page(url, UrlUtils.getPageClass(url));
	}

/**
 * Value object for app logic.
 */
	private record Page(String url, String tag) {
	}
}
