package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.util.concurrent.CompletableFuture;

public interface StreamDownloader {
    CompletableFuture<File> download(@NonNull String url, @NonNull File output, @Nullable ProgressCallback callback);
    void setMaxThreadCount(int count);
    void pause(@NonNull String url);
    void resume(@NonNull String url);
    void cancel(@NonNull String url);
}