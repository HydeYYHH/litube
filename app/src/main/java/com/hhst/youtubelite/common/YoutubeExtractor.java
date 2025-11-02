package com.hhst.youtubelite.common;

import androidx.annotation.Nullable;
import com.google.gson.Gson;
import com.tencent.mmkv.MMKV;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

public class YoutubeExtractor {
  private static final MMKV cache = MMKV.defaultMMKV();
  private static final Gson gson = new Gson();

  public YoutubeExtractor() {
    NewPipe.init(DownloaderImpl.getInstance());
  }

  /**
   * @param videoUrl not the video id but the whole url
   * @return VideoDetails contains everything we need
   */
  @Nullable
  public static VideoDetails info(String videoUrl) throws ExtractionException, IOException {
    String videoID = getVideoId(videoUrl);
    if (videoID == null) {
      throw new ExtractionException("Invalid YouTube URL: " + videoUrl);
    }
    if (cache.contains(videoID)) {
      return gson.fromJson(cache.decodeString(videoID, null), VideoDetails.class);
    }
    var extractor = new YoutubeExtractor();
    var info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoID);
    var details = new VideoDetails();

    details.setId(info.getId());
    details.setTitle(info.getName());
    details.setAuthor(info.getUploaderName());
    details.setDescription(info.getDescription().getContent());
    details.setDuration(info.getDuration());
    details.setThumbnail(extractor.getBestThumbnail(info));
    details.setVideoStreams(extractor.getVideoOnlyStreams(info));
    details.setAudioStream(extractor.getBestAudioStream(info));
    details.setLikeCount(info.getLikeCount());
    details.setDislikeCount(info.getDislikeCount());
    details.setUploadDate(Date.from(info.getUploadDate().offsetDateTime().toInstant()));
    details.setUploaderUrl(info.getUploaderUrl());
    details.setUploaderAvatar(extractor.getBestAvatar(info));
    details.setViewCount(info.getViewCount());
    details.setRelatedVideos(info.getRelatedItems());
    CommentsInfo commentsInfo = CommentsInfo.getInfo(ServiceList.YouTube, videoUrl);
    details.setCommentsInfo(commentsInfo);

    // 10 hours expires cache (as we know the streams will expire in 14 hours)
//    cache.encode(videoID, gson.toJson(details, VideoDetails.class), 36000);
    return details;
  }

  @Nullable
  private static String getVideoId(String videoUrl) {
    Pattern pattern = Pattern.compile("/watch\\?v=([^&#]+)");
    Matcher matcher = pattern.matcher(videoUrl);
    if (matcher.find()) {
      return matcher.group(1);
    }
    return null;
  }

  @Nullable
  public String getBestThumbnail(StreamInfo info) {
    var thumbnails = info.getThumbnails();
    Image bestThumbnail = null;
    Map<Image.ResolutionLevel, Integer> resolutionPriority =
        Map.of(
            Image.ResolutionLevel.HIGH, 3,
            Image.ResolutionLevel.MEDIUM, 2,
            Image.ResolutionLevel.LOW, 1,
            Image.ResolutionLevel.UNKNOWN, 0);
    for (var thumbnail : thumbnails) {

      if (bestThumbnail == null
          || Objects.requireNonNullElse(
                  resolutionPriority.get(thumbnail.getEstimatedResolutionLevel()), 0)
              > Objects.requireNonNullElse(
                  resolutionPriority.get(bestThumbnail.getEstimatedResolutionLevel()), 0)) {
        bestThumbnail = thumbnail;
      }
    }
    return bestThumbnail != null ? bestThumbnail.getUrl() : null;
  }

  @Nullable
  public AudioStream getBestAudioStream(StreamInfo info) {
    AudioStream bestAudioStream = null;
    for (var audioStream : info.getAudioStreams()) {
      if (audioStream.getFormat() == MediaFormat.M4A
          && (bestAudioStream == null
              || audioStream.getAverageBitrate() > bestAudioStream.getAverageBitrate())) {
        bestAudioStream = audioStream;
      }
    }
    return bestAudioStream;
  }

  public List<VideoStream> getVideoOnlyStreams(StreamInfo info) {
    return info.getVideoOnlyStreams().stream()
        .filter(s -> s.getFormat() == MediaFormat.MPEG_4)
        .collect(Collectors.toList());
  }

  @Nullable
  public String getBestAvatar(StreamInfo info) {
    if (!info.getUploaderAvatars().isEmpty()) {
      var avatars = info.getUploaderAvatars();
      Image bestAvatar = null;
      Map<Image.ResolutionLevel, Integer> resolutionPriority =
          Map.of(
              Image.ResolutionLevel.HIGH, 3,
              Image.ResolutionLevel.MEDIUM, 2,
              Image.ResolutionLevel.LOW, 1,
              Image.ResolutionLevel.UNKNOWN, 0);
      for (var avatar : avatars) {
        if (bestAvatar == null
            || Objects.requireNonNullElse(
                    resolutionPriority.get(avatar.getEstimatedResolutionLevel()), 0)
                > Objects.requireNonNullElse(
                    resolutionPriority.get(bestAvatar.getEstimatedResolutionLevel()), 0)) {
          bestAvatar = avatar;
        }
      }
      return bestAvatar != null ? bestAvatar.getUrl() : null;
    }
    return null;
  }
}
