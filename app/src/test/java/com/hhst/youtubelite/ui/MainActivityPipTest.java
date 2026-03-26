package com.hhst.youtubelite.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hhst.youtubelite.extension.ExtensionManager;
import com.hhst.youtubelite.player.LitePlayer;

import org.junit.Test;

public class MainActivityPipTest {
	@Test
	public void shouldEnterPictureInPictureOnUserLeaveHint_returnsTrueWhenEnabledAndPlayerAllowsIt() {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		when(extensionManager.isEnabled(com.hhst.youtubelite.Constant.ENABLE_PIP)).thenReturn(true);
		when(player.shouldAutoEnterPictureInPicture()).thenReturn(true);

		assertTrue(MainActivity.shouldEnterPictureInPictureOnUserLeaveHint(player, extensionManager, false, false));
	}

	@Test
	public void shouldEnterPictureInPictureOnUserLeaveHint_returnsFalseWhenFeatureDisabled() {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		when(extensionManager.isEnabled(com.hhst.youtubelite.Constant.ENABLE_PIP)).thenReturn(false);

		assertFalse(MainActivity.shouldEnterPictureInPictureOnUserLeaveHint(player, extensionManager, false, false));
	}

	@Test
	public void shouldEnterPictureInPictureOnUserLeaveHint_returnsFalseWhenPlayerDisallowsIt() {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		when(extensionManager.isEnabled(com.hhst.youtubelite.Constant.ENABLE_PIP)).thenReturn(true);
		when(player.shouldAutoEnterPictureInPicture()).thenReturn(false);

		assertFalse(MainActivity.shouldEnterPictureInPictureOnUserLeaveHint(player, extensionManager, false, false));
	}

	@Test
	public void shouldEnterPictureInPictureOnUserLeaveHint_returnsFalseWhenSuppressedForInAppNavigation() {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		when(extensionManager.isEnabled(com.hhst.youtubelite.Constant.ENABLE_PIP)).thenReturn(true);
		when(player.shouldAutoEnterPictureInPicture()).thenReturn(true);

		assertFalse(MainActivity.shouldEnterPictureInPictureOnUserLeaveHint(player, extensionManager, false, true));
	}

	@Test
	public void shouldSuppressPictureInPictureForStartedActivity_onlyForExplicitInAppActivities() {
		final android.content.Intent inAppIntent = mock(android.content.Intent.class);
		final android.content.ComponentName inAppComponent = mock(android.content.ComponentName.class);
		final android.content.Intent externalIntent = mock(android.content.Intent.class);
		when(inAppIntent.getComponent()).thenReturn(inAppComponent);
		when(inAppComponent.getPackageName()).thenReturn("com.hhst.youtubelite");
		when(externalIntent.getComponent()).thenReturn(null);

		assertTrue(MainActivity.shouldSuppressPictureInPictureForStartedActivity(inAppIntent, "com.hhst.youtubelite"));
		assertFalse(MainActivity.shouldSuppressPictureInPictureForStartedActivity(externalIntent, "com.hhst.youtubelite"));
		assertFalse(MainActivity.shouldSuppressPictureInPictureForStartedActivity(null, "com.hhst.youtubelite"));
	}

	@Test
	public void dispatchPictureInPictureModeChanged_forwardsToPlayer() {
		final LitePlayer player = mock(LitePlayer.class);

		MainActivity.dispatchPictureInPictureModeChanged(player, true);

		verify(player).onPictureInPictureModeChanged(true);
	}

	@Test
	public void shouldShowQueueUi_hidesQueueEntryPointsDuringPip() {
		assertTrue(MainActivity.shouldShowQueueUi(false));
		assertFalse(MainActivity.shouldShowQueueUi(true));
	}

	@Test
	public void shouldReleasePlayerOnDestroy_skipsReleaseDuringConfigurationChange() {
		assertFalse(MainActivity.shouldReleasePlayerOnDestroy(true));
		assertTrue(MainActivity.shouldReleasePlayerOnDestroy(false));
	}

	@Test
	public void shouldRestoreMiniPlayerOnResume_onlyWhenMiniPlayerSessionExists() {
		assertTrue(MainActivity.shouldRestoreMiniPlayerOnResume(true, false));
		assertFalse(MainActivity.shouldRestoreMiniPlayerOnResume(false, false));
		assertFalse(MainActivity.shouldRestoreMiniPlayerOnResume(true, true));
	}

	@Test
	public void shouldSuspendMiniPlayerOnStop_onlyWhenLeavingActivityWithMiniPlayerSession() {
		assertTrue(MainActivity.shouldSuspendMiniPlayerOnStop(true, false, false));
		assertFalse(MainActivity.shouldSuspendMiniPlayerOnStop(false, false, false));
		assertFalse(MainActivity.shouldSuspendMiniPlayerOnStop(true, true, false));
		assertFalse(MainActivity.shouldSuspendMiniPlayerOnStop(true, false, true));
	}

	@Test
	public void sheetMax_usesSpaceBelowEmbeddedPlayer() {
		assertEquals(1180, MainActivity.sheetMax(1920, 0, 740, false));
	}

	@Test
	public void sheetMax_fallsBackToFullHeightWhenPlayerBottomIsUnavailable() {
		assertEquals(1920, MainActivity.sheetMax(1920, 0, 0, false));
		assertEquals(1920, MainActivity.sheetMax(1920, 0, 1920, false));
		assertEquals(1920, MainActivity.sheetMax(1920, 0, 2200, false));
	}

	@Test
	public void sheetMax_reservesTopInsetForMiniPlayerMode() {
		assertEquals(1880, MainActivity.sheetMax(1920, 40, 1680, true));
	}

	@Test
	public void sheetPad_includesSystemBarInset() {
		assertEquals(36, MainActivity.sheetPad(4, 32));
		assertEquals(4, MainActivity.sheetPad(4, 0));
	}

	@Test
	public void listPad_preservesTrailingScrollSpace() {
		assertEquals(36, MainActivity.listPad(4, 32, 24));
		assertEquals(28, MainActivity.listPad(4, 0, 24));
	}

	@Test
	public void queueAnchor_biasesTowardUpperMiddle() {
		assertEquals(340, MainActivity.queueAnchor(900, 40));
		assertEquals(0, MainActivity.queueAnchor(0, 0));
	}
}
