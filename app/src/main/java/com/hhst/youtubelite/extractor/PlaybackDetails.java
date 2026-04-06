package com.hhst.youtubelite.extractor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackDetails {
	private VideoDetails videoDetails;
	private StreamDetails streamDetails;
}
