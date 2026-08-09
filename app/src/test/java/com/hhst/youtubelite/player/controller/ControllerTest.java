package com.hhst.youtubelite.player.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import org.junit.Test;

public class ControllerTest {

	@Test
	public void rotationCanEnterFullscreenFromVisiblePortraitPlayback() {
		assertTrue(Controller.shouldEnterFs(
						true,
						true,
						true,
						false,
						false,
						false,
						Configuration.ORIENTATION_PORTRAIT,
						Configuration.ORIENTATION_LANDSCAPE,
						true,
						false));
	}

	@Test
	public void portraitRotationCanExitAutoFullscreen() {
		assertTrue(Controller.shouldExitFs(
						true,
						true,
						Configuration.ORIENTATION_UNDEFINED,
						Configuration.ORIENTATION_PORTRAIT));
	}

	@Test
	public void manualExitRequestsPortraitOnlyFromLandscapeFullscreen() {
		assertTrue(Controller.shouldRequestPortraitOnManualExit(
						true,
						Configuration.ORIENTATION_LANDSCAPE,
						Configuration.ORIENTATION_PORTRAIT));
		assertFalse(Controller.shouldRequestPortraitOnManualExit(
						true,
						Configuration.ORIENTATION_PORTRAIT,
						Configuration.ORIENTATION_PORTRAIT));
	}

	@Test
	public void manualExitDoesNotForcePortraitWhenEnteredFromLandscape() {
		assertFalse(Controller.shouldRequestPortraitOnManualExit(
						true,
						Configuration.ORIENTATION_LANDSCAPE,
						Configuration.ORIENTATION_LANDSCAPE));
		// Cleared state (e.g. after a PiP round-trip) must not force portrait either.
		assertFalse(Controller.shouldRequestPortraitOnManualExit(
						true,
						Configuration.ORIENTATION_LANDSCAPE,
						Configuration.ORIENTATION_UNDEFINED));
	}

	@Test
	public void fullscreenOrientationLockFollowsSystemWhenDisabled() {
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
						Controller.fsOrientation(false, false, false));
	}

	@Test
	public void fullscreenOrientationLockLocksLandscapeForLandscapeVideo() {
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
						Controller.fsOrientation(false, false, true));
	}

	@Test
	public void fullscreenOrientationLockLocksPortraitForPortraitVideo() {
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
						Controller.fsOrientation(false, true, true));
	}

	@Test
	public void autoFullscreenAlwaysFollowsSystem() {
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
						Controller.fsOrientation(true, false, true));
	}
}
