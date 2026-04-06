package com.hhst.youtubelite.player.controller.gesture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlayerGestureListenerTest {
	@Test
	public void resolveDoubleTapAction_usesCenterThirdForPlaybackToggle() {
		assertEquals(PlayerGestureListener.DoubleTapAction.SEEK_BACKWARD,
				PlayerGestureListener.resolveDoubleTapAction(20f, 100f));
		assertEquals(PlayerGestureListener.DoubleTapAction.TOGGLE_PLAYBACK,
				PlayerGestureListener.resolveDoubleTapAction(50f, 100f));
		assertEquals(PlayerGestureListener.DoubleTapAction.SEEK_FORWARD,
				PlayerGestureListener.resolveDoubleTapAction(80f, 100f));
	}
}
