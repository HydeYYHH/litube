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
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class YoutubeExtractor {
	static final MMKV cache = MMKV.defaultMMKV();
	static final Gson gson = new Gson();
	private static final Map<String, StreamInfo> tempInfo = new ConcurrentHashMap<>();

	public YoutubeExtractor() {
		NewPipe.init(DownloaderImpl.getInstance());
		YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl.getInstance());
	}

	/**
	 * @param videoUrl not the video id but the url
	 * @return VideoDetails contains metadata
	 */
	@NonNull
	public static VideoDetails getVideoInfo(String videoUrl) throws ExtractionException, IOException, InterruptedException {
		String videoID = getVideoId(videoUrl);
		if (videoID == null) throw new ExtractionException("Invalid YouTube URL: " + videoUrl);
		if (cache.contains(videoID))
			return gson.fromJson(cache.decodeString(videoID, null), VideoDetails.class);
		var extractor = new YoutubeExtractor();
		checkInterrupt();
		var info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoID);
		// cache for getStreamInfo
		tempInfo.put(videoID, info);

		var details = new VideoDetails();
		details.setId(info.getId());
		details.setTitle(info.getName());
		details.setAuthor(info.getUploaderName());
		details.setDescription(info.getDescription().getContent());
		details.setDuration(info.getDuration());
		details.setThumbnail(extractor.getBestThumbnail(info));
		details.setLikeCount(info.getLikeCount());
		details.setDislikeCount(info.getDislikeCount());
		details.setUploadDate(Date.from(info.getUploadDate().offsetDateTime().toInstant()));
		details.setUploaderUrl(info.getUploaderUrl());
		details.setUploaderAvatar(extractor.getBestAvatar(info));
		details.setViewCount(info.getViewCount());
		// 1 hours expires cache
		cache.encode(videoID, gson.toJson(details, VideoDetails.class), 3600);
		return details;
	}

	/**
	 * @param videoUrl not the video id but the whole url
	 * @return StreamDetails contains stream urls
	 */
	@NonNull
	public static StreamDetails getStreamInfo(String videoUrl) throws ExtractionException, IOException, InterruptedException {
		String videoID = getVideoId(videoUrl);
		if (videoID == null) throw new ExtractionException("Invalid YouTube URL: " + videoUrl);

		var extractor = new YoutubeExtractor();
		StreamInfo info = tempInfo.remove(videoID);
		if (info == null) {
			checkInterrupt();
			info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoID);
		}
		var details = new StreamDetails();
		details.setVideoStreams(extractor.getVideoOnlyStreams(info));
		details.setAudioStream(extractor.getBestAudioStream(info));
		details.setDashUrl(info.getDashMpdUrl());
		return details;
	}

	@Nullable
	public static String getVideoId(String url) {
		if (url == null) return null;
		String pattern = "^.*((youtu.be/)|(v/)|(/u/\\w/)|(embed/)|(watch\\?))\\??v?=?([^#&?]*).*";
		Pattern compiledPattern = Pattern.compile(pattern);
		Matcher matcher = compiledPattern.matcher(url);
		if (matcher.matches()) {
			String videoId = matcher.group(7);
			if (videoId != null && videoId.length() == 11) {
				return videoId;
			}
		}
		return null;
	}


	@Nullable
	public String getBestThumbnail(StreamInfo info) {
		var thumbnails = info.getThumbnails();
		Image bestThumbnail = null;
		Map<Image.ResolutionLevel, Integer> resolutionPriority = Map.of(Image.ResolutionLevel.HIGH, 3, Image.ResolutionLevel.MEDIUM, 2, Image.ResolutionLevel.LOW, 1, Image.ResolutionLevel.UNKNOWN, 0);
		for (var thumbnail : thumbnails) {

			if (bestThumbnail == null || Objects.requireNonNullElse(resolutionPriority.get(thumbnail.getEstimatedResolutionLevel()), 0) > Objects.requireNonNullElse(resolutionPriority.get(bestThumbnail.getEstimatedResolutionLevel()), 0)) {
				bestThumbnail = thumbnail;
			}
		}
		return bestThumbnail != null ? bestThumbnail.getUrl() : null;
	}

	@Nullable
	public AudioStream getBestAudioStream(StreamInfo info) {
		AudioStream bestAudioStream = null;
		for (var audioStream : info.getAudioStreams()) {
			if (audioStream.getFormat() == MediaFormat.M4A && (bestAudioStream == null || audioStream.getAverageBitrate() > bestAudioStream.getAverageBitrate())) {
				bestAudioStream = audioStream;
			}
		}
		return bestAudioStream;
	}

	public List<VideoStream> getVideoOnlyStreams(StreamInfo info) {
		return info.getVideoOnlyStreams().stream().filter(s -> s.getFormat() == MediaFormat.MPEG_4).collect(Collectors.toList());
	}

	@Nullable
	public String getBestAvatar(StreamInfo info) {
		if (!info.getUploaderAvatars().isEmpty()) {
			var avatars = info.getUploaderAvatars();
			Image bestAvatar = null;
			Map<Image.ResolutionLevel, Integer> resolutionPriority = Map.of(Image.ResolutionLevel.HIGH, 3, Image.ResolutionLevel.MEDIUM, 2, Image.ResolutionLevel.LOW, 1, Image.ResolutionLevel.UNKNOWN, 0);
			for (var avatar : avatars) {
				if (bestAvatar == null || Objects.requireNonNullElse(resolutionPriority.get(avatar.getEstimatedResolutionLevel()), 0) > Objects.requireNonNullElse(resolutionPriority.get(bestAvatar.getEstimatedResolutionLevel()), 0)) {
					bestAvatar = avatar;
				}
			}
			return bestAvatar != null ? bestAvatar.getUrl() : null;
		}
		return null;
	}
}
