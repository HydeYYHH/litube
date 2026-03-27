package com.hhst.youtubelite.player.controller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hhst.youtubelite.player.queue.QueueNav;

import org.junit.Test;

public class ControllerQueueNavigationTest {
	@Test
	public void shouldEnablePrevious_reflectsQueueBoundaries() {
		assertTrue(Controller.shouldEnablePrevious(QueueNav.INACTIVE));
		assertTrue(Controller.shouldEnablePrevious(QueueNav.ACTIVE_WITH_PREVIOUS));
		assertFalse(Controller.shouldEnablePrevious(QueueNav.ACTIVE_WITHOUT_PREVIOUS));
		assertFalse(Controller.shouldEnablePrevious(QueueNav.ACTIVE_WITHOUT_PREVIOUS_OR_NEXT));
	}

	@Test
	public void shouldEnableNext_reflectsQueueBoundaries() {
		assertTrue(Controller.shouldEnableNext(QueueNav.INACTIVE));
		assertTrue(Controller.shouldEnableNext(QueueNav.ACTIVE_WITH_PREVIOUS));
		assertTrue(Controller.shouldEnableNext(QueueNav.ACTIVE_WITHOUT_PREVIOUS));
		assertFalse(Controller.shouldEnableNext(QueueNav.ACTIVE_WITHOUT_PREVIOUS_OR_NEXT));
	}

	@Test
	public void previousButtonAlpha_usesDisabledAlphaWhenPreviousIsBlocked() {
		assertEquals(1.0f, Controller.previousButtonAlpha(QueueNav.INACTIVE), 0.0f);
		assertEquals(1.0f, Controller.previousButtonAlpha(QueueNav.ACTIVE_WITH_PREVIOUS), 0.0f);
		assertEquals(Controller.DISABLED_BUTTON_ALPHA, Controller.previousButtonAlpha(QueueNav.ACTIVE_WITHOUT_PREVIOUS), 0.0f);
		assertEquals(Controller.DISABLED_BUTTON_ALPHA, Controller.previousButtonAlpha(QueueNav.ACTIVE_WITHOUT_PREVIOUS_OR_NEXT), 0.0f);
	}

	@Test
	public void nextButtonAlpha_usesDisabledAlphaWhenNextIsBlocked() {
		assertEquals(1.0f, Controller.nextButtonAlpha(QueueNav.INACTIVE), 0.0f);
		assertEquals(1.0f, Controller.nextButtonAlpha(QueueNav.ACTIVE_WITH_PREVIOUS), 0.0f);
		assertEquals(1.0f, Controller.nextButtonAlpha(QueueNav.ACTIVE_WITHOUT_PREVIOUS), 0.0f);
		assertEquals(Controller.DISABLED_BUTTON_ALPHA, Controller.nextButtonAlpha(QueueNav.ACTIVE_WITHOUT_PREVIOUS_OR_NEXT), 0.0f);
	}

	@Test
	public void shouldEnablePrevious_keepsWatchPrevEnabled() {
		final QueueNav availability = watch();

		assertTrue(Controller.shouldEnablePrevious(availability));
		assertEquals(1.0f, Controller.previousButtonAlpha(availability), 0.0f);
	}

	private static QueueNav watch() {
		return QueueNav.from(true, true, true, true, false, true);
	}
}
