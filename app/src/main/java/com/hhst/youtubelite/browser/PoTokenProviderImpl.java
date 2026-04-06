package com.hhst.youtubelite.browser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class PoTokenProviderImpl implements PoTokenProvider {
	@Nullable
	private PoTokenResult poToken;

	@Inject
	public PoTokenProviderImpl() {
	}

	@Override
	public void setPoToken(@NonNull final PoTokenResult poToken) {
		this.poToken = poToken;
	}

	@Nullable
	public PoTokenResult getPoToken() {
		return poToken;
	}
}
