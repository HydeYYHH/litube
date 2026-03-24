package com.hhst.youtubelite.browser;

import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import okhttp3.Headers;
import okhttp3.Response;

final class CookieAccessCoordinator {

	private static final long DEFAULT_READ_CACHE_TTL_MILLIS = 250L;
	private static final long DEFAULT_FLUSH_DELAY_MILLIS = 150L;
	private static final ScheduledExecutorService FLUSH_EXECUTOR = Executors.newSingleThreadScheduledExecutor(
					runnable -> {
						final Thread thread = new Thread(runnable, "web-cookie-flush");
						thread.setDaemon(true);
						return thread;
					});

	@NonNull
	private final Backend backend;
	@NonNull
	private final Scheduler scheduler;
	@NonNull
	private final LongSupplier nowMillisSupplier;
	private final long readCacheTtlMillis;
	private final long flushDelayMillis;
	@NonNull
	private final Map<String, CacheEntry> cookieCache = new ConcurrentHashMap<>();
	@NonNull
	private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

	CookieAccessCoordinator(@NonNull final Backend backend,
	                        @NonNull final Scheduler scheduler,
	                        @NonNull final LongSupplier nowMillisSupplier,
	                        final long readCacheTtlMillis,
	                        final long flushDelayMillis) {
		this.backend = Objects.requireNonNull(backend);
		this.scheduler = Objects.requireNonNull(scheduler);
		this.nowMillisSupplier = Objects.requireNonNull(nowMillisSupplier);
		this.readCacheTtlMillis = Math.max(0L, readCacheTtlMillis);
		this.flushDelayMillis = Math.max(0L, flushDelayMillis);
	}

	@NonNull
	static CookieAccessCoordinator create(@NonNull final CookieManager cookieManager) {
		return new CookieAccessCoordinator(
						new CookieManagerBackend(cookieManager),
						new ExecutorScheduler(FLUSH_EXECUTOR),
						System::currentTimeMillis,
						DEFAULT_READ_CACHE_TTL_MILLIS,
						DEFAULT_FLUSH_DELAY_MILLIS);
	}

	@Nullable
	String getCookie(@NonNull final String url) {
		final long nowMillis = nowMillisSupplier.getAsLong();
		final String cacheKey = normalizeCacheKey(url);
		final CacheEntry cachedEntry = cookieCache.get(cacheKey);
		if (cachedEntry != null && (nowMillis - cachedEntry.createdAtMillis) <= readCacheTtlMillis) {
			return cachedEntry.value;
		}

		final String cookie = backend.getCookie(url);
		cookieCache.put(cacheKey, new CacheEntry(cookie, nowMillis));
		return cookie;
	}

	void setCookie(@NonNull final String url, @NonNull final String cookie) {
		backend.setCookie(url, cookie);
		invalidateCache();
		scheduleFlush();
	}

	void syncFromHeaders(@NonNull final String url, @NonNull final Headers headers) {
		boolean cookiesUpdated = false;
		for (final String cookie : headers.values("Set-Cookie")) {
			backend.setCookie(url, cookie);
			cookiesUpdated = true;
		}
		if (!cookiesUpdated) return;
		invalidateCache();
		scheduleFlush();
	}

	void syncFromResponse(@NonNull final Response response) {
		final List<Response> responseChain = new ArrayList<>();
		Response current = response;
		while (current != null) {
			responseChain.add(current);
			current = current.priorResponse();
		}

		boolean cookiesUpdated = false;
		for (int i = responseChain.size() - 1; i >= 0; i--) {
			final Response chainResponse = responseChain.get(i);
			for (final String cookie : chainResponse.headers().values("Set-Cookie")) {
				backend.setCookie(chainResponse.request().url().toString(), cookie);
				cookiesUpdated = true;
			}
		}
		if (!cookiesUpdated) return;
		invalidateCache();
		scheduleFlush();
	}

	private void invalidateCache() {
		cookieCache.clear();
	}

	private void scheduleFlush() {
		if (!flushScheduled.compareAndSet(false, true)) return;
		scheduler.schedule(flushDelayMillis, () -> {
			flushScheduled.set(false);
			backend.flush();
		});
	}

	@NonNull
	private String normalizeCacheKey(@NonNull final String url) {
		try {
			final URI uri = URI.create(url);
			final String scheme = uri.getScheme();
			final String authority = uri.getRawAuthority();
			if (scheme == null || authority == null) return url;
			final String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
			return scheme + "://" + authority + path;
		} catch (final RuntimeException ignored) {
			return url;
		}
	}

	interface Backend {
		@Nullable
		String getCookie(@NonNull String url);

		void setCookie(@NonNull String url, @NonNull String cookie);

		void flush();
	}

	interface Scheduler {
		void schedule(long delayMillis, @NonNull Runnable task);
	}

	private record CacheEntry(@Nullable String value, long createdAtMillis) {
	}

	private record CookieManagerBackend(@NonNull CookieManager cookieManager) implements Backend {
			private CookieManagerBackend(@NonNull final CookieManager cookieManager) {
				this.cookieManager = Objects.requireNonNull(cookieManager);
			}

			@Override
			@Nullable
			public String getCookie(@NonNull final String url) {
				return cookieManager.getCookie(url);
			}

			@Override
			public void setCookie(@NonNull final String url, @NonNull final String cookie) {
				cookieManager.setCookie(url, cookie);
			}

			@Override
			public void flush() {
				cookieManager.flush();
			}
		}

	private record ExecutorScheduler(
					@NonNull ScheduledExecutorService executor) implements Scheduler {
			private ExecutorScheduler(@NonNull final ScheduledExecutorService executor) {
				this.executor = Objects.requireNonNull(executor);
			}

			@Override
			public void schedule(final long delayMillis, @NonNull final Runnable task) {
				executor.schedule(task, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
			}
		}
}
