package com.hhst.youtubelite.extractor;

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
  private long likeCount;
  private long dislikeCount;
  private Date uploadDate;
  private String uploaderUrl;
  private String uploaderAvatar;
  private long viewCount;
}
