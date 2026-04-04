package com.hhst.youtubelite.player.queue;

/**
 * Helper that finds the next queue item to play.
 */
public record QueueNav(boolean queue,
                       boolean next,
                       boolean shuffle,
                       boolean queuePrev,
                       boolean prev) {
	public static final QueueNav INACTIVE = new QueueNav(false, false, false, false, false);

	public boolean isNextActionEnabled() {
		return next;
	}

	public boolean isPreviousActionEnabled() {
		return queuePrev || prev;
	}
}
