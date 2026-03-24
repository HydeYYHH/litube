package com.hhst.youtubelite.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

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
		final TestFixture fixture = fixture();
		fixture.backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=first");

		assertEquals("SID=first", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals("SID=first", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(1, fixture.backend.getCookieCalls);

		fixture.clock.advanceMillis(251L);
		assertEquals("SID=first", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(2, fixture.backend.getCookieCalls);
	}

	@Test
	public void getCookie_reusesCachedValueAcrossQueryVariantsOfSamePath() {
		final TestFixture fixture = fixture();
		fixture.backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=first");

		assertEquals("SID=first", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals("SID=first", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=2&list=abc"));
		assertEquals(1, fixture.backend.getCookieCalls);
	}

	@Test
	public void getCookie_cachesNullResultUntilTtlExpires() {
		final TestFixture fixture = fixture();

		assertNull(fixture.coordinator.getCookie("https://m.youtube.com/watch?v=missing"));
		assertNull(fixture.coordinator.getCookie("https://m.youtube.com/watch?v=missing"));
		assertEquals(1, fixture.backend.getCookieCalls);

		fixture.clock.advanceMillis(251L);
		assertNull(fixture.coordinator.getCookie("https://m.youtube.com/watch?v=missing"));
		assertEquals(2, fixture.backend.getCookieCalls);
	}

	@Test
	public void getCookie_normalizesRootPathBetweenHostAndHostSlash() {
		final TestFixture fixture = fixture();
		fixture.backend.cookies.put("https://m.youtube.com", "SID=root");
		fixture.backend.cookies.put("https://m.youtube.com/", "SID=slash");

		assertEquals("SID=root", fixture.coordinator.getCookie("https://m.youtube.com"));
		assertEquals("SID=root", fixture.coordinator.getCookie("https://m.youtube.com/"));
		assertEquals(1, fixture.backend.getCookieCalls);
	}

	@Test
	public void getCookie_pathChangesBreakCacheReuse() {
		final TestFixture fixture = fixture();
		fixture.backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=watch");
		fixture.backend.cookies.put("https://m.youtube.com/channel/abc", "SID=channel");

		assertEquals("SID=watch", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals("SID=channel", fixture.coordinator.getCookie("https://m.youtube.com/channel/abc"));
		assertEquals(2, fixture.backend.getCookieCalls);
	}

	@Test
	public void setCookie_invalidatesReadCacheAndCoalescesFlushes() {
		final TestFixture fixture = fixture();
		fixture.backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=old");

		assertEquals("SID=old", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(1, fixture.backend.getCookieCalls);

		fixture.coordinator.setCookie("https://m.youtube.com/watch?v=1", "SID=new");
		fixture.coordinator.setCookie("https://m.youtube.com/watch?v=1", "HSID=newer");

		assertEquals(1, fixture.scheduler.pendingCount());
		assertEquals(0, fixture.backend.flushCalls);

		fixture.backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=latest");
		assertEquals("SID=latest", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(2, fixture.backend.getCookieCalls);

		fixture.scheduler.runNext();
		assertEquals(1, fixture.backend.flushCalls);
		assertEquals(0, fixture.scheduler.pendingCount());
	}

	@Test
	public void setCookie_rearmsFlushSchedulingAfterPreviousFlushExecutes() {
		final TestFixture fixture = fixture();

		fixture.coordinator.setCookie("https://m.youtube.com/watch?v=1", "SID=first");
		assertEquals(1, fixture.scheduler.pendingCount());
		fixture.scheduler.runNext();
		assertEquals(1, fixture.backend.flushCalls);

		fixture.coordinator.setCookie("https://m.youtube.com/watch?v=1", "SID=second");
		assertEquals(1, fixture.scheduler.pendingCount());
		fixture.scheduler.runNext();
		assertEquals(2, fixture.backend.flushCalls);
	}

	@Test
	public void syncFromHeaders_ignoresEmptyCookieSets() {
		final TestFixture fixture = fixture();

		fixture.coordinator.syncFromHeaders(
						"https://m.youtube.com/watch?v=1",
						new Headers.Builder()
										.add("Cache-Control", "no-cache")
										.build());

		assertEquals(0, fixture.backend.setCookieCalls.size());
		assertEquals(0, fixture.backend.flushCalls);
		assertEquals(0, fixture.scheduler.pendingCount());
		assertNull(fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(1, fixture.backend.getCookieCalls);
	}

	@Test
	public void syncFromHeaders_appliesMultipleSetCookieValues() {
		final TestFixture fixture = fixture();

		fixture.coordinator.syncFromHeaders(
						"https://m.youtube.com/watch?v=1",
						new Headers.Builder()
										.add("Set-Cookie", "SID=one")
										.add("Set-Cookie", "HSID=two")
										.build());

		assertEquals(
						List.of(
										"https://m.youtube.com/watch?v=1|SID=one",
										"https://m.youtube.com/watch?v=1|HSID=two"),
						fixture.backend.setCookieCalls);
		assertEquals(1, fixture.scheduler.pendingCount());
		fixture.scheduler.runNext();
		assertEquals(1, fixture.backend.flushCalls);
	}

	@Test
	public void syncFromHeaders_invalidatesCachedReadValue() {
		final TestFixture fixture = fixture();
		fixture.backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=old");

		assertEquals("SID=old", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(1, fixture.backend.getCookieCalls);

		fixture.coordinator.syncFromHeaders(
						"https://m.youtube.com/watch?v=1",
						new Headers.Builder().add("Set-Cookie", "SID=new").build());
		fixture.backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=latest");

		assertEquals("SID=latest", fixture.coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(2, fixture.backend.getCookieCalls);
	}

	@Test
	public void syncFromResponse_ignoresResponsesWithoutSetCookie() {
		final TestFixture fixture = fixture();
		final Response response = new Response.Builder()
						.request(new Request.Builder().url("https://m.youtube.com/final").build())
						.protocol(Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.addHeader("Cache-Control", "no-cache")
						.body(okhttp3.ResponseBody.create(new byte[0], null))
						.build();

		fixture.coordinator.syncFromResponse(response);

		assertEquals(0, fixture.backend.setCookieCalls.size());
		assertEquals(0, fixture.backend.flushCalls);
		assertEquals(0, fixture.scheduler.pendingCount());
	}

	@Test
	public void syncFromResponse_invalidatesCachedReadValue() {
		final TestFixture fixture = fixture();
		fixture.backend.cookies.put("https://m.youtube.com/final", "SID=old");
		assertEquals("SID=old", fixture.coordinator.getCookie("https://m.youtube.com/final"));
		assertEquals(1, fixture.backend.getCookieCalls);

		final Response response = new Response.Builder()
						.request(new Request.Builder().url("https://m.youtube.com/final").build())
						.protocol(Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.addHeader("Set-Cookie", "SID=new")
						.body(okhttp3.ResponseBody.create(new byte[0], null))
						.build();
		fixture.coordinator.syncFromResponse(response);
		fixture.backend.cookies.put("https://m.youtube.com/final", "SID=latest");

		assertEquals("SID=latest", fixture.coordinator.getCookie("https://m.youtube.com/final"));
		assertEquals(2, fixture.backend.getCookieCalls);
	}

	@Test
	public void syncFromResponse_appliesMultipleCookiesFromRedirectChainInChronologicalOrder() {
		final TestFixture fixture = fixture();

		final Response initialRedirect = new Response.Builder()
						.request(new Request.Builder().url("https://m.youtube.com/start").build())
						.protocol(Protocol.HTTP_1_1)
						.code(302)
						.message("Found")
						.addHeader("Set-Cookie", "SID=start")
						.addHeader("Set-Cookie", "HSID=start")
						.build();
		final Response secondRedirect = new Response.Builder()
						.request(new Request.Builder().url("https://m.youtube.com/redirect").build())
						.protocol(Protocol.HTTP_1_1)
						.code(302)
						.message("Found")
						.addHeader("Set-Cookie", "SID=redirect")
						.priorResponse(initialRedirect)
						.build();
		final Response response = new Response.Builder()
						.request(new Request.Builder().url("https://m.youtube.com/final").build())
						.protocol(Protocol.HTTP_1_1)
						.code(200)
						.message("OK")
						.addHeader("Set-Cookie", "SID=final")
						.addHeader("Set-Cookie", "HSID=final")
						.body(okhttp3.ResponseBody.create(new byte[0], null))
						.priorResponse(secondRedirect)
						.build();

		fixture.coordinator.syncFromResponse(response);

		assertEquals(
						List.of(
										"https://m.youtube.com/start|SID=start",
										"https://m.youtube.com/start|HSID=start",
										"https://m.youtube.com/redirect|SID=redirect",
										"https://m.youtube.com/final|SID=final",
										"https://m.youtube.com/final|HSID=final"),
						fixture.backend.setCookieCalls);
		assertEquals(1, fixture.scheduler.pendingCount());
		assertEquals(0, fixture.backend.flushCalls);

		fixture.scheduler.runNext();
		assertEquals(1, fixture.backend.flushCalls);
	}

	@Test
	public void constructor_clampsNegativeCacheTtlAndFlushDelayToZero() {
		final FakeBackend backend = new FakeBackend();
		final FakeClock clock = new FakeClock();
		final FakeScheduler scheduler = new FakeScheduler();
		final CookieAccessCoordinator coordinator = new CookieAccessCoordinator(
						backend,
						scheduler,
						clock::nowMillis,
						-1L,
						-1L);
		backend.cookies.put("https://m.youtube.com/watch?v=1", "SID=first");

		assertEquals("SID=first", coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		clock.advanceMillis(1L);
		assertEquals("SID=first", coordinator.getCookie("https://m.youtube.com/watch?v=1"));
		assertEquals(2, backend.getCookieCalls);

		coordinator.setCookie("https://m.youtube.com/watch?v=1", "SID=updated");
		assertEquals(List.of(0L), scheduler.delaysMillis);
	}

	private static TestFixture fixture() {
		return new TestFixture();
	}

	private static final class TestFixture {
		private final FakeBackend backend = new FakeBackend();
		private final FakeClock clock = new FakeClock();
		private final FakeScheduler scheduler = new FakeScheduler();
		private final CookieAccessCoordinator coordinator = new CookieAccessCoordinator(
						backend,
						scheduler,
						clock::nowMillis,
						250L,
						200L);
	}

	private static final class FakeBackend implements CookieAccessCoordinator.Backend {
		private final Map<String, String> cookies = new HashMap<>();
		private final List<String> setCookieCalls = new ArrayList<>();
		private int getCookieCalls;
		private int flushCalls;

		@Override
		public String getCookie(@NonNull String url) {
			getCookieCalls += 1;
			return cookies.get(url);
		}

		@Override
		public void setCookie(@NonNull String url, @NonNull String cookie) {
			setCookieCalls.add(url + "|" + cookie);
		}

		@Override
		public void flush() {
			flushCalls += 1;
		}
	}

	private static final class FakeScheduler implements CookieAccessCoordinator.Scheduler {
		private final Deque<Runnable> tasks = new ArrayDeque<>();
		private final List<Long> delaysMillis = new ArrayList<>();

		@Override
		public void schedule(long delayMillis, @NonNull Runnable task) {
			delaysMillis.add(delayMillis);
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
