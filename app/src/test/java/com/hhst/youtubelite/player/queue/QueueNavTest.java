package com.hhst.youtubelite.player.queue;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QueueNavTest {

	@Test
	public void isPreviousActionEnabled_returnsTrueWhenQueueOrBackIsEnabled() {
		assertTrue(new QueueNav(true, false, false, true, false).isPreviousActionEnabled());
		assertTrue(new QueueNav(false, false, false, false, true).isPreviousActionEnabled());
	}

	@Test
	public void isPreviousActionEnabled_returnsFalseWhenBothAreDisabled() {
		assertFalse(QueueNav.INACTIVE.isPreviousActionEnabled());
		assertFalse(new QueueNav(false, false, false, false, false).isPreviousActionEnabled());
	}
}
