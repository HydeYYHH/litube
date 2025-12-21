package com.hhst.youtubelite.player.interfaces;

import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;

import com.hhst.youtubelite.extractor.StreamDetails;
import com.hhst.youtubelite.player.PlayerPreferences;
import com.hhst.youtubelite.player.sponsor.SponsorBlockManager;
import com.hhst.youtubelite.webview.YoutubeWebview;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamSegment;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

/**
 * Internal interface for player implementation details.
 * This interface is used within the player module only.
 */
public interface IPlayerInternal extends IPlayer {
	PlayerPreferences getPrefs();

	SponsorBlockManager getSponsor();

	YoutubeWebview getWebview();

	long getDuration();

	float getSpeed();

	String getQuality();

	List<String> getAvailableResolutions();

	void onQualitySelected(String quality);

	void onSpeedSelected(float speed);

	void updateProgress(long position);

	void updateQualityDisplay();

	void updateSpeedDisplay();

	void play(VideoStream videoStream, AudioStream audioStream, String dashUrl, long duration, long resumePos, float speed);

	void handleError(Exception e);

	List<StreamSegment> getStreamSegments();

	String getThumbnail();

	StreamDetails getStreamDetails();

	void switchAudioTrack(AudioStream audioStream);

	AudioStream getAudioStream();

	Format getVideoFormat();

	Format getAudioFormat();

	@OptIn(markerClass = UnstableApi.class)
	DecoderCounters getVideoDecoderCounters();

	void addListener(Player.Listener listener);
}