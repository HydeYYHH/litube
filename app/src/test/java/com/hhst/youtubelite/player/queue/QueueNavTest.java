package com.hhst.youtubelite.player.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class QueueNavTest {
	@Test
	public void availability_keepsNextActiveWhenCurrentVideoIsMissing() {
		final QueueNav availability =
				QueueNav.from(true, true, false, false, false);

		assertEquals(QueueNav.ACTIVE_WITHOUT_PREVIOUS, availability);
		assertFalse(availability.usesQueueForPrevious());
		assertTrue(availability.usesQueueForNext());
		assertTrue(availability.usesQueueForShuffle());
		assertFalse(availability.isPreviousActionEnabled());
		assertTrue(availability.isNextActionEnabled());
	}

	@Test
	public void availability_isInactiveWhenQueueIsDisabledOrEmpty() {
		assertEquals(QueueNav.INACTIVE, QueueNav.from(false, true, true, false, false));
		assertEquals(QueueNav.INACTIVE, QueueNav.from(true, false, false, false, false));
	}

	@Test
	public void availability_blocksPreviousAtQueueHead() {
		final QueueNav availability =
				QueueNav.from(true, true, true, true, false);

		assertTrue(availability.usesQueueForNext());
		assertTrue(availability.usesQueueForShuffle());
		assertFalse(availability.usesQueueForPrevious());
		assertTrue(availability.isNextActionEnabled());
		assertFalse(availability.isPreviousActionEnabled());
	}

	@Test
	public void availability_keepsNextEnabledAtQueueTailForMultiItemQueue() {
		final QueueNav availability =
				QueueNav.from(true, true, true, false, false);

		assertEquals(QueueNav.ACTIVE_WITH_PREVIOUS, availability);
		assertTrue(availability.usesQueueForNext());
		assertTrue(availability.usesQueueForShuffle());
		assertTrue(availability.usesQueueForPrevious());
		assertTrue(availability.isNextActionEnabled());
		assertTrue(availability.isPreviousActionEnabled());
	}

	@Test
	public void availability_blocksBothDirectionalButtonsForSingleItemQueue() {
		final QueueNav availability =
				QueueNav.from(true, true, true, true, true);

		assertEquals(QueueNav.ACTIVE_WITHOUT_PREVIOUS_OR_NEXT, availability);
		assertFalse(availability.usesQueueForNext());
		assertTrue(availability.usesQueueForShuffle());
		assertFalse(availability.usesQueueForPrevious());
		assertFalse(availability.isNextActionEnabled());
		assertFalse(availability.isPreviousActionEnabled());
	}
}
