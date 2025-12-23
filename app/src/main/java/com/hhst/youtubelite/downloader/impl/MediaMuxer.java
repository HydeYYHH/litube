package com.hhst.youtubelite.downloader.impl;

import java.io.File;
import java.io.IOException;

public interface MediaMuxer {
	/**
	 * Merges the video and audio files into a single output file.
	 *
	 * @param videoFile  The video file to be merged.
	 * @param audioFile  The audio file to be merged.
	 * @param outputFile The output file where the merged content will be saved.
	 */
	void merge(File videoFile, File audioFile, File outputFile) throws IOException;

	/**
	 * Cancels the ongoing merge operation.
	 */
	void cancel();
}
