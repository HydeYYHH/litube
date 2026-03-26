package com.hhst.youtubelite.player.queue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class QueueItem {
	@Nullable
	private String videoId;
	@Nullable
	private String url;
	@Nullable
	private String title;
	@Nullable
	private String author;
	@Nullable
	private final String thumbnailUrl;

	public QueueItem(@Nullable final String videoId,
	                 @Nullable final String url,
	                 @Nullable final String title,
	                 @Nullable final String author,
	                 @Nullable final String thumbnailUrl) {
		this.videoId = videoId;
		this.url = url;
		this.title = title;
		this.author = author;
		this.thumbnailUrl = thumbnailUrl;
	}

	@Nullable
	public String getVideoId() {
		return videoId;
	}

	public void setVideoId(@Nullable final String videoId) {
		this.videoId = videoId;
	}

	@Nullable
	public String getUrl() {
		return url;
	}

	public void setUrl(@Nullable final String url) {
		this.url = url;
	}

	@Nullable
	public String getTitle() {
		return title;
	}

	public void setTitle(@Nullable final String title) {
		this.title = title;
	}

	@Nullable
	public String getAuthor() {
		return author;
	}

	public void setAuthor(@Nullable final String author) {
		this.author = author;
	}

	@Nullable
	public String getThumbnailUrl() {
		return thumbnailUrl;
	}

	@NonNull
	public QueueItem copy() {
		return new QueueItem(videoId, url, title, author, thumbnailUrl);
	}
}
