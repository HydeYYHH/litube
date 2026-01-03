package com.hhst.youtubelite.extractor;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreamDetails {
	private List<VideoStream> videoStreams;
	private List<AudioStream> audioStreams;
	private List<SubtitlesStream> subtitles;
	private String dashUrl;
	private String hlsUrl;
	private StreamType streamType = StreamType.VIDEO_STREAM;
}
