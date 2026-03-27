package com.hhst.youtubelite.ui.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.hhst.youtubelite.player.queue.QueueItem;

import org.junit.Test;

import java.util.List;

public class QueueAdapterTest {
	@Test
	public void isPlaying_onlyForMatchingVideoId() {
		assertTrue(QueueAdapter.isPlaying("two", "two"));
		assertFalse(QueueAdapter.isPlaying("one", "two"));
		assertFalse(QueueAdapter.isPlaying(null, "two"));
		assertFalse(QueueAdapter.isPlaying("two", null));
	}

	@Test
	public void moveItem_reordersWithoutDroppingIntermediateItems() {
		final QueueAdapter adapter = createAdapter();
		adapter.replaceItems(List.of(item("one"), item("two"), item("three")), "two");

		assertTrue(adapter.moveItem(2, 0));
		assertEquals(List.of("three", "one", "two"), videoIds(adapter.snapshotItems()));
	}

	@Test
	public void removeItem_returnsTheRemovedQueueEntryAndShrinksTheList() {
		final QueueAdapter adapter = createAdapter();
		adapter.replaceItems(List.of(item("one"), item("two"), item("three")), "two");

		final QueueItem removed = adapter.removeItem(1);

		assertNotNull(removed);
		assertEquals("two", removed.getVideoId());
		assertEquals(List.of("one", "three"), videoIds(adapter.snapshotItems()));
	}

	@Test
	public void playingPos_returnsMatchedItemIndex() {
		final QueueAdapter adapter = createAdapter();
		adapter.replaceItems(List.of(item("one"), item("two"), item("three")), "two");

		assertEquals(1, adapter.playingPos());
	}

	@Test
	public void playingPos_returnsMissingWhenItemIsAbsent() {
		final QueueAdapter adapter = createAdapter();
		adapter.replaceItems(List.of(item("one"), item("two"), item("three")), "four");

		assertEquals(-1, adapter.playingPos());
	}

	private static QueueAdapter createAdapter() {
		return new QueueAdapter(new QueueAdapter.Actions() {
			@Override
			public void onPlayRequested(@NonNull final QueueItem item) {
			}

			@Override
			public void onDeleteRequested(@NonNull final QueueItem item) {
			}
		});
	}

	private static QueueItem item(final String videoId) {
		return new QueueItem(videoId, "https://www.youtube.com/watch?v=" + videoId, videoId, "author-" + videoId, null);
	}

	private static List<String> videoIds(final List<QueueItem> items) {
		return items.stream().map(QueueItem::getVideoId).toList();
	}
}
