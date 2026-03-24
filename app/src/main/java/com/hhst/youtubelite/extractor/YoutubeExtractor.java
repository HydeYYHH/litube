package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class YoutubeExtractor {
	private final MMKV cache;
	private final Gson gson;
	private final PlaybackDetailsMemoryCache playbackDetailsMemoryCache;
	private final StreamInfoFetcher streamInfoFetcher;
	private final PlaybackCacheContextProvider playbackCacheContextProvider;
	private final LongSupplier currentTimeMillisSupplier;

	@Inject
	public YoutubeExtractor(@NonNull final DownloaderImpl downloader,
	                        @NonNull final MMKV cache,
	                        @NonNull final Gson gson,
	                        @NonNull final PlaybackDetailsMemoryCache playbackDetailsMemoryCache) {
		this(
						cache,
						gson,
						playbackDetailsMemoryCache,
						(videoID, session) -> downloader.withExtractionSession(
										() -> StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoID),
										session),
						new DownloaderPlaybackCacheContextProvider(downloader),
						System::currentTimeMillis);
		NewPipe.init(downloader);
	}

	YoutubeExtractor(@NonNull final MMKV cache,
	                 @NonNull final Gson gson,
	                 @NonNull final PlaybackDetailsMemoryCache playbackDetailsMemoryCache,
	                 @NonNull final StreamInfoFetcher streamInfoFetcher,
	                 @NonNull final PlaybackCacheContextProvider playbackCacheContextProvider,
	                 @NonNull final LongSupplier currentTimeMillisSupplier) {
		this.cache = cache;
		this.gson = gson;
		this.playbackDetailsMemoryCache = playbackDetailsMemoryCache;
		this.streamInfoFetcher = streamInfoFetcher;
		this.playbackCacheContextProvider = playbackCacheContextProvider;
		this.currentTimeMillisSupplier = currentTimeMillisSupplier;
	}

	@Nullable
	public static String getVideoId(@Nullable final String url) {
		if (url == null) return null;

		// Improved Regex to handle playlist URLs, shorts, and standard watch links
		final String pattern = "(?:v=|=v/|/v/|/u/\\w/|embed/|watch\\?v=|shorts/|youtu.be/)([a-zA-Z0-9_-]{11})";
		final Pattern compiledPattern = Pattern.compile(pattern);
		final Matcher matcher = compiledPattern.matcher(url);

		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	@NonNull
	public VideoDetails getVideoInfo(@NonNull final String videoUrl) throws ExtractionException, IOException, InterruptedException {
		final String videoID = requireVideoId(videoUrl);
		final VideoDetails cachedDetails = readCachedVideoDetails(videoID);
		if (cachedDetails != null) return cachedDetails;

		final StreamInfo streamInfo = fetchStreamInfo(videoID, null);
		final VideoDetails details = buildVideoDetails(streamInfo);
		cacheVideoDetails(videoID, details);
		return details;
	}

	@NonNull
	public StreamDetails getStreamInfo(@NonNull final String videoUrl) throws ExtractionException, IOException, InterruptedException {
		return buildStreamDetails(fetchStreamInfo(requireVideoId(videoUrl), null));
	}

	@NonNull
	public PlaybackDetails getPlaybackDetails(@NonNull final String videoUrl,
	                                          @Nullable final ExtractionSession session) throws ExtractionException, IOException, InterruptedException {
		final String videoID = requireVideoId(videoUrl);
		final VideoDetails cachedDetails = readCachedVideoDetails(videoID);
		final boolean cacheLookupAllowed = cachedDetails != null
						&& playbackCacheContextProvider.canUsePlaybackMemoryCache(videoUrl)
						&& playbackCacheContextProvider.canPopulatePlaybackMemoryCache(session);
		final String fingerprint = cacheLookupAllowed
						? playbackCacheContextProvider.buildRequestContextFingerprint(videoUrl)
						: null;
		try {
			if (fingerprint != null) {
				final StreamDetails cachedStreamDetails = playbackDetailsMemoryCache.get(
								videoID,
								fingerprint,
								currentTimeMillisSupplier.getAsLong());
				ensureNotCancelled(session);
				if (cachedStreamDetails != null) {
					return new PlaybackDetails(cachedDetails, cachedStreamDetails);
				}
			}

			final StreamInfo streamInfo = fetchStreamInfo(videoID, session);
			final StreamDetails streamDetails = buildStreamDetails(streamInfo);
			final VideoDetails videoDetails;
			if (cachedDetails != null) {
				videoDetails = cachedDetails;
			} else {
				videoDetails = buildVideoDetails(streamInfo);
				cacheVideoDetails(videoID, videoDetails);
			}
			ensureNotCancelled(session);
			if (playbackCacheContextProvider.canUsePlaybackMemoryCache(videoUrl)
							&& playbackCacheContextProvider.canPopulatePlaybackMemoryCache(session)) {
				playbackDetailsMemoryCache.put(
								videoID,
								playbackCacheContextProvider.buildRequestContextFingerprint(videoUrl),
								streamDetails,
								currentTimeMillisSupplier.getAsLong());
			}
			return new PlaybackDetails(videoDetails, streamDetails);
		} finally {
			playbackCacheContextProvider.clearPlaybackMemoryCacheSession(session);
		}
	}

	public void invalidatePlaybackCacheByVideoId(@NonNull final String videoId) {
		playbackDetailsMemoryCache.invalidateVideo(videoId);
	}

	@NonNull
	private StreamInfo fetchStreamInfo(@NonNull final String videoID,
	                                   @Nullable final ExtractionSession session) throws ExtractionException, IOException, InterruptedException {
		ensureNotCancelled(session);
		final StreamInfo streamInfo = streamInfoFetcher.fetch(videoID, session);
		ensureNotCancelled(session);
		return streamInfo;
	}

	@NonNull
	private VideoDetails buildVideoDetails(@NonNull final StreamInfo streamInfo) {
		final VideoDetails details = new VideoDetails();
		details.setId(streamInfo.getId());
		details.setTitle(streamInfo.getName());
		details.setAuthor(streamInfo.getUploaderName());
		details.setDescription(streamInfo.getDescription().getContent());
		details.setDuration(streamInfo.getDuration());
		details.setThumbnail(getBestThumbnail(streamInfo));
		details.setLikeCount(streamInfo.getLikeCount());
		details.setDislikeCount(streamInfo.getDislikeCount());
		details.setUploadDate(Date.from(streamInfo.getUploadDate().offsetDateTime().toInstant()));
		details.setUploaderUrl(streamInfo.getUploaderUrl());
		details.setUploaderAvatar(getBestAvatar(streamInfo));
		details.setViewCount(streamInfo.getViewCount());
		details.setSegments(streamInfo.getStreamSegments());
		return details;
	}

	@NonNull
	private StreamDetails buildStreamDetails(@NonNull final StreamInfo streamInfo) {
		final StreamDetails details = new StreamDetails();
		details.setVideoStreams(streamInfo.getVideoOnlyStreams());
		details.setAudioStreams(filterAudioStreams(streamInfo.getAudioStreams()));
		details.setSubtitles(streamInfo.getSubtitles());
		details.setDashUrl(streamInfo.getDashMpdUrl());
		details.setHlsUrl(streamInfo.getHlsUrl());
		details.setStreamType(streamInfo.getStreamType());
		return details;
	}

	private List<AudioStream> filterAudioStreams(@Nullable final List<AudioStream> streams) {
		if (streams == null) return new ArrayList<>();
		final List<AudioStream> m4aStreams = streams.stream()
						.filter(stream -> stream.getFormat() == MediaFormat.M4A)
						.collect(Collectors.toCollection(ArrayList::new));
		if (m4aStreams.isEmpty()) return new ArrayList<>();
		final int maxBitrate = m4aStreams.stream()
						.mapToInt(stream -> stream.getAverageBitrate() > 0 ? stream.getAverageBitrate() : stream.getBitrate())
						.max().orElse(-1);
		return m4aStreams.stream()
						.filter(stream -> (stream.getAverageBitrate() > 0 ? stream.getAverageBitrate() : stream.getBitrate()) == maxBitrate)
						.collect(Collectors.toCollection(ArrayList::new));
	}

	@Nullable
	public String getBestThumbnail(@NonNull final StreamInfo s) {
		return getBestImageUrl(s.getThumbnails());
	}

	@Nullable
	public String getBestAvatar(@NonNull final StreamInfo s) {
		return getBestImageUrl(s.getUploaderAvatars());
	}

	@Nullable
	private String getBestImageUrl(@NonNull final List<Image> images) {
		if (images.isEmpty()) return null;
		final Map<Image.ResolutionLevel, Integer> priority = Map.of(Image.ResolutionLevel.HIGH, 3, Image.ResolutionLevel.MEDIUM, 2, Image.ResolutionLevel.LOW, 1, Image.ResolutionLevel.UNKNOWN, 0);
		return images.stream().max(Comparator.comparingInt(img -> priority.getOrDefault(img.getEstimatedResolutionLevel(), 0))).map(Image::getUrl).orElse(null);
	}

	@NonNull
	private String requireVideoId(@NonNull final String videoUrl) throws ExtractionException {
		final String videoID = getVideoId(videoUrl);
		if (videoID == null) throw new ExtractionException("Invalid URL: " + videoUrl);
		return videoID;
	}

	@Nullable
	private VideoDetails readCachedVideoDetails(@NonNull final String videoID) {
		if (!cache.contains(videoID)) return null;
		return gson.fromJson(cache.decodeString(videoID, null), VideoDetails.class);
	}

	private void cacheVideoDetails(@NonNull final String videoID, @NonNull final VideoDetails details) {
		cache.encode(videoID, gson.toJson(details, VideoDetails.class), 3600);
	}

	private void ensureNotCancelled(@Nullable final ExtractionSession session) throws InterruptedException {
		if (session != null && session.isCancelled()) {
			throw new InterruptedException("Extraction canceled");
		}
		if (Thread.currentThread().isInterrupted()) {
			throw new InterruptedException("Extraction interrupted");
		}
	}
}

