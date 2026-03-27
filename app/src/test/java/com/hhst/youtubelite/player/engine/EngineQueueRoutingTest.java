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
		assertFalse(Engine.shouldUsePlaylistForNext(true, true));
	}

	@Test
	public void shuffle_usesWholeQueueWhenCurrentVideoIsMissing() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, false, false, false);

		assertTrue(Engine.shouldUseQueueForShuffle(availability));
		assertFalse(Engine.shouldUsePlaylistForShuffle(true, true));
	}

	@Test
	public void previous_blocksPlaylistWhenLocalQueueIsActiveAndCurrentVideoIsMissing() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, false, false, false);

		assertFalse(Engine.shouldUseQueueForPrevious(availability));
		assertFalse(Engine.shouldUsePlaylistForPrevious(true, true));
	}

	@Test
	public void next_wrapsToQueueHeadWhenCurrentVideoIsAtQueueTail() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, true, false, false);

		assertTrue(Engine.shouldUseQueueForNext(availability));
		assertFalse(Engine.shouldUsePlaylistForNext(true, true));
	}

	@Test
	public void next_blocksPlaylistWhenLocalQueueIsEnabledButEmpty() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, false, false, false, false);

		assertFalse(Engine.shouldUseQueueForNext(availability));
		assertFalse(Engine.shouldUsePlaylistForNext(true, true));
	}

	@Test
	public void shuffle_blocksPlaylistWhenLocalQueueIsEnabledButEmpty() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, false, false, false, false);

		assertFalse(Engine.shouldUseQueueForShuffle(availability));
		assertFalse(Engine.shouldUsePlaylistForShuffle(true, true));
	}

	@Test
	public void previous_prefersLocalQueueWhenQueuePreviousExists() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, true, false, false);

		assertTrue(Engine.shouldUseQueueForPrevious(availability));
		assertFalse(Engine.shouldUsePlaylistForPrevious(true, true));
	}

	@Test
	public void previous_blocksPlaylistWhenCurrentVideoIsAtQueueHeadAndLocalQueueIsActive() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, true, true, true, false);

		assertFalse(Engine.shouldUseQueueForPrevious(availability));
		assertFalse(Engine.shouldUsePlaylistForPrevious(true, true));
	}

	@Test
	public void previous_blocksPlaylistWhenLocalQueueIsEnabledButEmpty() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(true, false, false, false, false);

		assertFalse(Engine.shouldUseQueueForPrevious(availability));
		assertFalse(Engine.shouldUsePlaylistForPrevious(true, false));
	}

	@Test
	public void inactiveAvailability_fallsBackToWebPlaylistNavigation() {
		final QueueNav availability =
						Engine.resolveQueueNavigationAvailability(false, true, true, false, false);

		assertFalse(Engine.shouldUseQueueForNext(availability));
		assertFalse(Engine.shouldUseQueueForShuffle(availability));
		assertFalse(Engine.shouldUseQueueForPrevious(availability));
		assertTrue(Engine.shouldUsePlaylistForNext(false, true));
		assertTrue(Engine.shouldUsePlaylistForShuffle(false, true));
		assertTrue(Engine.shouldUsePlaylistForPrevious(false, true));
	}

	@Test
	public void next_doesNotUsePlaylistWhenPlaylistContextIsMissing() {
		assertFalse(Engine.shouldUsePlaylistForNext(false, false));
	}

	@Test
	public void shuffle_doesNotUsePlaylistWhenPlaylistContextIsMissing() {
		assertFalse(Engine.shouldUsePlaylistForShuffle(false, false));
	}

	@Test
	public void previous_keepsPlaylistBlockedWhenWatchPrevExistsAndLocalQueueIsActive() {
		final QueueNav availability = watch();

		assertTrue(availability.isPreviousActionEnabled());
		assertFalse(Engine.shouldUseQueueForPrevious(availability));
		assertFalse(Engine.shouldUsePlaylistForPrevious(true, false));
	}

	@Test
	public void previous_usesPlaybackBackWhenLocalQueueIsEnabledAndVideoIsOutsideQueueAndPlaylist() {
		assertTrue(Engine.shouldUseBackForPrevious(true, false, false, true));
	}

	@Test
	public void previous_blocksPlaybackBackWhenLocalQueueOwnsCurrentVideo() {
		assertFalse(Engine.shouldUseBackForPrevious(true, true, false, true));
	}

	@Test
	public void previous_blocksPlaybackBackWhenPlaylistExistsAndLocalQueueIsEnabled() {
		assertFalse(Engine.shouldUseBackForPrevious(true, false, true, true));
	}

	@Test
	public void previous_usesPlaybackBackWhenLocalQueueIsDisabledAndNoPlaylistExists() {
		assertTrue(Engine.shouldUseBackForPrevious(false, false, false, true));
	}

	@Test
	public void previous_doesNotUsePlaybackBackWhenPlaylistExists() {
		assertFalse(Engine.shouldUseBackForPrevious(false, false, true, true));
	}

	@Test
	public void previous_fallsBackToPlaybackBackOnlyWhenPlaylistContextIsMissing() {
		assertTrue(Engine.shouldFallbackToBackAfterPlaylistMiss("\"missing-playlist\""));
		assertTrue(Engine.shouldFallbackToBackAfterPlaylistMiss("\"missing-current-video-id\""));
		assertTrue(Engine.shouldFallbackToBackAfterPlaylistMiss("\"missing-current-video\""));
		assertFalse(Engine.shouldFallbackToBackAfterPlaylistMiss("\"target-out-of-range\""));
	}

	private static QueueNav watch() {
		return QueueNav.from(true, true, true, true, false, true);
	}
}
