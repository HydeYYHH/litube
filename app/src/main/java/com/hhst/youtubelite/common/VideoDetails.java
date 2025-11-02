package com.hhst.youtubelite.common;

import java.util.Date;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoDetails {
  private String id;
  private String title;
  private String author;
  private String description;
  private Long duration;
  private String thumbnail;
  private List<VideoStream> videoStreams;
  private AudioStream audioStream;
  private long likeCount;
  private long dislikeCount;
  private Date uploadDate;
  private String uploaderUrl;
  private String uploaderAvatar;
  private long viewCount;
  private List<InfoItem> relatedVideos;
  private CommentsInfo commentsInfo;
}
