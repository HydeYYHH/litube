package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CookieAccessCoordinatorTest {

	@Test
	public void getCookie_reusesFreshCachedValueUntilTtlExpires() {
		final FakeBackend backend = new FakeBackend();
		backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=first");
		final FakeClock clock = new FakeClock();
		final FakeScheduler scheduler = new FakeScheduler();
		final CookieAccessCoordinator coordinator = new CookieAccessCoordinator(
						backend,
						scheduler,
						clock::nowMillis,
						250L,
						200L);

		assertEquals("SID=first", coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals("SID=first", coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(1, backend.getCookieCalls);

		clock.advanceMillis(251L);
		assertEquals("SID=first", coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(2, backend.getCookieCalls);
	}

	@Test
	public void getCookie_reusesCachedValueAcrossQueryVariantsOfSamePath() {
		final FakeBackend backend = new FakeBackend();
		backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=first");
		final FakeClock clock = new FakeClock();
		final FakeScheduler scheduler = new FakeScheduler();
		final CookieAccessCoordinator coordinator = new CookieAccessCoordinator(
						backend,
						scheduler,
						clock::nowMillis,
						250L,
						200L);

		assertEquals("SID=first", coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals("SID=first", coordinator.getCookie("https://m.youtube.com/watch?v=2&list=abc"));
		assertEquals(1, backend.getCookieCalls);
	}

	@Test
	public void setCookie_invalidatesReadCacheAndCoalescesFlushes() {
		final FakeBackend backend = new FakeBackend();
		backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=old");
		final FakeClock clock = new FakeClock();
		final FakeScheduler scheduler = new FakeScheduler();
		final CookieAccessCoordinator coordinator = new CookieAccessCoordinator(
						backend,
						scheduler,
						clock::nowMillis,
						250L,
						200L);

		assertEquals("SID=old", coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(1, backend.getCookieCalls);

		coordinator.setCookie("https://m.youtube.com/watch?v=1", "SID=new");
		coordinator.setCookie("https://m.youtube.com/watch?v=1", "HSID=newer");

		assertEquals(1, scheduler.pendingCount());
		assertEquals(0, backend.flushCalls);

		backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=latest");
		assertEquals("SID=latest", coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(2, backend.getCookieCalls);

		scheduler.runNext();
		assertEquals(1, backend.flushCalls);
		assertEquals(0, scheduler.pendingCount());
	}

	@Test
	public void syncFromHeaders_ignoresEmptyCookieSets() {
		final FakeBackend backend = new FakeBackend();
		final FakeClock clock = new FakeClock();
		final FakeScheduler scheduler = new FakeScheduler();
		final CookieAccessCoordinator coordinator = new CookieAccessCoordinator(
						backend,
						scheduler,
						clock::nowMillis,
						250L,
						200L);

		coordinator.syncFromHeaders(
						"https://m.youtube.com/watch?v=1",
						new Headers.Builder()
										.add("Cache-Control", "no-cache")
										.build());

		assertEquals(0, backend.setCookieCalls.size());
		assertEquals(0, backend.flushCalls);
		assertEquals(0, scheduler.pendingCount());
		assertNull(coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(1, backend.getCookieCalls);
	}

	@Test
	public void syncFromResponse_appliesRedirectChainCookiesInChronologicalOrder() {
		final FakeBackend backend = new FakeBackend();
		final FakeClock clock = new FakeClock();
		final FakeScheduler scheduler = new FakeScheduler();
		final CookieAccessCoordinator coordinator = new CookieAccessCoordinator(
						backend,
						scheduler,
						clock::nowMillis,
						250L,
						200L);

		final Response response = new Response.Builder()
						.request(new Request.Builder().url("https://m.youtube.com/final").build())
						.protocol(Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.header("Set-Cookie", "SID=final")
						.body(okhttp3.ResponseBody.create(new byte[0], null))
						.priorResponse(new Response.Builder()
										.request(new Request.Builder().url("https://m.youtube.com/redirect").build())
										.protocol(Protocol.HTTP_1_1)
										.code(302)
										.message("Found")
										.header("Set-Cookie", "SID=redirect")
										.build())
						.build();

		coordinator.syncFromResponse(response);

		assertEquals(
						List.of(
										"https://m.youtube.com/redirect|SID=redirect",
										"https://m.youtube.com/final|SID=final"),
						backend.setCookieCalls);
		assertEquals(1, scheduler.pendingCount());
		assertEquals(0, backend.flushCalls);

		scheduler.runNext();
		assertEquals(1, backend.flushCalls);
	}

	private static final class FakeBackend implements CookieAccessCoordinator.Backend {
		private final Map<String, String> cookies = new HashMap<>();
		private final List<String> setCookieCalls = new ArrayList<>();
		private int getCookieCalls;
		private int flushCalls;

		@Override
		public String getCookie(String url) {
			getCookieCalls += 1;
			return cookies.get(url);
		}

		@Override
		public void setCookie(String url, String cookie) {
			setCookieCalls.add(url + "|" + cookie);
		}

		@Override
		public void flush() {
			flushCalls += 1;
		}
	}

	private static final class FakeScheduler implements CookieAccessCoordinator.Scheduler {
		private final Deque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void schedule(long delayMillis, Runnable task) {
			tasks.addLast(task);
		}

		private int pendingCount() {
			return tasks.size();
		}

		private void runNext() {
			final Runnable task = tasks.removeFirst();
			task.run();
		}
	}

	private static final class FakeClock {
		private long nowMillis;

		private long nowMillis() {
			return nowMillis;
		}

		private void advanceMillis(final long deltaMillis) {
			nowMillis += deltaMillis;
		}
	}
}
