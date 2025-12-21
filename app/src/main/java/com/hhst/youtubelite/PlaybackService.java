package com.hhst.youtubelite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.player.interfaces.IEngine;
import com.hhst.youtubelite.player.interfaces.IPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class PlaybackService extends Service {

	private static final String TAG = "PlaybackService";
	private static final String CHANNEL_ID = "player_channel";
	private static final int NOTIFICATION_ID = 100;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();
	private MediaSessionCompat mediaSession;
	private NotificationManager notificationManager;
	private boolean isSeeking = false;
	private final Runnable resetSeekFlagRunnable = () -> isSeeking = false;
	private boolean lastIsPlayingState = false;
	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return new PlaybackBinder();
	}

	@Override
	public void onCreate() {
		super.onCreate();
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Player Controls", NotificationManager.IMPORTANCE_LOW);
		channel.setDescription("Media playback controls");
		channel.setShowBadge(false);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		notificationManager.createNotificationChannel(channel);

		mediaSession = new MediaSessionCompat(this, TAG);
		PlaybackStateCompat initialState = new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO).setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f).build();
		mediaSession.setPlaybackState(initialState);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null) {
			MediaButtonReceiver.handleIntent(mediaSession, intent);
		}
		return super.onStartCommand(intent, flags, startId);
	}

	public void initialize(IPlayer player, IEngine engine) {
		if (player == null || engine == null) {
			stopSelf();
			return;
		}
		mediaSession.setCallback(new MediaSessionCompat.Callback() {
			@Override
			public void onPlay() {
				engine.play();
			}

			@Override
			public void onPause() {
				engine.pause();
			}

			@Override
			public void onSkipToNext() {
				engine.skipToNext();
			}

			@Override
			public void onSkipToPrevious() {
				engine.skipToPrevious();
			}

			@Override
			public void onSeekTo(long pos) {
				isSeeking = true;
				handler.removeCallbacks(resetSeekFlagRunnable);
				handler.postDelayed(resetSeekFlagRunnable, 1000);

				engine.seekTo(pos);
			}
		});
		mediaSession.setActive(true);
	}

	private Bitmap fetchThumbnail(String url) {
		if (url == null || url.isEmpty()) return null;
		Bitmap bitmap = null;
		HttpURLConnection conn = null;
		InputStream inputStream = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(10000);
			conn.connect();
			int responseCode = conn.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK) {
				inputStream = conn.getInputStream();
				Bitmap original = BitmapFactory.decodeStream(inputStream);
				if (original != null) {
					int size = Math.min(original.getWidth(), original.getHeight());
					int x = (original.getWidth() - size) / 2;
					int y = (original.getHeight() - size) / 2;
					bitmap = Bitmap.createBitmap(original, x, y, size, size);
					if (bitmap != original) original.recycle();
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "fetchThumbnail IOException: " + e.getMessage());
		} finally {
			if (inputStream != null) {
				try {
					inputStream.close();
				} catch (IOException ignored) {
				}
			}
			if (conn != null) conn.disconnect();
		}
		return bitmap;
	}

	private Notification buildNotification(boolean isPlaying) {
		MediaMetadataCompat metadata = mediaSession.getController().getMetadata();
		if (metadata == null) return null;

		String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
		String artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
		Bitmap largeIcon = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);

		int playPauseIconResId = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
		String playPauseActionTitle = isPlaying ? getString(R.string.action_pause) : getString(R.string.action_play);

		PendingIntent playPauseActionIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE);
		PendingIntent prevActionIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
		PendingIntent nextActionIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT);

		Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (launchIntent == null) launchIntent = new Intent(this, MainActivity.class);
		launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 101, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		return new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(artist).setLargeIcon(largeIcon).setContentIntent(contentIntent).setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(isPlaying).addAction(R.drawable.ic_previous, getString(R.string.action_previous), prevActionIntent).addAction(playPauseIconResId, playPauseActionTitle, playPauseActionIntent).addAction(R.drawable.ic_next, getString(R.string.action_next), nextActionIntent).setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
	}

	public void showNotification(String title, String author, String thumbnail, long duration) {
		executorService.execute(() -> {
			Bitmap largeIcon = fetchThumbnail(thumbnail);
			MediaMetadataCompat metadata = new MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, title).putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author).putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, largeIcon).putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration).build();
			mediaSession.setMetadata(metadata);

			PlaybackStateCompat initialState = new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO).setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1.0f).build();
			mediaSession.setPlaybackState(initialState);

			Notification notification = buildNotification(false);
			if (notification != null) {
				try {
					startForeground(NOTIFICATION_ID, notification);
				} catch (Exception e) {
					Log.e(TAG, "startForeground failed: " + e.getMessage());
				}
			}
		});
	}

	public void hideNotification() {
		stopForeground(true);
		if (notificationManager != null) {
			notificationManager.cancel(NOTIFICATION_ID);
		}
	}

	public void updateProgress(long pos, float playbackSpeed, boolean isPlaying) {
		// disable update while seeking
		if (isSeeking) return;
		int stateCompat = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
		PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO).setState(stateCompat, pos, playbackSpeed).build();

		mediaSession.setPlaybackState(playbackState);
		// disable update notification if state isn't change
		if (isPlaying != lastIsPlayingState) {
			Notification updatedNotification = buildNotification(isPlaying);
			if (updatedNotification != null) {
				notificationManager.notify(NOTIFICATION_ID, updatedNotification);
			}
		}
		lastIsPlayingState = isPlaying;
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		super.onTaskRemoved(rootIntent);
		stopForeground(true);
		stopSelf();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		stopForeground(true);
		if (mediaSession != null) {
			mediaSession.release();
			mediaSession = null;
		}
		if (notificationManager != null) {
			notificationManager.cancel(NOTIFICATION_ID);
			notificationManager = null;
		}
		handler.removeCallbacksAndMessages(null);
		executorService.shutdownNow();
	}

	public class PlaybackBinder extends Binder {
		public PlaybackService getService() {
			return PlaybackService.this;
		}
	}

}
