package com.hhst.youtubelite.player.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class ControllerMachineTest {
	private ControllerMachine stateMachine;

	@Before
	public void setUp() {
		stateMachine = new ControllerMachine();
	}

	@Test
	public void initialState_startsInNormalMode() {
		assertEquals(ControllerMachine.State.NORMAL, stateMachine.getState());
		assertFalse(stateMachine.isFullscreen());
		assertFalse(stateMachine.isLocked());
	}

	@Test
	public void fullscreenLockCycle_updatesControllerState() {
		stateMachine.enterFullscreen();
		assertEquals(ControllerMachine.State.FULLSCREEN_UNLOCKED, stateMachine.getState());

		stateMachine.toggleLock();
		assertEquals(ControllerMachine.State.FULLSCREEN_LOCKED, stateMachine.getState());
		assertTrue(stateMachine.isLocked());

		stateMachine.exitFullscreen();
		assertEquals(ControllerMachine.State.NORMAL, stateMachine.getState());
		assertFalse(stateMachine.isLocked());
	}

	@Test
	public void pictureInPicture_restoresPreviousFullscreenState() {
		stateMachine.enterFullscreen();
		stateMachine.toggleLock();

		stateMachine.onPictureInPictureModeChanged(true);
		assertEquals(ControllerMachine.State.PIP, stateMachine.getState());

		stateMachine.onPictureInPictureModeChanged(false);
		assertEquals(ControllerMachine.State.FULLSCREEN_LOCKED, stateMachine.getState());
		assertTrue(stateMachine.isLocked());
	}

	@Test
	public void renderState_forFullscreenLocked_showsOnlyLockButton() {
		stateMachine.enterFullscreen();
		stateMachine.toggleLock();

		final ControllerMachine.RenderState renderState =
						stateMachine.buildRenderState(true, false, true);

		assertTrue(renderState.controlsVisible());
		assertFalse(renderState.showCenterControls());
		assertFalse(renderState.showOtherControls());
		assertFalse(renderState.showProgressBar());
		assertFalse(renderState.showResetButton());
		assertTrue(renderState.showLockButton());
		assertTrue(renderState.locked());
	}

	@Test
	public void renderState_forPictureInPicture_hidesControllerUi() {
		stateMachine.enterFullscreen();
		stateMachine.onPictureInPictureModeChanged(true);

		final ControllerMachine.RenderState renderState =
						stateMachine.buildRenderState(true, false, true);

		assertFalse(renderState.controlsVisible());
		assertFalse(renderState.showCenterControls());
		assertFalse(renderState.showOtherControls());
		assertFalse(renderState.showProgressBar());
		assertFalse(renderState.showResetButton());
		assertFalse(renderState.showLockButton());
	}
}
