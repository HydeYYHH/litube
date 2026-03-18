package com.hhst.youtubelite.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class NativeRequestDeduplicator<T> {

	private final Object lock = new Object();
	private final Map<String, InFlightRequest<T>> requestsByKey = new HashMap<>();
	private final Map<String, InFlightRequest<T>> requestsById = new HashMap<>();

	void enqueue(@NonNull final String requestId,
	             @Nullable final String dedupeKey,
	             @NonNull final Subscriber<T> subscriber,
	             @NonNull final Runner<T> runner) {
		final String key = normalizeKey(requestId, dedupeKey);
		final InFlightRequest<T> inFlightRequest;
		final boolean shouldStart;
		synchronized (lock) {
			InFlightRequest<T> existing = requestsByKey.get(key);
			if (existing == null) {
				existing = new InFlightRequest<>(key);
				requestsByKey.put(key, existing);
				shouldStart = true;
			} else {
				shouldStart = false;
			}
			existing.subscribers.put(requestId, subscriber);
			requestsById.put(requestId, existing);
			inFlightRequest = existing;
		}

		if (!shouldStart) return;

		final Canceller canceller = runner.start(payload -> complete(inFlightRequest, payload));
		final boolean cancelNow = attachCanceller(inFlightRequest, canceller);
		if (cancelNow) {
			canceller.cancel();
		}
	}

	void cancel(@Nullable final String requestId) {
		if (requestId == null || requestId.trim().isEmpty()) return;

		final Canceller canceller;
		synchronized (lock) {
			final InFlightRequest<T> inFlightRequest = requestsById.remove(requestId);
			if (inFlightRequest == null) return;

			inFlightRequest.subscribers.remove(requestId);
			if (inFlightRequest.completed || !inFlightRequest.subscribers.isEmpty()) return;

			inFlightRequest.completed = true;
			requestsByKey.remove(inFlightRequest.key, inFlightRequest);
			canceller = inFlightRequest.canceller;
			if (canceller == null) {
				inFlightRequest.cancelWhenAttached = true;
				return;
			}
		}
		canceller.cancel();
	}

	private boolean attachCanceller(@NonNull final InFlightRequest<T> inFlightRequest,
	                                @NonNull final Canceller canceller) {
		synchronized (lock) {
			if (inFlightRequest.completed || inFlightRequest.subscribers.isEmpty()) {
				inFlightRequest.completed = true;
				return true;
			}
			inFlightRequest.canceller = canceller;
			if (inFlightRequest.cancelWhenAttached) {
				inFlightRequest.completed = true;
				requestsByKey.remove(inFlightRequest.key, inFlightRequest);
				return true;
			}
			return false;
		}
	}

	private void complete(@NonNull final InFlightRequest<T> inFlightRequest, @NonNull final T payload) {
		final List<Subscriber<T>> subscribers = new ArrayList<>();
		synchronized (lock) {
			if (inFlightRequest.completed) return;

			inFlightRequest.completed = true;
			requestsByKey.remove(inFlightRequest.key, inFlightRequest);
			for (final Map.Entry<String, Subscriber<T>> entry : inFlightRequest.subscribers.entrySet()) {
				requestsById.remove(entry.getKey(), inFlightRequest);
				subscribers.add(entry.getValue());
			}
			inFlightRequest.subscribers.clear();
		}
		for (final Subscriber<T> subscriber : subscribers) {
			subscriber.onResult(payload);
		}
	}

	@NonNull
	private String normalizeKey(@NonNull final String requestId, @Nullable final String dedupeKey) {
		if (dedupeKey == null || dedupeKey.trim().isEmpty()) {
			return "request-id:" + requestId;
		}
		return dedupeKey;
	}

	interface Runner<T> {
		@NonNull
		Canceller start(@NonNull Completion<T> completion);
	}

	interface Completion<T> {
		void complete(@NonNull T payload);
	}

	interface Subscriber<T> {
		void onResult(@NonNull T payload);
	}

	interface Canceller {
		void cancel();
	}

	private static final class InFlightRequest<T> {
		@NonNull
		private final String key;
		@NonNull
		private final Map<String, Subscriber<T>> subscribers = new LinkedHashMap<>();
		@Nullable
		private Canceller canceller;
		private boolean cancelWhenAttached;
		private boolean completed;

		private InFlightRequest(@NonNull final String key) {
			this.key = Objects.requireNonNull(key);
		}
	}
}
