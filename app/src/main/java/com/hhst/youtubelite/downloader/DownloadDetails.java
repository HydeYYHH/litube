package com.hhst.youtubelite.downloader;

import com.yausername.youtubedl_android.mapper.VideoFormat;
import java.util.List;
import lombok.Data;

@Data
public class DownloadDetails {
  private String id;
  private String title;
  private String author;
  private String description;
  private Long duration;
  private String thumbnail;
  private List<VideoFormat> formats;
}
