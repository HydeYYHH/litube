package com.hhst.youtubelite.player.engine;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleExtractor;

import com.hhst.youtubelite.extractor.PlaybackDetails;
import com.hhst.youtubelite.extractor.PlaybackPlan;
import com.hhst.youtubelite.extractor.StreamCandidate;
import com.hhst.youtubelite.extractor.VideoDetails;

import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Component that handles app logic.
 */
@OptIn(markerClass = UnstableApi.class)
class PlaybackRunner {
	private static final String TAG = "YTLPlayback";

	private PlaybackRunner() {
	}

	@NonNull
	static MediaSource create(@NonNull PlayerDataSource sources,
	                          @NonNull PlaybackDetails details,
	                          @NonNull PlaybackPlan plan) {
		VideoDetails video = details.video();
		Log.d(TAG, "create mode=" + plan.getMode()
						+ " streamType=" + plan.getStreamType()
						+ " manifestUrl=" + plan.getManifestUrl()
						+ " video=" + describe(plan.getVideoCandidate())
						+ " audio=" + describe(plan.getAudioCandidate())
						+ " muxed=" + describe(plan.getMuxedCandidate()));
		MediaSource base = switch (plan.getMode()) {
			case LIVE_DASH -> sources.liveYoutubeDashFactory().createMediaSource(buildItem(plan, true));
			case LIVE_HLS -> sources.liveHlsFactory().createMediaSource(buildItem(plan, true));
			case ADAPTIVE -> merge(
							createStreamSource(sources, plan.getVideoCandidate() != null ? plan.getVideoCandidate().getVideoStream() : null, video.getDuration(), false),
							createStreamSource(sources, plan.getAudioCandidate() != null ? plan.getAudioCandidate().getAudioStream() : null, video.getDuration(), false));
			case MUXED -> require(createStreamSource(sources,
							plan.getMuxedCandidate() != null ? plan.getMuxedCandidate().getVideoStream() : null,
							video.getDuration(), false));
			case AUDIO_ONLY -> require(createStreamSource(sources,
							plan.getAudioCandidate() != null ? plan.getAudioCandidate().getAudioStream() : null,
							video.getDuration(), false));
			default -> throw new IllegalArgumentException(Engine.NO_PLAYABLE_SOURCE_MESSAGE);
		};
		return mergeSubtitles(sources, base, details.subtitles());
	}

	@NonNull
	private static MediaItem buildItem(@NonNull PlaybackPlan plan, boolean live) {
		String url = normalizePlayableUrl(plan.getManifestUrl());
		if (url == null) {
			throw new IllegalArgumentException(Engine.NO_PLAYABLE_SOURCE_MESSAGE);
		}
		final MediaItem.Builder builder = MediaItem.fromUri(url).buildUpon();
		if (live) {
			builder.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
							.setTargetOffsetMs(20_000L)
							.build());
		}
		return builder.build();
	}

	@NonNull
	private static MediaSource mergeSubtitles(@NonNull PlayerDataSource sources,
	                                          @NonNull MediaSource base,
	                                          @Nullable List<SubtitlesStream> subtitles) {
		if (subtitles == null || subtitles.isEmpty()) {
			return base;
		}
		List<MediaSource> items = new ArrayList<>();
		items.add(base);
		DefaultSubtitleParserFactory parserFactory = new DefaultSubtitleParserFactory();
		for (SubtitlesStream sub : subtitles) {
			if (sub.getFormat() == null) {
				continue;
			}
			MediaSource item = createSubtitleSource(sources, sub, parserFactory);
			if (item != null) {
				items.add(item);
			}
		}
		return items.size() == 1 ? base : new MergingMediaSource(true, items.toArray(new MediaSource[0]));
	}

	@Nullable
	private static MediaSource createSubtitleSource(@NonNull PlayerDataSource sources,
	                                                @NonNull SubtitlesStream sub,
	                                                @NonNull DefaultSubtitleParserFactory parserFactory) {
		String subtitleUrl = normalizePlayableUrl(sub.getContent());
		if (subtitleUrl == null) {
			return null;
		}
		String label = sub.getDisplayLanguageName();
		if (sub.isAutoGenerated()) {
			label += " (Auto-generated)";
		}
		Format format = new Format.Builder()
						.setSampleMimeType(sub.getFormat() != null ? sub.getFormat().mimeType : null)
						.setLanguage(sub.getLanguageTag())
						.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
						.setRoleFlags(C.ROLE_FLAG_SUBTITLE)
						.setLabel(label)
						.build();
		ExtractorsFactory extractorFactory = () -> new Extractor[]{new SubtitleExtractor(parserFactory.create(format), format)};
		return new ProgressiveMediaSource.Factory(sources.progressiveSource(false), extractorFactory)
						.setContinueLoadingCheckIntervalBytes(sources.loadIntervalBytes())
						.createMediaSource(MediaItem.fromUri(Uri.parse(subtitleUrl)));
	}

	@Nullable
	private static MediaSource createStreamSource(@NonNull PlayerDataSource sources,
	                                              @Nullable Stream stream,
	                                              final long durationSeconds,
	                                              final boolean live) {
		if (stream == null) {
			Log.d(TAG, "createStreamSource stream=null live=" + live);
			return null;
		}
		String url = normalizePlayableUrl(stream.getContent());
		if (url == null) {
			Log.w(TAG, "createStreamSource dropped invalid url=" + stream.getContent());
			return null;
		}
		try {
			if (stream.getItagItem() != null) {
				Log.d(TAG, "createStreamSource dash url=" + url
								+ " itagItem=true"
								+ " contentLength=" + stream.getItagItem().getContentLength()
								+ " live=" + live
								+ " durationMs=" + TimeUnit.SECONDS.toMillis(durationSeconds));
				String manifest = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
								url,
								stream.getItagItem(),
								TimeUnit.SECONDS.toMillis(durationSeconds) / 1_000);
				DashManifest parsed = new DashManifestParser().parse(
								Uri.parse(url),
								new ByteArrayInputStream(manifest.getBytes(StandardCharsets.UTF_8)));
				return sources.youtubeProgressiveDashFactory(live).createMediaSource(parsed);
			}
		} catch (Exception e) {
			Log.w(TAG, "createStreamSource dash manifest failed url=" + url, e);
		}
		Log.d(TAG, "createStreamSource progressive url=" + url + " live=" + live);
		final MediaItem.Builder builder = MediaItem.fromUri(url).buildUpon();
		if (stream.getFormat() != null) {
			builder.setMimeType(stream.getFormat().mimeType);
		}
		return sources.youtubeProgressiveFactory(live).createMediaSource(builder.build());
	}

	@NonNull
	private static MediaSource merge(@Nullable MediaSource video,
	                                 @Nullable MediaSource audio) {
		if (video != null && audio != null) {
			return new MergingMediaSource(video, audio);
		}
		if (video != null) {
			return video;
		}
		return require(audio);
	}

	@NonNull
	private static MediaSource require(@Nullable MediaSource source) {
		if (source == null) {
			throw new IllegalArgumentException(Engine.NO_PLAYABLE_SOURCE_MESSAGE);
		}
		return source;
	}

	@Nullable
	private static String normalizePlayableUrl(@Nullable String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(url.trim());
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (host == null || host.isEmpty()) {
				return null;
			}
			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
				return null;
			}
			return url.trim();
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	@NonNull
	private static String describe(@Nullable StreamCandidate candidate) {
		if (candidate == null) {
			return "null";
		}
		return candidate.getKind() + ":" + candidate.getProtocol() + ":" + candidate.getUrl();
	}
}
