package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class PlaybackDetailsMemoryCache {
	private static final int DEFAULT_MAX_ENTRIES = 16;
	private static final long DEFAULT_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);

	private final int maxEntries;
	private final long ttlMillis;
	private final LinkedHashMap<CacheKey, CacheEntry> entries;

	@Inject
	public PlaybackDetailsMemoryCache() {
		this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL_MILLIS);
	}

	PlaybackDetailsMemoryCache(final int maxEntries, final long ttlMillis) {
		if (maxEntries <= 0) {
			throw new IllegalArgumentException("maxEntries must be > 0");
		}
		if (ttlMillis <= 0L) {
			throw new IllegalArgumentException("ttlMillis must be > 0");
		}

		this.maxEntries = maxEntries;
		this.ttlMillis = ttlMillis;
		this.entries = new LinkedHashMap<>(maxEntries, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(final Map.Entry<CacheKey, CacheEntry> eldest) {
				return size() > PlaybackDetailsMemoryCache.this.maxEntries;
			}
		};
	}

	@Nullable
	public synchronized StreamDetails get(@NonNull final String videoId,
	                                      @NonNull final String fingerprint,
	                                      final long nowMillis) {
		final CacheKey key = new CacheKey(videoId, fingerprint);
		final CacheEntry entry = entries.get(key);
		if (entry == null) return null;
		if (isExpired(entry, nowMillis)) {
			entries.remove(key);
			return null;
		}
		return copyOf(entry.details);
	}

	public synchronized void put(@NonNull final String videoId,
	                             @NonNull final String fingerprint,
	                             @NonNull final StreamDetails details,
	                             final long nowMillis) {
		if (isLive(details.getStreamType())) return;
		purgeExpired(nowMillis);
		entries.put(new CacheKey(videoId, fingerprint), new CacheEntry(copyOf(details), nowMillis + ttlMillis));
	}

	public synchronized void invalidate(@NonNull final String videoId,
	                                    @NonNull final String fingerprint) {
		entries.remove(new CacheKey(videoId, fingerprint));
	}

	public synchronized void invalidateVideo(@NonNull final String videoId) {
		entries.keySet().removeIf(cacheKey -> videoId.equals(cacheKey.videoId));
	}

	private boolean isExpired(@NonNull final CacheEntry entry, final long nowMillis) {
		return entry.expiresAtMillis <= nowMillis;
	}

	private void purgeExpired(final long nowMillis) {
		entries.entrySet().removeIf(cacheKeyCacheEntryEntry -> isExpired(cacheKeyCacheEntryEntry.getValue(), nowMillis));
	}

	private boolean isLive(@Nullable final StreamType streamType) {
		return streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM;
	}

	@NonNull
	static StreamDetails copyOf(@NonNull final StreamDetails details) {
		return new StreamDetails(
						copyList(details.getVideoStreams()),
						copyList(details.getAudioStreams()),
						copyList(details.getSubtitles()),
						details.getDashUrl(),
						details.getHlsUrl(),
						details.getStreamType());
	}

	@Nullable
	private static <T> List<T> copyList(@Nullable final List<T> source) {
		return source == null ? null : new ArrayList<>(source);
	}

	private record CacheEntry(StreamDetails details, long expiresAtMillis) {
			private CacheEntry(@NonNull final StreamDetails details, final long expiresAtMillis) {
				this.details = details;
				this.expiresAtMillis = expiresAtMillis;
			}
		}

	private record CacheKey(String videoId, String fingerprint) {
			private CacheKey(@NonNull final String videoId, @NonNull final String fingerprint) {
				this.videoId = Objects.requireNonNull(videoId, "videoId");
				this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
			}

			@Override
			public boolean equals(final Object other) {
				if (this == other) return true;
				if (!(other instanceof CacheKey cacheKey)) return false;
				return Objects.equals(videoId, cacheKey.videoId)
								&& Objects.equals(fingerprint, cacheKey.fingerprint);
			}

	}
}
