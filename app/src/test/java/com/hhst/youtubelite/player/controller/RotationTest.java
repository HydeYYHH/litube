package com.hhst.youtubelite.player.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;

import org.junit.Test;

public class RotationTest {
	@Test
	public void shouldEnterFs_onlyInLandscapeWatch() {
		assertTrue(Controller.shouldEnterFs(
						true,
						true,
						true,
						false,
						false,
						false,
						Configuration.ORIENTATION_LANDSCAPE,
						false));

		assertFalse(Controller.shouldEnterFs(
						true,
						false,
						true,
						false,
						false,
						false,
						Configuration.ORIENTATION_LANDSCAPE,
						false));
		assertFalse(Controller.shouldEnterFs(
						false,
						true,
						true,
						false,
						false,
						false,
						Configuration.ORIENTATION_LANDSCAPE,
						false));
		assertFalse(Controller.shouldEnterFs(
						true,
						true,
						true,
						false,
						false,
						false,
						Configuration.ORIENTATION_PORTRAIT,
						false));
	}

	@Test
	public void shouldEnterFs_respectsBlocks() {
		assertFalse(Controller.shouldEnterFs(
						true,
						true,
						true,
						true,
						false,
						false,
						Configuration.ORIENTATION_LANDSCAPE,
						false));
		assertFalse(Controller.shouldEnterFs(
						true,
						true,
						true,
						false,
						true,
						false,
						Configuration.ORIENTATION_LANDSCAPE,
						false));
		assertFalse(Controller.shouldEnterFs(
						true,
						true,
						true,
						false,
						false,
						true,
						Configuration.ORIENTATION_LANDSCAPE,
						false));
		assertFalse(Controller.shouldEnterFs(
						true,
						true,
						true,
						false,
						false,
						false,
						Configuration.ORIENTATION_LANDSCAPE,
						true));
	}

	@Test
	public void shouldExitFs_onlyInPortrait() {
		assertTrue(Controller.shouldExitFs(
						true,
						true,
						Configuration.ORIENTATION_PORTRAIT));

		assertFalse(Controller.shouldExitFs(
						true,
						false,
						Configuration.ORIENTATION_PORTRAIT));
		assertFalse(Controller.shouldExitFs(
						true,
						true,
						Configuration.ORIENTATION_LANDSCAPE));
		assertFalse(Controller.shouldExitFs(
						false,
						true,
						Configuration.ORIENTATION_PORTRAIT));
	}

	@Test
	public void shouldLockPortrait_onlyInLandscape() {
		assertTrue(Controller.shouldLockPortrait(
						true,
						true,
						Configuration.ORIENTATION_LANDSCAPE));

		assertFalse(Controller.shouldLockPortrait(
						true,
						true,
						Configuration.ORIENTATION_PORTRAIT));
		assertFalse(Controller.shouldLockPortrait(
						true,
						false,
						Configuration.ORIENTATION_LANDSCAPE));
		assertFalse(Controller.shouldLockPortrait(
						false,
						true,
						Configuration.ORIENTATION_LANDSCAPE));
	}

	@Test
	public void fsOrientation_prefersAutoFs() {
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
						Controller.fsOrientation(true, true));
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
						Controller.fsOrientation(false, true));
		assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
						Controller.fsOrientation(false, false));
	}
}
