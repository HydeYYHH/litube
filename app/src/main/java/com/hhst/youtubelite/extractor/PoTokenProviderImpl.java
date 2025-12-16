package com.hhst.youtubelite.extractor;

import android.util.Log;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


public class PoTokenProviderImpl implements PoTokenProvider {

	private static final String TAG = "PoTokenProvider";
	private static PoTokenProviderImpl instance;
	private CountDownLatch latch = new CountDownLatch(1);
	private volatile PoTokenResult poToken = null;

	public static synchronized PoTokenProviderImpl getInstance() {
		if (instance == null) throw new IllegalStateException("PoTokenProviderImpl not initialized.");
		return instance;
	}

	public static synchronized PoTokenProviderImpl init() {
		if (instance == null) instance = new PoTokenProviderImpl();
		return instance;
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
