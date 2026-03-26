package com.hhst.youtubelite.player.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hhst.youtubelite.player.queue.QueueNav;

import org.junit.Test;

public class EngineQueueRoutingTest {
	@Test
	public void next_entersQueueAtHeadWhenCurrentVideoIsMissing() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, false, false, false);

		assertTrue(Engine.shouldUseQueueForNext(availability));
		assertFalse(Engine.shouldUseWebPlaylistForNext(availability));
	}

	@Test
	public void shuffle_usesWholeQueueWhenCurrentVideoIsMissing() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, false, false, false);

		assertTrue(Engine.shouldUseQueueForShuffle(availability));
		assertFalse(Engine.shouldUseWebPlaylistForShuffle(availability));
	}

	@Test
	public void previous_isBlockedWhenCurrentVideoIsMissingButQueueIsActive() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, false, false, false);

		assertFalse(Engine.shouldUseQueueForPrevious(availability));
		assertFalse(Engine.shouldUseWebPlaylistForPrevious(availability));
	}

	@Test
	public void next_wrapsToQueueHeadWhenCurrentVideoIsAtQueueTail() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, true, false, false);

		assertTrue(Engine.shouldUseQueueForNext(availability));
		assertFalse(Engine.shouldUseWebPlaylistForNext(availability));
	}

	@Test
	public void previous_isBlockedWhenCurrentVideoIsAtQueueHead() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, true, true, false);

		assertFalse(Engine.shouldUseQueueForPrevious(availability));
		assertFalse(Engine.shouldUseWebPlaylistForPrevious(availability));
	}

	@Test
	public void inactiveAvailability_fallsBackToWebPlaylistNavigation() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(false, true, true, false, false);

		assertFalse(Engine.shouldUseQueueForNext(availability));
		assertFalse(Engine.shouldUseQueueForShuffle(availability));
		assertFalse(Engine.shouldUseQueueForPrevious(availability));
		assertTrue(Engine.shouldUseWebPlaylistForNext(availability));
		assertTrue(Engine.shouldUseWebPlaylistForShuffle(availability));
		assertTrue(Engine.shouldUseWebPlaylistForPrevious(availability));
	}
}
