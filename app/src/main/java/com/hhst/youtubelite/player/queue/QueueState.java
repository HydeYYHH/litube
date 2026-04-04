package com.hhst.youtubelite.player.queue;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot of queue enablement and items.
 */
public record QueueState(boolean enabled, @NonNull List<QueueItem> items) {
	public QueueState {
		List<QueueItem> copies = new ArrayList<>(items.size());
		for (QueueItem item : items) {
			copies.add(item.copy());
		}
		items = Collections.unmodifiableList(copies);
	}
}
