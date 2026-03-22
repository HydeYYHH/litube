package com.hhst.youtubelite.player.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hhst.youtubelite.R;

import org.junit.Test;

public class ControllerStateTest {

	@Test
	public void initialState_startsInNormalWithHiddenControls() {
		final ControllerState state = ControllerState.initial();
		final ControllerState.UiState uiState = state.toUiState(false, false);

		assertEquals(ControllerState.Mode.NORMAL, state.mode());
		assertFalse(state.controlsVisible());
		assertFalse(uiState.fullscreen());
		assertFalse(uiState.fullscreenLayout());
		assertFalse(uiState.otherControlsVisible());
		assertFalse(uiState.centerControlsVisible());
		assertFalse(uiState.progressVisible());
		assertFalse(uiState.lockButtonVisible());
		assertEquals(R.drawable.ic_fullscreen, uiState.fullscreenIconRes());
		assertEquals(R.drawable.ic_unlock, uiState.lockIconRes());
	}

	@Test
	public void enterFullscreen_switchesToUnlockedFullscreenAndShowsControls() {
		final ControllerState state = ControllerState.initial().enterFullscreen();
		final ControllerState.UiState uiState = state.toUiState(false, false);

		assertEquals(ControllerState.Mode.FULLSCREEN_UNLOCK, state.mode());
		assertTrue(state.controlsVisible());
		assertTrue(uiState.fullscreen());
		assertTrue(uiState.fullscreenLayout());
		assertTrue(uiState.otherControlsVisible());
		assertTrue(uiState.centerControlsVisible());
		assertTrue(uiState.progressVisible());
		assertTrue(uiState.lockButtonVisible());
		assertFalse(uiState.resetVisible());
		assertEquals(R.drawable.ic_fullscreen_exit, uiState.fullscreenIconRes());
		assertEquals(R.drawable.ic_unlock, uiState.lockIconRes());
	}

	@Test
	public void toggleLock_inFullscreenHidesOverlayControlsButKeepsLockAction() {
		final ControllerState state = ControllerState.initial()
						.enterFullscreen()
						.toggleLock();
		final ControllerState.UiState uiState = state.toUiState(false, true);

		assertEquals(ControllerState.Mode.FULLSCREEN_LOCK, state.mode());
		assertTrue(state.controlsVisible());
		assertTrue(uiState.fullscreen());
		assertFalse(uiState.otherControlsVisible());
		assertFalse(uiState.centerControlsVisible());
		assertFalse(uiState.progressVisible());
		assertTrue(uiState.lockButtonVisible());
		assertFalse(uiState.resetVisible());
		assertEquals(R.drawable.ic_lock, uiState.lockIconRes());
	}

	@Test
	public void exitFullscreenFromLockedState_returnsToNormalAndClearsLock() {
		final ControllerState state = ControllerState.initial()
						.enterFullscreen()
						.toggleLock()
						.exitFullscreen();
		final ControllerState.UiState uiState = state.toUiState(false, false);

		assertEquals(ControllerState.Mode.NORMAL, state.mode());
		assertTrue(state.controlsVisible());
		assertFalse(uiState.fullscreen());
		assertFalse(uiState.fullscreenLayout());
		assertTrue(uiState.otherControlsVisible());
		assertTrue(uiState.centerControlsVisible());
		assertTrue(uiState.progressVisible());
		assertFalse(uiState.lockButtonVisible());
		assertEquals(R.drawable.ic_unlock, uiState.lockIconRes());
		assertEquals(R.drawable.ic_fullscreen, uiState.fullscreenIconRes());
	}

	@Test
	public void pipState_hidesControlsAndRestoresPreviousModeWhenExited() {
		final ControllerState pipState = ControllerState.initial()
						.enterFullscreen()
						.toggleLock()
						.enterPip();
		final ControllerState.UiState pipUiState = pipState.toUiState(false, true);

		assertEquals(ControllerState.Mode.PIP, pipState.mode());
		assertFalse(pipState.controlsVisible());
		assertFalse(pipUiState.fullscreen());
		assertTrue(pipUiState.fullscreenLayout());
		assertFalse(pipUiState.otherControlsVisible());
		assertFalse(pipUiState.centerControlsVisible());
		assertFalse(pipUiState.progressVisible());
		assertFalse(pipUiState.lockButtonVisible());
		assertFalse(pipUiState.resetVisible());

		final ControllerState restoredState = pipState.exitPip();
		final ControllerState.UiState restoredUiState = restoredState.toUiState(false, false);

		assertEquals(ControllerState.Mode.FULLSCREEN_LOCK, restoredState.mode());
		assertTrue(restoredState.controlsVisible());
		assertTrue(restoredUiState.fullscreen());
		assertTrue(restoredUiState.fullscreenLayout());
		assertTrue(restoredUiState.lockButtonVisible());
		assertFalse(restoredUiState.otherControlsVisible());
	}

	@Test
	public void bufferingSuppressesCenterControlsUntilPlaybackReady() {
		final ControllerState state = ControllerState.initial().enterFullscreen();
		final ControllerState.UiState uiState = state.toUiState(true, false);

		assertTrue(uiState.otherControlsVisible());
		assertFalse(uiState.centerControlsVisible());
		assertTrue(uiState.progressVisible());
	}
}
