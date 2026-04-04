package com.hhst.youtubelite.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * Utility methods for device-level operations like PIP mode, clipboard, and system settings.
 */
public final class DeviceUtils {

	private static final float DEFAULT_BRIGHTNESS = 0.5f;
	private static final float SCROLL_SENSITIVITY_FACTOR = 3.0f;


	/**
	 * Checks if the activity is currently in Picture-in-Picture mode.
	 */
	public static boolean isInPictureInPictureMode(@NonNull Activity activity) {
		return activity.isInPictureInPictureMode();
	}

	public static boolean isRotateOn(@NonNull Context context) {
		return Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
	}

	/**
	 * Copies text to the system clipboard.
	 */
	public static void copyToClipboard(@NonNull Context context, @NonNull String label, @NonNull String text) {
		ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null) {
			ClipData clip = ClipData.newPlainText(label, text);
			clipboard.setPrimaryClip(clip);
		}
	}

	/**
	 * Adjusts device brightness based on vertical movement.
	 */
	public static float adjustBrightness(@NonNull Activity activity, float dy, @NonNull View view, float brightness, float scrollSens) {
		float b = brightness;
		if (b == -1) {
			final WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
			b = lp.screenBrightness < 0 ? DEFAULT_BRIGHTNESS : lp.screenBrightness;
		}
		float delta = (dy / view.getHeight()) * scrollSens * SCROLL_SENSITIVITY_FACTOR;
		b = Math.max(0.01f, Math.min(1.0f, b + delta));

		final WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
		lp.screenBrightness = b;
		activity.getWindow().setAttributes(lp);

		return b;
	}

	/**
	 * Adjusts device volume based on vertical movement.
	 */
	public static float adjustVolume(@NonNull Activity activity, float dy, @NonNull View view, float volume, float scrollSens) {
		AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
		int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		float delta = (dy / view.getHeight()) * maxVolume * scrollSens * SCROLL_SENSITIVITY_FACTOR;
		float newVolume = volume + delta;
		newVolume = Math.max(0, Math.min(maxVolume, newVolume));

		am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(newVolume), 0);
		return newVolume;
	}
}
