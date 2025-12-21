package com.hhst.youtubelite.extractor;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreamDetails {
  private List<VideoStream> videoStreams;
  private List<AudioStream> audioStreams;
  private String dashUrl;
}
