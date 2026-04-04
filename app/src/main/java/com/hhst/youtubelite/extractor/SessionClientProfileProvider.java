package com.hhst.youtubelite.extractor;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Provider for client profile selection during extraction.
 */
@Singleton
public final class SessionClientProfileProvider
				implements YoutubeStreamExtractor.ClientProfileProvider {
	private final ExtractionSessionScope scope;

	@Inject
	public SessionClientProfileProvider(ExtractionSessionScope scope) {
		this.scope = scope;
	}

	@Override
	public boolean isLoggedIn() {
		AuthContext auth = auth();
		return auth != null && auth.loggedIn();
	}

	@Override
	public boolean isPremium() {
		AuthContext auth = auth();
		return auth != null && auth.premium();
	}

	@Nullable
	private AuthContext auth() {
		ExtractionSession session = scope.get();
		return session != null ? session.getAuth() : null;
	}
}
