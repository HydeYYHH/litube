package com.hhst.youtubelite.di;

import com.hhst.youtubelite.downloader.core.LiteDownloader;
import com.hhst.youtubelite.downloader.core.StreamDownloader;
import com.hhst.youtubelite.downloader.core.impl.LiteDownloaderImpl;
import com.hhst.youtubelite.downloader.core.impl.StreamDownloaderImpl;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

@Module
@InstallIn(SingletonComponent.class)
public abstract class DownloaderModule {

	@Binds
	@Singleton
	public abstract LiteDownloader bindLiteDownloader(LiteDownloaderImpl impl);

	@Binds
	@Singleton
	public abstract StreamDownloader bindStreamDownloader(StreamDownloaderImpl impl);

}
