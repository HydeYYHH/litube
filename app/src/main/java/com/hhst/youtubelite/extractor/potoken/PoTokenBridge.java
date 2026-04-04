package com.hhst.youtubelite.extractor.potoken;

import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Bridge between the WebView and PoToken extraction flow.
 */
@Singleton
public final class PoTokenBridge {
	public static final String JS_INTERFACE = "LitePoTokenBridge";

	@NonNull
	private final ConcurrentMap<String, CompletableFuture<String>> pendingRequests =
					new ConcurrentHashMap<>();

	@Inject
	public PoTokenBridge() {
	}

	@NonNull
	public CompletableFuture<String> prepare(@NonNull String requestId) {
		CompletableFuture<String> future = new CompletableFuture<>();
		CompletableFuture<String> previous = pendingRequests.put(requestId, future);
		if (previous != null) {
			previous.cancel(true);
		}
		return future;
	}

	@JavascriptInterface
	public void onSuccess(@NonNull String requestId, @NonNull String value) {
		CompletableFuture<String> future = pendingRequests.remove(requestId);
		if (future != null) {
			future.complete(value);
		}
	}

	@JavascriptInterface
	public void onError(@NonNull String requestId, @NonNull String error) {
		CompletableFuture<String> future = pendingRequests.remove(requestId);
		if (future != null) {
			future.completeExceptionally(new IllegalStateException(error));
		}
	}
}
