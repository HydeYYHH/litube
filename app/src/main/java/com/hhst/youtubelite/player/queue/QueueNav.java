package com.hhst.youtubelite.player.queue;

public record QueueNav(boolean queue,
                       boolean next,
                       boolean shuffle,
                       boolean qPrev,
                       boolean prev) {
	public static final QueueNav INACTIVE = new QueueNav(false, false, false, false, false);
	public static final QueueNav ACTIVE_WITH_PREVIOUS = new QueueNav(true, true, true, true, false);
	public static final QueueNav ACTIVE_WITHOUT_PREVIOUS = new QueueNav(true, true, true, false, false);
	public static final QueueNav ACTIVE_WITHOUT_PREVIOUS_OR_NEXT = new QueueNav(true, false, true, false, false);

	public static QueueNav from(final boolean enabled,
	                            final boolean hasItems,
	                            final boolean inQueue,
	                            final boolean atHead,
	                            final boolean atTail) {
		if (!enabled || !hasItems) {
			return INACTIVE;
		}
		if (!inQueue) {
			return ACTIVE_WITHOUT_PREVIOUS;
		}
		if (atHead && atTail) {
			return ACTIVE_WITHOUT_PREVIOUS_OR_NEXT;
		}
		if (atHead) {
			return ACTIVE_WITHOUT_PREVIOUS;
		}
		return ACTIVE_WITH_PREVIOUS;
	}

	public static QueueNav from(final boolean enabled,
	                            final boolean hasItems,
	                            final boolean inQueue,
	                            final boolean atHead,
	                            final boolean atTail,
	                            final boolean prev) {
		return from(enabled, hasItems, inQueue, atHead, atTail).withPrev(prev);
	}

	public QueueNav withNext(final boolean next) {
		return this.next == next ? this : new QueueNav(queue, next, shuffle, qPrev, prev);
	}

	public QueueNav withPrev(final boolean prev) {
		return this.prev == prev ? this : new QueueNav(queue, next, shuffle, qPrev, prev);
	}

	public boolean usesQueueForNext() {
		return queue && next;
	}

	public boolean usesQueueForShuffle() {
		return queue && shuffle;
	}

	public boolean usesQueueForPrevious() {
		return queue && qPrev;
	}

	public boolean hasPreviousFallback() {
		return prev;
	}

	public boolean isNextActionEnabled() {
		return next;
	}

	public boolean isPreviousActionEnabled() {
		return qPrev || prev;
	}
}
