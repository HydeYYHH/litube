package com.hhst.youtubelite.downloader.core;

import androidx.annotation.NonNull;

import com.coremedia.iso.boxes.Container;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.List;

/**
 * Utility that merges audio and video streams into one file.
 */
public final class MediaMuxer {
	public static void merge(@NonNull File videoFile, @NonNull File audioFile, @NonNull File outputFile) throws IOException {

		Movie video = MovieCreator.build(videoFile.getAbsolutePath());
		Movie audio = MovieCreator.build(audioFile.getAbsolutePath());

		List<Track> videoTracks = video.getTracks().stream().filter(track -> "vide".equals(track.getHandler())).toList();
		List<Track> audioTracks = audio.getTracks().stream().filter(track -> "soun".equals(track.getHandler())).toList();

		if (videoTracks.isEmpty() || audioTracks.isEmpty()) throw new EmptyTrackException();

		Movie result = new Movie();
		result.addTrack(videoTracks.get(0));
		result.addTrack(audioTracks.get(0));

		Container out = new DefaultMp4Builder().build(result);
		try (FileOutputStream fos = new FileOutputStream(outputFile); final FileChannel fc = fos.getChannel()) {
			out.writeContainer(fc);
		}
	}

/**
 * Component that handles app logic.
 */
	private static class EmptyTrackException extends RuntimeException {
		public EmptyTrackException() {
			super("No video or audio tracks found in the provided files.");
		}
	}

}
