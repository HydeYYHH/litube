package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public final class ExtractionSession {
	private final Object lock = new Object();
	private final List<Cancellable> cancellables = new ArrayList<>();
	private boolean cancelled = false;

	public boolean isCancelled() {
		synchronized (lock) {
			return cancelled;
		}
	}

	public void register(@NonNull final Cancellable cancellable) {
		synchronized (lock) {
			if (!cancelled) {
				cancellables.add(cancellable);
				return;
			}
		}
		cancellable.cancel();
	}

	public void cancel() {
		final List<Cancellable> pending;
		synchronized (lock) {
			if (cancelled) return;
			cancelled = true;
			pending = new ArrayList<>(cancellables);
			cancellables.clear();
		}
		for (final Cancellable cancellable : pending) {
			cancellable.cancel();
		}
	}

	@FunctionalInterface
	public interface Cancellable {
		void cancel();
	}
}
