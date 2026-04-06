package com.hhst.youtubelite.util;

import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.hhst.youtubelite.R;

public final class ImageUtils {

	private static final int THUMB = R.drawable.bg_thumbnail_placeholder;

	private ImageUtils() {
	}

	public static void loadThumb(@NonNull final ImageView view, @Nullable final String url) {
		if (url == null || url.isBlank()) {
			showThumb(view);
			return;
		}
		Glide.with(view)
				.load(url)
				.placeholder(THUMB)
				.error(THUMB)
				.centerCrop()
				.into(view);
	}

	public static void showThumb(@NonNull final ImageView view) {
		view.setImageResource(THUMB);
	}
}
