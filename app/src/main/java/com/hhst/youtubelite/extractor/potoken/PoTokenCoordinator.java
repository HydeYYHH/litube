package com.hhst.youtubelite.extractor.potoken;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.hhst.youtubelite.extractor.AuthContext;
import com.hhst.youtubelite.extractor.ExtractionSession;
import com.hhst.youtubelite.extractor.ExtractionSessionScope;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Loads and mints Web client PoTokens.
 */
@Singleton
public final class PoTokenCoordinator {
	private static final String REQUEST_KEY = "O43z0dpjhgX20SCx4KAo";
	private static final String KEY_PREFIX = "potoken.";
	private static final long INIT_TIMEOUT_MS = 4_000L;
	private static final long MINT_TIMEOUT_MS = 2_000L;

	@NonNull
	private final Gson gson;
	@NonNull
	private final PoTokenBridge poTokenBridge;
	@NonNull
	private final PoTokenHost poTokenHost;
	@NonNull
	private final ExtractionSessionScope scope;
	@NonNull
	private final OkHttpClient okHttpClient;
	@NonNull
	private final MMKV kv;
	@NonNull
	private final Object lock = new Object();

	@Nullable
	private PoTokenSession session;
	private long requestCounter;

	@Inject
	public PoTokenCoordinator(@NonNull Gson gson,
	                          @NonNull PoTokenBridge poTokenBridge,
	                          @NonNull PoTokenHost poTokenHost,
	                          @NonNull ExtractionSessionScope scope,
	                          @NonNull OkHttpClient okHttpClient,
	                          @NonNull MMKV kv) {
		this.gson = gson;
		this.poTokenBridge = poTokenBridge;
		this.poTokenHost = poTokenHost;
		this.scope = scope;
		this.okHttpClient = okHttpClient;
		this.kv = kv;
	}

