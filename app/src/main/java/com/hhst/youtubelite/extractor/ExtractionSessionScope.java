package com.hhst.youtubelite.extractor;

import androidx.annotation.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Scope annotation for extractor session dependencies.
 */
@Singleton
public final class ExtractionSessionScope {
	private final InheritableThreadLocal<ExtractionSession> session = new InheritableThreadLocal<>();

	@Inject
	public ExtractionSessionScope() {
	}

	@Nullable
	public ExtractionSession get() {
		return session.get();
	}

	public void set(@Nullable ExtractionSession session) {
		if (session == null) {
			this.session.remove();
			return;
		}
		this.session.set(session);
	}
}
