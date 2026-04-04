package com.hhst.youtubelite.di;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import com.google.gson.Gson;
import com.hhst.youtubelite.cache.WebViewCachePolicy;
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

/**
 * Hilt module that wires shared app dependencies.
 */
@Module
@InstallIn(SingletonComponent.class)
public class CommonModule {
	private static Dispatcher createDispatcher() {
		var d = new Dispatcher();
		d.setMaxRequests(128);
		d.setMaxRequestsPerHost(24);
		return d;
	}

	@Provides
	@Singleton
	public Cache provideOkHttpCache(@ApplicationContext Context ctx) {
		File dir = new File(ctx.getCacheDir(), "okhttp");
		return new Cache(dir, 512L * 1024L * 1024L);
	}

	@Provides
	@Singleton
	public OkHttpClient provideOkHttpClient(@NonNull Cache cache,
	                                        @NonNull WebViewCachePolicy policy) {
		return new OkHttpClient.Builder()
						.cache(cache)
						.dispatcher(createDispatcher())
						.addNetworkInterceptor(chain -> {
							var req = chain.request();
							Response resp = chain.proceed(req);
							return policy.maybeRewriteResponse(null, req, resp);
						})
						.followRedirects(true)
						.followSslRedirects(true)
						.retryOnConnectionFailure(true)
						.callTimeout(0L, TimeUnit.MILLISECONDS)
						.connectTimeout(20L, TimeUnit.SECONDS)
						.writeTimeout(30L, TimeUnit.SECONDS)
						.readTimeout(45L, TimeUnit.SECONDS)
						.connectionPool(new ConnectionPool(24, 10L, TimeUnit.MINUTES))
						.build();
	}

	@Provides
	@Singleton
	public Executor provideExecutor() {
		return new ThreadPoolExecutor(
						8,
						24,
						30,
						TimeUnit.SECONDS,
						new ArrayBlockingQueue<>(96));
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
	public SimpleCache provideSimpleCache(@ApplicationContext Context ctx) {
		File dir = new File(ctx.getCacheDir(), "player");
		var evictor = new LeastRecentlyUsedCacheEvictor(512L * 1024L * 1024L);
		return new SimpleCache(dir, evictor, new StandaloneDatabaseProvider(ctx));
	}
}
