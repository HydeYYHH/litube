package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.util.List;

/**
 * Aggregated playback data assembled from extraction results.
 */
public record PlaybackDetails(@NonNull VideoDetails video,
                              @NonNull StreamCatalog catalog,
                              @NonNull DeliveryCatalog deliveries,
                              @NonNull PlaybackPlan plan,
                              @NonNull List<StreamSegment> segments,
                              @NonNull List<SubtitlesStream> subtitles) {
}
