package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;

import com.hhst.youtubelite.downloader.service.DownloadNotification;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DownloadTask implements Cloneable {
	private String url;
	private String fileName;
	private String thumbnail;
	private VideoStream videoStream;
	private AudioStream audioStream;
	private SubtitlesStream subtitlesStream;
	private boolean audio;
	private boolean subtitle;
	private int threadCount;
	private DownloaderState state = DownloaderState.PENDING;
	private File output;
	private DownloadNotification notification;

	public boolean isComplexDownload() {
		return !subtitle || audioStream != null || videoStream != null;
	}

	public String getDownloadUrl() {
		if (subtitle && subtitlesStream != null) {
			return subtitlesStream.getContent();
		}
		return url;
	}

	@NonNull
	@Override
	public DownloadTask clone() {
		try {
			return (DownloadTask) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new RuntimeException(e);
		}
	}
}
