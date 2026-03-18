package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NativeRequestDeduplicatorTest {

	@Test
	public void enqueue_sameKeyStartsOneCallAndFansOutResult() {
		final NativeRequestDeduplicator<String> deduplicator = new NativeRequestDeduplicator<>();
		final AtomicInteger starts = new AtomicInteger();
		final List<String> firstResults = new ArrayList<>();
		final List<String> secondResults = new ArrayList<>();
		final AtomicReference<NativeRequestDeduplicator.Completion<String>> completionHolder = new AtomicReference<>();

		deduplicator.enqueue(
						"req-1",
						"GET https://m.youtube.com/youtubei/v1/guide",
						firstResults::add,
						completion -> {
							starts.incrementAndGet();
							completionHolder.set(completion);
							return () -> {
							};
						});
		deduplicator.enqueue(
						"req-2",
						"GET https://m.youtube.com/youtubei/v1/guide",
						secondResults::add,
						completion -> {
							starts.incrementAndGet();
							return () -> {
							};
						});

		assertEquals(1, starts.get());

		completionHolder.get().complete("ok");

		assertEquals(List.of("ok"), firstResults);
		assertEquals(List.of("ok"), secondResults);
	}

	@Test
	public void cancel_oneSubscriberKeepsSharedCallAlive() {
		final NativeRequestDeduplicator<String> deduplicator = new NativeRequestDeduplicator<>();
		final List<String> firstResults = new ArrayList<>();
		final List<String> secondResults = new ArrayList<>();
		final AtomicReference<NativeRequestDeduplicator.Completion<String>> completionHolder = new AtomicReference<>();
		final boolean[] canceled = {false};

		deduplicator.enqueue(
						"req-1",
						"POST https://m.youtube.com/youtubei/v1/next",
						firstResults::add,
						completion -> {
							completionHolder.set(completion);
							return () -> canceled[0] = true;
						});
		deduplicator.enqueue(
						"req-2",
						"POST https://m.youtube.com/youtubei/v1/next",
						secondResults::add,
						completion -> () -> canceled[0] = true);

		deduplicator.cancel("req-1");

		assertFalse(canceled[0]);

		completionHolder.get().complete("ok");

		assertTrue(firstResults.isEmpty());
		assertEquals(List.of("ok"), secondResults);
	}

	@Test
	public void cancel_lastSubscriberCancelsUnderlyingCall() {
		final NativeRequestDeduplicator<String> deduplicator = new NativeRequestDeduplicator<>();
		final List<String> results = new ArrayList<>();
		final AtomicReference<NativeRequestDeduplicator.Completion<String>> completionHolder = new AtomicReference<>();
		final boolean[] canceled = {false};

		deduplicator.enqueue(
						"req-1",
						"GET https://m.youtube.com/youtubei/v1/browse",
						results::add,
						completion -> {
							completionHolder.set(completion);
							return () -> canceled[0] = true;
						});

		deduplicator.cancel("req-1");

		assertTrue(canceled[0]);

		completionHolder.get().complete("ok");

		assertTrue(results.isEmpty());
	}
}
