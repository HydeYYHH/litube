package com.hhst.youtubelite.browser;

import android.app.Activity;
import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;
import android.webkit.ValueCallback;

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
import com.hhst.youtubelite.player.queue.QueueWarmer;
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
	private final ExtensionManager extensionManager;
	private final QueueWarmer queueWarmer;
	private final Deque<YoutubeFragment> tabs = new LinkedList<>();
	@Getter
	@Nullable
	private YoutubeFragment tab;
	@Nullable
	private YoutubeFragment suspendedWatchFragment;

	@Inject
	public TabManager(@NonNull final Activity activity,
	                  @NonNull final Lazy<LitePlayer> player,
	                  @NonNull final ExtensionManager extensionManager,
	                  @NonNull final QueueWarmer queueWarmer) {
		this.activity = activity;
		this.player = player;
		this.extensionManager = extensionManager;
		this.queueWarmer = queueWarmer;
	}


	public void onUrlChanged(@NonNull final YoutubeFragment fragment, @NonNull final String url) {
		if (fragment != tab) return;
		final LitePlayer litePlayer = player.get();
		if (Constant.PAGE_WATCH.equals(UrlUtils.getPageClass(url))) {
			if (litePlayer.isInAppMiniPlayer()) litePlayer.exitInAppMiniPlayer();
			litePlayer.play(url);
			return;
		}
		if (suspendedWatchFragment != null || litePlayer.isInAppMiniPlayer()) return;
		litePlayer.hide();
	}

	private void onTabChanged() {
		if (tab == null || tab.getUrl() == null) return;
		onUrlChanged(tab, tab.getUrl());
	}

	@NonNull
	private FragmentManager getFm() {
		return ((FragmentActivity) activity).getSupportFragmentManager();
	}

	@NonNull
	protected YoutubeFragment createFragment(@NonNull final String url, @NonNull final String tag) {
		return YoutubeFragment.newInstance(url, tag);
	}

	public void openTab(@NonNull final String url, @Nullable String tag) {
		if (tag == null) tag = UrlUtils.getPageClass(url);
		if (shouldReplaceSuspendedWatch(suspendedWatchFragment != null, tag)) {
			clearSuspendedWatchSession(true);
		}
		// Open in current tab
		if (tab != null && (tag.equals(tab.getMTag()) && NAV_TAGS.contains(tag) || tag.equals(Constant.PAGE_SHORTS))) {
			if (!url.equals(tab.getUrl())) tab.loadUrl(url);
			return;
		}
		final String homeTag = Constant.PAGE_HOME;
		final FragmentTransaction ft = getFm().beginTransaction();
		final boolean suspendCurrentWatch = shouldSuspendCurrentWatch(
						tab != null ? tab.getMTag() : null,
						tag,
						extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER),
						player.get().isSuspendableWatchSession());
		if (suspendCurrentWatch) suspendCurrentWatch(ft);
		else if (tab != null) ft.hide(tab);
		if (!NAV_TAGS.contains(tag)) {
			// Open tab directly and ensure home is on the bottom
			final var first = tabs.peekFirst();
			if (first == null || !Constant.PAGE_HOME.equals(first.getMTag())) {
				final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
				tabs.offerFirst(home);
				ft.add(R.id.fragment_container, home, Constant.PAGE_HOME);
				ft.hide(home);
			}
			tab = createFragment(url, tag);
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
				home = createFragment(Constant.HOME_URL, homeTag);
				ft.add(R.id.fragment_container, home, homeTag);
			}
			tabs.offer(home);
			if (homeTag.equals(tag)) tab = home;
			else {
				if (nav == null) {
					nav = createFragment(url, tag);
					ft.add(R.id.fragment_container, nav, tag);
				}
				tabs.offer(nav);
				tab = nav;
			}
		}
		ft.show(tab);
		commitAndRun(ft, () -> {
			if (suspendCurrentWatch) enterMiniPlayer();
			onTabChanged();
		});
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

	public void evaluateJavascriptForPlayback(@NonNull final String script,
	                                          @Nullable final ValueCallback<String> callback) {
		final YoutubeWebview webview = resolvePlaybackWebview();
		if (webview != null) webview.evaluateJavascript(script, callback);
	}

	public void playInPlaybackSession(@NonNull final String url) {
		queueWarmer.prioritizeUrl(url);
		player.get().play(url);
		final YoutubeWebview webview = resolvePlaybackWebview();
		if (webview != null) {
			webview.loadUrl(url);
			return;
		}
		openTab(url, UrlUtils.getPageClass(url));
	}

	@Nullable
	public String getPlaybackSessionUrl() {
		if (isWatchSession(suspendedWatchFragment)) {
			return suspendedWatchFragment != null ? suspendedWatchFragment.getUrl() : null;
		}
		return isWatchSession(tab) && tab != null ? tab.getUrl() : null;
	}

	public void loadUrl(@NonNull final String url) {
		final YoutubeWebview webview = getWebview();
		if (webview != null) webview.loadUrl(url);
	}

	@Nullable
	private YoutubeWebview resolvePlaybackWebview() {
		if (isWatchSession(suspendedWatchFragment)) {
			final YoutubeWebview suspendedWebview = suspendedWatchFragment.getWebview();
			if (suspendedWebview != null) {
				return suspendedWebview;
			}
		}
		if (!isWatchSession(tab)) {
			return null;
		}
		return tab != null ? tab.getWebview() : null;
	}

	private boolean isWatchSession(@Nullable final YoutubeFragment fragment) {
		if (fragment == null) return false;
		if (Constant.PAGE_WATCH.equals(fragment.getMTag())) {
			return true;
		}
		final String url = fragment.getUrl();
		return url != null && Constant.PAGE_WATCH.equals(UrlUtils.getPageClass(url));
	}

	public void hidePlayer() {
		if (suspendedWatchFragment != null) return;
		player.get().hide();
	}

	public boolean goBack() {
		if (tab == null) return false;
		final YoutubeWebview webview = getWebview();
		final String previousHistoryUrl = getPreviousHistoryUrl(webview);
		final String previousHistoryTag = previousHistoryUrl != null
						? UrlUtils.getPageClass(previousHistoryUrl)
						: null;
		final boolean hasBackStack = tabs.size() > 1;
		if (shouldSuspendCurrentWatchOnBack(
						tab.getMTag(),
						previousHistoryTag,
						hasBackStack,
						extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER),
						player.get().isSuspendableWatchSession())) {
			final YoutubeFragment previousTab = getPreviousTab();
			if (previousHistoryUrl != null
							&& (previousTab == null || !previousHistoryUrl.equals(previousTab.getUrl()))) {
				openTab(previousHistoryUrl, previousHistoryTag);
				return true;
			}
			final FragmentTransaction ft = getFm().beginTransaction();
			suspendCurrentWatch(ft);
			tab = previousTab;
			if (tab != null) ft.show(tab);
			commitAndRun(ft, () -> {
				enterMiniPlayer();
				onTabChanged();
			});
			return true;
		}
		if (webview != null && webview.canGoBack()) {
			// Webview back
			webview.goBack();
			return true;
		} else if (hasBackStack) {
			// Tabs back
			final FragmentTransaction ft = getFm().beginTransaction();
			ft.remove(tabs.pollLast());
			tab = tabs.peekLast();
			if (tab != null) ft.show(tab);
			commitAndRun(ft, this::onTabChanged);
			return true;
		}
		return false;
	}

	private void suspendCurrentWatch(@NonNull final FragmentTransaction ft) {
		if (tab == null) return;
		suspendedWatchFragment = tab;
		tabs.pollLast();
		ft.hide(tab);
	}

	private void enterMiniPlayer() {
		final LitePlayer litePlayer = player.get();
		litePlayer.setMiniPlayerCallbacks(this::restoreSuspendedWatch, this::onMiniPlayerClosed);
		litePlayer.enterInAppMiniPlayer();
	}

	private void restoreSuspendedWatch() {
		if (suspendedWatchFragment == null) return;
		final FragmentTransaction ft = getFm().beginTransaction();
		if (tab != null) ft.hide(tab);
		tabs.offerLast(suspendedWatchFragment);
		tab = suspendedWatchFragment;
		suspendedWatchFragment = null;
		ft.show(tab);
		final LitePlayer litePlayer = player.get();
		commitAndRun(ft, () -> {
			litePlayer.exitInAppMiniPlayer();
			litePlayer.setMiniPlayerCallbacks(null, null);
			onTabChanged();
		});
	}

	private void onMiniPlayerClosed() {
		clearSuspendedWatchSession(false);
	}

	private void clearSuspendedWatchSession(final boolean stopPlayback) {
		final LitePlayer litePlayer = player.get();
		if (stopPlayback) litePlayer.hide();
		if (suspendedWatchFragment != null) {
			final FragmentTransaction ft = getFm().beginTransaction();
			ft.remove(suspendedWatchFragment);
			ft.commit();
			suspendedWatchFragment = null;
		}
		litePlayer.exitInAppMiniPlayer();
		litePlayer.setMiniPlayerCallbacks(null, null);
	}

	private void commitAndRun(@NonNull final FragmentTransaction ft, @NonNull final Runnable afterCommit) {
		ft.runOnCommit(afterCommit);
		ft.commit();
	}

	@Nullable
	private YoutubeFragment getPreviousTab() {
		if (tabs.size() < 2) return null;
		final var iterator = tabs.descendingIterator();
		iterator.next();
		return iterator.hasNext() ? iterator.next() : null;
	}

	@Nullable
	private String getPreviousHistoryUrl(@Nullable final YoutubeWebview webview) {
		final WebBackForwardList history = webview != null
						? webview.copyBackForwardList()
						: tab != null ? tab.getHistorySnapshot() : null;
		if (history == null) return null;
		final int currentIndex = history.getCurrentIndex();
		if (currentIndex <= 0) return null;
		final WebHistoryItem previousItem = history.getItemAtIndex(currentIndex - 1);
		return previousItem != null ? previousItem.getUrl() : null;
	}

	static boolean shouldSuspendCurrentWatch(@Nullable final String currentTag,
	                                         @Nullable final String targetTag,
	                                         final boolean inAppMiniPlayerEnabled,
	                                         final boolean suspendableWatchSession) {
		return Constant.PAGE_WATCH.equals(currentTag)
						&& !Constant.PAGE_WATCH.equals(targetTag)
						&& inAppMiniPlayerEnabled
						&& suspendableWatchSession;
	}

	static boolean shouldReplaceSuspendedWatch(final boolean hasSuspendedWatchSession,
	                                           @Nullable final String targetTag) {
		return hasSuspendedWatchSession && Constant.PAGE_WATCH.equals(targetTag);
	}

	static boolean shouldSuspendCurrentWatchOnBack(@Nullable final String currentTag,
	                                               @Nullable final String previousHistoryTag,
	                                               final boolean hasTabBackStack,
	                                               final boolean inAppMiniPlayerEnabled,
	                                               final boolean suspendableWatchSession) {
		if (!Constant.PAGE_WATCH.equals(currentTag)
						|| !inAppMiniPlayerEnabled
						|| !suspendableWatchSession) {
			return false;
		}
		if (previousHistoryTag != null) {
			return !Constant.PAGE_WATCH.equals(previousHistoryTag);
		}
		return hasTabBackStack;
	}

}
