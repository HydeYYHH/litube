package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;

public record Task(@NonNull String vid, @Nullable VideoStream video, @Nullable AudioStream audio,
                   @Nullable SubtitlesStream subtitle, @Nullable String thumbnail, @NonNull String fileName,
                   @NonNull File desDir, int threadCount) {

}
