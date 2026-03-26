package com.hhst.youtubelite.player.queue;

@FunctionalInterface
public interface QueueInvalidationListener {
	void onQueueInvalidated();
}
