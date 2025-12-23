package com.hhst.youtubelite.extractor;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StreamDetails {
	@NonNull
	private List<VideoStream> videoStreams;
	@NonNull
	private List<AudioStream> audioStreams;
	@NonNull
	private List<SubtitlesStream> subtitles;
	@Nullable
	private String dashUrl;
}
