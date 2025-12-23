package com.hhst.youtubelite.extractor;

import static com.hhst.youtubelite.utils.IOUtils.checkInterrupt;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class YoutubeExtractor {
	static final MMKV cache = MMKV.defaultMMKV();
	static final Gson gson = new Gson();
	private static StreamInfo info = null;

	public YoutubeExtractor() {
		NewPipe.init(DownloaderImpl.getInstance());
		YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl.getInstance());
	}

	/**
	 * @param videoUrl not the video id but the url
	 * @return VideoDetails contains metadata
	 */
	@NonNull
	public static VideoDetails getVideoInfo(@NonNull final String videoUrl) throws ExtractionException, IOException, InterruptedException {
		final String videoID = getVideoId(videoUrl);
		if (videoID == null) throw new ExtractionException("Invalid YouTube URL: " + videoUrl);
		if (cache.contains(videoID))
			return gson.fromJson(cache.decodeString(videoID, null), VideoDetails.class);
		final YoutubeExtractor extractor = new YoutubeExtractor();
		checkInterrupt();
		final StreamInfo streamInfo = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoID);
		// cache for StreamInfo
		YoutubeExtractor.info = streamInfo;

		final VideoDetails details = new VideoDetails();
		details.setId(streamInfo.getId());
		details.setTitle(streamInfo.getName());
		details.setAuthor(streamInfo.getUploaderName());
		details.setDescription(streamInfo.getDescription().getContent());
		details.setDuration(streamInfo.getDuration());
		details.setThumbnail(extractor.getBestThumbnail(streamInfo));
		details.setLikeCount(streamInfo.getLikeCount());
		details.setDislikeCount(streamInfo.getDislikeCount());
		details.setUploadDate(Date.from(streamInfo.getUploadDate().offsetDateTime().toInstant()));
		details.setUploaderUrl(streamInfo.getUploaderUrl());
		details.setUploaderAvatar(extractor.getBestAvatar(streamInfo));
		details.setViewCount(streamInfo.getViewCount());
		details.setSegments(streamInfo.getStreamSegments());
		// 1 hours expires cache
		cache.encode(videoID, gson.toJson(details, VideoDetails.class), 3600);
		return details;
	}

	/**
	 * @param videoUrl not the video id but the whole url
	 * @return StreamDetails contains stream urls
	 */
	@NonNull
	public static StreamDetails getStreamInfo(@NonNull final String videoUrl) throws ExtractionException, IOException, InterruptedException {
		StreamInfo streamInfo = YoutubeExtractor.info;
		YoutubeExtractor.info = null;

		final String videoID = getVideoId(videoUrl);
		if (videoID == null) throw new ExtractionException("Invalid YouTube URL: " + videoUrl);

		final YoutubeExtractor extractor = new YoutubeExtractor();
		if (streamInfo == null) {
			checkInterrupt();
			streamInfo = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoID);
		}
		final StreamDetails details = new StreamDetails();
		details.setVideoStreams(streamInfo.getVideoOnlyStreams());

		// Filter audio streams: M4A only, max bitrate, allow multiples
		details.setAudioStreams(extractor.filterAudioStreams(streamInfo.getAudioStreams()));
		details.setSubtitles(streamInfo.getSubtitles());

		details.setDashUrl(streamInfo.getDashMpdUrl());
		return details;
	}

	@Nullable
	public static String getVideoId(@Nullable final String url) {
		if (url == null) return null;
		final String pattern = "^.*((youtu.be/)|(v/)|(/u/\\w/)|(embed/)|(watch\\?))\\??v?=?([^#&?]*).*";
		final Pattern compiledPattern = Pattern.compile(pattern);
		final Matcher matcher = compiledPattern.matcher(url);
		if (matcher.matches()) {
			final String videoId = matcher.group(7);
			if (videoId != null && videoId.length() == 11) return videoId;
		}
		return null;
	}

	private List<AudioStream> filterAudioStreams(@Nullable final List<AudioStream> streams) {
		if (streams == null) return Collections.emptyList();

		// 1. Filter M4A streams
		final List<AudioStream> m4aStreams = streams.stream()
				.filter(stream -> stream.getFormat() == MediaFormat.M4A)
				.toList();

		if (m4aStreams.isEmpty()) return Collections.emptyList();

		// 2. Find max bitrate (using fallback for averageBitrate)
		final int maxBitrate = m4aStreams.stream()
				.mapToInt(stream -> {
					int br = stream.getAverageBitrate();
					if (br <= 0) br = stream.getBitrate();
					return br;
				})
				.max().orElse(-1);

		// 3. Select all streams with max bitrate
		final List<AudioStream> bestStreams = m4aStreams.stream()
				.filter(stream -> {
					int br = stream.getAverageBitrate();
					if (br <= 0) br = stream.getBitrate();
					return br == maxBitrate;
				})
				.collect(Collectors.toList());

		Collections.reverse(bestStreams);
		return bestStreams;
	}

	@Nullable
	public String getBestThumbnail(@NonNull final StreamInfo streamInfo) {
		return getBestImageUrl(streamInfo.getThumbnails());
	}

	@Nullable
	public String getBestAvatar(@NonNull final StreamInfo streamInfo) {
		return getBestImageUrl(streamInfo.getUploaderAvatars());
	}

	@Nullable
	private String getBestImageUrl(@NonNull final List<Image> images) {
		if (images.isEmpty()) return null;
		final Map<Image.ResolutionLevel, Integer> resolutionPriority = Map.of(Image.ResolutionLevel.HIGH, 3, Image.ResolutionLevel.MEDIUM, 2, Image.ResolutionLevel.LOW, 1, Image.ResolutionLevel.UNKNOWN, 0);
		return images.stream().max(Comparator.comparingInt(img -> resolutionPriority.getOrDefault(img.getEstimatedResolutionLevel(), 0))).map(Image::getUrl).orElse(null);
	}
}
