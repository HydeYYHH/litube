package com.hhst.youtubelite.di;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import com.google.gson.Gson;
import com.hhst.youtubelite.util.WebResourceUtils;
import com.tencent.mmkv.MMKV;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Response;

@Module
@InstallIn(SingletonComponent.class)
public class CommonModule {
	static final long OKHTTP_CACHE_BYTES = 256L * 1024L * 1024L;
	static final int OKHTTP_MAX_IDLE_CONNECTIONS = 10;
	static final long OKHTTP_KEEP_ALIVE_MINUTES = 5L;
	static final int OKHTTP_MAX_REQUESTS = 64;
	static final int OKHTTP_MAX_REQUESTS_PER_HOST = 12;
	static final long OKHTTP_CONNECT_TIMEOUT_SECONDS = 10L;
	static final long OKHTTP_WRITE_TIMEOUT_SECONDS = 12L;
	static final long OKHTTP_READ_TIMEOUT_SECONDS = 12L;
	static final long OKHTTP_CALL_TIMEOUT_SECONDS = 20L;
	static final long PLAYER_CACHE_BYTES = 256L * 1024L * 1024L;

	@Provides
	@Singleton
	public OkHttpClient provideOkHttpClient(@ApplicationContext final Context context) {
		final Cache cache = createOkHttpCache(context);
		return new OkHttpClient.Builder()
						.cache(cache)
						.dispatcher(createDispatcher(OKHTTP_MAX_REQUESTS, OKHTTP_MAX_REQUESTS_PER_HOST))
						.addNetworkInterceptor(chain -> {
							final var request = chain.request();
							final Response response = chain.proceed(request);
							if (!"GET".equalsIgnoreCase(request.method())) {
								return response;
							}
							if (!WebResourceUtils.shouldForceCache(request.url())) {
								return response;
							}
							final Response.Builder builder = response.newBuilder()
											.removeHeader("Pragma")
											.removeHeader("Cache-Control")
											.header("Cache-Control", "public, max-age=" + WebResourceUtils.WEBVIEW_CACHE_MAX_AGE_SECONDS + ", immutable");
							final String originalCacheControl = response.header("Cache-Control");
							if (originalCacheControl != null && !originalCacheControl.isEmpty()) {
								builder.header(WebResourceUtils.ORIGINAL_CACHE_CONTROL_HEADER, originalCacheControl);
							}
							return builder.build();
						})
						.followRedirects(true)
						.followSslRedirects(true)
						.retryOnConnectionFailure(true)
						.callTimeout(OKHTTP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.connectTimeout(OKHTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.writeTimeout(OKHTTP_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.readTimeout(OKHTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						.connectionPool(createConnectionPool())
						.build();
	}

	@Provides
	@Singleton
	public Executor provideExecutor() {
		return new ThreadPoolExecutor(4, 12, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
	}

	@Provides
	@Singleton
	public Gson provideGson() {
		return new Gson();
	}

	@Provides
	@Singleton
	public MMKV provideMMKV() {
		return MMKV.defaultMMKV();
	}

	@Provides
	@Singleton
	@UnstableApi
	public SimpleCache provideSimpleCache(@ApplicationContext Context context) {
		final File cacheDir = new File(context.getCacheDir(), "player");
		final LeastRecentlyUsedCacheEvictor evictor = createPlayerCacheEvictor();
		return new SimpleCache(cacheDir, evictor, new StandaloneDatabaseProvider(context));
	}

	Cache createOkHttpCache(@NonNull final Context context) {
		final File cacheDir = new File(context.getCacheDir(), "okhttp");
		return new Cache(cacheDir, OKHTTP_CACHE_BYTES);
	}

	ConnectionPool createConnectionPool() {
		return new ConnectionPool(OKHTTP_MAX_IDLE_CONNECTIONS, OKHTTP_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES);
	}

	@NonNull
	Dispatcher createDispatcher(final int maxRequests, final int maxRequestsPerHost) {
		final Dispatcher dispatcher = new Dispatcher();
		dispatcher.setMaxRequests(maxRequests);
		dispatcher.setMaxRequestsPerHost(maxRequestsPerHost);
		return dispatcher;
	}

	@UnstableApi
	LeastRecentlyUsedCacheEvictor createPlayerCacheEvictor() {
		return new LeastRecentlyUsedCacheEvictor(PLAYER_CACHE_BYTES);
	}
}
