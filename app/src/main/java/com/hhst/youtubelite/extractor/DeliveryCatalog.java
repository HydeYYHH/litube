package com.hhst.youtubelite.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Catalog of playback delivery candidates.
 */
@Data
@NoArgsConstructor
public class DeliveryCatalog {
	@NonNull
	private StreamType streamType = StreamType.VIDEO_STREAM;
	@NonNull
	private List<Delivery> items = new ArrayList<>();

	@Nullable
	public Delivery first(@NonNull PlaybackMode mode) {
		for (Delivery delivery : items) {
			if (delivery.getMode() == mode) {
				return delivery;
			}
		}
		return null;
	}
}
