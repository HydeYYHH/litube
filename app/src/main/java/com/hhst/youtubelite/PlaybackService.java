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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import androidx.media3.common.util.UnstableApi;

import com.hhst.youtubelite.player.engine.Engine;
import com.hhst.youtubelite.ui.MainActivity;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dagger.hilt.android.AndroidEntryPoint;


@AndroidEntryPoint
@UnstableApi
public class PlaybackService extends Service {

	private static final String TAG = "PlaybackService";
	private static final String CHANNEL_ID = "player_channel";
	private static final int NOTIFICATION_ID = 100;
	private static final int CONNECT_TIMEOUT = 5000;
	private static final int READ_TIMEOUT = 10000;
	private static final int SEEK_RESET_DELAY = 1000;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();

	private MediaSessionCompat mediaSession;
	private NotificationManager notificationManager;
	private boolean isSeeking = false;
	private final Runnable resetSeekFlagRunnable = () -> isSeeking = false;
	private boolean lastIsPlayingState = false;

	@Nullable
	@Override
	public IBinder onBind(@NonNull final Intent intent) {
		return new PlaybackBinder();
	}

	@Override
	public void onCreate() {
		super.onCreate();
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Player Controls", NotificationManager.IMPORTANCE_LOW);
		channel.setDescription("Media playback controls");
		channel.setShowBadge(false);
		channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
		if (notificationManager != null) notificationManager.createNotificationChannel(channel);

		mediaSession = new MediaSessionCompat(this, TAG);
		final PlaybackStateCompat initialState = new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO).setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f).build();
		mediaSession.setPlaybackState(initialState);
	}

	@Override
	public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
		if (intent != null) MediaButtonReceiver.handleIntent(mediaSession, intent);
		return super.onStartCommand(intent, flags, startId);
	}

	public void initialize(@NonNull final Engine engine) {
		if (mediaSession == null) return;
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
			public void onSeekTo(final long pos) {
				isSeeking = true;
				handler.removeCallbacks(resetSeekFlagRunnable);
				handler.postDelayed(resetSeekFlagRunnable, SEEK_RESET_DELAY);

				engine.seekTo(pos);
			}
		});
		mediaSession.setActive(true);
	}

	@Nullable
	private Bitmap fetchThumbnail(@Nullable final String url) {
		if (url == null || url.isEmpty()) return null;
		Bitmap bitmap = null;
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT);
			conn.setReadTimeout(READ_TIMEOUT);
			conn.connect();
			final int responseCode = conn.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK) {
				try (final InputStream inputStream = conn.getInputStream()) {
					final Bitmap original = BitmapFactory.decodeStream(inputStream);
					if (original != null) {
						final int size = Math.min(original.getWidth(), original.getHeight());
						final int x = (original.getWidth() - size) / 2;
						final int y = (original.getHeight() - size) / 2;
						bitmap = Bitmap.createBitmap(original, x, y, size, size);
						if (bitmap != original) original.recycle();
					}
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "fetchThumbnail IOException: " + e.getMessage());
		} finally {
			if (conn != null) conn.disconnect();
		}
		return bitmap;
	}

	@Nullable
	private Notification buildNotification(final boolean isPlaying) {
		final MediaMetadataCompat metadata = mediaSession.getController().getMetadata();
		if (metadata == null) return null;

		final String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
		final String artist = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
		final Bitmap largeIcon = metadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART);

		final int playPauseIconResId = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
		final String playPauseActionTitle = isPlaying ? getString(R.string.action_pause) : getString(R.string.action_play);

		final PendingIntent playPauseActionIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE);
		final PendingIntent prevActionIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);
		final PendingIntent nextActionIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT);

		Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (launchIntent == null) launchIntent = new Intent(this, MainActivity.class);
		launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		final PendingIntent contentIntent = PendingIntent.getActivity(this, 101, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		return new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title).setContentText(artist).setLargeIcon(largeIcon).setContentIntent(contentIntent).setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(isPlaying).addAction(R.drawable.ic_previous, getString(R.string.action_previous), prevActionIntent).addAction(playPauseIconResId, playPauseActionTitle, playPauseActionIntent).addAction(R.drawable.ic_next, getString(R.string.action_next), nextActionIntent).setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
	}

	public void showNotification(@Nullable final String title, @Nullable final String author, @Nullable final String thumbnail, final long duration) {
		executorService.execute(() -> {
			final Bitmap largeIcon = fetchThumbnail(thumbnail);
			final MediaMetadataCompat metadata = new MediaMetadataCompat.Builder().putString(MediaMetadataCompat.METADATA_KEY_TITLE, title).putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author).putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, largeIcon).putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration).build();
			mediaSession.setMetadata(metadata);

			final PlaybackStateCompat initialState = new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO).setState(PlaybackStateCompat.STATE_PAUSED, 0L, 1.0f).build();
			mediaSession.setPlaybackState(initialState);

			final Notification notification = buildNotification(false);
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
		if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
	}

	public void updateProgress(final long pos, final float playbackSpeed, final boolean isPlaying) {
		// disable update while seeking
		if (isSeeking) return;
		final int stateCompat = isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
		final PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder().setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO).setState(stateCompat, pos, playbackSpeed).build();

		mediaSession.setPlaybackState(playbackState);
		// disable update notification if state isn't change
		if (isPlaying != lastIsPlayingState) {
			final Notification updatedNotification = buildNotification(isPlaying);
			if (updatedNotification != null && notificationManager != null)
				notificationManager.notify(NOTIFICATION_ID, updatedNotification);
		}
		lastIsPlayingState = isPlaying;
	}

	@Override
	public void onTaskRemoved(final Intent rootIntent) {
		super.onTaskRemoved(rootIntent);
		stopForeground(true);
		stopSelf();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		stopForeground(true);
		if (mediaSession != null) {
			mediaSession.setActive(false);
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
