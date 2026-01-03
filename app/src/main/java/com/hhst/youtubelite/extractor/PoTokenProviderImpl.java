package com.hhst.youtubelite.extractor;

import android.util.Log;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class PoTokenProviderImpl implements PoTokenProvider {

	private static final String TAG = "PoTokenProvider";
	private CountDownLatch latch = new CountDownLatch(1);
	private volatile PoTokenResult poToken = null;

	@Inject
	public PoTokenProviderImpl() {
	}

	// Explicit setter for poToken with notification
	public synchronized void setPoToken(PoTokenResult poToken) {
		this.poToken = poToken;
		latch.countDown();
	}

	@Nullable
	@Override
	public PoTokenResult getWebClientPoToken(String videoId) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) return null;
			latch = new CountDownLatch(1);
			Log.d(TAG, "PoToken: " + poToken.playerRequestPoToken + "\nvisitorData:" + poToken.visitorData);
			return poToken;
		} catch (InterruptedException e) {
			return null;
		}
	}

	@Nullable
	@Override
	public PoTokenResult getWebEmbedClientPoToken(String videoId) {
		return null;
	}

	@Nullable
	@Override
	public PoTokenResult getAndroidClientPoToken(String videoId) {
		return null;
	}

	@Nullable
	@Override
	public PoTokenResult getIosClientPoToken(String videoId) {
		return null;
	}
}
