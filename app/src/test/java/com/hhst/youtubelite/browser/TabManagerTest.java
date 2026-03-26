package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.webkit.WebBackForwardList;
import android.webkit.WebHistoryItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.player.LitePlayer;
import com.hhst.youtubelite.player.queue.QueueWarmer;
import com.hhst.youtubelite.Constant;
import com.hhst.youtubelite.util.UrlUtils;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public class TabManagerTest {
	private static final Map<String, YoutubeFragment> FRAGMENTS = new HashMap<>();
	private static FragmentTransaction lastFragmentTransaction;

	@Test
	public void shouldSuspendCurrentWatch_returnsTrueWhenLeavingPlayingWatchForNonWatch() {
		assertTrue(TabManager.shouldSuspendCurrentWatch(
						Constant.PAGE_WATCH,
						UrlUtils.PAGE_CHANNEL,
						true,
						true));
	}

	@Test
	public void shouldSuspendCurrentWatch_returnsFalseWhenFeatureDisabled() {
		assertFalse(TabManager.shouldSuspendCurrentWatch(
						Constant.PAGE_WATCH,
						UrlUtils.PAGE_CHANNEL,
						false,
						true));
	}

	@Test
	public void shouldSuspendCurrentWatch_returnsFalseWhenTargetIsWatch() {
		assertFalse(TabManager.shouldSuspendCurrentWatch(
						Constant.PAGE_WATCH,
						Constant.PAGE_WATCH,
						true,
						true));
	}

	@Test
	public void shouldReplaceSuspendedWatch_returnsTrueWhenOpeningWatchWithExistingMiniPlayerSession() {
		assertTrue(TabManager.shouldReplaceSuspendedWatch(true, Constant.PAGE_WATCH));
	}

	@Test
	public void shouldReplaceSuspendedWatch_returnsFalseWhenThereIsNoSuspendedSession() {
		assertFalse(TabManager.shouldReplaceSuspendedWatch(false, Constant.PAGE_WATCH));
	}

	@Test
	public void shouldSuspendCurrentWatchOnBack_returnsTrueWhenBackWouldLeaveWatch() {
		assertTrue(TabManager.shouldSuspendCurrentWatchOnBack(
						Constant.PAGE_WATCH,
						UrlUtils.PAGE_CHANNEL,
						true,
						true,
						true));
	}

	@Test
	public void shouldSuspendCurrentWatchOnBack_returnsFalseWhenBackStaysWithinWatch() {
		assertFalse(TabManager.shouldSuspendCurrentWatchOnBack(
						Constant.PAGE_WATCH,
						Constant.PAGE_WATCH,
						true,
						true,
						true));
	}

	@Test
	public void openTab_leavingPlayingWatchEntersMiniPlayerAndCachesCurrentWatch() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		seedTabs(tabManager, home, watch);

		tabManager.openTab("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);

		verify(player).enterInAppMiniPlayer();
		verify(player).setMiniPlayerCallbacks(any(), any());
		verify(player, never()).hide();
		assertSame(watch, getSuspendedWatch(tabManager));
	}

	@Test
	public void openTab_leavingPlayingWatch_defersMiniPlayerUntilAfterTransactionCommit() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		seedTabs(tabManager, home, watch);

		tabManager.openTab("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);

		assertNotNull(lastFragmentTransaction);
		final InOrder inOrder = org.mockito.Mockito.inOrder(lastFragmentTransaction, player);
		inOrder.verify(lastFragmentTransaction).runOnCommit(any(Runnable.class));
		inOrder.verify(lastFragmentTransaction).commit();
		inOrder.verify(player).setMiniPlayerCallbacks(any(), any());
		inOrder.verify(player).enterInAppMiniPlayer();
	}

	@Test
	public void openTab_leavingWatchWithFeatureDisabledFallsBackToHidingPlayer() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(false);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		seedTabs(tabManager, home, watch);

		tabManager.openTab("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);

		verify(player, never()).enterInAppMiniPlayer();
		verify(player).hide();
		assertNull(getSuspendedWatch(tabManager));
	}

	@Test
	public void restoreMiniPlayer_returnsToSameWatchFragmentInstance() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		seedTabs(tabManager, home, watch);
		tabManager.openTab("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);

		final ArgumentCaptor<Runnable> restoreCaptor = ArgumentCaptor.forClass(Runnable.class);
		final ArgumentCaptor<Runnable> closeCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(player).setMiniPlayerCallbacks(restoreCaptor.capture(), closeCaptor.capture());

		restoreCaptor.getValue().run();

		verify(player).exitInAppMiniPlayer();
		assertSame(watch, getCurrentTab(tabManager));
		assertNull(getSuspendedWatch(tabManager));
	}

	@Test
	public void closeMiniPlayer_clearsSuspendedWatchSession() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		seedTabs(tabManager, home, watch);
		tabManager.openTab("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);

		final ArgumentCaptor<Runnable> restoreCaptor = ArgumentCaptor.forClass(Runnable.class);
		final ArgumentCaptor<Runnable> closeCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(player).setMiniPlayerCallbacks(restoreCaptor.capture(), closeCaptor.capture());

		closeCaptor.getValue().run();

		assertNull(getSuspendedWatch(tabManager));
	}

	@Test
	public void openTab_newWatchWhileMiniPlayerExistsStopsOldSessionBeforeOpeningNewWatch() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		seedTabs(tabManager, home, watch);
		tabManager.openTab("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);

		tabManager.openTab("https://m.youtube.com/watch?v=new", Constant.PAGE_WATCH);

		verify(player).hide();
		assertNull(getSuspendedWatch(tabManager));
	}

	@Test
	public void hidePlayer_doesNotHideWhenMiniPlayerSessionIsActive() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment channel = createFragment("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		seedTabs(tabManager, channel);
		setField(tabManager, "tab", channel);
		setField(tabManager, "suspendedWatchFragment", watch);

		tabManager.hidePlayer();

		verify(player, never()).hide();
		assertSame(watch, getSuspendedWatch(tabManager));
	}

	@Test
	public void evaluateJavascriptForPlayback_targetsSuspendedWatchWebViewWhenMiniPlayerSessionExists() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment current = createFragment("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);
		final YoutubeFragment suspendedWatch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);
		final YoutubeWebview currentWebView = mock(YoutubeWebview.class);
		final YoutubeWebview suspendedWatchWebView = mock(YoutubeWebview.class);
		setField(current, "webview", currentWebView);
		setField(suspendedWatch, "webview", suspendedWatchWebView);
		seedTabs(tabManager, current);
		setField(tabManager, "suspendedWatchFragment", suspendedWatch);

		tabManager.evaluateJavascriptForPlayback("nextVideo()", null);

		verify(suspendedWatchWebView).evaluateJavascript(anyString(), any());
		verify(currentWebView, never()).evaluateJavascript(anyString(), any());
	}

	@Test
	public void evaluateJavascriptForPlayback_fallsBackToCurrentWebViewWhenNoSuspendedWatchSession() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment current = createFragment("https://m.youtube.com/watch?v=current", Constant.PAGE_WATCH);
		final YoutubeWebview currentWebView = mock(YoutubeWebview.class);
		setField(current, "webview", currentWebView);
		seedTabs(tabManager, current);

		tabManager.evaluateJavascriptForPlayback("previousVideo()", null);

		verify(currentWebView).evaluateJavascript(anyString(), any());
	}

	@Test
	public void playInPlaybackSession_targetsSuspendedWatchAndStartsPlayback() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final QueueWarmer warmer = mock(QueueWarmer.class);
		final TabManager tabManager = createTabManager(player, extensionManager, warmer);
		final YoutubeFragment current = createFragment("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);
		final YoutubeFragment suspendedWatch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);
		final YoutubeWebview suspendedWatchWebView = mock(YoutubeWebview.class);
		setField(suspendedWatch, "webview", suspendedWatchWebView);
		seedTabs(tabManager, current);
		setField(tabManager, "suspendedWatchFragment", suspendedWatch);

		tabManager.playInPlaybackSession("https://m.youtube.com/watch?v=new");

		verify(warmer).prioritizeUrl("https://m.youtube.com/watch?v=new");
		verify(player).play("https://m.youtube.com/watch?v=new");
		verify(suspendedWatchWebView).loadUrl("https://m.youtube.com/watch?v=new");
	}

	@Test
	public void evaluateJavascriptForPlayback_skipsExecutionWhenNoWatchSessionExists() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment current = createFragment("https://m.youtube.com/channel/test", UrlUtils.PAGE_CHANNEL);
		final YoutubeWebview currentWebView = mock(YoutubeWebview.class);
		setField(current, "webview", currentWebView);
		seedTabs(tabManager, current);

		tabManager.evaluateJavascriptForPlayback("nextVideo()", null);

		verify(currentWebView, never()).evaluateJavascript(anyString(), any());
	}

	@Test
	public void goBack_fromPlayingWatchSuspendsIntoMiniPlayerInsteadOfRemovingWatch() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		seedTabs(tabManager, home, watch);

		assertTrue(tabManager.goBack());

		verify(player).enterInAppMiniPlayer();
		verify(player, never()).hide();
		assertSame(home, getCurrentTab(tabManager));
		assertSame(watch, getSuspendedWatch(tabManager));
	}

	@Test
	public void goBack_fromPlayingWatch_defersMiniPlayerUntilAfterTransactionCommit() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		seedTabs(tabManager, home, watch);

		assertTrue(tabManager.goBack());

		assertNotNull(lastFragmentTransaction);
		final InOrder inOrder = org.mockito.Mockito.inOrder(lastFragmentTransaction, player);
		inOrder.verify(lastFragmentTransaction).runOnCommit(any(Runnable.class));
		inOrder.verify(lastFragmentTransaction).commit();
		inOrder.verify(player).setMiniPlayerCallbacks(any(), any());
		inOrder.verify(player).enterInAppMiniPlayer();
	}

	@Test
	public void goBack_withNonWatchHistoryAndNoPreviousTabOpensHistoryTargetInMiniPlayer() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);
		final WebBackForwardList history = mock(WebBackForwardList.class);
		final WebHistoryItem previousItem = mock(WebHistoryItem.class);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		when(history.getCurrentIndex()).thenReturn(1);
		when(history.getItemAtIndex(0)).thenReturn(previousItem);
		when(previousItem.getUrl()).thenReturn("https://m.youtube.com/results?search_query=test");
		setField(watch, "historySnapshot", history);
		seedTabs(tabManager, watch);

		assertTrue(tabManager.goBack());

		verify(player).enterInAppMiniPlayer();
		assertSame("https://m.youtube.com/results?search_query=test", getCurrentTab(tabManager).getUrl());
		assertSame(watch, getSuspendedWatch(tabManager));
	}

	@Test
	public void goBack_prefersLiveWebViewWatchHistoryOverStaleSnapshot() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);
		final WebBackForwardList staleSnapshot = mock(WebBackForwardList.class);
		final WebHistoryItem stalePreviousItem = mock(WebHistoryItem.class);
		final WebBackForwardList liveHistory = mock(WebBackForwardList.class);
		final WebHistoryItem livePreviousItem = mock(WebHistoryItem.class);
		final YoutubeWebview webview = mock(YoutubeWebview.class);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		when(staleSnapshot.getCurrentIndex()).thenReturn(1);
		when(staleSnapshot.getItemAtIndex(0)).thenReturn(stalePreviousItem);
		when(stalePreviousItem.getUrl()).thenReturn("https://m.youtube.com/results?search_query=stale");
		when(liveHistory.getCurrentIndex()).thenReturn(1);
		when(liveHistory.getItemAtIndex(0)).thenReturn(livePreviousItem);
		when(livePreviousItem.getUrl()).thenReturn("https://m.youtube.com/watch?v=older");
		when(webview.canGoBack()).thenReturn(true);
		when(webview.copyBackForwardList()).thenReturn(liveHistory);
		setField(watch, "historySnapshot", staleSnapshot);
		setField(watch, "webview", webview);
		seedTabs(tabManager, watch);

		assertTrue(tabManager.goBack());

		verify(player, never()).enterInAppMiniPlayer();
		verify(webview).goBack();
		assertNull(getSuspendedWatch(tabManager));
	}

	@Test
	public void goBack_withNonWatchLiveHistoryAndPreviousTabSuspendsWatchInsteadOfWebViewBack() throws Exception {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		final TabManager tabManager = createTabManager(player, extensionManager);
		final YoutubeFragment home = createFragment(Constant.HOME_URL, Constant.PAGE_HOME);
		final YoutubeFragment watch = createFragment("https://m.youtube.com/watch?v=old", Constant.PAGE_WATCH);
		final WebBackForwardList liveHistory = mock(WebBackForwardList.class);
		final WebHistoryItem livePreviousItem = mock(WebHistoryItem.class);
		final YoutubeWebview webview = mock(YoutubeWebview.class);

		when(extensionManager.isEnabled(Constant.ENABLE_IN_APP_MINI_PLAYER)).thenReturn(true);
		when(player.isSuspendableWatchSession()).thenReturn(true);
		when(liveHistory.getCurrentIndex()).thenReturn(1);
		when(liveHistory.getItemAtIndex(0)).thenReturn(livePreviousItem);
		when(livePreviousItem.getUrl()).thenReturn(Constant.HOME_URL);
		when(webview.canGoBack()).thenReturn(true);
		when(webview.copyBackForwardList()).thenReturn(liveHistory);
		setField(watch, "webview", webview);
		seedTabs(tabManager, home, watch);

		assertTrue(tabManager.goBack());

		verify(player).enterInAppMiniPlayer();
		verify(webview, never()).goBack();
		assertSame(home, getCurrentTab(tabManager));
		assertSame(watch, getSuspendedWatch(tabManager));
	}

	private static TabManager createTabManager(final LitePlayer player,
	                                           final ExtensionManager extensionManager) {
		return createTabManager(player, extensionManager, mock(QueueWarmer.class));
	}

	private static TabManager createTabManager(final LitePlayer player,
	                                           final ExtensionManager extensionManager,
	                                           final QueueWarmer warmer) {
		FRAGMENTS.clear();
		final FragmentActivity activity = mock(FragmentActivity.class);
		final FragmentManager fragmentManager = mock(FragmentManager.class);
		final FragmentTransaction fragmentTransaction = mock(FragmentTransaction.class, Answers.RETURNS_SELF);
		final Runnable[] onCommitCallback = {null};
		lastFragmentTransaction = fragmentTransaction;
		when(activity.getSupportFragmentManager()).thenReturn(fragmentManager);
		when(fragmentManager.beginTransaction()).thenReturn(fragmentTransaction);
		when(fragmentTransaction.runOnCommit(any(Runnable.class))).thenAnswer(invocation -> {
			onCommitCallback[0] = invocation.getArgument(0);
			return fragmentTransaction;
		});
		when(fragmentTransaction.commit()).thenAnswer(invocation -> {
			if (onCommitCallback[0] != null) {
				final Runnable callback = onCommitCallback[0];
				onCommitCallback[0] = null;
				callback.run();
			}
			return 0;
		});
		return new TabManager(activity, () -> player, extensionManager, warmer) {
			@NonNull
			@Override
			protected YoutubeFragment createFragment(@NonNull final String url, @NonNull final String tag) {
				try {
					return FRAGMENTS.computeIfAbsent(url + "|" + tag, key -> {
						try {
							return TabManagerTest.createFragment(url, tag);
						} catch (final Exception e) {
							throw new RuntimeException(e);
						}
					});
				} catch (final RuntimeException e) {
					throw e;
				}
			}
		};
	}

	private static YoutubeFragment createFragment(final String url, final String tag) throws Exception {
		final YoutubeFragment fragment = new YoutubeFragment();
		setField(fragment, "url", url);
		setField(fragment, "mTag", tag);
		return fragment;
	}

	private static void seedTabs(final TabManager tabManager, final YoutubeFragment... fragments) throws Exception {
		final Deque<YoutubeFragment> tabs = getTabs(tabManager);
		tabs.clear();
		for (final YoutubeFragment fragment : fragments) {
			tabs.offer(fragment);
		}
		setField(tabManager, "tab", fragments[fragments.length - 1]);
	}

	@SuppressWarnings("unchecked")
	private static Deque<YoutubeFragment> getTabs(final TabManager tabManager) throws Exception {
		final Field field = TabManager.class.getDeclaredField("tabs");
		field.setAccessible(true);
		return (Deque<YoutubeFragment>) field.get(tabManager);
	}

	private static YoutubeFragment getCurrentTab(final TabManager tabManager) throws Exception {
		final Field field = TabManager.class.getDeclaredField("tab");
		field.setAccessible(true);
		return (YoutubeFragment) field.get(tabManager);
	}

	private static YoutubeFragment getSuspendedWatch(final TabManager tabManager) throws Exception {
		final Field field = TabManager.class.getDeclaredField("suspendedWatchFragment");
		field.setAccessible(true);
		return (YoutubeFragment) field.get(tabManager);
	}

	private static void setField(final Object target, final String fieldName, final Object value) throws Exception {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				final Field field = type.getDeclaredField(fieldName);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (final NoSuchFieldException ignored) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(fieldName);
	}
}
