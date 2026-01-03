package com.hhst.youtubelite.util;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Utility methods for View-related operations like DP/PX conversion and animations.
 */
public final class ViewUtils {

	private static final float ALPHA_VISIBLE = 1.0f;
	private static final float ALPHA_INVISIBLE = 0.0f;
	private static final int ANIMATION_DURATION_MS = 100;

	/**
	 * Converts DP to pixels.
	 */
	public static int dpToPx(@NonNull final Context context, final float dp) {
		return (int) (dp * context.getResources().getDisplayMetrics().density);
	}

	/**
	 * Gets the screen width in pixels.
	 */
	public static int getScreenWidth(@NonNull final Context context) {
		return context.getResources().getDisplayMetrics().widthPixels;
	}

	/**
	 * Animates a view's alpha.
	 */
	public static void animateViewAlpha(@NonNull final View v, final float alpha, final int visibilityIfGone) {
		if (Float.compare(alpha, ALPHA_VISIBLE) == 0) {
			v.animate().cancel();
			v.setAlpha(ALPHA_VISIBLE);
			v.setVisibility(View.VISIBLE);
		} else {
			v.setVisibility(View.VISIBLE);
			v.animate().alpha(alpha).setDuration(ANIMATION_DURATION_MS).withEndAction(() -> {
				if (Float.compare(alpha, ALPHA_INVISIBLE) == 0) {
					v.setVisibility(visibilityIfGone);
				}
			}).start();
		}
	}

	/**
	 * Sets the system UI visibility for fullscreen mode.
	 *
	 * @param view       The view to apply the visibility to.
	 * @param fullscreen True to enter fullscreen, false to exit.
	 */
	public static void setFullscreen(@NonNull final View view, final boolean fullscreen) {
		if (fullscreen) {
			view.setSystemUiVisibility(
							View.SYSTEM_UI_FLAG_LOW_PROFILE
											| View.SYSTEM_UI_FLAG_FULLSCREEN
											| View.SYSTEM_UI_FLAG_LAYOUT_STABLE
											| View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
											| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
											| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
			);
		} else {
			view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		}
	}
}
