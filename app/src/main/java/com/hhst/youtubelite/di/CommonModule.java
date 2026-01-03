package com.hhst.youtubelite.di;


import android.content.Context;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import com.google.gson.Gson;
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
import okhttp3.OkHttpClient;

@Module
@InstallIn(SingletonComponent.class)
public class CommonModule {
	@Provides
	@Singleton
	public OkHttpClient provideOkHttpClient() {
		return new OkHttpClient();
	}

	@Provides
	@Singleton
	public Executor provideExecutor() {
		return new ThreadPoolExecutor(
						4,
						12,
						30,
						TimeUnit.SECONDS,
						new ArrayBlockingQueue<>(32));
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
		final LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024);
		return new SimpleCache(cacheDir, evictor, new StandaloneDatabaseProvider(context));
	}
}
