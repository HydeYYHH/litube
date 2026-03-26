package com.hhst.youtubelite.player.queue;

public enum QueueNav {
	INACTIVE(false, false, false, false),
	ACTIVE_WITH_PREVIOUS(true, true, true, true),
	ACTIVE_WITHOUT_PREVIOUS(true, true, true, false),
	ACTIVE_WITHOUT_PREVIOUS_OR_NEXT(true, false, true, false);

	private final boolean queueActive;
	private final boolean nextAndShuffleAvailable;
	private final boolean shuffleAvailable;
	private final boolean previousAvailable;

	QueueNav(final boolean queueActive,
	         final boolean nextAndShuffleAvailable,
	         final boolean shuffleAvailable,
	         final boolean previousAvailable) {
		this.queueActive = queueActive;
		this.nextAndShuffleAvailable = nextAndShuffleAvailable;
		this.shuffleAvailable = shuffleAvailable;
		this.previousAvailable = previousAvailable;
	}

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

	public boolean usesQueueForNext() {
		return queueActive && nextAndShuffleAvailable;
	}

	public boolean usesQueueForShuffle() {
		return queueActive && shuffleAvailable;
	}

	public boolean usesQueueForPrevious() {
		return queueActive && previousAvailable;
	}

	public boolean isNextActionEnabled() {
		return !queueActive || nextAndShuffleAvailable;
	}

	public boolean isPreviousActionEnabled() {
		return !queueActive || previousAvailable;
	}
}
