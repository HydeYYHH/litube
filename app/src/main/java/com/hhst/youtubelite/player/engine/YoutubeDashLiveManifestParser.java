package com.hhst.youtubelite.player.engine;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.ProgramInformation;
import androidx.media3.exoplayer.dash.manifest.ServiceDescriptionElement;
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement;

import java.util.List;

/**
 * Mirrors NewPipe's YouTube live DASH parser workaround so playback starts from the newest
 * available period instead of an outdated one.
 */
@OptIn(markerClass = UnstableApi.class)
class YoutubeDashLiveManifestParser extends DashManifestParser {

	@SuppressWarnings("ParameterNumber")
	@NonNull
	@Override
	protected DashManifest buildMediaPresentationDescription(
					final long availabilityStartTimeMs,
					final long durationMs,
					final long minBufferTimeMs,
					final boolean dynamic,
					final long minUpdatePeriodMs,
					final long timeShiftBufferDepthMs,
					final long suggestedPresentationDelayMs,
					final long publishTimeMs,
					@Nullable ProgramInformation programInformation,
					@Nullable UtcTimingElement utcTiming,
					@Nullable ServiceDescriptionElement serviceDescription,
					@Nullable Uri location,
					@NonNull List<Period> periods) {
		return super.buildMediaPresentationDescription(
						0L,
						durationMs,
						minBufferTimeMs,
						dynamic,
						minUpdatePeriodMs,
						timeShiftBufferDepthMs,
						suggestedPresentationDelayMs,
						publishTimeMs,
						programInformation,
						utcTiming,
						serviceDescription,
						location,
						periods);
	}
}
