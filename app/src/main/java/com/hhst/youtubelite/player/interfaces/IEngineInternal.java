package com.hhst.youtubelite.player.interfaces;

import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;

import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.util.List;
import java.util.function.Consumer;

/**
 * Internal engine interface for module use.
 * This interface provides additional methods for internal module communication.
 */
@UnstableApi
public interface IEngineInternal extends IEngine {
	void setSpeed(float speed);

	float speed();

	void setVideoQuality(int height);

	void addListener(Player.Listener listener);

	long position();

	void release();

	void play(Stream videoStream, Stream audioStream, List<SubtitlesStream> subtitlesStreams, String dashUrl, long durationMs, long resumePosMs, float speed);

	boolean areSubtitlesEnabled();

	void setSubtitlesEnabled(boolean enabled);

	void setSubtitleLanguage(String language);

	void setSubtitleLanguage(int index);

	List<String> getSubtitles();

	void setRepeatMode(int mode);

	int getPlaybackState();

	void stop();

	void clearMediaItems();

	Format getVideoFormat();

	Format getAudioFormat();

	DecoderCounters getVideoDecoderCounters();

	void setNavCallback(NavigationCallback navCallback);

	void setErrorListener(Consumer<PlaybackException> errorListener);

	interface NavigationCallback {
		void onNext();

		void onPrev();
	}
}
