package com.hhst.youtubelite.player.queue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Value object for one queue entry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class QueueItem {
	@Nullable
	private String videoId;
	@Nullable
	private String videoUrl;
	@Nullable
	private String title;
	@Nullable
	private String author;
	@Nullable
	private String thumbnailUrl;

	@NonNull
	public QueueItem copy() {
		return new QueueItem(videoId, videoUrl, title, author, thumbnailUrl);
	}
}