	@Nullable
	public PoTokenResult getWebClientPoToken(@NonNull String videoId) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			return null;
		}
		synchronized (lock) {
			// Reuse the active session when it is still valid for this host generation.
			poTokenHost.prewarm();
			if (!poTokenHost.awaitReady(4_000L)) {
				session = null;
				return null;
			}
			long hostGeneration = poTokenHost.getGeneration();
			long nowMs = System.currentTimeMillis();
			PoTokenSession active = session;
			if (active == null || !active.matches(hostGeneration) || active.isExpired(nowMs)) {
				active = initializeSession(hostGeneration);
				session = active;
			}
			if (active == null) {
				return null;
			}

			String visitorData = fetchVisitorData();
			if (visitorData == null) {
				return null;
			}

			String playerPoToken = mintPoToken(hostGeneration, videoId);
			if (playerPoToken == null) {
				active = initializeSession(hostGeneration);
				session = active;
				if (active == null) {
					return null;
				}
				playerPoToken = mintPoToken(hostGeneration, videoId);
			}
			if (playerPoToken == null) {
				return null;
			}
			return new PoTokenResult(
							visitorData,
							playerPoToken,
							playerPoToken);
		}
	}

	@Nullable
	public PoTokenResult getAndroidClientPoToken(@NonNull String videoId) {
		return load("android", videoId);
	}

	@Nullable
	public PoTokenResult getIosClientPoToken(@NonNull String videoId) {
		return load("ios", videoId);
	}

	@Nullable
	private PoTokenResult load(@NonNull String client,
	                           @NonNull String videoId) {
		String player = read(client, videoId, "player");
		if (player == null) {
			return null;
		}
		String visitor = read(client, videoId, "visitor");
		String gvs = read(client, videoId, "gvs");
		return new PoTokenResult(
						visitor != null ? visitor : fetchVisitorData(),
						player,
						gvs != null ? gvs : player);
	}

	@Nullable
	private String read(@NonNull String client,
	                    @NonNull String videoId,
	                    @NonNull String kind) {
		String value = kv.decodeString(KEY_PREFIX + client + "." + videoId + "." + kind, null);
		if (value == null) {
			value = kv.decodeString(KEY_PREFIX + client + "." + kind, null);
		}
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	@Nullable
	private PoTokenSession initializeSession(long hostGeneration) {
		// Build and verify the session in order.
		if (!ensureScriptLoaded(hostGeneration)) {
			return null;
		}

		JsonArray createBody = new JsonArray();
		createBody.add(REQUEST_KEY);
		String createResponse =
						makeBotguardServiceRequest("https://www.youtube.com/api/jnn/v1/Create", gson.toJson(createBody));
		if (createResponse == null || !isSameGeneration(hostGeneration)) {
			return null;
		}

		String botguardResponse = runBotGuard(hostGeneration, createResponse);
		if (botguardResponse == null || !isSameGeneration(hostGeneration)) {
			return null;
		}

		JsonArray generateItBody = new JsonArray();
		generateItBody.add(REQUEST_KEY);
		generateItBody.add(botguardResponse);
		String generateItResponse = makeBotguardServiceRequest(
						"https://www.youtube.com/api/jnn/v1/GenerateIT", gson.toJson(generateItBody));
		if (generateItResponse == null || !isSameGeneration(hostGeneration)) {
			return null;
		}

		final GenerateItResult generateItResult;
		try {
			JsonArray array = JsonParser.parseString(generateItResponse).getAsJsonArray();
			generateItResult = new GenerateItResult(array.get(0).getAsString(), array.get(1).getAsLong());
		} catch (Exception ignored) {
			return null;
		}
		if (!setIntegrityToken(hostGeneration, generateItResult.integrityTokenBase64)) {
			return null;
		}

		long expiresAtMs = System.currentTimeMillis()
						+ Math.max(0L, TimeUnit.SECONDS.toMillis(generateItResult.expirationSeconds) - TimeUnit.MINUTES.toMillis(10L));
		return new PoTokenSession(hostGeneration, expiresAtMs);
	}

	private boolean ensureScriptLoaded(long hostGeneration) {
		String result = evaluateForResult(
						hostGeneration,
						"(function(){try{return window.__litePoToken ? 'ok' : 'missing';}"
										+ "catch(error){return 'error:' + (error && error.stack ? error.stack : error);}})();",
						INIT_TIMEOUT_MS);
		return Objects.equals(result, "ok") && isSameGeneration(hostGeneration);
	}

	@Nullable
	private String fetchVisitorData() {
		AuthContext auth = getAuth();
		if (auth != null && auth.visitorData() != null) {
			return auth.visitorData();
		}
		if (auth != null && auth.clientVersion() != null) {
			return fetchVisitorDataFromInnertube(auth.clientVersion());
		}
		try {
			return fetchVisitorDataFromInnertube(YoutubeParsingHelper.getClientVersion());
		} catch (Exception ignored) {
			return null;
		}
	}

	@Nullable
	private String fetchVisitorDataFromInnertube(@NonNull String clientVersion) {
		try {
			InnertubeClientRequestInfo requestInfo = InnertubeClientRequestInfo.ofWebClient();
			requestInfo.clientInfo.clientVersion = clientVersion;
			return YoutubeParsingHelper.getVisitorDataFromInnertube(
							requestInfo,
							Localization.DEFAULT,
							ContentCountry.DEFAULT,
							YoutubeParsingHelper.getYouTubeHeaders(),
							YoutubeParsingHelper.YOUTUBEI_V1_URL,
							null,
							false);
		} catch (Exception ignored) {
			return null;
		}
	}

	@Nullable
	private AuthContext getAuth() {
		ExtractionSession session = scope.get();
		return session != null ? session.getAuth() : null;
	}

	@Nullable
	private String runBotGuard(long hostGeneration,
	                           @NonNull String createResponse) {
		String requestId = nextRequestId("init");
		CompletableFuture<String> future = poTokenBridge.prepare(requestId);
		String evaluateResult = evaluateForResult(
						hostGeneration,
						"(function(){try{window.__litePoToken.runInit("
										+ gson.toJson(createResponse) + ","
										+ gson.toJson(requestId)
										+ ");return 'queued';}catch(error){return 'error:' + (error && error.stack ? error.stack : error);}})();",
						INIT_TIMEOUT_MS);
		if (!Objects.equals(evaluateResult, "queued")) return null;
		return awaitBridgeResult(future, hostGeneration, INIT_TIMEOUT_MS);
	}

	private boolean setIntegrityToken(long hostGeneration,
	                                  @NonNull String integrityTokenBase64) {
		String result = evaluateForResult(
						hostGeneration,
						"(function(){try{return window.__litePoToken.setIntegrityToken("
										+ gson.toJson(integrityTokenBase64)
										+ ") ? 'ok' : 'error';}catch(error){return 'error:' + (error && error.stack ? error.stack : error);}})();",
						INIT_TIMEOUT_MS);
		return Objects.equals(result, "ok") && isSameGeneration(hostGeneration);
	}

	@Nullable
	private String mintPoToken(long hostGeneration,
	                           @NonNull String identifier) {
		String requestId = nextRequestId("mint");
		CompletableFuture<String> future = poTokenBridge.prepare(requestId);
		String evaluateResult = evaluateForResult(
						hostGeneration,
						"(function(){try{window.__litePoToken.mint("
										+ gson.toJson(identifier) + ","
										+ gson.toJson(requestId)
										+ ");return 'queued';}catch(error){return 'error:' + (error && error.stack ? error.stack : error);}})();",
						MINT_TIMEOUT_MS);
		if (!Objects.equals(evaluateResult, "queued")) return null;
		return awaitBridgeResult(future, hostGeneration, MINT_TIMEOUT_MS);
	}

	@Nullable
	private String awaitBridgeResult(@NonNull CompletableFuture<String> future,
	                                 final long hostGeneration,
	                                 final long timeoutMs) {
		try {
			String result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
			return isSameGeneration(hostGeneration) ? result : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ExecutionException | TimeoutException e) {
			future.cancel(true);
			return null;
		}
	}

	@Nullable
	private String evaluateForResult(long hostGeneration,
	                                 @NonNull String script,
	                                 final long timeoutMs) {
		String rawValue = poTokenHost.evaluateJavascript(hostGeneration, script, timeoutMs);
		return PoTokenJsonUtils.normalizeEvaluateJavascriptResult(rawValue);
	}

	@Nullable
	private String makeBotguardServiceRequest(@NonNull String url,
	                                          @NonNull String body) {
		Request request = new Request.Builder()
						.url(url)
						.post(RequestBody.create(body, MediaType.get("application/json+protobuf")))
						.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
										+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3")
						.header("Accept", "application/json")
						.header("Content-Type", "application/json+protobuf")
						.header("x-goog-api-key", "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw")
						.header("x-user-agent", "grpc-web-javascript/0.1")
						.build();
		try (Response response = okHttpClient.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				return null;
			}
			return response.body().string();
		} catch (Exception e) {
			return null;
		}
	}

	private boolean isSameGeneration(long hostGeneration) {
		return poTokenHost.isCurrentGeneration(hostGeneration);
	}

	@NonNull
	private String nextRequestId(@NonNull String prefix) {
		requestCounter += 1L;
		return prefix + "-" + requestCounter;
	}

/**
 * Value object for app logic.
 */
	private record GenerateItResult(@NonNull String integrityTokenBase64, long expirationSeconds) {
	}
}
