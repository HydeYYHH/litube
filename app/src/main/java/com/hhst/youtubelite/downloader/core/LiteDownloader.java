package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface LiteDownloader {

	void setCallback(@NonNull String vid, @Nullable ProgressCallback2 callback);

	void download(@NonNull Task task);

	void cancel(@NonNull String vid);
}