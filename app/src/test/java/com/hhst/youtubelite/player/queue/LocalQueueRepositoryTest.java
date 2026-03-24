package com.hhst.youtubelite.player.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LocalQueueRepositoryTest {
	private MMKV mmkv;
	private LocalQueueRepository repository;
	private Map<String, Object> store;

	@Before
	public void setUp() {
		mmkv = mock(MMKV.class);
		store = new HashMap<>();
		doAnswer(invocation -> {
			store.put(invocation.getArgument(0), invocation.getArgument(1));
			return true;
		}).when(mmkv).encode(anyString(), anyBoolean());
		doAnswer(invocation -> {
			store.put(invocation.getArgument(0), invocation.getArgument(1));
			return true;
		}).when(mmkv).encode(anyString(), anyString());
		doAnswer(invocation -> {
			final Object value = store.get(invocation.getArgument(0));
			return value instanceof Boolean ? value : invocation.getArgument(1);
		}).when(mmkv).decodeBool(anyString(), anyBoolean());
		doAnswer(invocation -> {
			final Object value = store.get(invocation.getArgument(0));
			return value instanceof String ? value : invocation.getArgument(1);
		}).when(mmkv).decodeString(anyString(), nullable(String.class));
		doAnswer(invocation -> {
			store.remove(invocation.getArgument(0));
			return null;
		}).when(mmkv).removeValueForKey(anyString());
		repository = new LocalQueueRepository(mmkv, new Gson());
	}

	@Test
	public void enabledFlag_roundTripsPersistedState() {
		repository.setEnabled(true);

		verify(mmkv).encode("local_queue_enabled", true);

		assertTrue(repository.isEnabled());
	}

	@Test
	public void add_replacesExistingVideoAndMovesItToEnd() {
		final QueueItem first = new QueueItem("video-1", "https://m.youtube.com/watch?v=video-1", "First", "Author A", "https://img/1.jpg");
		final QueueItem second = new QueueItem("video-2", "https://m.youtube.com/watch?v=video-2", "Second", "Author B", "https://img/2.jpg");
		final QueueItem updatedFirst = new QueueItem("video-1", "https://m.youtube.com/watch?v=video-1", "First Updated", "Author A", "https://img/1b.jpg");

		repository.add(first);
		repository.add(second);
		repository.add(updatedFirst);

		final List<QueueItem> items = repository.getItems();
		assertEquals(2, items.size());
		assertEquals("video-2", items.get(0).getVideoId());
		assertEquals("video-1", items.get(1).getVideoId());
		assertEquals("First Updated", items.get(1).getTitle());
	}

	@Test
	public void findRelative_returnsQueueEdgesWhenCurrentVideoMissing() {
		repository.add(new QueueItem("video-1", "https://m.youtube.com/watch?v=video-1", "First", "Author A", "https://img/1.jpg"));
		repository.add(new QueueItem("video-2", "https://m.youtube.com/watch?v=video-2", "Second", "Author B", "https://img/2.jpg"));

		assertEquals("video-1", repository.findRelative("missing", 1).getVideoId());
		assertEquals("video-2", repository.findRelative("missing", -1).getVideoId());
		assertNull(repository.findRelative("video-1", -1));
		assertNull(repository.findRelative("video-2", 1));
	}

	@Test
	public void clear_removesAllPersistedQueueItems() {
		repository.clear();

		verify(mmkv).removeValueForKey("local_queue_items");
	}

	@Test
	public void add_handlesBlankPersistedState() {
		store.put("local_queue_items", " ");

		repository.add(new QueueItem("video-1", "https://m.youtube.com/watch?v=video-1", "First", "Author A", "https://img/1.jpg"));

		assertFalse(repository.getItems().isEmpty());
	}
}
