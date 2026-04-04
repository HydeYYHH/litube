package com.hhst.youtubelite.extractor.potoken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Store for the latest PoToken context.
 */
@Singleton
public final class PoTokenContextStore {
	@NonNull
	private final AtomicReference<PoTokenWebViewContext> snapshot = new AtomicReference<>();

	@Inject
	public PoTokenContextStore() {
	}

	public void update(@Nullable PoTokenWebViewContext nextSnapshot) {
		if (nextSnapshot != null) {
			snapshot.set(nextSnapshot);
		}
	}

	@Nullable
	public PoTokenWebViewContext getSnapshot() {
		return snapshot.get();
	}

}
