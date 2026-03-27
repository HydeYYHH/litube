package com.hhst.youtubelite;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.support.v4.media.session.PlaybackStateCompat;

import com.hhst.youtubelite.player.queue.QueueNav;

import org.junit.Test;

public class PlaybackServiceTest {
	@Test
	public void playbackActions_excludeSkipToPreviousWhenAvailabilityBlocksPrevious() {
		final long actions = PlaybackService.playbackActionsFor(QueueNav.ACTIVE_WITHOUT_PREVIOUS);

		assertFalse(hasAction(actions, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));
		assertTrue(hasAction(actions, PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
		assertTrue(hasAction(actions, PlaybackStateCompat.ACTION_PLAY));
		assertTrue(hasAction(actions, PlaybackStateCompat.ACTION_PAUSE));
		assertTrue(hasAction(actions, PlaybackStateCompat.ACTION_PLAY_PAUSE));
		assertTrue(hasAction(actions, PlaybackStateCompat.ACTION_SEEK_TO));
	}

	@Test
	public void playbackActions_includeSkipToPreviousWhenAvailabilityAllowsIt() {
		assertTrue(hasAction(
				PlaybackService.playbackActionsFor(QueueNav.INACTIVE.withPrev(true)),
				PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));
		assertTrue(hasAction(
				PlaybackService.playbackActionsFor(QueueNav.ACTIVE_WITH_PREVIOUS),
				PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));
	}

	@Test
	public void playbackActions_excludeSkipToNextWhenAvailabilityBlocksNext() {
		assertFalse(hasAction(
				PlaybackService.playbackActionsFor(QueueNav.ACTIVE_WITHOUT_PREVIOUS_OR_NEXT),
				PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
	}

	@Test
	public void playbackActions_includeSkipToNextWhenAvailabilityAllowsIt() {
		assertTrue(hasAction(
				PlaybackService.playbackActionsFor(QueueNav.INACTIVE.withNext(true)),
				PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
		assertTrue(hasAction(
				PlaybackService.playbackActionsFor(QueueNav.ACTIVE_WITH_PREVIOUS),
				PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
		assertTrue(hasAction(
				PlaybackService.playbackActionsFor(QueueNav.ACTIVE_WITHOUT_PREVIOUS),
				PlaybackStateCompat.ACTION_SKIP_TO_NEXT));
	}

	@Test
	public void playbackActions_includeSkipToPreviousWhenFallbackExists() {
		final long actions = PlaybackService.playbackActionsFor(watch());

		assertTrue(hasAction(actions, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS));
	}

	private static boolean hasAction(final long actions, final long action) {
		return (actions & action) != 0L;
	}

	private static QueueNav watch() {
		return QueueNav.from(true, true, true, true, false, true);
	}
}
