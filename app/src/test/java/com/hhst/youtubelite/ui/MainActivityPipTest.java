package com.hhst.youtubelite.ui;

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

		assertTrue(MainActivity.shouldEnterPictureInPictureOnUserLeaveHint(player, extensionManager, false));
	}

	@Test
	public void shouldEnterPictureInPictureOnUserLeaveHint_returnsFalseWhenFeatureDisabled() {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		when(extensionManager.isEnabled(com.hhst.youtubelite.Constant.ENABLE_PIP)).thenReturn(false);

		assertFalse(MainActivity.shouldEnterPictureInPictureOnUserLeaveHint(player, extensionManager, false));
	}

	@Test
	public void shouldEnterPictureInPictureOnUserLeaveHint_returnsFalseWhenPlayerDisallowsIt() {
		final LitePlayer player = mock(LitePlayer.class);
		final ExtensionManager extensionManager = mock(ExtensionManager.class);
		when(extensionManager.isEnabled(com.hhst.youtubelite.Constant.ENABLE_PIP)).thenReturn(true);
		when(player.shouldAutoEnterPictureInPicture()).thenReturn(false);

		assertFalse(MainActivity.shouldEnterPictureInPictureOnUserLeaveHint(player, extensionManager, false));
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
}
