package com.hhst.youtubelite.extractor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackInfo {
	private VideoDetails videoDetails;
	private StreamDetails streamDetails;
}
