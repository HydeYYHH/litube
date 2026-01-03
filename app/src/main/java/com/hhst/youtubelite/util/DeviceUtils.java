package com.hhst.youtubelite.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioManager;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * Utility methods for device-level operations like PIP mode, clipboard, and system settings.
 */
public final class DeviceUtils {

	private static final float DEFAULT_BRIGHTNESS = 0.5f;
	private static final float MIN_BRIGHTNESS = 0.01f;
	private static final float MAX_BRIGHTNESS = 1.0f;
	private static final float SCROLL_SENSITIVITY_FACTOR = 3.0f;
	private static final int PERCENT_MULTIPLIER = 100;


	/**
	 * Checks if the activity is currently in Picture-in-Picture mode.
	 */
	public static boolean isInPictureInPictureMode(@NonNull final Activity activity) {
		return activity.isInPictureInPictureMode();
	}

	/**
	 * Copies text to the system clipboard.
	 */
	public static void copyToClipboard(@NonNull final Context context, @NonNull final String label, @NonNull final String text) {
		final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			final ClipData clip = ClipData.newPlainText(label, text);
			clipboard.setPrimaryClip(clip);
		}
	}

	/**
	 * Adjusts device brightness based on vertical movement.
	 */
	public static float adjustBrightness(@NonNull final Activity activity, final float dy, @NonNull final View view, final float brightness, final float scrollSens) {
		float b = brightness;
		if (b == -1) {
			final WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
			b = lp.screenBrightness < 0 ? DEFAULT_BRIGHTNESS : lp.screenBrightness;
		}
		final float delta = (dy / view.getHeight()) * scrollSens * SCROLL_SENSITIVITY_FACTOR;
		b = Math.max(MIN_BRIGHTNESS, Math.min(MAX_BRIGHTNESS, b + delta));

		final WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
		lp.screenBrightness = b;
		activity.getWindow().setAttributes(lp);

		return b;
	}

	/**
	 * Adjusts device volume based on vertical movement.
	 */
	public static float adjustVolume(@NonNull final Activity activity, final float dy, @NonNull final View view, final float volume, final float scrollSens) {
		final AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
		final int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		final float delta = (dy / view.getHeight()) * maxVolume * scrollSens * SCROLL_SENSITIVITY_FACTOR;
		float newVolume = volume + delta;
		newVolume = Math.max(0, Math.min(maxVolume, newVolume));

		am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(newVolume), 0);
		return newVolume;
	}

	public static int getVolumePercent(final float volume, final int maxVolume) {
		return Math.round(volume * PERCENT_MULTIPLIER / (float) maxVolume);
	}

	public static int getBrightnessPercent(final float brightness) {
		return Math.round(brightness * PERCENT_MULTIPLIER);
	}
}