final class DownloaderPlaybackCacheContextProvider implements PlaybackCacheContextProvider {
	private final DownloaderImpl downloader;

	DownloaderPlaybackCacheContextProvider(@NonNull final DownloaderImpl downloader) {
		this.downloader = downloader;
	}

	@Override
	public boolean canUsePlaybackMemoryCache(@NonNull final String url) {
		return downloader.canUsePlaybackMemoryCache(url);
	}

	@Override
	@NonNull
	public String buildRequestContextFingerprint(@NonNull final String url) {
		return downloader.buildRequestContextFingerprint(url);
	}

	@Override
	public boolean canPopulatePlaybackMemoryCache(@Nullable final ExtractionSession session) {
		return downloader.canPopulatePlaybackMemoryCache(session);
	}

	@Override
	public void clearPlaybackMemoryCacheSession(@Nullable final ExtractionSession session) {
		downloader.clearPlaybackMemoryCacheSession(session);
	}
}

@FunctionalInterface
interface StreamInfoFetcher {
	StreamInfo fetch(@NonNull String videoID,
	                 @Nullable ExtractionSession session) throws org.schabi.newpipe.extractor.exceptions.ExtractionException, IOException;
}

interface PlaybackCacheContextProvider {
	boolean canUsePlaybackMemoryCache(@NonNull String url);

	@NonNull
	String buildRequestContextFingerprint(@NonNull String url);

	boolean canPopulatePlaybackMemoryCache(@Nullable ExtractionSession session);

	void clearPlaybackMemoryCacheSession(@Nullable ExtractionSession session);
}
